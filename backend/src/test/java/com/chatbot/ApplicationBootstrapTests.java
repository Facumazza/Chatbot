package com.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationBootstrapTests {

    private static final int APPLICATION_TABLES = 21;
    private static final int SESSION_TABLES = 2;

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void migrationsCreateTheFullSchema() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        Integer tables = jdbc.queryForObject(
                """
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
                """,
                Integer.class);

        assertThat(applied).isEqualTo(2);
        assertThat(tables).isEqualTo(APPLICATION_TABLES + SESSION_TABLES);
    }

    @Test
    void healthIsPublicAndHidesDetails() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("UP").doesNotContain("components");
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() throws Exception {
        assertThat(get("/actuator/env").statusCode()).isEqualTo(401);
    }

    @Test
    void protectedRouteAnswersUnauthorizedAsProblemDetail() throws Exception {
        HttpResponse<String> response = get("/api/admin/conversations");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.body()).contains("correlationId");
    }

    @Test
    void webhookPathIsReachableWithoutSessionOrCsrf() throws Exception {
        HttpRequest request = request("/webhooks/whatsapp")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> response = send(request);

        // 404 porque el endpoint llega en la Etapa 5: ni 401 ni 403.
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("correlationId");
    }

    @Test
    void apiDocumentationIsDisabledOutsideDev() throws Exception {
        assertThat(get("/v3/api-docs").statusCode()).isEqualTo(404);
    }

    @Test
    void validCorrelationIdIsKept() throws Exception {
        HttpRequest request = request("/actuator/health").header("X-Correlation-Id", "abc12345-valid").build();

        assertThat(send(request).headers().firstValue("X-Correlation-Id")).hasValue("abc12345-valid");
    }

    @Test
    void unsafeCorrelationIdIsReplaced() throws Exception {
        HttpRequest request = request("/actuator/health").header("X-Correlation-Id", "abc123{forged}").build();

        assertThat(send(request).headers().firstValue("X-Correlation-Id"))
                .hasValueSatisfying(id -> assertThat(id).doesNotContain("forged").hasSize(36));
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send(request(path).GET().build());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
