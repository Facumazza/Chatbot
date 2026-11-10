package com.chatbot.assistant;

import com.chatbot.assistant.LlmClient.FunctionCall;
import com.chatbot.catalog.CatalogService;
import com.chatbot.catalog.FaqService;
import com.chatbot.catalog.Quote;
import com.chatbot.catalog.QuoteRequest;
import com.chatbot.customer.CustomerService;
import com.chatbot.customer.LeadService;
import com.chatbot.order.OrderResult;
import com.chatbot.order.OrderService;
import com.chatbot.order.PlaceOrderRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * Herramientas que el modelo puede invocar. La empresa y el cliente salen del turno en curso,
 * nunca de los argumentos: el modelo no puede consultar datos de otro cliente ni de otra empresa.
 */
@Component
class AssistantTools {

    private static final Logger log = LoggerFactory.getLogger(AssistantTools.class);

    private static final Pattern CONFIRMATION = Pattern.compile(
            "(^|[^a-záéíóúñ])(s[ií]|dale|confirmo|confirmado|ok|okey|listo|de acuerdo|perfecto|correcto|hacelo|avanz[aá]|va)([^a-záéíóúñ]|$)");
    private static final Pattern NEGATION = Pattern.compile("(^|[^a-záéíóúñ])(no|todav[ií]a no|esper[aá])([^a-záéíóúñ]|$)");

    private final CatalogService catalog;
    private final FaqService faqs;
    private final CustomerService customers;
    private final LeadService leads;
    private final OrderService orders;
    private final JsonMapper jsonMapper;
    private final ArrayNode definitions;

    AssistantTools(CatalogService catalog, FaqService faqs, CustomerService customers, LeadService leads,
            OrderService orders, JsonMapper jsonMapper) throws IOException {
        this.catalog = catalog;
        this.faqs = faqs;
        this.customers = customers;
        this.leads = leads;
        this.orders = orders;
        this.jsonMapper = jsonMapper;
        try (InputStream tools = new ClassPathResource("assistant/tools.json").getInputStream()) {
            this.definitions = (ArrayNode) jsonMapper.readTree(tools);
        }
    }

    ArrayNode definitions() {
        return definitions;
    }

    /** Nunca lanza: un error se le informa al modelo para que pida el dato o derive. */
    String execute(FunctionCall call, TurnState state) {
        state.toolNames.add(call.name());
        try {
            JsonNode args = jsonMapper.readTree(call.arguments());
            Object result = switch (call.name()) {
                case "buscar_productos" -> findProducts(args);
                case "calcular_presupuesto" -> quote(args, state);
                case "buscar_preguntas_frecuentes" -> findFaqs(args);
                case "registrar_datos_cliente" -> recordCustomer(args, state);
                case "registrar_oportunidad" -> recordLead(args, state);
                case "crear_pedido" -> placeOrder(args, state);
                case "adjuntar_comprobante" -> orderResult(orders.attachReceipt(state.input.conversationId()),
                        "Comprobante enviado. Avisale al cliente que el equipo lo va a revisar y que le confirmamos por acá.");
                case "consultar_pedido" -> orders.currentOrder(state.input.conversationId())
                        .<Object>map(order -> orderResult(order, "Contale el estado en palabras simples."))
                        .orElse(Map.of("error", "El cliente no tiene pedidos cargados por WhatsApp."));
                case "derivar_a_operador" -> handoff(args, state);
                default -> Map.of("error", "Herramienta desconocida");
            };
            String output = jsonMapper.writeValueAsString(result);
            collectAmounts(jsonMapper.readTree(output), state);
            return output;
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Falló la herramienta {}", call.name(), e);
            return error("No se pudo completar la operación. Si es necesario, derivá a un operador.");
        }
    }

