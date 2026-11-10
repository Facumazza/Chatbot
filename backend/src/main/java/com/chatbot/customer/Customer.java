package com.chatbot.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "customers")
public class Customer {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "wa_user_id")
    private String waUserId;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "wa_profile_name")
    private String waProfileName;

    @Column(name = "full_name")
    private String fullName;

    private String locality;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private int version;

    protected Customer() {
    }

    public UUID getId() {
        return id;
    }

    String getWaUserId() {
        return waUserId;
    }

    String getPhoneNumber() {
        return phoneNumber;
    }

    /** Nombre para dirigirse al cliente: el que dio en la charla o, si no, el de su perfil. */
    String displayName() {
        return fullName != null ? fullName : waProfileName;
    }

    String getLocality() {
        return locality;
    }

    void completeDetails(String newFullName, String newLocality) {
        if (newFullName != null) {
            this.fullName = newFullName;
        }
        if (newLocality != null) {
            this.locality = newLocality;
        }
        this.updatedAt = Instant.now();
    }

    void linkWaUserId(String value) {
        this.waUserId = value;
    }

    void linkPhoneNumber(String value) {
        this.phoneNumber = value;
    }

    void registerContact(String profileName, Instant seenAt) {
        if (profileName != null) {
            this.waProfileName = profileName;
        }
        if (lastSeenAt == null || seenAt.isAfter(lastSeenAt)) {
            this.lastSeenAt = seenAt;
        }
        this.updatedAt = Instant.now();
    }
}
