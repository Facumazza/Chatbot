package com.chatbot.assistant;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Proveedor del modelo de lenguaje.
 *
 * @param apiFormat       {@code responses} (OpenAI) o {@code chat-completions} (Gemini y otros compatibles)
 * @param apiKey          vacía en desarrollo hasta tenerla: los turnos fallan y se derivan a un operador
 * @param reasoningEffort vacío para no enviarlo
 * @param pricing         precios en USD por millón de tokens, para estimar el costo de cada respuesta
 */
@ConfigurationProperties("llm")
public record LlmProperties(
        String apiFormat,
        String apiKey,
        String baseUrl,
        String model,
        String reasoningEffort,
        int maxOutputTokens,
        Duration timeout,
        Map<String, ModelPrice> pricing) {

    public record ModelPrice(BigDecimal input, BigDecimal cachedInput, BigDecimal output) {
    }

    boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    boolean hasReasoningEffort() {
        return reasoningEffort != null && !reasoningEffort.isBlank();
    }
}
