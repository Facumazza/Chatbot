package com.chatbot.catalog;

import com.chatbot.catalog.CatalogRepository.OptionRow;
import com.chatbot.catalog.CatalogRepository.Product;
import com.chatbot.catalog.CatalogRepository.TierRow;
import com.chatbot.tenant.TenantContext;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Consultas y presupuestos. Los importes salen siempre de las escalas vigentes, nunca del modelo. */
@Service
public class CatalogService {

    private final CatalogRepository repository;
    private final CatalogProperties properties;
    private final Clock clock;

    CatalogService(CatalogRepository repository, CatalogProperties properties) {
        this.repository = repository;
        this.properties = properties;
        this.clock = Clock.systemUTC();
    }

    /** Si la sincronización está activa y falló hace demasiado, no deben informarse precios. */
    public boolean pricesAreCurrent() {
        if (!properties.syncEnabled()) {
            return true;
        }
        Optional<Instant> lastSync = repository.lastSync(TenantContext.require());
        return lastSync.isPresent() && lastSync.get().plus(properties.maxAge()).isAfter(clock.instant());
    }

    public List<ProductSummary> findProducts(String query) {
        List<Product> products = repository.activeProducts(TenantContext.require());
        List<Product> matches = products.stream().filter(product -> matches(product, query)).toList();
        // El catálogo es chico: si la búsqueda no encuentra nada, se devuelve completo.
        return (matches.isEmpty() ? products : matches).stream().map(CatalogService::summarize).toList();
    }

