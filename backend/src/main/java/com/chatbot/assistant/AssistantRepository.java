package com.chatbot.assistant;

import com.chatbot.assistant.LlmClient.Usage;
import com.chatbot.shared.Uuids;
import com.chatbot.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Configuración del asistente y registro de cada ejecución, con tokens y costo estimado. */
@Component
class AssistantRepository {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final JdbcClient jdbc;
    private final LlmProperties properties;

    AssistantRepository(JdbcClient jdbc, LlmProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    Optional<AssistantSettings> settings() {
        return jdbc.sql("""
                SELECT c.assistant_enabled AS enabled, p.id AS prompt_version_id, p.system_prompt, c.llm_model AS model,
                       c.history_message_limit AS history_limit, c.max_tool_iterations,
                       c.disabled_message, c.fallback_message
                FROM chatbot_configurations c
                JOIN chatbot_prompt_versions p ON p.tenant_id = c.tenant_id AND p.id = c.active_prompt_version_id
                WHERE c.tenant_id = :tenantId
                """)
                .param("tenantId", TenantContext.require())
                .query(AssistantSettings.class)
                .optional();
    }

    UUID recordRun(UUID conversationId, UUID turnId, UUID promptVersionId, String model, Usage usage,
            List<String> toolNames, int iterations, long latencyMs, String outcome) {
        UUID id = Uuids.v7();
        jdbc.sql("""
                INSERT INTO assistant_runs (id, tenant_id, conversation_id, turn_id, prompt_version_id, model,
                    input_tokens, cached_tokens, output_tokens, tool_names, iterations, latency_ms,
                    estimated_cost_usd, outcome)
                VALUES (:id, :tenantId, :conversationId, :turnId, :promptVersionId, :model,
                    :input, :cached, :output, CAST(:tools AS text[]), :iterations, :latency, :cost, :outcome)
                """)
                .param("id", id)
                .param("tenantId", TenantContext.require())
                .param("conversationId", conversationId)
                .param("turnId", turnId)
                .param("promptVersionId", promptVersionId)
                .param("model", model)
                .param("input", usage.inputTokens())
                .param("cached", usage.cachedTokens())
                .param("output", usage.outputTokens())
                // Los nombres de herramienta son identificadores fijos: no requieren escape.
                .param("tools", "{" + String.join(",", toolNames) + "}")
                .param("iterations", iterations)
                .param("latency", (int) latencyMs)
                .param("cost", cost(model, usage))
                .param("outcome", outcome)
                .update();
        return id;
    }

    void markDiscarded(UUID runId) {
        jdbc.sql("UPDATE assistant_runs SET outcome = 'DISCARDED' WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", TenantContext.require())
                .param("id", runId)
                .update();
    }

    private BigDecimal cost(String model, Usage usage) {
        LlmProperties.ModelPrice price = properties.pricing() == null ? null : properties.pricing().get(model);
        if (price == null) {
            return null;
        }
        BigDecimal uncached = BigDecimal.valueOf(usage.inputTokens() - usage.cachedTokens()).multiply(price.input());
        BigDecimal cached = BigDecimal.valueOf(usage.cachedTokens()).multiply(price.cachedInput());
        BigDecimal output = BigDecimal.valueOf(usage.outputTokens()).multiply(price.output());
        return uncached.add(cached).add(output).divide(MILLION, 6, RoundingMode.HALF_UP);
    }
}
