package com.chatbot.order;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cuenta de servicio en la web de Grenlus. Es una solución de prueba: todos los pedidos de
 * WhatsApp quedan bajo este usuario. Ver docs/07-pedidos-por-whatsapp.md.
 *
 * @param apiUrl   API de la web; vacía desactiva los pedidos por WhatsApp
 */
@ConfigurationProperties("orders.grenlus")
public record ShopProperties(String apiUrl, String username, String password) {

    boolean enabled() {
        return notBlank(apiUrl) && notBlank(username) && notBlank(password);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