    public Quote quote(QuoteRequest request) {
        Optional<Product> found = repository.activeProducts(TenantContext.require()).stream()
                .filter(product -> product.row().id().equals(request.productId()))
                .findFirst();
        if (found.isEmpty()) {
            return new Quote.Invalid("El producto no existe o no está disponible. Usá buscar_productos.");
        }
        Product product = found.get();
        if (request.lines().isEmpty() || request.lines().stream().anyMatch(line -> line.quantity() < 1)) {
            return new Quote.Invalid("Cada línea necesita una cantidad mayor a cero.");
        }
        int totalQuantity = request.lines().stream().mapToInt(QuoteRequest.Line::quantity).sum();
        if (totalQuantity < product.row().minOrderQuantity()) {
            return new Quote.Invalid("La cantidad mínima es " + product.row().minOrderQuantity() + ".");
        }
        Optional<TierRow> base = tier(product, null, totalQuantity);
        if (base.isEmpty()) {
            return new Quote.Invalid("El producto no tiene precio cargado. Derivá a un operador.");
        }
        List<OptionRow> designSizes = options(product, "DESIGN_SIZE");
        BigDecimal designPrice = BigDecimal.ZERO;
        if (!designSizes.isEmpty()) {
            Optional<OptionRow> designSize = find(designSizes, request.designSize());
            if (designSize.isEmpty()) {
                return new Quote.Invalid("Falta el tamaño de estampa. Valores posibles: " + values(designSizes) + ".");
            }
            designPrice = surcharge(product, designSize.get(), totalQuantity);
        }
        List<Quote.Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (QuoteRequest.Line line : request.lines()) {
            BigDecimal sizeSurcharge = BigDecimal.ZERO;
            List<OptionRow> sizes = options(product, "SIZE");
            if (!sizes.isEmpty()) {
                Optional<OptionRow> size = find(sizes, line.size());
                if (size.isEmpty()) {
                    return new Quote.Invalid("Talle inválido o faltante. Talles disponibles: " + values(sizes) + ".");
                }
                sizeSurcharge = surcharge(product, size.get(), totalQuantity);
            }
            BigDecimal colorSurcharge = BigDecimal.ZERO;
            List<OptionRow> colors = options(product, "COLOR");
            if (!colors.isEmpty() && line.color() != null) {
                Optional<OptionRow> color = find(colors, line.color());
                if (color.isEmpty()) {
                    return new Quote.Invalid("Color no disponible. Colores: " + values(colors) + ".");
                }
                colorSurcharge = surcharge(product, color.get(), totalQuantity);
            }
            BigDecimal unitPrice = base.get().unitPrice().add(designPrice).add(sizeSurcharge).add(colorSurcharge);
            BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(line.quantity()));
            lines.add(new Quote.Line(line.quantity(), line.size(), line.color(), base.get().unitPrice(), designPrice,
                    sizeSurcharge, colorSurcharge, unitPrice, subtotal));
            total = total.add(subtotal);
        }
        boolean wholesale = product.tiers().stream()
                .filter(tier -> tier.optionId() == null)
                .anyMatch(tier -> tier.minQuantity() > 1 && tier.covers(totalQuantity));
        return new Quote.Ok(product.row().id(), product.row().name(), product.row().publicUrl(), totalQuantity,
                wholesale, colors(product).isEmpty(), lines, total);
    }

    private static ProductSummary summarize(Product product) {
        Optional<TierRow> single = tier(product, null, 1);
        Optional<TierRow> wholesale = product.tiers().stream()
                .filter(tier -> tier.optionId() == null && tier.minQuantity() > 1)
                .findFirst();
        List<ProductSummary.PricedOption> designSizes = options(product, "DESIGN_SIZE").stream()
                .map(option -> new ProductSummary.PricedOption(option.value(), surcharge(product, option, 1),
                        dimensions(option.attributes())))
                .toList();
        List<ProductSummary.PricedOption> sizes = options(product, "SIZE").stream()
                .map(option -> new ProductSummary.PricedOption(option.value(), surcharge(product, option, 1), Map.of()))
                .toList();
        List<ProductSummary.PricedOption> colors = colors(product).stream()
                .map(option -> new ProductSummary.PricedOption(option.value(), surcharge(product, option, 1), Map.of()))
                .toList();
        return new ProductSummary(
                product.row().id(),
                product.row().name(),
                product.row().description(),
                product.row().publicUrl(),
                single.map(TierRow::unitPrice).orElse(null),
                wholesale.map(TierRow::minQuantity).orElse(null),
                wholesale.map(TierRow::unitPrice).orElse(null),
                designSizes,
                sizes,
                colors,
                values(options(product, "PLACEMENT")));
    }

    private static Map<String, Object> dimensions(JsonNode attributes) {
        Map<String, Object> dimensions = new LinkedHashMap<>();
        attributes.properties().forEach(entry -> dimensions.put(entry.getKey(), entry.getValue().decimalValue()));
        return dimensions;
    }

    private static Optional<TierRow> tier(Product product, UUID optionId, int quantity) {
        return product.tiers().stream()
                .filter(tier -> java.util.Objects.equals(tier.optionId(), optionId) && tier.covers(quantity))
                .findFirst();
    }

    private static BigDecimal surcharge(Product product, OptionRow option, int quantity) {
        return tier(product, option.id(), quantity).map(TierRow::unitPrice).orElse(BigDecimal.ZERO);
    }

    private static List<OptionRow> options(Product product, String type) {
        return product.options().stream().filter(option -> option.type().equals(type)).toList();
    }

    private static List<OptionRow> colors(Product product) {
        return options(product, "COLOR");
    }

    private static Optional<OptionRow> find(List<OptionRow> options, String value) {
        if (value == null) {
            return Optional.empty();
        }
        String wanted = normalize(value);
        return options.stream().filter(option -> normalize(option.value()).equals(wanted)).findFirst();
    }

    private static List<String> values(List<OptionRow> options) {
        return options.stream().map(OptionRow::value).toList();
    }

    private static boolean matches(Product product, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String haystack = normalize(product.row().name() + " " + product.row().description());
        for (String word : normalize(query).split("\\s+")) {
            if (word.length() > 2 && haystack.contains(stem(word))) {
                return true;
            }
        }
        return false;
    }

    /** Quita el plural simple para que "remeras" encuentre "remera". */
    private static String stem(String word) {
        return word.endsWith("es") && word.length() > 4 ? word.substring(0, word.length() - 2)
                : word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
    }

    private static String normalize(String text) {
        String decomposed = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }
}
