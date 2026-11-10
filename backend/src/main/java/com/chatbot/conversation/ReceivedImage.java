package com.chatbot.conversation;

import java.time.Instant;
import java.util.UUID;

/** Imagen que mandó el cliente. {@code stored} es false mientras se descarga de WhatsApp. */
public record ReceivedImage(UUID mediaId, String storageKey, String mimeType, Instant receivedAt, boolean stored) {
}
