package com.chatbot.customer;

import com.chatbot.shared.Uuids;
import com.chatbot.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {

    private static final int MAX_FIELD_LENGTH = 120;

    private final CustomerRepository customers;
    private final JdbcClient jdbc;

    CustomerService(CustomerRepository customers, JdbcClient jdbc) {
        this.customers = customers;
        this.jdbc = jdbc;
    }

    /** Datos mínimos para el asistente: nunca incluye el teléfono. */
    @Transactional(readOnly = true)
    public CustomerProfile profile(UUID customerId) {
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("El cliente no existe"));
        Integer conversations = jdbc.sql("""
                SELECT count(*) FROM conversations WHERE tenant_id = :tenantId AND customer_id = :customerId
                """)
                .param("tenantId", TenantContext.require())
                .param("customerId", customerId)
                .query(Integer.class)
                .single();
        return new CustomerProfile(customer.displayName(), customer.getLocality(), conversations > 1);
    }

    /** Lo que el asistente aprendió en la charla. Los valores vacíos se ignoran. */
    @Transactional
    public void recordDetails(UUID customerId, String fullName, String locality, String interest, UUID sourceMessageId) {
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("El cliente no existe"));
        customer.completeDetails(clean(fullName), clean(locality));
        String cleanInterest = clean(interest);
        if (cleanInterest != null) {
            jdbc.sql("""
                    INSERT INTO customer_interests (id, tenant_id, customer_id, description, source_message_id)
                    VALUES (:id, :tenantId, :customerId, :description, :messageId)
                    """)
                    .param("id", Uuids.v7())
                    .param("tenantId", TenantContext.require())
                    .param("customerId", customerId)
                    .param("description", cleanInterest)
                    .param("messageId", sourceMessageId)
                    .update();
        }
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > MAX_FIELD_LENGTH ? trimmed.substring(0, MAX_FIELD_LENGTH) : trimmed;
    }

    /** Encuentra o crea al cliente, y completa los identificadores que todavía no tenía. */
    @Transactional
    public UUID resolveInbound(CustomerContact contact, Instant seenAt) {
        Customer customer = find(contact).orElseGet(() -> create(contact));
        linkMissingIdentifiers(customer, contact);
        customer.registerContact(contact.profileName(), seenAt);
        return customer.getId();
    }

    private Optional<Customer> find(CustomerContact contact) {
        Optional<Customer> byUserId = contact.waUserId() == null
                ? Optional.empty()
                : customers.findByWaUserId(contact.waUserId());
        if (byUserId.isPresent() || contact.phoneNumber() == null) {
            return byUserId;
        }
        return customers.findByPhoneNumber(contact.phoneNumber());
    }

    private Customer create(CustomerContact contact) {
        customers.insertIfAbsent(
                Uuids.v7(), TenantContext.require(), contact.waUserId(), contact.phoneNumber(), contact.profileName());
        return find(contact).orElseThrow(() -> new IllegalStateException("El cliente no pudo registrarse"));
    }

    private void linkMissingIdentifiers(Customer customer, CustomerContact contact) {
        if (customer.getWaUserId() == null && contact.waUserId() != null) {
            customer.linkWaUserId(contact.waUserId());
        }
        if (customer.getPhoneNumber() == null
                && contact.phoneNumber() != null
                && !customers.existsByPhoneNumber(contact.phoneNumber())) {
            customer.linkPhoneNumber(contact.phoneNumber());
        }
    }
}
