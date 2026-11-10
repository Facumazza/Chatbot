package com.chatbot.conversation;

import com.chatbot.tenant.TenantContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Historial reciente en texto, tal como lo lee el asistente. */
@Component
class ConversationHistory {

    private final JdbcClient jdbc;

    ConversationHistory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Entry(boolean fromCustomer, String text, UUID messageId) {
    }

    List<Entry> recent(UUID conversationId, int limit) {
        List<Entry> entries = new ArrayList<>(jdbc.sql("""
                SELECT m.id, m.direction, m.type, m.body, mm.original_filename
                FROM messages m
                LEFT JOIN message_media mm ON mm.tenant_id = m.tenant_id AND mm.message_id = m.id
                WHERE m.tenant_id = :tenantId AND m.conversation_id = :conversationId
                  AND m.status NOT IN ('FAILED') AND m.type <> 'REACTION'
                ORDER BY m.created_at DESC, m.id DESC
                LIMIT :limit
                """)
                .param("tenantId", TenantContext.require())
                .param("conversationId", conversationId)
                .param("limit", limit)
                .query((rs, row) -> {
                    boolean inbound = "INBOUND".equals(rs.getString("direction"));
                    String text = inbound
                            ? describeInbound(rs.getString("type"), rs.getString("body"), rs.getString("original_filename"))
                            : rs.getString("body");
                    return new Entry(inbound, text, rs.getObject("id", UUID.class));
                })
                .list());
        entries.removeIf(entry -> entry.text() == null || entry.text().isBlank());
        Collections.reverse(entries);
        return entries;
    }

    List<UUID> receivedFiles(UUID conversationId) {
        return jdbc.sql("""
                SELECT mm.id FROM message_media mm
                JOIN messages m ON m.tenant_id = mm.tenant_id AND m.id = mm.message_id
                WHERE mm.tenant_id = :tenantId AND m.conversation_id = :conversationId
                  AND mm.download_status IN ('PENDING', 'STORED')
                """)
                .param("tenantId", TenantContext.require())
                .param("conversationId", conversationId)
                .query(UUID.class)
                .list();
    }

    List<ReceivedImage> receivedImages(UUID conversationId) {
        return jdbc.sql("""
                SELECT mm.id AS media_id, mm.storage_key, mm.mime_type, mm.created_at AS received_at,
                       mm.download_status = 'STORED' AS stored
                FROM message_media mm
                JOIN messages m ON m.tenant_id = mm.tenant_id AND m.id = mm.message_id
                WHERE mm.tenant_id = :tenantId AND m.conversation_id = :conversationId
                  AND mm.mime_type LIKE 'image/%' AND mm.download_status IN ('PENDING', 'STORED')
                ORDER BY mm.created_at
                """)
                .param("tenantId", TenantContext.require())
                .param("conversationId", conversationId)
                .query(ReceivedImage.class)
                .list();
    }

    /** Los archivos se describen: el modelo no ve imágenes ni escucha audios en esta versión. */
    private static String describeInbound(String type, String body, String filename) {
        String caption = body == null || body.isBlank() ? "" : " con el texto: " + body;
        return switch (type) {
            case "TEXT", "INTERACTIVE", "BUTTON" -> body;
            case "IMAGE" -> "[El cliente envió una imagen" + caption + "]";
            case "DOCUMENT" -> "[El cliente envió un documento" + (filename == null ? "" : " (" + filename + ")") + caption + "]";
            case "AUDIO" -> "[El cliente envió un audio. No podés escuchar audios.]";
            default -> "[El cliente envió un mensaje de tipo " + type.toLowerCase() + " que no podés ver]";
        };
    }
}
