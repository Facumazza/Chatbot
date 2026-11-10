package com.chatbot.conversation;

import java.time.Instant;

/** Cambio de estado de un mensaje saliente informado por el canal. */
public record DeliveryUpdate(
        String providerMessageId,
        Message.Status status,
        Instant occurredAt,
        String errorCode,
        String errorDetail) {
}
