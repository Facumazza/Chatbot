package com.chatbot.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "messages")
public class Message {

    public enum Direction {
        INBOUND, OUTBOUND
    }

    public enum SenderType {
        CUSTOMER, ASSISTANT, AGENT, SYSTEM
    }

    /** El orden importa: un estado solo puede avanzar. FAILED es definitivo. */
    public enum Status {
        RECEIVED, PENDING, SENDING, UNKNOWN, SENT, DELIVERED, READ, FAILED
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type")
    private SenderType senderType;

    private String wamid;

    private String type;

    private String body;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_detail")
    private String errorDetail;

    @Column(name = "provider_at")
    private Instant providerAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Message() {
    }

    static Message pendingText(UUID id, UUID conversationId, SenderType senderType, String body, Instant now) {
        Message message = new Message();
        message.id = id;
        message.conversationId = conversationId;
        message.direction = Direction.OUTBOUND;
        message.senderType = senderType;
        message.type = "TEXT";
        message.body = body;
        message.status = Status.PENDING;
        message.createdAt = now;
        message.updatedAt = now;
        return message;
    }

    public UUID getId() {
        return id;
    }

    public Status getStatus() {
        return status;
    }

    /** Meta no garantiza el orden de los estados: uno atrasado no debe pisar a uno posterior. */
    void applyDeliveryUpdate(DeliveryUpdate update) {
        if (update.status().ordinal() <= status.ordinal() || status == Status.FAILED) {
            return;
        }
        status = update.status();
        providerAt = update.occurredAt();
        errorCode = update.errorCode();
        errorDetail = update.errorDetail();
        updatedAt = Instant.now();
    }
}
