package com.chatbot.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "conversations")
public class Conversation {

    /** Plazo de Meta para enviar texto libre desde el último mensaje del cliente. */
    private static final Duration CUSTOMER_SERVICE_WINDOW = Duration.ofHours(24);

    public enum Status {
        ACTIVE, WAITING_FOR_AGENT, WITH_AGENT, CLOSED
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "whatsapp_account_id")
    private UUID whatsappAccountId;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "last_inbound_at")
    private Instant lastInboundAt;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private int version;

    protected Conversation() {
    }

    public UUID getId() {
        return id;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    /** @return false si la conversación ya no estaba a cargo de la IA */
    boolean requestAgent() {
        if (status != Status.ACTIVE) {
            return false;
        }
        status = Status.WAITING_FOR_AGENT;
        updatedAt = Instant.now();
        return true;
    }

    public boolean isCustomerServiceWindowOpen(Instant now) {
        return lastInboundAt != null && lastInboundAt.plus(CUSTOMER_SERVICE_WINDOW).isAfter(now);
    }

    void registerInbound(Instant sentAt) {
        if (lastInboundAt == null || sentAt.isAfter(lastInboundAt)) {
            lastInboundAt = sentAt;
        }
        registerActivity(sentAt);
    }

    void registerActivity(Instant at) {
        if (lastMessageAt == null || at.isAfter(lastMessageAt)) {
            lastMessageAt = at;
        }
        updatedAt = Instant.now();
    }
}
