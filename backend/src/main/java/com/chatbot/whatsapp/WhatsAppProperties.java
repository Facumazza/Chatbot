package com.chatbot.whatsapp;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Si falta un valor, la aplicación no arranca: es preferible a fallar con el primer mensaje. */
@Validated
@ConfigurationProperties("whatsapp")
public record WhatsAppProperties(
        @NotBlank String appSecret,
        @NotBlank String verifyToken,
        @NotBlank String graphBaseUrl,
        @NotBlank String apiVersion) {
}
