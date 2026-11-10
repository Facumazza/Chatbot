package com.chatbot.order;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cliente de la web de Grenlus con la cuenta de servicio. El token de la web dura una hora:
 * se renueva antes de vencer y, si la web lo rechaza, se inicia sesión otra vez.
 */
@Component
class GrenlusShopClient {

    private static final Logger log = LoggerFactory.getLogger(GrenlusShopClient.class);
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(50);

    private final ShopProperties properties;
    private final JsonMapper jsonMapper;
    private final RestClient rest;
    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    GrenlusShopClient(ShopProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.rest = RestClient.builder()
                .baseUrl(properties.enabled() ? properties.apiUrl() : "http://localhost")
                .requestFactory(factory)
                .build();
    }

    boolean enabled() {
        return properties.enabled();
    }

    /** @return la ruta con la que la web referencia la imagen */
    String uploadImage(byte[] content, String filename, String mimeType) {
        JsonNode response = parse(rest.post()
                .uri("/archivos/imagen")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(filePart(content, filename, mimeType))
                .retrieve()
                .body(String.class));
        return response.path("ruta").asString();
    }

    JsonNode createOrder(Map<String, Object> order) {
        String body = jsonMapper.writeValueAsString(order);
        return authenticated(bearer -> rest.post()
                .uri("/pedidos")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
    }

    JsonNode myOrders() {
        return authenticated(bearer -> rest.get()
                .uri("/pedidos/mis-compras")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .retrieve()
                .body(String.class));
    }

    void uploadReceipt(String orderId, byte[] content, String filename, String mimeType) {
        authenticated(bearer -> rest.post()
                .uri("/pagos/transferencia/{id}/comprobante-cuenta", orderId)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(filePart(content, filename, mimeType))
                .retrieve()
                .body(String.class));
    }

    JsonNode transferDetails() {
        return parse(rest.get().uri("/pagos/transferencia/datos").retrieve().body(String.class));
    }

    /** Mensaje de error de la web, si lo envió en un formato conocido. */
    String errorMessage(RestClientResponseException e) {
        try {
            String message = jsonMapper.readTree(e.getResponseBodyAsString()).path("message").asString("");
            return message.isBlank() ? null : message;
        } catch (JacksonException ignored) {
            return null;
        }
    }

    private JsonNode authenticated(Function<String, String> call) {
        try {
            return parse(call.apply("Bearer " + currentToken()));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() != HttpStatus.UNAUTHORIZED && e.getStatusCode() != HttpStatus.FORBIDDEN) {
                throw e;
            }
            log.info("La web rechazó la sesión del bot: se inicia sesión de nuevo");
            invalidateToken();
            return parse(call.apply("Bearer " + currentToken()));
        }
    }

    private synchronized String currentToken() {
        if (token == null || Instant.now().isAfter(tokenExpiresAt)) {
            String response = rest.post()
                    .uri("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonMapper.writeValueAsString(
                            Map.of("username", properties.username(), "password", properties.password())))
                    .retrieve()
                    .body(String.class);
            token = parse(response).path("token").asString();
            tokenExpiresAt = Instant.now().plus(TOKEN_LIFETIME);
        }
        return token;
    }

    private synchronized void invalidateToken() {
        token = null;
    }

    private JsonNode parse(String body) {
        return body == null || body.isBlank() ? jsonMapper.createObjectNode() : jsonMapper.readTree(body);
    }

    private static MultiValueMap<String, Object> filePart(byte[] content, String filename, String mimeType) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mimeType));
        headers.setContentDispositionFormData("archivo", filename);
        parts.add("archivo", new org.springframework.http.HttpEntity<>(new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        }, headers));
        return parts;
    }
}
