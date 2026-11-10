package com.chatbot.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Lo que el asistente puede contar de un producto. Los importes son los vigentes. */
public record ProductSummary(
        UUID id,
        String name,
        String description,
        String url,
        BigDecimal basePrice,
        Integer wholesaleFromQuantity,
        BigDecimal wholesaleBasePrice,
        List<PricedOption> designSizes,
        List<PricedOption> sizes,
        List<PricedOption> colors,
        List<String> placements) {

    public record PricedOption(String value, BigDecimal price, Map<String, Object> dimensionsCm) {
    }
}
