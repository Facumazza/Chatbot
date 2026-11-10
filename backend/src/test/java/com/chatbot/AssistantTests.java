package com.chatbot;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.chatbot.catalog.CatalogSyncService;
import com.chatbot.conversation.TurnProcessor;
import com.chatbot.whatsapp.WebhookEventProcessor;
import com.github.tomakehurst.wiremock.client.ScenarioMappingBuilder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

class AssistantTests extends IntegrationTest {

    private static final String RESPONSES = "/v1/responses";
    private static final String FALLBACK = "Te paso con una persona del equipo.";

    @Autowired
    private WebhookEventProcessor webhooks;

    @Autowired
    private TurnProcessor turns;

    @Autowired
    private CatalogSyncService catalogSync;

    @Autowired
    private JsonMapper jsonMapper;

    private UUID remeraId;

    @BeforeEach
    void prepareCompany() {
        GrenlusFixture.stubCatalog(META, GrenlusFixture.remera(14900));
        catalogSync.sync();
        remeraId = jdbc.queryForObject("SELECT id FROM products WHERE external_id = '1'", UUID.class);
        UUID promptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chatbot_prompt_versions (id, tenant_id, version_number, system_prompt)
                VALUES (?, ?, 1, 'Sos el asistente de Grenlus.')
                """, promptId, tenantId);
        jdbc.update("""
                INSERT INTO chatbot_configurations (id, tenant_id, active_prompt_version_id, privacy_notice,
                    disabled_message, outside_hours_message, fallback_message)
                VALUES (?, ?, ?, 'aviso', 'Asistente apagado.', 'fuera de horario', ?)
                """, UUID.randomUUID(), tenantId, promptId, FALLBACK);
    }

    @Test
    void quotesWithToolAndSendsTheVerifiedAmount() throws Exception {
        stubSequence(
                functionCall("calcular_presupuesto", Map.of("product_id", remeraId.toString(), "design_size", "CHICA",
                        "lines", List.of(Map.of("quantity", 1, "size", "M", "color", "Negro")))),
                message("Una remera talle M con estampa chica sale $16.900."));
        customerWrites("¿Cuánto sale una remera M con un logo chico?");

        turns.processDueTurns();

        assertThat(lastOutbound()).isEqualTo("Una remera talle M con estampa chica sale $16.900.");
        META.verify(postRequestedFor(urlEqualTo(RESPONSES))
                .withHeader("Authorization", equalTo("Bearer clave-de-prueba"))
                .withRequestBody(matchingJsonPath("$.store", equalTo("false")))
                .withRequestBody(matchingJsonPath("$.input[?(@.type == 'function_call_output')]"))
                .withRequestBody(matchingJsonPath("$.input[?(@.type == 'reasoning')]")));
        assertThat(jdbc.queryForMap("SELECT outcome, input_tokens, cached_tokens, iterations, estimated_cost_usd, "
                + "array_to_string(tool_names, ',') AS tools FROM assistant_runs"))
                .containsEntry("outcome", "REPLIED")
                .containsEntry("input_tokens", 200)
                .containsEntry("cached_tokens", 40)
                .containsEntry("iterations", 2)
                .containsEntry("tools", "calcular_presupuesto");
        assertThat(count("SELECT count(*) FROM assistant_runs WHERE estimated_cost_usd > 0")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM conversation_turns WHERE status = 'DONE'")).isEqualTo(1);
    }

    @Test
    void customerPhoneIsNeverSentToTheModel() throws Exception {
        stubSequence(message("¡Hola! Soy el asistente virtual de Grenlus."));
        customerWrites("Hola");

        turns.processDueTurns();

        assertThat(META.findAll(postRequestedFor(urlEqualTo(RESPONSES))).getFirst().getBodyAsString())
                .doesNotContain("5491155550000")
                .contains("Ana");
    }

    @Test
    void inventedAmountIsCorrectedOnceThenHandedOff() throws Exception {
        stubSequence(message("Sale $9.999."), message("Sale $9.999, te lo aseguro."));
        customerWrites("¿Cuánto sale?");

        turns.processDueTurns();

        assertThat(lastOutbound()).isEqualTo(FALLBACK);
        assertThat(conversationStatus()).isEqualTo("WAITING_FOR_AGENT");
        assertThat(jdbc.queryForObject("SELECT reason FROM conversation_transitions", String.class))
                .isEqualTo("Importes no verificados");
        META.verify(postRequestedFor(urlEqualTo(RESPONSES))
                .withRequestBody(matchingJsonPath("$.input[?(@.role == 'developer')]")));
    }

    @Test
    void handoffToolPassesConversationToAnAgentAndNotifiesCustomer() throws Exception {
        stubSequence(functionCall("derivar_a_operador", Map.of("reason", "Pide hablar con una persona")),
                message("Listo, una persona del equipo te va a responder."));
        customerWrites("Quiero hablar con alguien");

        turns.processDueTurns();

        assertThat(conversationStatus()).isEqualTo("WAITING_FOR_AGENT");
        assertThat(lastOutbound()).isEqualTo("Listo, una persona del equipo te va a responder.");
        assertThat(jdbc.queryForObject("SELECT outcome FROM assistant_runs", String.class)).isEqualTo("HANDED_OFF");
    }

    @Test
    void leadKeepsQuoteAndReceivedDesign() throws Exception {
        stubSequence(
                functionCall("calcular_presupuesto", Map.of("product_id", remeraId.toString(), "design_size", "CHICA",
                        "lines", List.of(Map.of("quantity", 1, "size", "M", "color", "Negro")))),
                functionCall("registrar_oportunidad", Map.of("product_id", remeraId.toString(), "quantity", 1,
                        "sizes", "1 M", "colors", "Negro", "design", "chica en el frente", "notes", "Manda logo")),
                message("Recibí tu diseño. Te queda en $16.900."));
        postSigned(envelope(PHONE_NUMBER_ID, """
                "messages":[{"from_user_id":"AR.1","id":"wamid.img","timestamp":"1700000000","type":"image",
                "image":{"id":"media-1","mime_type":"image/png","caption":"mi logo"}}]
                """));
        customerWrites("¿Cuánto sale una remera con este logo?");

        turns.processDueTurns();

        assertThat(jdbc.queryForMap("SELECT status, estimated_amount, quantity, details->>'design' AS design, "
                + "quote_snapshot IS NOT NULL AS quoted FROM leads"))
                .containsEntry("status", "QUOTED")
                .containsEntry("quantity", 1)
                .containsEntry("design", "chica en el frente")
                .containsEntry("quoted", true);
        assertThat(count("SELECT count(*) FROM lead_attachments")).isEqualTo(1);
    }

    @Test
    void replyIsDiscardedIfCustomerWroteWhileItWasBeingGenerated() throws Exception {
        stubSequence(message("Hola, ¿en qué te ayudo?"));
        customerWrites("Hola");
        jdbc.update("""
                INSERT INTO messages (id, tenant_id, conversation_id, direction, sender_type, wamid, status, body, created_at)
                SELECT ?, tenant_id, id, 'INBOUND', 'CUSTOMER', 'wamid.late', 'RECEIVED', 'Otra cosa', now() + interval '1 minute'
                FROM conversations
                """, UUID.randomUUID());

        turns.processDueTurns();

        assertThat(count("SELECT count(*) FROM messages WHERE direction = 'OUTBOUND'")).isZero();
        assertThat(jdbc.queryForObject("SELECT outcome FROM assistant_runs", String.class)).isEqualTo("DISCARDED");
    }

    @Test
    void conversationWaitingForAgentIsNotAnsweredByTheAssistant() throws Exception {
        customerWrites("Hola");
        jdbc.update("UPDATE conversations SET status = 'WAITING_FOR_AGENT'");

        turns.processDueTurns();

        META.verify(0, postRequestedFor(urlEqualTo(RESPONSES)));
        assertThat(count("SELECT count(*) FROM conversation_turns WHERE status = 'DISCARDED'")).isEqualTo(1);
    }

    @Test
    void exhaustedCreditHandsOffWithoutRetrying() throws Exception {
        META.stubFor(post(urlEqualTo(RESPONSES)).willReturn(aResponse().withStatus(429)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"type\":\"insufficient_quota\",\"code\":\"credit_balance_exhausted\"}}")));
        customerWrites("Hola");

        turns.processDueTurns();

        META.verify(1, postRequestedFor(urlEqualTo(RESPONSES)));
        assertThat(lastOutbound()).isEqualTo(FALLBACK);
        assertThat(conversationStatus()).isEqualTo("WAITING_FOR_AGENT");
        assertThat(count("SELECT count(*) FROM conversation_turns WHERE status = 'FAILED'")).isEqualTo(1);
    }

    @Test
    void temporaryErrorIsRetried() throws Exception {
        META.stubFor(post(urlEqualTo(RESPONSES)).inScenario("error").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("ok"));
        META.stubFor(post(urlEqualTo(RESPONSES)).inScenario("error").whenScenarioStateIs("ok")
                .willReturn(okJson(message("¡Hola! Soy el asistente virtual de Grenlus."))));
        customerWrites("Hola");

        turns.processDueTurns();

        assertThat(lastOutbound()).isEqualTo("¡Hola! Soy el asistente virtual de Grenlus.");
    }

    @Test
    void disabledAssistantSendsItsNoticeOnlyOnce() throws Exception {
        jdbc.update("UPDATE chatbot_configurations SET assistant_enabled = false");
        customerWrites("Hola");
        turns.processDueTurns();
        customerWrites("¿Hay alguien?");
        turns.processDueTurns();

        META.verify(0, postRequestedFor(urlEqualTo(RESPONSES)));
        assertThat(count("SELECT count(*) FROM messages WHERE direction = 'OUTBOUND' AND body = 'Asistente apagado.'"))
                .isEqualTo(1);
    }

    private int messageCounter;

    private void customerWrites(String text) throws Exception {
        postSigned(textEvent("wamid.c" + (++messageCounter), "AR.1", "5491155550000", text));
        webhooks.processPending();
        jdbc.update("UPDATE conversation_turns SET run_after = now() - interval '1 second' WHERE status = 'PENDING'");
    }

    private String lastOutbound() {
        return jdbc.queryForObject(
                "SELECT body FROM messages WHERE direction = 'OUTBOUND' ORDER BY created_at DESC LIMIT 1", String.class);
    }

    private String conversationStatus() {
        return jdbc.queryForObject("SELECT status FROM conversations", String.class);
    }

    /** Cada llamada a OpenAI devuelve la siguiente respuesta de la lista. */
    private void stubSequence(String... responses) {
        for (int i = 0; i < responses.length; i++) {
            ScenarioMappingBuilder stub = post(urlEqualTo(RESPONSES)).inScenario("turno")
                    .whenScenarioStateIs(i == 0 ? STARTED : "paso-" + i);
            if (i < responses.length - 1) {
                stub = stub.willSetStateTo("paso-" + (i + 1));
            }
            META.stubFor(stub.willReturn(okJson(responses[i])));
        }
    }

    private String functionCall(String name, Map<String, Object> arguments) {
        return jsonMapper.writeValueAsString(Map.of(
                "status", "completed",
                "output", List.of(
                        Map.of("type", "reasoning", "id", "rs_1", "encrypted_content", "cifrado", "summary", List.of()),
                        Map.of("type", "function_call", "id", "fc_1", "call_id", "call_" + name, "name", name,
                                "arguments", jsonMapper.writeValueAsString(arguments))),
                "usage", usage()));
    }

    private String message(String text) {
        return jsonMapper.writeValueAsString(Map.of(
                "status", "completed",
                "output", List.of(Map.of("type", "message", "id", "msg_1", "role", "assistant",
                        "content", List.of(Map.of("type", "output_text", "text", text)))),
                "usage", usage()));
    }

    private static Map<String, Object> usage() {
        return Map.of("input_tokens", 100, "input_tokens_details", Map.of("cached_tokens", 20), "output_tokens", 30);
    }
}
