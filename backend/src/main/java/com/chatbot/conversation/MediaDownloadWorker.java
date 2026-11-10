package com.chatbot.conversation;

import com.chatbot.conversation.MediaQueue.Claim;
import com.chatbot.conversation.MessagingGateway.MediaTooLargeException;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/** Descarga los archivos recibidos; WhatsApp solo los conserva unos días. */
@Component
public class MediaDownloadWorker {

    private static final Logger log = LoggerFactory.getLogger(MediaDownloadWorker.class);
    private static final int BATCH_SIZE = 10;
    private static final int MAX_ATTEMPTS = 6;
    private static final int BASE_BACKOFF_SECONDS = 30;

    private static final Map<String, String> ALLOWED_TYPES = Map.ofEntries(
            Map.entry("image/jpeg", ".jpg"),
            Map.entry("image/png", ".png"),
            Map.entry("image/webp", ".webp"),
            Map.entry("application/pdf", ".pdf"),
            Map.entry("audio/ogg", ".ogg"),
            Map.entry("audio/mpeg", ".mp3"),
            Map.entry("audio/mp4", ".m4a"),
            Map.entry("audio/aac", ".aac"),
            Map.entry("audio/amr", ".amr"));

    private final MediaQueue queue;
    private final MessagingGateway gateway;
    private final MediaStorage storage;
    private final long maxBytes;

    MediaDownloadWorker(
            MediaQueue queue,
            MessagingGateway gateway,
            MediaStorage storage,
            @Value("${app.media.max-size}") DataSize maxSize) {
        this.queue = queue;
        this.gateway = gateway;
        this.storage = storage;
        this.maxBytes = maxSize.toBytes();
    }

    @Scheduled(fixedDelayString = "${app.workers.media-delay}")
    public void downloadPending() {
        queue.claim(BATCH_SIZE).forEach(this::download);
    }

    private void download(Claim claim) {
        String extension = ALLOWED_TYPES.get(baseMimeType(claim.mimeType()));
        if (extension == null) {
            log.warn("Archivo {} rechazado por tipo no permitido", claim.id());
            queue.markFinal(claim.id(), "REJECTED");
            return;
        }
        try {
            queue.markStored(claim.id(), storage.store(claim.tenantId(), claim.id(), extension, target ->
                    gateway.downloadMedia(claim.accountId(), claim.waMediaId(), target, maxBytes)));
        } catch (MediaTooLargeException e) {
            log.warn("Archivo {} rechazado por tamaño", claim.id());
            queue.markFinal(claim.id(), "REJECTED");
        } catch (IOException | RuntimeException e) {
            retryOrFail(claim, e);
        }
    }

    private void retryOrFail(Claim claim, Exception cause) {
        log.warn("Falló la descarga del archivo {} ({})", claim.id(), cause.getClass().getSimpleName());
        if (claim.attempts() >= MAX_ATTEMPTS) {
            queue.markFinal(claim.id(), "FAILED");
        } else {
            queue.retryAfter(claim.id(), BASE_BACKOFF_SECONDS * (1 << (claim.attempts() - 1)));
        }
    }

    private static String baseMimeType(String mimeType) {
        return mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }
}
