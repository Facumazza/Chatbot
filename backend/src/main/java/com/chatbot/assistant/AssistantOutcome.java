package com.chatbot.assistant;

import java.util.UUID;

public sealed interface AssistantOutcome {

    UUID runId();

    /** @param handoffReason no null si hay que pasar la conversación a un operador antes de enviar */
    record Reply(String text, String handoffReason, UUID runId) implements AssistantOutcome {
    }

    record Failure(boolean retryable, String reason, UUID runId) implements AssistantOutcome {
    }
}