    private Object findProducts(JsonNode args) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean current = catalog.pricesAreCurrent();
        result.put("pricesAreCurrent", current);
        if (!current) {
            result.put("instruction", "Los precios no están actualizados: no informes importes y derivá las cotizaciones.");
            result.put("products", catalog.findProducts(text(args, "query")).stream()
                    .map(product -> Map.of("id", product.id(), "name", product.name(), "url", product.url()))
                    .toList());
            return result;
        }
        result.put("wholesaleRule", "El descuento mayorista se aplica al precio base desde la cantidad indicada, sumando todas las unidades del producto.");
        result.put("products", catalog.findProducts(text(args, "query")));
        return result;
    }

    private Object quote(JsonNode args, TurnState state) {
        if (!catalog.pricesAreCurrent()) {
            return Map.of("error", "Los precios no están actualizados. Derivá la cotización a un operador.");
        }
        List<QuoteRequest.Line> lines = new ArrayList<>();
        args.path("lines").forEach(line -> lines.add(new QuoteRequest.Line(
                line.path("quantity").asInt(0), text(line, "size"), text(line, "color"))));
        Quote quote = catalog.quote(new QuoteRequest(uuid(text(args, "product_id")), text(args, "design_size"), lines));
        return switch (quote) {
            case Quote.Invalid invalid -> Map.of("error", invalid.reason());
            case Quote.Ok ok -> {
                state.lastQuote = ok;
                state.lastQuoteJson = jsonMapper.writeValueAsString(ok);
                yield ok;
            }
        };
    }

    private Object findFaqs(JsonNode args) {
        List<FaqService.FaqAnswer> answers = faqs.search(text(args, "query"));
        if (answers.isEmpty()) {
            return Map.of("answers", List.of(),
                    "instruction", "No hay información cargada sobre esto. No la inventes: ofrecé derivar a un operador.");
        }
        return Map.of("answers", answers);
    }

    private Object recordCustomer(JsonNode args, TurnState state) {
        customers.recordDetails(state.input.customerId(), text(args, "full_name"), text(args, "locality"),
                text(args, "interest"), state.lastCustomerMessageId());
        return Map.of("saved", true);
    }

    private Object recordLead(JsonNode args, TurnState state) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (String field : List.of("sizes", "colors", "design", "notes")) {
            String value = text(args, field);
            if (value != null) {
                details.put(field, value);
            }
        }
        UUID productId = text(args, "product_id") == null ? null : uuid(text(args, "product_id"));
        Integer quantity = args.path("quantity").isNumber() ? args.path("quantity").asInt() : null;
        leads.register(state.input.customerId(), state.input.conversationId(), productId, quantity,
                jsonMapper.writeValueAsString(details),
                state.lastQuote == null ? null : state.lastQuote.total(),
                state.lastQuoteJson,
                state.input.receivedFiles());
        return Map.of("saved", true, "attachedDesigns", state.input.receivedFiles().size());
    }

    /**
     * Control fijo, independiente del modelo: el pedido solo se carga si el último mensaje del
     * cliente es una confirmación explícita.
     */
    private Object placeOrder(JsonNode args, TurnState state) {
        if (!isExplicitConfirmation(state.lastCustomerText())) {
            return Map.of("error", "El cliente todavía no confirmó. Mostrale el resumen con el total y preguntale si confirma el pedido.");
        }
        List<QuoteRequest.Line> lines = new ArrayList<>();
        args.path("lines").forEach(line -> lines.add(new QuoteRequest.Line(
                line.path("quantity").asInt(0), text(line, "size"), text(line, "color"))));
        List<PlaceOrderRequest.Design> designs = new ArrayList<>();
        args.path("designs").forEach(design -> designs.add(new PlaceOrderRequest.Design(
                String.valueOf(text(design, "position")).toUpperCase(Locale.ROOT), text(design, "notes"))));
        OrderResult result = orders.placeOrder(new PlaceOrderRequest(
                state.input.conversationId(), state.input.customerId(), uuid(text(args, "product_id")),
                text(args, "design_size"), lines, designs, text(args, "customer_name"),
                String.valueOf(text(args, "delivery_method")).toUpperCase(Locale.ROOT)));
        return orderResult(result, "Pasale al cliente el número de pedido, el total y los datos para transferir, "
                + "y pedile que mande la foto del comprobante por acá. No digas que está confirmado: "
                + "se confirma cuando el equipo aprueba el pago.");
    }

    private static Object orderResult(OrderResult result, String instruction) {
        return switch (result) {
            case OrderResult.Rejected rejected -> Map.of("error", rejected.reason());
            case OrderResult.Placed placed -> {
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("orderNumber", placed.orderNumber());
                output.put("total", placed.total());
                output.put("status", placed.status());
                output.put("paymentStatus", placed.paymentStatus());
                if (!placed.transferDetails().isEmpty()) {
                    output.put("transferDetails", placed.transferDetails());
                }
                if (placed.alreadyExisted()) {
                    output.put("note", "Este pedido ya existía: no se creó otro.");
                }
                output.put("instruction", instruction);
                yield output;
            }
        };
    }

    static boolean isExplicitConfirmation(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
        return CONFIRMATION.matcher(normalized).find() && !NEGATION.matcher(normalized).find();
    }

    private Object handoff(JsonNode args, TurnState state) {
        String reason = text(args, "reason");
        state.handoffReason = reason == null ? "Pedido del asistente" : reason;
        return Map.of("handedOff", true,
                "instruction", "Avisale al cliente que una persona del equipo lo va a atender. No prometas horarios.");
    }

    private void collectAmounts(JsonNode node, TurnState state) {
        if (node.isNumber()) {
            state.knownAmounts.add(node.decimalValue());
        }
        node.forEach(child -> collectAmounts(child, state));
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("product_id inválido: usá un id devuelto por buscar_productos.");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asString("").trim();
        return text.isEmpty() ? null : text;
    }

    private String error(String message) {
        return jsonMapper.writeValueAsString(Map.of("error", message));
    }
}
