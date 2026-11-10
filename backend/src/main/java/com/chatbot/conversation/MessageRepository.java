package com.chatbot.conversation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MessageRepository extends JpaRepository<Message, UUID> {

    Optional<Message> findByWamid(String wamid);

    boolean existsByWamid(String wamid);

    @Query("""
            SELECT count(m) > 0 FROM Message m
            WHERE m.conversationId = :conversationId
              AND m.direction = com.chatbot.conversation.Message.Direction.INBOUND
              AND m.createdAt > :after
            """)
    boolean existsInboundAfter(@Param("conversationId") UUID conversationId, @Param("after") Instant after);

    boolean existsByConversationIdAndDirectionAndBody(UUID conversationId, Message.Direction direction, String body);

    /** Devuelve 0 si Meta reenvió un mensaje ya guardado. */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO messages (id, tenant_id, conversation_id, direction, sender_type, wamid, type, body, status, provider_at)
            VALUES (:id, :tenantId, :conversationId, 'INBOUND', 'CUSTOMER', :wamid, :type, CAST(:body AS text), 'RECEIVED', :sentAt)
            ON CONFLICT (tenant_id, wamid) DO NOTHING
            """)
    int insertInboundIfAbsent(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("conversationId") UUID conversationId,
            @Param("wamid") String wamid,
            @Param("type") String type,
            @Param("body") String body,
            @Param("sentAt") Instant sentAt);
}
