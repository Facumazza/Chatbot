package com.chatbot.assistant;

import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/** Puerto hacia el proveedor de modelos de lenguaje. */
public interface LlmClient {

    LlmResponse respond(LlmRequest request);

    /** @param input elementos de la conversación, incluidos los devueltos por el modelo en vueltas anteriores */
    record LlmRequest(String model, String instructions, List<JsonNode> input, ArrayNode tools) {
    }

    /** @param output elementos tal como llegaron, para reenviarlos en la vuelta siguiente */
    record LlmResponse(List<JsonNode> output, String text, List<FunctionCall> functionCalls, Usage usage) {
    }

    record FunctionCall(String callId, String name, String arguments) {
    }

    record Usage(int inputTokens, int cachedTokens, int outputTokens) {

        static final Usage NONE = new Usage(0, 0, 0);

        Usage plus(Usage other) {
            return new Usage(inputTokens + other.inputTokens, cachedTokens + other.cachedTokens,
                    outputTokens + other.outputTokens);
        }
    }

    /** @param retryable el mismo pedido puede funcionar más tarde */
    class LlmException extends RuntimeException {

        private final boolean retryable;

        public LlmException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        public boolean isRetryable() {
            return retryable;
        }
    }
}
