package com.chatbot.customer;

import com.chatbot.shared.Uuids;
import com.chatbot.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {

    private final CustomerRepository customers;

    CustomerService(CustomerRepository customers) {
        this.customers = customers;
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
