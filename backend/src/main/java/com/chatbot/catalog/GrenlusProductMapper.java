package com.chatbot.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Traduce un producto de la web a opciones y escalas de precio. Replica la lógica del
 * personalizador de la web (revisada el 2026-11-10), que es la fuente de verdad:
 * precio = base (con descuento mayorista desde 6 unidades) + una estampa según el diseño
 * más grande + adicional de talle especial + adicional del color.
 */
final class GrenlusProductMapper {

    static final int WHOLESALE_MIN_QUANTITY = 6;

    private static final List<String> CHILD_SIZES = List.of("4", "6", "8", "10", "12", "14", "16", "18");
    private static final List<String> SPECIAL_SIZES = List.of("T6", "T8", "T10", "T14", "T16");
    private static final List<String> LARGE_SPECIAL_SIZES = List.of("T14", "T16");
    private static final List<String> ADULT_SIZES = List.of("S", "M", "L", "XL", "XXL");
    private static final List<String> ALL_SIZES = concat(CHILD_SIZES, SPECIAL_SIZES, ADULT_SIZES);

    private GrenlusProductMapper() {
    }

    record DesiredOption(String type, String value, Map<String, Object> attributes, BigDecimal surcharge) {

        String key() {
            return type + "|" + value;
        }
    }

    record DesiredTier(String optionKey, int minQuantity, Integer maxQuantity, BigDecimal unitPrice) {
    }

    static List<DesiredOption> options(JsonNode product, JsonNode colors) {
        List<DesiredOption> options = new ArrayList<>();
        BigDecimal specialSurcharge = money(product, "precioAdicionalTalleEspecial");
        for (String size : offeredSizes(product)) {
            boolean special = SPECIAL_SIZES.contains(size);
            options.add(new DesiredOption("SIZE", size, Map.of("special", special),
                    special && specialSurcharge.signum() > 0 ? specialSurcharge : null));
        }
        addDesignSize(options, product, "CHICA", "Chica", "maxWidthCm", "anchoChicaCm", "altoChicaCm");
        addDesignSize(options, product, "MEDIA", "Media", "maxWidthCm", "anchoMediaCm", "altoMediaCm");
        addDesignSize(options, product, "GRANDE", "Grande", "minWidthCm", "anchoMediaCm", "altoMediaCm");
        addPlacement(options, product, "permiteFrente", "FRENTE");
        addPlacement(options, product, "permiteEspalda", "ESPALDA");
        addPlacement(options, product, "permiteManga", "MANGA");
        for (JsonNode color : colors) {
            String name = firstText(color, "nombre", "name", "color");
            if (name != null) {
                BigDecimal surcharge = money(color, "precioAdicional");
                options.add(new DesiredOption("COLOR", name.trim(), Map.of(),
                        surcharge.signum() > 0 ? surcharge : null));
            }
        }
        return options;
    }

    static List<DesiredTier> tiers(JsonNode product, List<DesiredOption> options) {
        List<DesiredTier> tiers = new ArrayList<>();
        BigDecimal base = money(product, "precioBase");
        BigDecimal discount = money(product, "descuentoMayoristaPorcentaje");
        if (discount.signum() > 0 && discount.compareTo(BigDecimal.valueOf(100)) <= 0) {
            BigDecimal discounted = base.multiply(BigDecimal.valueOf(100).subtract(discount))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            tiers.add(new DesiredTier(null, 1, WHOLESALE_MIN_QUANTITY - 1, base));
            tiers.add(new DesiredTier(null, WHOLESALE_MIN_QUANTITY, null, discounted));
        } else {
            tiers.add(new DesiredTier(null, 1, null, base));
        }
        for (DesiredOption option : options) {
            if (option.surcharge() != null) {
                tiers.add(new DesiredTier(option.key(), 1, null, option.surcharge()));
            }
        }
        return tiers;
    }

    static Map<String, Object> attributes(JsonNode product) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("tipo", product.path("tipo").asString(""));
        attributes.put("discountPercent", money(product, "descuentoMayoristaPorcentaje"));
        attributes.put("requiresImage", product.path("requiereImagen").asBoolean(false));
        attributes.put("usesColors", product.path("usaColores").asBoolean(false));
        return attributes;
    }

    /** Misma prioridad que la web: talles ofrecidos, luego disponibles, luego los indicadores. */
    static List<String> offeredSizes(JsonNode product) {
        if (!product.path("usaTalles").asBoolean(false)) {
            return List.of();
        }
        List<String> explicit = texts(product.path("tallesOfrecidos"));
        if (explicit.isEmpty()) {
            explicit = texts(product.path("tallesDisponibles"));
        }
        Set<String> selected = new LinkedHashSet<>();
        if (!explicit.isEmpty()) {
            explicit.forEach(size -> selected.add(size.trim().toUpperCase(Locale.ROOT)));
        } else {
            if (!product.path("incluyeTallesInfantiles").isBoolean()
                    || product.path("incluyeTallesInfantiles").asBoolean()) {
                selected.addAll(CHILD_SIZES);
            }
            if (product.path("incluyeTallesEspeciales").asBoolean(false)) {
                boolean large = !product.path("incluyeTallesEspecialesGrandes").isBoolean()
                        || product.path("incluyeTallesEspecialesGrandes").asBoolean();
                SPECIAL_SIZES.stream().filter(size -> large || !LARGE_SPECIAL_SIZES.contains(size)).forEach(selected::add);
            }
            selected.addAll(ADULT_SIZES);
        }
        return ALL_SIZES.stream().filter(selected::contains).toList();
    }

    private static void addDesignSize(List<DesiredOption> options, JsonNode product, String value, String flagSuffix,
            String dimensionPrefix, String widthField, String heightField) {
        if (!product.path("permiteEstampa" + flagSuffix).asBoolean(false)) {
            return;
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        BigDecimal width = money(product, widthField);
        BigDecimal height = money(product, heightField);
        if (width.signum() > 0 && height.signum() > 0) {
            String heightKey = dimensionPrefix.replace("Width", "Height");
            attributes.put(dimensionPrefix, width);
            attributes.put(heightKey, height);
        }
        String priceField = "precioEstampa" + (value.equals("MEDIA") ? "Media" : flagSuffix);
        options.add(new DesiredOption("DESIGN_SIZE", value, attributes, money(product, priceField)));
    }

    private static void addPlacement(List<DesiredOption> options, JsonNode product, String flag, String value) {
        if (product.path(flag).asBoolean(false)) {
            options.add(new DesiredOption("PLACEMENT", value, Map.of(), null));
        }
    }

    static BigDecimal money(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.decimalValue() : BigDecimal.ZERO;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asString("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asString("")));
        values.removeIf(String::isBlank);
        return values;
    }

    @SafeVarargs
    private static List<String> concat(List<String>... lists) {
        List<String> all = new ArrayList<>();
        for (List<String> list : lists) {
            all.addAll(list);
        }
        return List.copyOf(all);
    }
}
