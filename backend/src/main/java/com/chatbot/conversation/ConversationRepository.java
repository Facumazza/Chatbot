package com.chatbot.conversation;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /** Bloquea la fila: el operador no puede tomar la conversación mientras se decide el envío. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Conversation c WHERE c.id = :id")
    Optional<Conversation> findForUpdate(@Param("id") UUID id);

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
