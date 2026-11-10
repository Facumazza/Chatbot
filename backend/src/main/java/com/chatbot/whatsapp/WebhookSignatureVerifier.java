package com.chatbot.whatsapp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Comprueba que el cuerpo recibido haya sido firmado por Meta con el secreto de la app. */
@Component
class WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    private final SecretKeySpec key;

    WebhookSignatureVerifier(WhatsAppProperties properties) {
        this.key = new SecretKeySpec(properties.appSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    boolean isValid(byte[] rawBody, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
            return false;
        }
        byte[] received;
        try {
            received = HexFormat.of().parseHex(signatureHeader.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return false;
        }
        // Comparación en tiempo constante, para no filtrar la firma por diferencias de tiempo.
        return MessageDigest.isEqual(hmac(rawBody), received);
    }

    private byte[] hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo calcular la firma", e);
        }
    }
}
