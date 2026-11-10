package com.chatbot.customer;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByWaUserId(String waUserId);

    Optional<Customer> findByPhoneNumber(String phoneNumber);

    boolean existsByPhoneNumber(String phoneNumber);

    /** Inserción atómica: dos eventos simultáneos del mismo cliente crean una sola fila. */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO customers (id, tenant_id, wa_user_id, phone_number, wa_profile_name)
            VALUES (:id, :tenantId, CAST(:waUserId AS text), CAST(:phone AS text), CAST(:profileName AS text))
            ON CONFLICT DO NOTHING
            """)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("waUserId") String waUserId,
            @Param("phone") String phone,
            @Param("profileName") String profileName);
}
