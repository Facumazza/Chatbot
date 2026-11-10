package com.chatbot.conversation;

import com.chatbot.shared.Uuids;
import com.chatbot.tenant.TenantContext;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Auditoría de cambios de estado: quién derivó, quién tomó y cuándo volvió a la IA. */
@Component
class ConversationTransitions {

    private final JdbcClient jdbc;

    ConversationTransitions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void record(UUID conversationId, Conversation.Status from, Conversation.Status to, String actorType, String reason) {
        jdbc.sql("""
                INSERT INTO conversation_transitions (id, tenant_id, conversation_id, from_status, to_status, actor_type, reason)
                VALUES (:id, :tenantId, :conversationId, :from, :to, :actor, :reason)
                """)
                .param("id", Uuids.v7())
                .param("tenantId", TenantContext.require())
                .param("conversationId", conversationId)
                .param("from", from.name())
                .param("to", to.name())
                .param("actor", actorType)
                .param("reason", reason)
                .update();
    }
}
