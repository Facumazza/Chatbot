package com.chatbot.assistant;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Responses API de OpenAI. Con {@code store: false} OpenAI no guarda la conversación;
 * por eso cada vuelta reenvía los elementos que el modelo devolvió, incluido su razonamiento cifrado.
 */
@Component
@ConditionalOnProperty(name = "llm.api-format", havingValue = "responses", matchIfMissing = true)
class OpenAiResponsesClient implements LlmClient {

    private final LlmProperties properties;
    private final JsonMapper jsonMapper;
    private final LlmHttp http;

    OpenAiResponsesClient(LlmProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.http = new LlmHttp(properties, jsonMapper);
    }

    @Override
    public LlmResponse respond(LlmRequest request) {
        return parse(http.post("/responses", requestBody(request)));
    }

    private ObjectNode requestBody(LlmRequest request) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("model", request.model());
        body.put("instructions", request.instructions());
        ArrayNode input = body.putArray("input");
        request.input().forEach(input::add);
        body.set("tools", request.tools());
        body.put("tool_choice", "auto");
        body.put("parallel_tool_calls", false);
        body.put("store", false);
        body.put("max_output_tokens", properties.maxOutputTokens());
        if (properties.hasReasoningEffort()) {
            body.putObject("reasoning").put("effort", properties.reasoningEffort());
        }
        return body;
    }

    private static LlmResponse parse(JsonNode root) {
        if ("incomplete".equals(root.path("status").asString(""))) {
            throw new LlmException("Respuesta incompleta: " + root.path("incomplete_details").path("reason").asString(""), false);
        }
        List<JsonNode> output = new ArrayList<>();
        List<FunctionCall> calls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (JsonNode item : root.path("output")) {
            output.add(item);
            switch (item.path("type").asString("")) {
                case "function_call" -> calls.add(new FunctionCall(
                        item.path("call_id").asString(), item.path("name").asString(), item.path("arguments").asString("{}")));
                case "message" -> item.path("content").forEach(part -> {
                    if ("output_text".equals(part.path("type").asString(""))) {
                        text.append(part.path("text").asString(""));
                    }
                });
                default -> {
                    // Razonamiento y otros elementos: solo se reenvían.
                }
            }
        }
        JsonNode usage = root.path("usage");
        return new LlmResponse(output, text.toString().trim(), calls, new Usage(
                usage.path("input_tokens").asInt(0),
                usage.path("input_tokens_details").path("cached_tokens").asInt(0),
                usage.path("output_tokens").asInt(0)));
    }
}
