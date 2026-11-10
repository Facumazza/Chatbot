package com.chatbot.conversation;

import com.chatbot.assistant.AssistantInput;
import com.chatbot.assistant.AssistantOutcome;
import com.chatbot.assistant.AssistantService;
import com.chatbot.assistant.AssistantSettings;
import com.chatbot.tenant.TenantContext;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Responde los turnos pendientes. La llamada al modelo ocurre fuera de toda transacción;
 * el envío se decide al final, con la conversación bloqueada.
 */
@Component
public class TurnProcessor {

    private static final Logger log = LoggerFactory.getLogger(TurnProcessor.class);
    private static final int BATCH_SIZE = 5;
    private static final int LEASE_MINUTES = 3;
    private static final int MAX_ATTEMPTS = 3;
    private static final int RETRY_DELAY_SECONDS = 30;

    private final JdbcClient jdbc;
    private final ConversationService conversations;
    private final ConversationHistory history;
    private final AssistantService assistant;
    private final String workerId = UUID.randomUUID().toString();

    TurnProcessor(JdbcClient jdbc, ConversationService conversations, ConversationHistory history,
            AssistantService assistant) {
        this.jdbc = jdbc;
        this.conversations = conversations;
        this.history = history;
        this.assistant = assistant;
    }

    @Scheduled(fixedDelayString = "${app.workers.turn-delay}")
    public void processDueTurns() {
        recoverAbandonedTurns();
        claim().forEach(turn -> TenantContext.runAs(turn.tenantId(), () -> process(turn)));
    }

    private void process(Claim turn) {
        try {
            Conversation conversation = conversations.get(turn.conversationId());
            if (conversation.getStatus() != Conversation.Status.ACTIVE) {
                finish(turn, "DISCARDED", null);
                return;
            }
            Optional<AssistantSettings> settings = assistant.settings();
            if (settings.isEmpty()) {
                log.error("La empresa {} no tiene configurado el asistente", turn.tenantId());
                finish(turn, "FAILED", "Sin configuración");
                return;
            }
            if (!settings.get().enabled()) {
                answerWhileDisabled(turn, settings.get());
                return;
            }
            AssistantOutcome outcome = assistant.respond(settings.get(), input(turn, conversation, settings.get()));
            switch (outcome) {
                case AssistantOutcome.Reply reply -> deliver(turn, reply);
                case AssistantOutcome.Failure failure -> handleFailure(turn, failure, settings.get());
            }
        } catch (RuntimeException e) {
            log.error("Error inesperado en el turno {}", turn.id(), e);
            retryOrFail(turn, e.getClass().getSimpleName());
        }
    }

    private AssistantInput input(Claim turn, Conversation conversation, AssistantSettings settings) {
        List<AssistantInput.HistoryItem> items = history.recent(turn.conversationId(), settings.historyLimit()).stream()
                .map(entry -> new AssistantInput.HistoryItem(entry.fromCustomer(), entry.text(), entry.messageId()))
                .toList();
        return new AssistantInput(turn.id(), turn.conversationId(), conversation.getCustomerId(), items,
                history.receivedFiles(turn.conversationId()));
    }

    private void deliver(Claim turn, AssistantOutcome.Reply reply) {
        boolean handedOff = reply.handoffReason() != null
                && conversations.requestAgent(turn.conversationId(), reply.handoffReason());
        boolean sent = conversations.deliverAutomaticReply(
                turn.conversationId(), reply.text(), turn.claimedAt().toInstant(), handedOff);
        if (!sent) {
            assistant.markDiscarded(reply.runId());
            log.info("Respuesta descartada en la conversación {}: cambió mientras se generaba", turn.conversationId());
        }
        finish(turn, sent ? "DONE" : "DISCARDED", null);
    }

