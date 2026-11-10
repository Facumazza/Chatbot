package com.chatbot;

import com.chatbot.tenant.CredentialCipher;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Base con PostgreSQL real y un servidor que simula a Meta. */
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    protected static final String PHONE_NUMBER_ID = "111222333";
    protected static final String ACCESS_TOKEN = "token-de-prueba";

    protected static final WireMockServer META = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        META.start();
    }

    protected final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected CredentialCipher cipher;

    protected UUID tenantId;
    protected UUID accountId;

    @DynamicPropertySource
    static void metaUrl(DynamicPropertyRegistry registry) {
        registry.add("whatsapp.graph-base-url", META::baseUrl);
    }

    @BeforeEach
    void resetState() {
        META.resetAll();
        jdbc.execute("TRUNCATE tenants, webhook_events CASCADE");
        tenantId = UUID.randomUUID();
        accountId = createAccount(tenantId, "grenlus", PHONE_NUMBER_ID);
    }

    protected UUID createAccount(UUID newTenantId, String slug, String phoneNumberId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, ?, ?)", newTenantId, slug, slug);
        jdbc.update("""
                INSERT INTO whatsapp_accounts
                    (id, tenant_id, phone_number_id, waba_id, display_phone_number, access_token_encrypted)
                VALUES (?, ?, ?, 'waba', '5491100000000', ?)
                """, id, newTenantId, phoneNumberId, cipher.encrypt(ACCESS_TOKEN));
        return id;
    }

    protected HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    protected HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> postWebhook(String body, String signature) throws Exception {
        HttpRequest.Builder builder = request("/webhooks/whatsapp")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) {
            builder.header("X-Hub-Signature-256", signature);
        }
        return send(builder.build());
    }

    protected HttpResponse<String> postSigned(String body) throws Exception {
        return postWebhook(body, sign(body));
    }

    protected static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-app-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    protected int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    protected static String envelope(String phoneNumberId, String valueFields) {
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"waba","changes":[{"field":"messages","value":{
                "messaging_product":"whatsapp",
                "metadata":{"display_phone_number":"5491100000000","phone_number_id":"%s"},
                %s}}]}]}
                """.formatted(phoneNumberId, valueFields);
    }

    protected static String textMessage(String wamid, String userId, String phone, String text) {
        String from = phone == null ? "" : "\"from\":\"%s\",".formatted(phone);
        return """
                {%s"from_user_id":"%s","id":"%s","timestamp":"%d","type":"text","text":{"body":"%s"}}
                """.formatted(from, userId, wamid, System.currentTimeMillis() / 1000, text);
    }

    protected static String textEvent(String wamid, String userId, String phone, String text) {
        String waId = phone == null ? "" : "\"wa_id\":\"%s\",".formatted(phone);
        return envelope(PHONE_NUMBER_ID, """
                "contacts":[{"profile":{"name":"Ana"},%s"user_id":"%s"}],
                "messages":[%s]
                """.formatted(waId, userId, textMessage(wamid, userId, phone, text)));
    }
}
