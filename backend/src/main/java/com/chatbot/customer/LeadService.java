package com.chatbot.customer;

import com.chatbot.shared.Uuids;
import com.chatbot.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Oportunidades comerciales. Hay una sola abierta por conversación y se va completando. */
@Service
public class LeadService {

    private final JdbcClient jdbc;

    LeadService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param detailsJson    datos a sumar a los existentes (talles, colores, diseño, localidad)
     * @param quoteJson      copia del presupuesto calculado; null si todavía no se cotizó
     * @param attachmentIds  archivos recibidos en la conversación, para que el operador revise el diseño
     */
    @Transactional
    public UUID register(UUID customerId, UUID conversationId, UUID productId, Integer quantity, String detailsJson,
            BigDecimal estimatedAmount, String quoteJson, List<UUID> attachmentIds) {
        UUID tenantId = TenantContext.require();
        UUID leadId = openLead(tenantId, conversationId).orElseGet(() -> create(tenantId, customerId, conversationId));
        jdbc.sql("""
                UPDATE leads SET
                    product_id = COALESCE(:productId, product_id),
                    quantity = COALESCE(:quantity, quantity),
                    details = details || CAST(:details AS jsonb),
                    estimated_amount = COALESCE(:amount, estimated_amount),
                    currency = CASE WHEN CAST(:amount AS numeric) IS NULL THEN currency ELSE 'ARS' END,
                    quote_snapshot = COALESCE(CAST(:quote AS jsonb), quote_snapshot),
                    status = CASE
                        WHEN CAST(:quote AS jsonb) IS NOT NULL THEN 'QUOTED'
                        WHEN status = 'NEW' THEN 'QUALIFIED'
                        ELSE status END,
                    updated_at = now(),
                    version = version + 1
                WHERE tenant_id = :tenantId AND id = :leadId
                """)
                .param("productId", productId)
                .param("quantity", quantity)
                .param("details", detailsJson == null ? "{}" : detailsJson)
                .param("amount", estimatedAmount)
                .param("quote", quoteJson)
                .param("tenantId", tenantId)
                .param("leadId", leadId)
                .update();
        for (UUID mediaId : attachmentIds) {
            jdbc.sql("""
                    INSERT INTO lead_attachments (id, tenant_id, lead_id, media_id)
                    VALUES (:id, :tenantId, :leadId, :mediaId)
                    ON CONFLICT (tenant_id, lead_id, media_id) DO NOTHING
                    """)
                    .param("id", Uuids.v7())
                    .param("tenantId", tenantId)
                    .param("leadId", leadId)
                    .param("mediaId", mediaId)
                    .update();
        }
        return leadId;
    }

    /** La oportunidad se convirtió en un pedido. */
    @Transactional
    public void markWon(UUID conversationId) {
        jdbc.sql("""
                UPDATE leads SET status = 'WON', closed_at = now(), updated_at = now(), version = version + 1
                WHERE tenant_id = :tenantId AND conversation_id = :conversationId AND status NOT IN ('WON', 'LOST')
                """)
                .param("tenantId", TenantContext.require())
                .param("conversationId", conversationId)
                .update();
    }

    private Optional<UUID> openLead(UUID tenantId, UUID conversationId) {
        return jdbc.sql("""
                SELECT id FROM leads
                WHERE tenant_id = :tenantId AND conversation_id = :conversationId AND status NOT IN ('WON', 'LOST')
                FOR UPDATE
                """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .query(UUID.class)
                .optional();
    }

    private UUID create(UUID tenantId, UUID customerId, UUID conversationId) {
        UUID id = Uuids.v7();
        jdbc.sql("INSERT INTO leads (id, tenant_id, customer_id, conversation_id) VALUES (:id, :tenantId, :customerId, :conversationId)")
                .param("id", id)
                .param("tenantId", tenantId)
                .param("customerId", customerId)
                .param("conversationId", conversationId)
                .update();
        return id;
    }
}
