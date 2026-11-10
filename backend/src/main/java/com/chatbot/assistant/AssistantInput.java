package com.chatbot.assistant;

import java.util.List;
import java.util.UUID;

/**
 * Lo que el asistente necesita para responder un turno. Lo arma el módulo de conversaciones:
 * el asistente no accede a ellas directamente.
 *
 * @param receivedFiles archivos de la conversación, para adjuntarlos a la oportunidad comercial
 */
public record AssistantInput(
        UUID turnId,
        UUID conversationId,
        UUID customerId,
        List<HistoryItem> history,
        List<UUID> receivedFiles) {

    public record HistoryItem(boolean fromCustomer, String text, UUID messageId) {
    }
}
