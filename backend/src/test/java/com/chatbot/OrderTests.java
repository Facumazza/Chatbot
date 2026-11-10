package com.chatbot;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.chatbot.catalog.CatalogSyncService;
import com.chatbot.catalog.QuoteRequest;
import com.chatbot.conversation.MediaDownloadWorker;
import com.chatbot.conversation.TurnProcessor;
import com.chatbot.order.OrderResult;
import com.chatbot.order.OrderService;
import com.chatbot.order.OrderStatusWatcher;
import com.chatbot.order.PlaceOrderRequest;
import com.chatbot.tenant.TenantContext;
import com.chatbot.whatsapp.WebhookEventProcessor;
import com.github.tomakehurst.wiremock.client.ScenarioMappingBuilder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

class OrderTests extends IntegrationTest {

    private static final String WEB = "/grenlus";
    private static final String CREATED = """
            {"id":501,"total":16900,"estado":"PENDIENTE","estadoPago":"PENDIENTE","metodoPago":"TRANSFERENCIA"}
            """;

    @Autowired
    private WebhookEventProcessor webhooks;

    @Autowired
    private MediaDownloadWorker mediaWorker;

    @Autowired
    private TurnProcessor turns;

    @Autowired
    private CatalogSyncService catalogSync;

    @Autowired
    private OrderService orders;

    @Autowired
    private OrderStatusWatcher watcher;

    @Autowired
    private JsonMapper jsonMapper;

    private UUID remeraId;
    private int counter;

    @BeforeEach
    void prepare() {
        GrenlusFixture.stubCatalog(META, GrenlusFixture.remera(14900));
        catalogSync.sync();
        remeraId = jdbc.queryForObject("SELECT id FROM products WHERE external_id = '1'", UUID.class);
        UUID promptId = UUID.randomUUID();
        jdbc.update("INSERT INTO chatbot_prompt_versions (id, tenant_id, version_number, system_prompt) VALUES (?, ?, 1, 'p')",
                promptId, tenantId);
        jdbc.update("""
                INSERT INTO chatbot_configurations (id, tenant_id, active_prompt_version_id, privacy_notice,
                    disabled_message, outside_hours_message, fallback_message)
                VALUES (?, ?, ?, 'a', 'b', 'c', 'Te paso con una persona.')
                """, UUID.randomUUID(), tenantId, promptId);
        META.stubFor(post(urlEqualTo(WEB + "/auth/login")).willReturn(okJson("{\"token\":\"jwt-1\"}")));
        META.stubFor(post(urlEqualTo(WEB + "/archivos/imagen")).willReturn(okJson("{\"ruta\":\"/uploads/diseno.png\"}")));
        META.stubFor(get(urlEqualTo(WEB + "/pagos/transferencia/datos"))
                .willReturn(okJson("{\"alias\":\"grenlus.mp\",\"cbu\":\"0000003100000000000001\",\"titular\":\"\"}")));
        META.stubFor(get(urlEqualTo("/v27.0/media-1")).willReturn(okJson("""
                {"url":"%s/files/x","mime_type":"image/png","id":"media-1"}
                """.formatted(META.baseUrl()))));
        META.stubFor(get(urlEqualTo("/files/x")).willReturn(aResponse().withBody("png-del-diseno")));
    }

