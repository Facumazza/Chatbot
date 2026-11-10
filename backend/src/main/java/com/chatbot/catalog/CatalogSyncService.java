package com.chatbot.catalog;

import com.chatbot.catalog.GrenlusProductMapper.DesiredOption;
import com.chatbot.catalog.GrenlusProductMapper.DesiredTier;
import com.chatbot.shared.Uuids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Copia el catálogo de la web. Si un precio cambia, cierra la vigencia del anterior y crea uno
 * nuevo: los presupuestos ya enviados siguen siendo explicables.
 */
@Service
public class CatalogSyncService {

    private static final Logger log = LoggerFactory.getLogger(CatalogSyncService.class);
    private static final String PILOT_SLUG = "grenlus";

    private final CatalogProperties properties;
    private final GrenlusCatalogClient client;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final JsonMapper jsonMapper;

    CatalogSyncService(
            CatalogProperties properties,
            GrenlusCatalogClient client,
            JdbcClient jdbc,
            TransactionTemplate transaction,
            JsonMapper jsonMapper) {
        this.properties = properties;
        this.client = client;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.jsonMapper = jsonMapper;
    }

    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "${catalog.sync-interval}")
    public void syncScheduled() {
        try {
            sync();
        } catch (RuntimeException e) {
            // Se conserva el último catálogo válido; el bot deriva si queda desactualizado.
            log.error("Falló la sincronización del catálogo ({})", e.getClass().getSimpleName());
        }
    }

    /** @return cantidad de productos recibidos, o vacío si no hay empresa piloto o sincronización */
    public Optional<Integer> sync() {
        if (!properties.syncEnabled()) {
            return Optional.empty();
        }
        Optional<UUID> tenantId = jdbc.sql("SELECT id FROM tenants WHERE slug = :slug")
                .param("slug", PILOT_SLUG)
                .query(UUID.class)
                .optional();
        if (tenantId.isEmpty()) {
            return Optional.empty();
        }
        List<SourceProduct> source = fetch();
        transaction.executeWithoutResult(status -> store(tenantId.get(), source));
        log.info("Catálogo sincronizado: {} productos", source.size());
        return Optional.of(source.size());
    }

    private List<SourceProduct> fetch() {
        List<SourceProduct> products = new ArrayList<>();
        for (JsonNode product : client.products()) {
            String externalId = product.path("id").asString("");
            JsonNode colors = product.path("usaColores").asBoolean(false)
                    ? client.colors(externalId)
                    : jsonMapper.createArrayNode();
            products.add(new SourceProduct(externalId, product, colors));
        }
        return products;
    }

    private void store(UUID tenantId, List<SourceProduct> source) {
        Instant now = jdbc.sql("SELECT now()").query(Timestamp.class).single().toInstant();
        Set<String> seen = new HashSet<>();
        for (SourceProduct item : source) {
            seen.add(item.externalId());
            UUID productId = upsertProduct(tenantId, item);
            List<DesiredOption> options = GrenlusProductMapper.options(item.product(), item.colors());
            Map<String, UUID> optionIds = upsertOptions(tenantId, productId, options);
            replaceTiersIfChanged(tenantId, productId, GrenlusProductMapper.tiers(item.product(), options), optionIds, now);
        }
        jdbc.sql("""
                UPDATE products SET active = false, updated_at = now()
                WHERE tenant_id = :tenantId AND external_id IS NOT NULL AND external_id NOT IN (:seen)
                """)
                .param("tenantId", tenantId)
                .param("seen", withSentinel(seen))
                .update();
        jdbc.sql("UPDATE products SET synced_at = :now WHERE tenant_id = :tenantId AND external_id IS NOT NULL")
                .param("now", Timestamp.from(now))
                .param("tenantId", tenantId)
                .update();
    }

    private UUID upsertProduct(UUID tenantId, SourceProduct item) {
        JsonNode product = item.product();
        return jdbc.sql("""
                INSERT INTO products (id, tenant_id, kind, name, description, attributes, active, external_id, public_url)
                VALUES (:id, :tenantId, 'PRODUCT', :name, :description, CAST(:attributes AS jsonb), :active, :externalId, :url)
                ON CONFLICT (tenant_id, external_id) WHERE external_id IS NOT NULL
                DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description,
                    attributes = EXCLUDED.attributes, active = EXCLUDED.active,
                    public_url = EXCLUDED.public_url, updated_at = now()
                RETURNING id
                """)
                .param("id", Uuids.v7())
                .param("tenantId", tenantId)
                .param("name", product.path("nombre").asString("").trim())
                .param("description", product.path("descripcion").asString("").trim())
                .param("attributes", jsonMapper.writeValueAsString(GrenlusProductMapper.attributes(product)))
                .param("active", product.path("activo").asBoolean(false))
                .param("externalId", item.externalId())
                .param("url", properties.publicUrl() + "/producto/" + item.externalId())
                .query(UUID.class)
                .single();
    }

    private Map<String, UUID> upsertOptions(UUID tenantId, UUID productId, List<DesiredOption> options) {
        Map<String, UUID> ids = new HashMap<>();
        int order = 0;
        for (DesiredOption option : options) {
            UUID id = jdbc.sql("""
                    INSERT INTO product_options (id, tenant_id, product_id, option_type, value, attributes, active, sort_order)
                    VALUES (:id, :tenantId, :productId, :type, :value, CAST(:attributes AS jsonb), true, :order)
                    ON CONFLICT (tenant_id, product_id, option_type, value)
                    DO UPDATE SET attributes = EXCLUDED.attributes, active = true,
                        sort_order = EXCLUDED.sort_order, updated_at = now()
                    RETURNING id
                    """)
                    .param("id", Uuids.v7())
                    .param("tenantId", tenantId)
                    .param("productId", productId)
                    .param("type", option.type())
                    .param("value", option.value())
                    .param("attributes", jsonMapper.writeValueAsString(option.attributes()))
                    .param("order", order++)
                    .query(UUID.class)
                    .single();
            ids.put(option.key(), id);
        }
        jdbc.sql("""
                UPDATE product_options SET active = false, updated_at = now()
                WHERE tenant_id = :tenantId AND product_id = :productId AND active
                  AND option_type || '|' || value NOT IN (:keys)
                """)
                .param("tenantId", tenantId)
                .param("productId", productId)
                .param("keys", withSentinel(ids.keySet()))
                .update();
        return ids;
    }

    private void replaceTiersIfChanged(UUID tenantId, UUID productId, List<DesiredTier> desired,
            Map<String, UUID> optionIds, Instant now) {
        Set<String> desiredSignatures = desired.stream()
                .map(tier -> signature(optionIds.get(tier.optionKey()), tier.minQuantity(), tier.maxQuantity(), tier.unitPrice()))
                .collect(Collectors.toSet());
        Set<String> currentSignatures = jdbc.sql("""
                SELECT option_id, min_quantity, max_quantity, unit_price FROM price_tiers
                WHERE tenant_id = :tenantId AND product_id = :productId AND valid_to IS NULL
                """)
                .param("tenantId", tenantId)
                .param("productId", productId)
                .query((rs, row) -> signature(
                        rs.getObject("option_id", UUID.class),
                        rs.getInt("min_quantity"),
                        (Integer) rs.getObject("max_quantity"),
                        rs.getBigDecimal("unit_price")))
                .set();
        if (desiredSignatures.equals(currentSignatures)) {
            return;
        }
        jdbc.sql("""
                UPDATE price_tiers SET valid_to = :now
                WHERE tenant_id = :tenantId AND product_id = :productId AND valid_to IS NULL
                """)
                .param("now", Timestamp.from(now))
                .param("tenantId", tenantId)
                .param("productId", productId)
                .update();
        for (DesiredTier tier : desired) {
            jdbc.sql("""
                    INSERT INTO price_tiers (id, tenant_id, product_id, option_id, min_quantity, max_quantity, unit_price, valid_from)
                    VALUES (:id, :tenantId, :productId, :optionId, :min, :max, :price, :now)
                    """)
                    .param("id", Uuids.v7())
                    .param("tenantId", tenantId)
                    .param("productId", productId)
                    .param("optionId", tier.optionKey() == null ? null : optionIds.get(tier.optionKey()))
                    .param("min", tier.minQuantity())
                    .param("max", tier.maxQuantity())
                    .param("price", tier.unitPrice().setScale(2, RoundingMode.HALF_UP))
                    .param("now", Timestamp.from(now))
                    .update();
        }
    }

    /** Spring expande la colección a "IN (?, ?)"; una lista vacía generaría SQL inválido. */
    private static List<String> withSentinel(Set<String> values) {
        List<String> list = new ArrayList<>(values);
        list.add("__sin_valor__");
        return list;
    }

    private static String signature(UUID optionId, int min, Integer max, BigDecimal price) {
        return optionId + "|" + min + "|" + max + "|" + price.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private record SourceProduct(String externalId, JsonNode product, JsonNode colors) {
    }
}
