package com.chatbot.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Formato Chat Completions, compatible con Gemini y otros proveedores. Traduce desde el formato
 * interno, que sigue a la Responses API, y reenvía cada mensaje del modelo exactamente como llegó:
 * Gemini lo exige para validar sus firmas de razonamiento al usar herramientas.
 */
@Component
@ConditionalOnProperty(name = "llm.api-format", havingValue = "chat-completions")
class ChatCompletionsClient implements LlmClient {

    /** Elemento interno que guarda el mensaje del modelo sin modificar. */
    static final String RAW_MESSAGE_TYPE = "chat_completions_message";

    private final LlmProperties properties;
    private final JsonMapper jsonMapper;
    private final LlmHttp http;

    ChatCompletionsClient(LlmProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.http = new LlmHttp(properties, jsonMapper);
    }

    @Override
    public LlmResponse respond(LlmRequest request) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("model", request.model());
        body.set("messages", messages(request));
        body.set("tools", tools(request.tools()));
        body.put("tool_choice", "auto");
        body.put("max_tokens", properties.maxOutputTokens());
        if (properties.hasReasoningEffort()) {
            body.put("reasoning_effort", properties.reasoningEffort());
        }
        return parse(http.post("/chat/completions", body));
    }

    private ArrayNode messages(LlmRequest request) {
        ArrayNode messages = jsonMapper.createArrayNode();
        messages.add(message("system", request.instructions()));
        for (JsonNode item : request.input()) {
            String type = item.path("type").asString("");
            if (RAW_MESSAGE_TYPE.equals(type)) {
                messages.add(item.path("message"));
            } else if ("function_call_output".equals(type)) {
                ObjectNode tool = jsonMapper.createObjectNode();
                tool.put("role", "tool");
                tool.put("tool_call_id", item.path("call_id").asString());
                tool.put("content", item.path("output").asString());
                messages.add(tool);
            } else if (item.has("role")) {
                String role = item.path("role").asString();
                messages.add(message("developer".equals(role) ? "system" : role, item.path("content").asString("")));
            }
        }
        return messages;
    }

    /** Formato de herramientas de Chat Completions, con un esquema que aceptan todos los proveedores. */
    private ArrayNode tools(ArrayNode definitions) {
        ArrayNode tools = jsonMapper.createArrayNode();
        for (JsonNode definition : definitions) {
            ObjectNode tool = tools.addObject();
            tool.put("type", "function");
            ObjectNode function = tool.putObject("function");
            function.put("name", definition.path("name").asString());
            function.put("description", definition.path("description").asString());
            function.set("parameters", portableSchema(definition.path("parameters")));
        }
        return tools;
    }

    /**
     * Quita lo propio del modo estricto de OpenAI: tipos con null y {@code additionalProperties}.
     * Un campo que admitía null deja de ser obligatorio; las herramientas ya toleran su ausencia.
     */
    JsonNode portableSchema(JsonNode schema) {
        ObjectNode copy = (ObjectNode) schema.deepCopy();
        copy.remove("additionalProperties");
        if (copy.path("type").isArray()) {
            String concrete = "string";
            for (JsonNode type : copy.path("type")) {
                if (!"null".equals(type.asString())) {
                    concrete = type.asString();
                }
            }
            copy.put("type", concrete);
        }
        if (copy.path("properties").isObject()) {
            ObjectNode properties = jsonMapper.createObjectNode();
            ArrayNode required = jsonMapper.createArrayNode();
            for (Map.Entry<String, JsonNode> property : copy.path("properties").properties()) {
                properties.set(property.getKey(), portableSchema(property.getValue()));
                if (!isNullable(property.getValue()) && isRequired(copy, property.getKey())) {
                    required.add(property.getKey());
                }
            }
            copy.set("properties", properties);
            copy.set("required", required);
        }
        if (copy.path("items").isObject()) {
            copy.set("items", portableSchema(copy.path("items")));
        }
        return copy;
    }

    private LlmResponse parse(JsonNode root) {
        JsonNode choice = root.path("choices").path(0);
        if ("length".equals(choice.path("finish_reason").asString(""))) {
            throw new LlmException("Respuesta incompleta: límite de tokens", false);
        }
        JsonNode message = choice.path("message");
        List<FunctionCall> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            calls.add(new FunctionCall(call.path("id").asString(),
                    call.path("function").path("name").asString(),
                    call.path("function").path("arguments").asString("{}")));
        }
        ObjectNode raw = jsonMapper.createObjectNode();
        raw.put("type", RAW_MESSAGE_TYPE);
        raw.set("message", message);
        JsonNode usage = root.path("usage");
        String text = message.path("content").isString() ? message.path("content").asString().trim() : "";
        return new LlmResponse(List.of(raw), text, calls, new Usage(
                usage.path("prompt_tokens").asInt(0),
                usage.path("prompt_tokens_details").path("cached_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0)));
    }

    private ObjectNode message(String role, String content) {
        ObjectNode message = jsonMapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static boolean isNullable(JsonNode schema) {
        for (JsonNode type : schema.path("type")) {
            if ("null".equals(type.asString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRequired(JsonNode schema, String field) {
        for (JsonNode name : schema.path("required")) {
            if (field.equals(name.asString())) {
                return true;
            }
        }
        return false;
    }
}
