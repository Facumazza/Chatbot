package com.chatbot.order;

import java.math.BigDecimal;
import java.util.Map;

public sealed interface OrderResult {

    /**
     * @param alreadyExisted ya había un pedido pendiente de pago en esta conversación: no se creó otro
     * @param transferDetails alias, CBU y titular informados por la web; vacío si no respondió
     */
    record Placed(
            String orderNumber,
            BigDecimal total,
            String status,
            String paymentStatus,
            Map<String, String> transferDetails,
            boolean alreadyExisted) implements OrderResult {
    }

    /** @param reason indicación para el asistente: qué falta o qué respondió la web */
    record Rejected(String reason) implements OrderResult {
    }
}
