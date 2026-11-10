package com.chatbot.conversation;

import com.chatbot.conversation.InboundMessage.MediaAttachment;
import com.chatbot.shared.Uuids;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Estado persistente de los archivos pendientes de descarga. */
@Component
class MediaQueue {

    private static final int LEASE_MINUTES = 10;

    private final JdbcClient jdbc;

    MediaQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void enqueue(UUID tenantId, UUID messageId, MediaAttachment media) {
        jdbc.sql("""
                INSERT INTO message_media (id, tenant_id, message_id, wa_media_id, mime_type, original_filename)
                VALUES (:id, :tenantId, :messageId, :mediaId, :mimeType, :filename)
                """)
                .param("id", Uuids.v7())
                .param("tenantId", tenantId)
                .param("messageId", messageId)
                .param("mediaId", media.providerMediaId())
                .param("mimeType", media.mimeType())
                .param("filename", media.filename())
                .update();
    }

    /** El plazo de reintento funciona como lease: si la instancia se cae, otra lo retoma. */
    List<Claim> claim(int limit) {
        return jdbc.sql("""
                WITH claimed AS (
                    UPDATE message_media
                    SET attempts = attempts + 1, next_attempt_at = now() + make_interval(mins => :lease)
                    WHERE id IN (
                        SELECT id FROM message_media
                        WHERE download_status = 'PENDING' AND next_attempt_at <= now()
                        ORDER BY next_attempt_at
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED)
                    RETURNING id, tenant_id, message_id, wa_media_id, mime_type, attempts)
                SELECT claimed.id, claimed.tenant_id, conv.whatsapp_account_id AS account_id,
                       claimed.wa_media_id, claimed.mime_type, claimed.attempts
                FROM claimed
                JOIN messages m ON m.tenant_id = claimed.tenant_id AND m.id = claimed.message_id
                JOIN conversations conv ON conv.tenant_id = m.tenant_id AND conv.id = m.conversation_id
                """)
                .param("lease", LEASE_MINUTES)
                .param("limit", limit)
                .query(Claim.class)
                .list();
    }

    void markStored(UUID id, MediaStorage.StoredMedia stored) {
        jdbc.sql("""
                UPDATE message_media
                SET download_status = 'STORED', storage_key = :key, size_bytes = :size, sha256 = :sha256, updated_at = now()
                WHERE id = :id
                """)
                .param("id", id)
                .param("key", stored.storageKey())
                .param("size", stored.sizeBytes())
                .param("sha256", stored.sha256())
                .update();
    }

    void markFinal(UUID id, String status) {
        jdbc.sql("UPDATE message_media SET download_status = :status, updated_at = now() WHERE id = :id")
                .param("id", id)
                .param("status", status)
                .update();
    }

    void retryAfter(UUID id, int delaySeconds) {
        jdbc.sql("UPDATE message_media SET next_attempt_at = now() + make_interval(secs => :delay), updated_at = now() WHERE id = :id")
                .param("id", id)
                .param("delay", delaySeconds)
                .update();
    }

    record Claim(UUID id, UUID tenantId, UUID accountId, String waMediaId, String mimeType, int attempts) {
    }
}
