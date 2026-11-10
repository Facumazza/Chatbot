package com.chatbot.whatsapp;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Punto de entrada de Meta: solo valida y guarda, para responder sin depender de servicios externos. */
@Hidden
@RestController
@RequestMapping("/webhooks/whatsapp")
class WhatsAppWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

    /** Meta envía hasta 3 MB; se admite un margen y el exceso se corta antes de leerlo completo. */
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private final WhatsAppProperties properties;
    private final WebhookSignatureVerifier signatureVerifier;
    private final WebhookEventStore eventStore;

    WhatsAppWebhookController(
            WhatsAppProperties properties,
            WebhookSignatureVerifier signatureVerifier,
            WebhookEventStore eventStore) {
        this.properties = properties;
        this.signatureVerifier = signatureVerifier;
        this.eventStore = eventStore;
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode) && challenge != null && matchesVerifyToken(token)) {
            return ResponseEntity.ok(challenge);
        }
        log.warn("Verificación de webhook rechazada");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @PostMapping
    ResponseEntity<Void> receive(
            HttpServletRequest request,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) throws IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            log.warn("Webhook rechazado por tamaño");
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        if (!signatureVerifier.isValid(body, signature)) {
            log.warn("Webhook rechazado por firma inválida");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        eventStore.save(body);
        return ResponseEntity.ok().build();
    }

    private boolean matchesVerifyToken(String token) {
        return token != null && MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                properties.verifyToken().getBytes(StandardCharsets.UTF_8));
    }
}
