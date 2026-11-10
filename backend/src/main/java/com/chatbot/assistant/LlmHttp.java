package com.chatbot.assistant;

import com.chatbot.assistant.LlmClient.LlmException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** POST con reintentos acotados, común a los formatos de API. Nunca registra la clave ni el contenido. */
final class LlmHttp {

    private static final Logger log = LoggerFactory.getLogger(LlmHttp.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long MAX_RETRY_WAIT_MS = 10_000;

    /** Errores de cuota o facturación: reintentar no los resuelve. */
    private static final Set<String> BILLING_CODES = Set.of(
            "credit_balance_exhausted", "insufficient_quota", "organization_spend_limit_exceeded",
            "project_spend_limit_exceeded", "organization_usage_limit_exceeded");

    private final LlmProperties properties;
    private final JsonMapper jsonMapper;
    private final RestClient rest;

    LlmHttp(LlmProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.timeout());
        this.rest = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }

    JsonNode post(String path, JsonNode body) {
        if (!properties.hasApiKey()) {
            throw new LlmException("Falta LLM_API_KEY", false);
        }
        String json = jsonMapper.writeValueAsString(body);
        for (int attempt = 1; ; attempt++) {
            try {
                return jsonMapper.readTree(rest.post()
                        .uri(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json)
                        .retrieve()
                        .body(String.class));
            } catch (RestClientResponseException e) {
                LlmException failure = classify(e);
                if (!failure.isRetryable() || attempt == MAX_ATTEMPTS) {
                    throw failure;
                }
                waitBeforeRetry(attempt, e.getResponseHeaders());
            } catch (ResourceAccessException e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new LlmException("Sin respuesta del proveedor de IA", true);
                }
                waitBeforeRetry(attempt, null);
            }
        }
    }

    private LlmException classify(RestClientResponseException e) {
        JsonNode error = parseError(e.getResponseBodyAsString());
        String code = error.path("code").isString() ? error.path("code").asString("") : error.path("status").asString("");
        int status = e.getStatusCode().value();
        log.warn("El proveedor de IA respondió {} ({})", status, code.isBlank() ? "sin código" : code);
        boolean retryable = !BILLING_CODES.contains(code) && (status == 429 || status >= 500);
        return new LlmException("Proveedor de IA " + status + (code.isBlank() ? "" : " " + code), retryable);
    }

    private JsonNode parseError(String body) {
        try {
            JsonNode root = jsonMapper.readTree(body);
            // OpenAI devuelve un objeto; Gemini puede devolver una lista con un objeto.
            return (root.isArray() ? root.path(0) : root).path("error");
        } catch (JacksonException e) {
            return jsonMapper.createObjectNode();
        }
    }

    private static void waitBeforeRetry(int attempt, HttpHeaders headers) {
        long waitMs = 1000L << (attempt - 1);
        String retryAfter = headers == null ? null : headers.getFirst("Retry-After");
        if (retryAfter != null && retryAfter.matches("\\d+")) {
            waitMs = Long.parseLong(retryAfter) * 1000;
        }
        try {
            Thread.sleep(Math.min(waitMs, MAX_RETRY_WAIT_MS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LlmException("Reintento interrumpido", true);
        }
    }
}