    private void handleFailure(Claim turn, AssistantOutcome.Failure failure, AssistantSettings settings) {
        if (failure.retryable() && turn.attempts() < MAX_ATTEMPTS) {
            retryOrFail(turn, failure.reason());
            return;
        }
        // Sin respuesta posible: el cliente no queda sin atención.
        boolean handedOff = conversations.requestAgent(turn.conversationId(), "Asistente sin respuesta: " + failure.reason());
        conversations.deliverAutomaticReply(turn.conversationId(), settings.fallbackMessage(),
                turn.claimedAt().toInstant(), handedOff);
        finish(turn, "FAILED", failure.reason());
    }

    private void answerWhileDisabled(Claim turn, AssistantSettings settings) {
        if (!conversations.alreadySent(turn.conversationId(), settings.disabledMessage())) {
            conversations.deliverAutomaticReply(turn.conversationId(), settings.disabledMessage(),
                    turn.claimedAt().toInstant(), false);
        }
        finish(turn, "DONE", null);
    }

    private List<Claim> claim() {
        return jdbc.sql("""
                WITH due AS (
                    SELECT t.id FROM conversation_turns t
                    WHERE t.status = 'PENDING' AND t.run_after <= now()
                      AND NOT EXISTS (
                          SELECT 1 FROM conversation_turns p
                          WHERE p.conversation_id = t.conversation_id AND p.status = 'PROCESSING')
                    ORDER BY t.run_after
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED)
                UPDATE conversation_turns t
                SET status = 'PROCESSING', attempts = t.attempts + 1, locked_by = :worker, updated_at = now(),
                    locked_until = now() + make_interval(mins => :lease)
                FROM due WHERE t.id = due.id
                RETURNING t.id, t.tenant_id, t.conversation_id, t.attempts, now() AS claimed_at
                """)
                .param("limit", BATCH_SIZE)
                .param("worker", workerId)
                .param("lease", LEASE_MINUTES)
                .query(Claim.class)
                .list();
    }

    /** Si una instancia se cayó a mitad de un turno, otra lo retoma cuando vence el lease. */
    private void recoverAbandonedTurns() {
        jdbc.sql("""
                UPDATE conversation_turns t SET status = 'DISCARDED', locked_until = NULL, updated_at = now()
                WHERE t.status = 'PROCESSING' AND t.locked_until < now()
                  AND EXISTS (SELECT 1 FROM conversation_turns p
                              WHERE p.conversation_id = t.conversation_id AND p.status = 'PENDING')
                """).update();
        jdbc.sql("""
                UPDATE conversation_turns SET status = 'PENDING', locked_until = NULL, run_after = now(), updated_at = now()
                WHERE status = 'PROCESSING' AND locked_until < now()
                """).update();
    }

    private void retryOrFail(Claim turn, String error) {
        if (turn.attempts() >= MAX_ATTEMPTS) {
            finish(turn, "FAILED", error);
            return;
        }
        // Si ya llegó un mensaje nuevo hay otro turno pendiente: ese responde todo junto.
        jdbc.sql("""
                UPDATE conversation_turns t
                SET status = CASE WHEN EXISTS (SELECT 1 FROM conversation_turns p
                                               WHERE p.conversation_id = t.conversation_id AND p.status = 'PENDING')
                                  THEN 'DISCARDED' ELSE 'PENDING' END,
                    run_after = now() + make_interval(secs => :delay),
                    locked_until = NULL, last_error = :error, updated_at = now()
                WHERE t.id = :id
                """)
                .param("id", turn.id())
                .param("delay", RETRY_DELAY_SECONDS)
                .param("error", error)
                .update();
    }

    private void finish(Claim turn, String status, String error) {
        jdbc.sql("""
                UPDATE conversation_turns SET status = :status, locked_until = NULL, last_error = :error, updated_at = now()
                WHERE id = :id
                """)
                .param("id", turn.id())
                .param("status", status)
                .param("error", error)
                .update();
    }

    /** @param claimedAt hora de la base al tomar el turno: un mensaje posterior invalida la respuesta */
    private record Claim(UUID id, UUID tenantId, UUID conversationId, int attempts, Timestamp claimedAt) {
    }
}
