package com.chatbot.conversation;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/** Puerto hacia el almacenamiento de archivos: volumen local en el MVP, S3 compatible después. */
public interface MediaStorage {

    StoredMedia store(UUID tenantId, UUID mediaId, String extension, ContentWriter writer) throws IOException;

    byte[] read(String storageKey) throws IOException;

    @FunctionalInterface
    interface ContentWriter {
        void writeTo(OutputStream target) throws IOException;
    }

    record StoredMedia(String storageKey, long sizeBytes, String sha256) {
    }
}
