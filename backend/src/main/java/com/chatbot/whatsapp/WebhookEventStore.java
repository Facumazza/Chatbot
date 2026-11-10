package com.chatbot.whatsapp;

import com.chatbot.shared.Uuids;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Cola de eventos crudos de Meta. */
@Component
class WebhookEventStore {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventStore.class);
    private static final int LEASE_MINUTES = 2;
    private static final int MAX_ATTEMPTS = 8;
    private static final int BASE_BACKOFF_SECONDS = 5;
    private static final int MAX_BACKOFF_SECONDS = 600;
    private static final int RETENTION_DAYS = 30;

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    WebhookEventStore(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    void save(byte[] rawBody) {
        String text = new String(rawBody, StandardCharsets.UTF_8);
        if (isStorableJson(text)) {
            insert(text, "PENDING", null);
            return;
        }
        // Se conserva para diagnóstico, y Meta recibe 200 para que no lo reenvíe durante días.
        log.error("Webhook firmado con contenido inválido");
        String preserved = jsonMapper.writeValueAsString(
                Map.of("malformedBodyBase64", Base64.getEncoder().encodeToString(rawBody)));
        insert(preserved, "DEAD", "Contenido inválido");
    }

    List<ClaimedEvent> claim(int limit) {
        return jdbc.sql("""
                UPDATE webhook_events
                SET status = 'PROCESSING', attempts = attempts + 1,
                    locked_until = now() + make_interval(mins => :lease)
                WHERE id IN (
                    SELECT id FROM webhook_events
                    WHERE (status = 'PENDING' AND next_attempt_at <= now())
                       OR (status = 'PROCESSING' AND locked_until < now())
                    ORDER BY received_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED)
                RETURNING id, payload::text AS payload, attempts
                """)
                .param("lease", LEASE_MINUTES)
                .param("limit", limit)
                .query(ClaimedEvent.class)
                .list();
    }

    void markDone(UUID id) {
        jdbc.sql("""
                UPDATE webhook_events
                SET status = 'DONE', locked_until = NULL, last_error = NULL, processed_at = now()
                WHERE id = :id
                """)
                .param("id", id)
                .update();
    }

    void markFailure(ClaimedEvent event, Exception cause) {
        // Solo la clase del error: el mensaje podría contener datos personales.
        String error = cause.getClass().getSimpleName();
        if (event.attempts() >= MAX_ATTEMPTS) {
            jdbc.sql("UPDATE webhook_events SET status = 'DEAD', locked_until = NULL, last_error = :error WHERE id = :id")
                    .param("id", event.id())
                    .param("error", error)
                    .update();
            return;
        }
        int delay = Math.min(BASE_BACKOFF_SECONDS * (1 << (event.attempts() - 1)), MAX_BACKOFF_SECONDS);
        jdbc.sql("""
                UPDATE webhook_events
                SET status = 'PENDING', locked_until = NULL, last_error = :error,
                    next_attempt_at = now() + make_interval(secs => :delay)
                WHERE id = :id
                """)
                .param("id", event.id())
                .param("error", error)
                .param("delay", delay)
                .update();
    }

    int purgeExpired() {
        return jdbc.sql("""
                DELETE FROM webhook_events
                WHERE status IN ('DONE', 'DEAD') AND received_at < now() - make_interval(days => :days)
                """)
                .param("days", RETENTION_DAYS)
                .update();
    }

    private void insert(String payload, String status, String error) {
        jdbc.sql("""
                INSERT INTO webhook_events (id, payload, status, last_error)
                VALUES (:id, CAST(:payload AS jsonb), :status, :error)
                """)
                .param("id", Uuids.v7())
                .param("payload", payload)
                .param("status", status)
                .param("error", error)
                .update();
    }

    private boolean isStorableJson(String text) {
        // PostgreSQL rechaza el carácter nulo dentro de jsonb.
        if (text.contains("\\u0000")) {
            return false;
        }
        try {
            jsonMapper.readTree(text);
            return true;
        } catch (JacksonException e) {
            return false;
        }
    }

    record ClaimedEvent(UUID id, String payload, int attempts) {
    }
}
