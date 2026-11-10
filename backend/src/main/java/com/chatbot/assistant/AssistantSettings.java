package com.chatbot.assistant;

import java.util.UUID;

/** Configuración vigente del asistente de una empresa. Se edita sin desplegar. */
public record AssistantSettings(
        boolean enabled,
        UUID promptVersionId,
        String systemPrompt,
        String model,
        int historyLimit,
        int maxToolIterations,
        String disabledMessage,
        String fallbackMessage) {
}
