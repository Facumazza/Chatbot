package com.chatbot.catalog;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Lecturas del catálogo vigente. Toda consulta filtra explícitamente por empresa. */
@Component
class CatalogRepository {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    CatalogRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    record ProductRow(UUID id, String name, String description, String publicUrl, int minOrderQuantity,
            Integer leadTimeDays) {
    }

    record OptionRow(UUID id, String type, String value, JsonNode attributes) {
    }

    record TierRow(UUID optionId, int minQuantity, Integer maxQuantity, BigDecimal unitPrice) {

        boolean covers(int quantity) {
            return quantity >= minQuantity && (maxQuantity == null || quantity <= maxQuantity);
        }
    }

    record Product(ProductRow row, List<OptionRow> options, List<TierRow> tiers) {
    }

    List<Product> activeProducts(UUID tenantId) {
        List<ProductRow> rows = jdbc.sql("""
                SELECT id, name, description, public_url, min_order_quantity, lead_time_days
                FROM products WHERE tenant_id = :tenantId AND active ORDER BY name
                """)
                .param("tenantId", tenantId)
                .query(ProductRow.class)
                .list();
        Map<UUID, List<OptionRow>> options = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT o.product_id, o.id, o.option_type, o.value, o.attributes::text AS attributes
                FROM product_options o JOIN products p ON p.tenant_id = o.tenant_id AND p.id = o.product_id
                WHERE o.tenant_id = :tenantId AND o.active AND p.active
                ORDER BY o.sort_order
                """)
                .param("tenantId", tenantId)
                .query((rs, row) -> {
                    options.computeIfAbsent(rs.getObject("product_id", UUID.class), id -> new ArrayList<>())
                            .add(new OptionRow(rs.getObject("id", UUID.class), rs.getString("option_type"),
                                    rs.getString("value"), jsonMapper.readTree(rs.getString("attributes"))));
                    return null;
                })
                .list();
        Map<UUID, List<TierRow>> tiers = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT product_id, option_id, min_quantity, max_quantity, unit_price FROM price_tiers
                WHERE tenant_id = :tenantId AND tstzrange(valid_from, valid_to, '[)') @> now()
                """)
                .param("tenantId", tenantId)
                .query((rs, row) -> {
                    tiers.computeIfAbsent(rs.getObject("product_id", UUID.class), id -> new ArrayList<>())
                            .add(new TierRow(rs.getObject("option_id", UUID.class), rs.getInt("min_quantity"),
                                    (Integer) rs.getObject("max_quantity"), rs.getBigDecimal("unit_price")));
                    return null;
                })
                .list();
        return rows.stream()
                .map(row -> new Product(row, options.getOrDefault(row.id(), List.of()), tiers.getOrDefault(row.id(), List.of())))
                .toList();
    }

    Optional<Instant> lastSync(UUID tenantId) {
        return Optional.ofNullable(jdbc.sql("""
                SELECT max(synced_at) FROM products WHERE tenant_id = :tenantId AND external_id IS NOT NULL
                """)
                .param("tenantId", tenantId)
                .query(Timestamp.class)
                .single()).map(Timestamp::toInstant);
    }
}
