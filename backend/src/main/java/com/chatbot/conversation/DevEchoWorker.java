package com.chatbot.conversation;

import com.chatbot.tenant.TenantContext;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Respuesta fija para probar WhatsApp de punta a punta antes de que exista la IA.
 * Se activa solo con DEV_ECHO_ENABLED=true y se elimina en la Etapa 6.
 */
@Component
@ConditionalOnProperty(name = "app.dev-echo.enabled", havingValue = "true")
public class DevEchoWorker {

    static final String REPLY = "Hola, soy el asistente en modo de prueba. Recibí tu mensaje.";

    private final JdbcClient jdbc;
    private final ConversationService conversations;

    DevEchoWorker(JdbcClient jdbc, ConversationService conversations) {
        this.jdbc = jdbc;
        this.conversations = conversations;
    }

    @Scheduled(fixedDelayString = "${app.workers.outbound-delay}")
    public void answerDueTurns() {
        jdbc.sql("""
                UPDATE conversation_turns SET status = 'DONE', updated_at = now()
                WHERE id IN (
                    SELECT id FROM conversation_turns
                    WHERE status = 'PENDING' AND run_after <= now()
                    LIMIT 10
                    FOR UPDATE SKIP LOCKED)
                RETURNING tenant_id, conversation_id
                """)
                .query(DueTurn.class)
                .list()
                .forEach(turn -> TenantContext.runAs(turn.tenantId(), () ->
                        conversations.enqueueReply(turn.conversationId(), Message.SenderType.ASSISTANT, REPLY)));
    }

    private record DueTurn(UUID tenantId, UUID conversationId) {
    }
}
