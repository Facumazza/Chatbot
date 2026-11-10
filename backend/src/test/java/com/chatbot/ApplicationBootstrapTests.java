package com.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;



class ApplicationBootstrapTests extends IntegrationTest {

    private static final int APPLICATION_TABLES = 21;
    private static final int SESSION_TABLES = 2;




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

        assertThat(applied).isEqualTo(3);
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
    void webhookIsReachableWithoutSessionOrCsrf() throws Exception {
        HttpRequest request = request("/webhooks/whatsapp")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> response = send(request);

        // 401 sin cuerpo: lo rechazó el controlador por la firma, no Spring Security.
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEmpty();
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

    private HttpResponse<String> get(String path) throws Exception {
        return send(request(path).GET().build());
    }


}
