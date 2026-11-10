package com.chatbot.conversation;

import com.chatbot.conversation.MessagingGateway.OutboundText;
import com.chatbot.conversation.MessagingGateway.SendResult;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Envía los mensajes pendientes. Toma cada uno de forma exclusiva entre instancias. */
@Component
public class OutboundDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboundDispatcher.class);
    private static final int BATCH_SIZE = 20;
    private static final int MAX_ATTEMPTS = 5;
    private static final int BASE_BACKOFF_SECONDS = 5;
    private static final int SENDING_TIMEOUT_MINUTES = 2;

    private final JdbcClient jdbc;
    private final MessagingGateway gateway;

    OutboundDispatcher(JdbcClient jdbc, MessagingGateway gateway) {
        this.jdbc = jdbc;
        this.gateway = gateway;
    }

    @Scheduled(fixedDelayString = "${app.workers.outbound-delay}")
    public void dispatchPending() {
        markAbandonedSendsAsUnknown();
        claim().forEach(this::send);
    }

    private List<Claim> claim() {
        return jdbc.sql("""
                WITH claimed AS (
                    UPDATE messages SET status = 'SENDING', send_attempts = send_attempts + 1, updated_at = now()
                    WHERE id IN (
                        SELECT id FROM messages
                        WHERE status = 'PENDING' AND next_attempt_at <= now()
                        ORDER BY next_attempt_at
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED)
                    RETURNING id, tenant_id, conversation_id, body, send_attempts)
                SELECT claimed.id, conv.whatsapp_account_id AS account_id, cust.phone_number, cust.wa_user_id,
                       claimed.body, claimed.send_attempts AS attempts
                FROM claimed
                JOIN conversations conv ON conv.tenant_id = claimed.tenant_id AND conv.id = claimed.conversation_id
                JOIN customers cust ON cust.tenant_id = conv.tenant_id AND cust.id = conv.customer_id
                """)
                .param("limit", BATCH_SIZE)
                .query(Claim.class)
                .list();
    }

    private void send(Claim claim) {
        SendResult result = sendSafely(claim);
        switch (result) {
            case SendResult.Sent sent -> markSent(claim.id(), sent.providerMessageId());
            case SendResult.Retryable retryable -> retryOrFail(claim, retryable);
            case SendResult.Rejected rejected -> markFailed(claim.id(), rejected.code(), rejected.detail());
            case SendResult.Uncertain uncertain -> markUnknown(claim.id(), uncertain.detail());
        }
    }

    private SendResult sendSafely(Claim claim) {
        try {
            return gateway.sendText(
                    new OutboundText(claim.accountId(), claim.phoneNumber(), claim.waUserId(), claim.body()));
        } catch (RuntimeException e) {
            log.error("Error inesperado al enviar el mensaje {}", claim.id(), e);
            return new SendResult.Uncertain(e.getClass().getSimpleName());
        }
    }

    private void retryOrFail(Claim claim, SendResult.Retryable result) {
        if (claim.attempts() >= MAX_ATTEMPTS) {
            markFailed(claim.id(), result.code(), result.detail());
            return;
        }
        log.warn("Reintento del mensaje {} por el error {}", claim.id(), result.code());
        jdbc.sql("""
                UPDATE messages
                SET status = 'PENDING', error_code = :code, error_detail = :detail, updated_at = now(),
                    next_attempt_at = now() + make_interval(secs => :delay)
                WHERE id = :id
                """)
                .param("id", claim.id())
                .param("code", result.code())
                .param("detail", result.detail())
                .param("delay", BASE_BACKOFF_SECONDS * (1 << (claim.attempts() - 1)))
                .update();
    }

    private void markSent(UUID id, String wamid) {
        jdbc.sql("""
                UPDATE messages SET status = 'SENT', wamid = :wamid, error_code = NULL, error_detail = NULL,
                    updated_at = now()
                WHERE id = :id AND status = 'SENDING'
                """)
                .param("id", id)
                .param("wamid", wamid)
                .update();
    }

    private void markFailed(UUID id, String code, String detail) {
        log.warn("El mensaje {} no pudo enviarse: error {}", id, code);
        jdbc.sql("""
                UPDATE messages SET status = 'FAILED', error_code = :code, error_detail = :detail, updated_at = now()
                WHERE id = :id
                """)
                .param("id", id)
                .param("code", code)
                .param("detail", detail)
                .update();
    }

    private void markUnknown(UUID id, String detail) {
        log.error("Resultado incierto al enviar el mensaje {}: no se reintenta", id);
        jdbc.sql("UPDATE messages SET status = 'UNKNOWN', error_detail = :detail, updated_at = now() WHERE id = :id")
                .param("id", id)
                .param("detail", detail)
                .update();
    }

    private void markAbandonedSendsAsUnknown() {
        int abandoned = jdbc.sql("""
                UPDATE messages SET status = 'UNKNOWN', error_detail = 'Envío interrumpido', updated_at = now()
                WHERE status = 'SENDING' AND updated_at < now() - make_interval(mins => :minutes)
                """)
                .param("minutes", SENDING_TIMEOUT_MINUTES)
                .update();
        if (abandoned > 0) {
            log.error("{} envíos interrumpidos quedaron en estado incierto", abandoned);
        }
    }

    private record Claim(UUID id, UUID accountId, String phoneNumber, String waUserId, String body, int attempts) {
    }
}