    @Test
    void assistantCreatesTheOrderAfterExplicitConfirmation() throws Exception {
        META.stubFor(post(urlEqualTo(WEB + "/pedidos")).willReturn(okJson(CREATED)));
        customerSendsDesign();
        customerWrites("Sí, confirmo");
        stubModel(
                functionCall("crear_pedido", orderArguments("RETIRO")),
                message("Listo, tu pedido #501 quedó cargado por $16.900. Transferí al alias grenlus.mp y mandame el comprobante."));

        turns.processDueTurns();

        META.verify(postRequestedFor(urlEqualTo(WEB + "/pedidos"))
                .withHeader("Authorization", equalTo("Bearer jwt-1"))
                .withRequestBody(matchingJsonPath("$.nombreCliente", equalTo("Ana")))
                .withRequestBody(matchingJsonPath("$.telefono", equalTo("+5491155550000")))
                .withRequestBody(matchingJsonPath("$.metodoPago", equalTo("TRANSFERENCIA")))
                .withRequestBody(matchingJsonPath("$.metodoEntrega", equalTo("RETIRO")))
                .withRequestBody(matchingJsonPath("$.detalles[0].productoId", equalTo("1")))
                .withRequestBody(matchingJsonPath("$.detalles[0].talle", equalTo("M")))
                .withRequestBody(matchingJsonPath("$.detalles[0].disenos[0].rutaImagen", equalTo("/uploads/diseno.png")))
                .withRequestBody(matchingJsonPath("$.detalles[0].disenos[0].posicion", equalTo("FRENTE")))
                .withRequestBody(matchingJsonPath("$.detalles[0].disenos[0].tamano", equalTo("CHICA"))));
        assertThat(jdbc.queryForMap("SELECT external_order_id, payment_status, total FROM orders"))
                .containsEntry("external_order_id", "501")
                .containsEntry("payment_status", "PENDIENTE");
        assertThat(lastOutbound()).contains("#501").contains("$16.900");
    }

    @Test
    void orderIsNotCreatedWithoutExplicitConfirmation() throws Exception {
        META.stubFor(post(urlEqualTo(WEB + "/pedidos")).willReturn(okJson(CREATED)));
        customerSendsDesign();
        customerWrites("Quiero una remera M con este logo");
        stubModel(functionCall("crear_pedido", orderArguments("RETIRO")), message("¿Confirmás el pedido?"));

        turns.processDueTurns();

        META.verify(0, postRequestedFor(urlEqualTo(WEB + "/pedidos")));
        assertThat(lastOutbound()).isEqualTo("¿Confirmás el pedido?");
    }

    @Test
    void secondOrderWhilePaymentIsPendingIsNotCreated() throws Exception {
        META.stubFor(post(urlEqualTo(WEB + "/pedidos")).willReturn(okJson(CREATED)));
        UUID conversation = customerSendsDesign();

        place(conversation, "RETIRO");
        OrderResult second = place(conversation, "RETIRO");

        META.verify(1, postRequestedFor(urlEqualTo(WEB + "/pedidos")));
        assertThat(second).isInstanceOfSatisfying(OrderResult.Placed.class, placed -> {
            assertThat(placed.alreadyExisted()).isTrue();
            assertThat(placed.transferDetails()).containsEntry("alias", "grenlus.mp").doesNotContainKey("titular");
        });
    }

    @Test
    void homeDeliveryIsRejectedWithoutCallingTheWeb() throws Exception {
        UUID conversation = customerSendsDesign();

        assertThat(place(conversation, "ENVIO_DOMICILIO")).isInstanceOfSatisfying(OrderResult.Rejected.class,
                rejected -> assertThat(rejected.reason()).contains("Mercado Pago"));
        META.verify(0, postRequestedFor(urlEqualTo(WEB + "/pedidos")));
    }

    @Test
    void missingDesignIsRequestedBeforeOrdering() throws Exception {
        customerWrites("Hola");
        UUID conversation = jdbc.queryForObject("SELECT id FROM conversations", UUID.class);

        assertThat(place(conversation, "RETIRO")).isInstanceOfSatisfying(OrderResult.Rejected.class,
                rejected -> assertThat(rejected.reason()).contains("imagen"));
    }

    @Test
    void webRejectionIsReportedToTheAssistant() throws Exception {
        META.stubFor(post(urlEqualTo(WEB + "/pedidos")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"message\":\"El producto \\\"Remera\\\" no está disponible.\"}")));
        UUID conversation = customerSendsDesign();

