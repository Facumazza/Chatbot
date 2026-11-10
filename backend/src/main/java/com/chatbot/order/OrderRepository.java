package com.chatbot.order;

import com.chatbot.shared.Uuids;
import com.chatbot.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class OrderRepository {

    private final JdbcClient jdbc;

    OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record LocalOrder(UUID id, UUID tenantId, UUID conversationId, String externalOrderId, String status,
            String paymentStatus, BigDecimal total, String deliveryMethod, Timestamp createdAt) {
    }

    private static final String COLUMNS = """
            id, tenant_id, conversation_id, external_order_id, status, payment_status, total, delivery_method, created_at
            """;

    void save(UUID conversationId, UUID customerId, String externalOrderId, String status, String paymentStatus,
            BigDecimal total, String deliveryMethod, String requestJson) {
        jdbc.sql("""
                INSERT INTO orders (id, tenant_id, conversation_id, customer_id, external_order_id, status,
                    payment_status, total, delivery_method, request)
                VALUES (:id, :tenantId, :conversationId, :customerId, :externalId, :status, :paymentStatus, :total,
                    :delivery, CAST(:request AS jsonb))
                """)
                .param("id", Uuids.v7())
                .param("tenantId", TenantContext.require())
                .param("conversationId", conversationId)
                .param("customerId", customerId)
                .param("externalId", externalOrderId)
                .param("status", status)
                .param("paymentStatus", paymentStatus)
                .param("total", total)
                .param("delivery", deliveryMethod)
                .param("request", requestJson)
                .update();
    }

    Optional<LocalOrder> latest(UUID conversationId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                FROM orders WHERE tenant_id = :tenantId AND conversation_id = :conversationId
                ORDER BY created_at DESC LIMIT 1
                """)
                .param("tenantId", TenantContext.require())
                .param("conversationId", conversationId)
                .query(LocalOrder.class)
                .optional();
    }

    /** Pedidos de todas las empresas que todavía pueden cambiar. Lo usa el proceso de avisos. */
    List<LocalOrder> open() {
        return jdbc.sql("SELECT " + COLUMNS + """
                FROM orders WHERE status NOT IN ('ENTREGADO', 'CANCELADO')
                  AND created_at > now() - interval '60 days'
                """)
                .query(LocalOrder.class)
                .list();
    }

    void updateStatus(UUID id, String status, String paymentStatus, BigDecimal total) {
        jdbc.sql("""
                UPDATE orders SET status = :status, payment_status = :paymentStatus,
                    total = COALESCE(:total, total), updated_at = now()
                WHERE id = :id
                """)
                .param("id", id)
                .param("status", status)
                .param("paymentStatus", paymentStatus)
                .param("total", total)
                .update();
    }

    void markReceiptSent(UUID id) {
        jdbc.sql("UPDATE orders SET receipt_sent_at = now(), updated_at = now() WHERE id = :id")
                .param("id", id)
                .update();
    }
}
