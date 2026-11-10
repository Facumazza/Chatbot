package com.chatbot.conversation;

import com.chatbot.customer.CustomerContact;
import java.time.Instant;
import java.util.Set;

/** Mensaje recibido, independiente del formato del canal. {@code media} es null si no hay adjunto. */
public record InboundMessage(
        String providerMessageId,
        CustomerContact contact,
        String type,
        String body,
        MediaAttachment media,
        Instant sentAt) {

    private static final Set<String> NON_CONVERSATIONAL_TYPES = Set.of("REACTION", "SYSTEM");

    public record MediaAttachment(String providerMediaId, String mimeType, String filename) {
    }

    boolean expectsReply() {
        return !NON_CONVERSATIONAL_TYPES.contains(type);
    }
}
