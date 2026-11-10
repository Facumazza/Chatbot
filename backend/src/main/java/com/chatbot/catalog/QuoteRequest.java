package com.chatbot.catalog;

import java.util.List;
import java.util.UUID;

/**
 * Pedido a cotizar. Lleva un solo tamaño de estampa, el del diseño más grande, porque así cobra la web.
 * Cada línea agrupa las unidades de un mismo talle y color.
 */
public record QuoteRequest(UUID productId, String designSize, List<Line> lines) {

    public record Line(int quantity, String size, String color) {
    }
}
