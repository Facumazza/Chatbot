package com.chatbot.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public sealed interface Quote {

    /** @param colorsToConfirm la web no informa colores para este producto: se confirman con la tienda */
    record Ok(
            UUID productId,
            String productName,
            String url,
            int totalQuantity,
            boolean wholesaleDiscountApplied,
            boolean colorsToConfirm,
            List<Line> lines,
            BigDecimal total) implements Quote {
    }

    /** @param reason indicación para el asistente sobre qué dato falta o es inválido */
    record Invalid(String reason) implements Quote {
    }

    record Line(
            int quantity,
            String size,
            String color,
            BigDecimal basePrice,
            BigDecimal designPrice,
            BigDecimal sizeSurcharge,
            BigDecimal colorSurcharge,
            BigDecimal unitPrice,
            BigDecimal subtotal) {
    }
}
