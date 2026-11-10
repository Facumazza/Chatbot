package com.chatbot.whatsapp;

import com.chatbot.conversation.MessagingGateway;
import com.chatbot.tenant.CredentialCipher;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Adaptador de WhatsApp Cloud API. Nunca registra tokens ni el contenido de los mensajes. */
@Component
class WhatsAppCloudGateway implements MessagingGateway {

    /** Meta indica que estos errores son temporales y admiten un nuevo intento. */
    private static final Set<Integer> RETRYABLE_CODES = Set.of(2, 4, 80007, 130429, 131000, 131016, 131056, 133004);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final int COPY_BUFFER_BYTES = 8192;

    private final RestClient rest;
    private final WhatsAppProperties properties;
    private final WhatsAppAccountRepository accounts;
    private final CredentialCipher cipher;
    private final JsonMapper jsonMapper;

    WhatsAppCloudGateway(
            WhatsAppProperties properties,
            WhatsAppAccountRepository accounts,
            CredentialCipher cipher,
            JsonMapper jsonMapper) {
        this.properties = properties;
        this.accounts = accounts;
        this.cipher = cipher;
        this.jsonMapper = jsonMapper;
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.rest = RestClient.builder().baseUrl(properties.graphBaseUrl()).requestFactory(requestFactory).build();
    }

    @Override
    public SendResult sendText(OutboundText message) {
        WhatsAppAccount account = account(message.accountId());
        try {
            String response = rest.post()
                    .uri("/{version}/{phoneNumberId}/messages", properties.apiVersion(), account.getPhoneNumberId())
                    .header(HttpHeaders.AUTHORIZATION, bearer(account))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonMapper.writeValueAsString(sendPayload(message)))
                    .retrieve()
                    .body(String.class);
            return toSent(response);
        } catch (RestClientResponseException e) {
            return classify(e);
        } catch (ResourceAccessException e) {
            return classify(e);
        }
    }

    @Override
    public void downloadMedia(UUID accountId, String providerMediaId, OutputStream target, long maxBytes)
            throws IOException {
        WhatsAppAccount account = account(accountId);
        String bearer = bearer(account);
        try {
            URI location = mediaLocation(providerMediaId, bearer);
            rest.get()
                    .uri(location)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new IOException("Descarga rechazada: " + response.getStatusCode().value());
                        }
                        copyLimited(response.getBody(), target, maxBytes);
                        return null;
                    });
        } catch (RestClientResponseException | ResourceAccessException e) {
            throw unwrap(e);
        }
    }

    private WhatsAppAccount account(UUID accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("La cuenta de WhatsApp no existe"));
    }

    private String bearer(WhatsAppAccount account) {
        return "Bearer " + cipher.decrypt(account.getAccessTokenEncrypted());
    }

    private static Map<String, Object> sendPayload(OutboundText message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("recipient_type", "individual");
        if (message.phoneNumber() != null) {
            payload.put("to", RecipientPhoneFormat.forSending(message.phoneNumber()));
        } else {
            payload.put("recipient", message.waUserId());
        }
        payload.put("type", "text");
        payload.put("text", Map.of("body", message.body(), "preview_url", false));
        return payload;
    }

    private SendResult toSent(String response) {
        String messageId = jsonMapper.readTree(response).path("messages").path(0).path("id").asString("");
        return messageId.isBlank()
                ? new SendResult.Uncertain("Respuesta sin id de mensaje")
                : new SendResult.Sent(messageId);
    }

    private SendResult classify(RestClientResponseException e) {
        JsonNode error = parseError(e.getResponseBodyAsString());
        int code = error.path("code").asInt(-1);
        String detail = error.path("message").asString(e.getStatusText());
        String codeText = code == -1 ? "HTTP_" + e.getStatusCode().value() : String.valueOf(code);
        if (RETRYABLE_CODES.contains(code)) {
            return new SendResult.Retryable(codeText, detail);
        }
        // Un 5xx sin código conocido no prueba que Meta haya descartado el mensaje.
        return e.getStatusCode().is5xxServerError() && code == -1
                ? new SendResult.Uncertain(codeText)
                : new SendResult.Rejected(codeText, detail);
    }

    private static SendResult classify(ResourceAccessException e) {
        // Sin conexión establecida, el mensaje seguro no llegó; un timeout de lectura no lo garantiza.
        Throwable cause = e.getCause();
        boolean neverConnected = cause instanceof ConnectException
                || cause instanceof UnknownHostException
                || cause instanceof HttpConnectTimeoutException;
        String detail = cause == null ? e.getClass().getSimpleName() : cause.getClass().getSimpleName();
        return neverConnected ? new SendResult.Retryable("CONNECTION", detail) : new SendResult.Uncertain(detail);
    }

    private JsonNode parseError(String body) {
        try {
            return jsonMapper.readTree(body).path("error");
        } catch (JacksonException e) {
            return jsonMapper.createObjectNode();
        }
    }

    private URI mediaLocation(String mediaId, String bearer) throws IOException {
        String response = rest.get()
                .uri("/{version}/{mediaId}", properties.apiVersion(), mediaId)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .retrieve()
                .body(String.class);
        String url = jsonMapper.readTree(response).path("url").asString("");
        if (url.isBlank()) {
            throw new IOException("Respuesta de Meta sin URL de descarga");
        }
        return URI.create(url);
    }

    private static void copyLimited(InputStream source, OutputStream target, long maxBytes) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long total = 0;
        int read;
        while ((read = source.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new MediaTooLargeException();
            }
            target.write(buffer, 0, read);
        }
    }

    private static IOException unwrap(RuntimeException e) {
        return e.getCause() instanceof IOException io ? io : new IOException(e.getClass().getSimpleName());
    }
}
