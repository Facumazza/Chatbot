package com.chatbot.conversation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    @Query("""
            SELECT c FROM Conversation c
            WHERE c.whatsappAccountId = :accountId AND c.customerId = :customerId
              AND c.status <> com.chatbot.conversation.Conversation.Status.CLOSED
            """)
    Optional<Conversation> findOpen(@Param("accountId") UUID accountId, @Param("customerId") UUID customerId);

    /** El índice único parcial garantiza una sola conversación abierta ante eventos simultáneos. */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO conversations (id, tenant_id, customer_id, whatsapp_account_id)
            VALUES (:id, :tenantId, :customerId, :accountId)
            ON CONFLICT DO NOTHING
            """)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("customerId") UUID customerId,
            @Param("accountId") UUID accountId);
}
