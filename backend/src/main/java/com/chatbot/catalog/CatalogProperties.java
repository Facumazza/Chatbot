package com.chatbot.catalog;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param apiUrl    API pública de la web de Grenlus; vacía desactiva la sincronización
 * @param publicUrl web donde el cliente personaliza y compra
 * @param maxAge    antigüedad máxima del catálogo para informar precios
 */
@ConfigurationProperties("catalog.grenlus")
public record CatalogProperties(String apiUrl, String publicUrl, Duration maxAge) {

    boolean syncEnabled() {
        return apiUrl != null && !apiUrl.isBlank();
    }
}