        assertThat(place(conversation, "RETIRO")).isInstanceOfSatisfying(OrderResult.Rejected.class,
                rejected -> assertThat(rejected.reason()).contains("no está disponible"));
        assertThat(count("SELECT count(*) FROM orders")).isZero();
    }

    @Test
    void expiredWebSessionIsRenewed() throws Exception {
        META.stubFor(post(urlEqualTo(WEB + "/pedidos")).inScenario("sesion").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(401)).willSetStateTo("renovada"));
        META.stubFor(post(urlEqualTo(WEB + "/pedidos")).inScenario("sesion").whenScenarioStateIs("renovada")
                .willReturn(okJson(CREATED)));
        UUID conversation = customerSendsDesign();

        assertThat(place(conversation, "COORDINAR")).isInstanceOf(OrderResult.Placed.class);
        // Puede haber una sesión previa de otra prueba: lo que importa es que, tras el 401, se inicie sesión y se reintente.
        META.verify(2, postRequestedFor(urlEqualTo(WEB + "/pedidos")));
        META.verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo(WEB + "/auth/login"))
                .withRequestBody(equalToJson("{\"username\":\"bot-whatsapp\",\"password\":\"clave-web-de-prueba\"}")));
    }

    @Test
    void receiptSentAfterTheOrderIsUploaded() throws Exception {
        META.stubFor(post(urlEqualTo(WEB + "/pedidos")).willReturn(okJson(CREATED)));
        META.stubFor(post(urlEqualTo(WEB + "/pagos/transferencia/501/comprobante-cuenta")).willReturn(okJson("{}")));
        UUID conversation = customerSendsDesign();
        place(conversation, "RETIRO");
        jdbc.update("UPDATE orders SET created_at = now() - interval '1 minute'");
        customerSendsImage("wamid.receipt");

        OrderResult result = TenantContext.callAs(tenantId, () -> orders.attachReceipt(conversation));

        assertThat(result).isInstanceOf(OrderResult.Placed.class);
        META.verify(postRequestedFor(urlEqualTo(WEB + "/pagos/transferencia/501/comprobante-cuenta"))
                .withHeader("Authorization", equalTo("Bearer jwt-1"))
                .withRequestBody(containing("png-del-diseno")));
        assertThat(count("SELECT count(*) FROM orders WHERE receipt_sent_at IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void approvedPaymentIsAnnouncedOnce() throws Exception {
        META.stubFor(post(urlEqualTo(WEB + "/pedidos")).willReturn(okJson(CREATED)));
        UUID conversation = customerSendsDesign();
        place(conversation, "RETIRO");
        stubMyOrders("PAGADO", "APROBADO");

        watcher.checkOpenOrders();
        watcher.checkOpenOrders();

        assertThat(count("SELECT count(*) FROM messages WHERE sender_type = 'SYSTEM' AND body LIKE '%#501%confirmado%'"))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payment_status FROM orders", String.class)).isEqualTo("APROBADO");
    }

    @Test
    void readyOrderTellsHowToPickItUp() throws Exception {
        META.stubFor(post(urlEqualTo(WEB + "/pedidos")).willReturn(okJson(CREATED)));
        UUID conversation = customerSendsDesign();
        place(conversation, "RETIRO");
        jdbc.update("UPDATE orders SET status = 'EN_PREPARACION', payment_status = 'APROBADO'");
        stubMyOrders("LISTO", "APROBADO");

        watcher.checkOpenOrders();

        assertThat(lastOutbound()).contains("#501").contains("retirarlo");
    }

    @Test
    void noticeWaitsWhileTheWhatsAppWindowIsClosed() throws Exception {
        META.stubFor(post(urlEqualTo(WEB + "/pedidos")).willReturn(okJson(CREATED)));
        UUID conversation = customerSendsDesign();
        place(conversation, "RETIRO");
        jdbc.update("UPDATE conversations SET last_inbound_at = now() - interval '25 hours'");
        stubMyOrders("PAGADO", "APROBADO");

        watcher.checkOpenOrders();

        assertThat(count("SELECT count(*) FROM messages WHERE sender_type = 'SYSTEM'")).isZero();
        assertThat(jdbc.queryForObject("SELECT payment_status FROM orders", String.class)).isEqualTo("PENDIENTE");
    }

    private OrderResult place(UUID conversation, String delivery) {
        UUID customer = jdbc.queryForObject("SELECT customer_id FROM conversations WHERE id = ?", UUID.class, conversation);
        return TenantContext.callAs(tenantId, () -> orders.placeOrder(new PlaceOrderRequest(conversation, customer,
                remeraId, "CHICA", List.of(new QuoteRequest.Line(1, "M", "Negro")),
                List.of(new PlaceOrderRequest.Design("FRENTE", "Logo centrado")), "Ana", delivery)));
    }

    private void stubMyOrders(String status, String paymentStatus) {
        META.stubFor(get(urlEqualTo(WEB + "/pedidos/mis-compras")).willReturn(okJson("""
                [{"id":501,"total":16900,"estado":"%s","estadoPago":"%s"}]
                """.formatted(status, paymentStatus))));
    }

    private UUID customerSendsDesign() throws Exception {
        customerSendsImage("wamid.design");
        return jdbc.queryForObject("SELECT id FROM conversations", UUID.class);
    }

    private void customerSendsImage(String wamid) throws Exception {
        postSigned(envelope(PHONE_NUMBER_ID, """
                "contacts":[{"profile":{"name":"Ana"},"wa_id":"5491155550000","user_id":"AR.1"}],
                "messages":[{"from":"5491155550000","from_user_id":"AR.1","id":"%s","timestamp":"%d","type":"image",
                "image":{"id":"media-1","mime_type":"image/png"}}]
                """.formatted(wamid, System.currentTimeMillis() / 1000)));
        webhooks.processPending();
        mediaWorker.downloadPending();
    }

    private void customerWrites(String text) throws Exception {
        postSigned(textEvent("wamid.t" + (++counter), "AR.1", "5491155550000", text));
        webhooks.processPending();
        jdbc.update("UPDATE conversation_turns SET run_after = now() - interval '1 second' WHERE status = 'PENDING'");
    }

    private String lastOutbound() {
        return jdbc.queryForObject(
                "SELECT body FROM messages WHERE direction = 'OUTBOUND' ORDER BY created_at DESC LIMIT 1", String.class);
    }

    private Map<String, Object> orderArguments(String delivery) {
        return Map.of("product_id", remeraId.toString(), "design_size", "CHICA",
                "lines", List.of(Map.of("quantity", 1, "size", "M", "color", "Negro")),
                "designs", List.of(Map.of("position", "frente", "notes", "Logo centrado")),
                "customer_name", "Ana", "delivery_method", delivery);
    }

    private void stubModel(String... responses) {
        for (int i = 0; i < responses.length; i++) {
            ScenarioMappingBuilder stub = post(urlEqualTo("/v1/responses")).inScenario("modelo")
                    .whenScenarioStateIs(i == 0 ? STARTED : "paso-" + i);
            if (i < responses.length - 1) {
                stub = stub.willSetStateTo("paso-" + (i + 1));
            }
            META.stubFor(stub.willReturn(okJson(responses[i])));
        }
    }

    private String functionCall(String name, Map<String, Object> arguments) {
        return jsonMapper.writeValueAsString(Map.of("status", "completed", "output", List.of(Map.of(
                "type", "function_call", "id", "fc_1", "call_id", "call_1", "name", name,
                "arguments", jsonMapper.writeValueAsString(arguments))), "usage", Map.of()));
    }

    private String message(String text) {
        return jsonMapper.writeValueAsString(Map.of("status", "completed", "output", List.of(Map.of(
                "type", "message", "role", "assistant",
                "content", List.of(Map.of("type", "output_text", "text", text)))), "usage", Map.of()));
    }
}
