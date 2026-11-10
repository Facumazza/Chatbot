package com.chatbot.conversation;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Guarda con nombres generados por el sistema; el nombre que manda el cliente no se usa. */
@Component
class LocalMediaStorage implements MediaStorage {

    private final Path root;

    LocalMediaStorage(@Value("${app.media.storage-path}") Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public StoredMedia store(UUID tenantId, UUID mediaId, String extension, ContentWriter writer) throws IOException {
        Path directory = Files.createDirectories(root.resolve(tenantId.toString()));
        Path temporary = Files.createTempFile(directory, "upload-", ".tmp");
        try {
            MessageDigest digest = sha256();
            try (OutputStream target = new DigestOutputStream(Files.newOutputStream(temporary), digest)) {
                writer.writeTo(target);
            }
            String fileName = mediaId + extension;
            Files.move(temporary, directory.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            long size = Files.size(directory.resolve(fileName));
            return new StoredMedia(tenantId + "/" + fileName, size, HexFormat.of().formatHex(digest.digest()));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
