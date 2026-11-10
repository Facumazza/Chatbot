package com.chatbot.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTests {

    // HMAC-SHA256 de {"a":1} con la clave "secreto", calculado con otra implementación (Node.js crypto).
    private static final String VALID =
            "sha256=87c6bf752c360faf8b89e2d9ed15dcc45e947f9ecdf9c4b637c8fa62d691c598";

    private final WebhookSignatureVerifier verifier =
            new WebhookSignatureVerifier(new WhatsAppProperties("secreto", "token", "http://x", "v27.0"));

    private final byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

    @Test
    void acceptsCorrectSignature() {
        assertThat(verifier.isValid(body, VALID)).isTrue();
    }

    @Test
    void rejectsSignatureOfOtherBody() {
        assertThat(verifier.isValid("{\"a\":2}".getBytes(StandardCharsets.UTF_8), VALID)).isFalse();
    }

    @Test
    void rejectsMissingHeader() {
        assertThat(verifier.isValid(body, null)).isFalse();
    }

    @Test
    void rejectsWrongPrefix() {
        assertThat(verifier.isValid(body, VALID.replace("sha256=", "sha1="))).isFalse();
    }

    @Test
    void rejectsNonHexadecimalValue() {
        assertThat(verifier.isValid(body, "sha256=no-hexadecimal")).isFalse();
    }

    @Test
    void rejectsWrongLength() {
        assertThat(verifier.isValid(body, "sha256=abcd")).isFalse();
    }
}
