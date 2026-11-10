package com.chatbot.assistant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chatbot.assistant.LlmClient.FunctionCall;
import com.chatbot.assistant.LlmClient.LlmException;
import com.chatbot.assistant.LlmClient.LlmRequest;
import com.chatbot.assistant.LlmClient.LlmResponse;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

class ChatCompletionsClientTests {

    private static final WireMockServer GEMINI = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final String PATH = "/v1beta/openai/chat/completions";

    private final JsonMapper json = JsonMapper.builder().build();
    private ChatCompletionsClient client;
    private ArrayNode tools;

    @BeforeAll
    static void start() {
        GEMINI.start();
    }

    @AfterAll
    static void stop() {
        GEMINI.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        GEMINI.resetAll();
        LlmProperties properties = new LlmProperties("chat-completions", "clave-gemini",
                GEMINI.baseUrl() + "/v1beta/openai", "gemini-3.6-flash", "low", 4000, Duration.ofSeconds(5), Map.of());
        client = new ChatCompletionsClient(properties, json);
        try (var stream = new ClassPathResource("assistant/tools.json").getInputStream()) {
            tools = (ArrayNode) json.readTree(stream);
        }
    }

    @Test
    void translatesRequestToChatCompletionsFormat() {
        GEMINI.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(textResponse("Hola"))));
        List<JsonNode> input = new ArrayList<>(List.of(
                json.readTree("{\"role\":\"user\",\"content\":\"Hola\"}"),
                json.readTree("{\"role\":\"developer\",\"content\":\"Corregí\"}")));

        LlmResponse response = client.respond(new LlmRequest("gemini-3.6-flash", "Sos el asistente.", input, tools));

        assertThat(response.text()).isEqualTo("Hola");
        JsonNode sent = lastRequest();
        assertThat(GEMINI.getAllServeEvents().getFirst().getRequest().getHeader("Authorization"))
                .isEqualTo("Bearer clave-gemini");
        assertThat(sent.path("messages").path(0).path("role").asString()).isEqualTo("system");
        assertThat(sent.path("messages").path(0).path("content").asString()).isEqualTo("Sos el asistente.");
        assertThat(sent.path("messages").path(2).path("role").asString()).isEqualTo("system");
        assertThat(sent.path("reasoning_effort").asString()).isEqualTo("low");
        JsonNode quote = sent.path("tools").path(1).path("function");
        assertThat(quote.path("name").asString()).isEqualTo("calcular_presupuesto");
        assertThat(quote.path("parameters").has("additionalProperties")).isFalse();
        assertThat(quote.path("parameters").path("properties").path("design_size").path("type").asString())
                .isEqualTo("string");
        assertThat(quote.path("parameters").path("required").toString()).isEqualTo("[\"product_id\",\"lines\"]");
        assertThat(quote.path("parameters").path("properties").path("lines").path("items").path("required").toString())
                .isEqualTo("[\"quantity\"]");
    }

    @Test
    void replaysModelMessageExactlyAsReceived() {
        GEMINI.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("""
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                 "tool_calls":[{"id":"call_1","type":"function",
                   "function":{"name":"buscar_productos","arguments":"{\\"query\\":\\"remera\\"}"},
                   "extra_content":{"google":{"thought_signature":"firma-123"}}}]}}],
                 "usage":{"prompt_tokens":50,"completion_tokens":10}}
                """)));
        List<JsonNode> input = new ArrayList<>(List.of(json.readTree("{\"role\":\"user\",\"content\":\"remeras?\"}")));

        LlmResponse first = client.respond(new LlmRequest("m", "i", input, tools));

        assertThat(first.functionCalls()).containsExactly(new FunctionCall("call_1", "buscar_productos", "{\"query\":\"remera\"}"));
        assertThat(first.usage().inputTokens()).isEqualTo(50);
        input.addAll(first.output());
        input.add(json.readTree("{\"type\":\"function_call_output\",\"call_id\":\"call_1\",\"output\":\"{}\"}"));
        GEMINI.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(textResponse("Listo"))));

        client.respond(new LlmRequest("m", "i", input, tools));

        JsonNode messages = lastRequest().path("messages");
        assertThat(messages.path(2).path("tool_calls").path(0).path("extra_content").path("google")
                .path("thought_signature").asString()).isEqualTo("firma-123");
        assertThat(messages.path(3).path("role").asString()).isEqualTo("tool");
        assertThat(messages.path(3).path("tool_call_id").asString()).isEqualTo("call_1");
    }

    @Test
    void truncatedAnswerIsAFailure() {
        GEMINI.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("""
                {"choices":[{"finish_reason":"length","message":{"role":"assistant","content":"Hola, te"}}]}
                """)));

        assertThatThrownBy(() -> client.respond(new LlmRequest("m", "i", new ArrayList<>(), tools)))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.isRetryable()).isFalse());
    }

    @Test
    void invalidKeyIsNotRetried() {
        GEMINI.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("[{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"message\":\"API key not valid\"}}]")));

        assertThatThrownBy(() -> client.respond(new LlmRequest("m", "i", new ArrayList<>(), tools)))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.isRetryable()).isFalse());
        GEMINI.verify(1, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer clave-gemini")));
    }

    private JsonNode lastRequest() {
        return json.readTree(GEMINI.getAllServeEvents().getFirst().getRequest().getBodyAsString());
    }

    private static String textResponse(String text) {
        return """
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"%s"}}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5}}
                """.formatted(text);
    }
}
