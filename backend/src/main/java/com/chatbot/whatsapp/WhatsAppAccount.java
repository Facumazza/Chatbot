package com.chatbot.whatsapp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Sin {@code @TenantId}: es justamente la tabla que permite averiguar la empresa de un evento. */
@Entity
@Table(name = "whatsapp_accounts")
class WhatsAppAccount {

    @Id
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "phone_number_id")
    private String phoneNumberId;

    @Column(name = "access_token_encrypted")
    private String accessTokenEncrypted;

    private boolean active;

    protected WhatsAppAccount() {
    }

    UUID getId() {
        return id;
    }

    UUID getTenantId() {
        return tenantId;
    }

    String getPhoneNumberId() {
        return phoneNumberId;
    }

    String getAccessTokenEncrypted() {
        return accessTokenEncrypted;
    }
}
