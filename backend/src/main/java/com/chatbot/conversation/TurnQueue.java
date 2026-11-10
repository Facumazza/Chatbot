package com.chatbot.conversation;

import com.chatbot.shared.Uuids;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Deja pendiente la respuesta de una conversación, agrupando los mensajes seguidos. */
@Component
class TurnQueue {

    // En la Etapa 7 estos valores se leen de la configuración de cada empresa.
    private static final int DEBOUNCE_SECONDS = 4;
    private static final int DEBOUNCE_MAX_SECONDS = 20;

    private final JdbcClient jdbc;

    TurnQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void enqueue(UUID tenantId, UUID conversationId) {
        jdbc.sql("""
                INSERT INTO conversation_turns (id, tenant_id, conversation_id, run_after)
                VALUES (:id, :tenantId, :conversationId, now() + make_interval(secs => :debounce))
                ON CONFLICT (conversation_id) WHERE status = 'PENDING'
                DO UPDATE SET
                    run_after = LEAST(
                        now() + make_interval(secs => :debounce),
                        conversation_turns.first_queued_at + make_interval(secs => :maxDebounce)),
                    updated_at = now()
                """)
                .param("id", Uuids.v7())
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .param("debounce", DEBOUNCE_SECONDS)
                .param("maxDebounce", DEBOUNCE_MAX_SECONDS)
                .update();
    }
}
