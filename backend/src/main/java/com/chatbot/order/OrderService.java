package com.chatbot.order;

import com.chatbot.catalog.CatalogService;
import com.chatbot.catalog.Quote;
import com.chatbot.catalog.QuoteRequest;
import com.chatbot.conversation.ConversationService;
import com.chatbot.conversation.ReceivedImage;
import com.chatbot.customer.CustomerService;
import com.chatbot.customer.LeadService;
import com.chatbot.order.OrderRepository.LocalOrder;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Carga pedidos en la web de Grenlus en nombre del cliente de WhatsApp. */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final Set<String> DELIVERY_METHODS = Set.of("RETIRO", "COORDINAR");
    private static final Set<String> POSITIONS =
            Set.of("FRENTE", "ESPALDA", "MANGA_DERECHA", "MANGA_IZQUIERDA", "PECHO", "OTRA");
    private static final String PAYMENT_PENDING = "PENDIENTE";

    private final GrenlusShopClient shop;
    private final OrderRepository orders;
    private final CatalogService catalog;
    private final ConversationService conversations;
    private final CustomerService customers;
    private final LeadService leads;
    private final JsonMapper jsonMapper;

    OrderService(GrenlusShopClient shop, OrderRepository orders, CatalogService catalog,
            ConversationService conversations, CustomerService customers, LeadService leads, JsonMapper jsonMapper) {
        this.shop = shop;
        this.orders = orders;
        this.catalog = catalog;
        this.conversations = conversations;
        this.customers = customers;
        this.leads = leads;
        this.jsonMapper = jsonMapper;
    }

    public OrderResult placeOrder(PlaceOrderRequest request) {
        if (!shop.enabled()) {
            return new OrderResult.Rejected("Los pedidos por WhatsApp no están habilitados. Derivá a un operador.");
        }
        Optional<LocalOrder> pending = orders.latest(request.conversationId())
                .filter(order -> PAYMENT_PENDING.equals(order.paymentStatus()) && !"CANCELADO".equals(order.status()));
        if (pending.isPresent()) {
            // Evita pedidos duplicados si el modelo repite la herramienta o el cliente confirma dos veces.
            LocalOrder order = pending.get();
            return new OrderResult.Placed(order.externalOrderId(), order.total(), order.status(),
                    order.paymentStatus(), transferDetails(), true);
        }
        Optional<String> invalid = validate(request);
        if (invalid.isPresent()) {
            return new OrderResult.Rejected(invalid.get());
        }
        Quote quote = catalog.quote(new QuoteRequest(request.productId(), request.designSize(), request.lines()));
        if (quote instanceof Quote.Invalid rejected) {
            return new OrderResult.Rejected(rejected.reason());
        }
        Optional<String> externalProductId = catalog.externalId(request.productId());
        if (externalProductId.isEmpty()) {
            return new OrderResult.Rejected("Este producto no se puede pedir por WhatsApp. Derivá a un operador.");
        }
        List<ReceivedImage> images = conversations.receivedImages(request.conversationId());
        if (images.stream().anyMatch(image -> !image.stored())) {
            return new OrderResult.Rejected("La imagen del diseño todavía se está recibiendo. Pedile al cliente que espere un momento.");
        }
        if (images.isEmpty() && catalog.requiresImage(request.productId())) {
            return new OrderResult.Rejected("Falta la imagen del diseño. Pedile al cliente que la mande por acá.");
        }
        try {
            Map<String, Object> payload = payload(request, externalProductId.get(), uploadDesigns(images, request.designs()));
            JsonNode created = shop.createOrder(payload);
            String orderNumber = created.path("id").asString();
            BigDecimal total = created.path("total").isNumber() ? created.path("total").decimalValue() : null;
            if (total != null && quote instanceof Quote.Ok ok && total.compareTo(ok.total()) != 0) {
                log.warn("El total de la web ({}) difiere del presupuesto del bot ({}) en el pedido {}", total, ok.total(), orderNumber);
            }
            orders.save(request.conversationId(), request.customerId(), orderNumber,
                    created.path("estado").asString(PAYMENT_PENDING), created.path("estadoPago").asString(PAYMENT_PENDING),
                    total, request.deliveryMethod(), jsonMapper.writeValueAsString(payload));
            leads.markWon(request.conversationId());
            log.info("Pedido {} cargado en la web desde la conversación {}", orderNumber, request.conversationId());
            return new OrderResult.Placed(orderNumber, total, created.path("estado").asString(PAYMENT_PENDING),
                    created.path("estadoPago").asString(PAYMENT_PENDING), transferDetails(), false);
        } catch (RestClientResponseException e) {
            String message = shop.errorMessage(e);
            log.warn("La web rechazó el pedido: {} {}", e.getStatusCode().value(), message);
            return new OrderResult.Rejected("La web no aceptó el pedido"
                    + (message == null ? "." : ": " + message) + " Si no se resuelve, derivá a un operador.");
        } catch (RestClientException e) {
            log.error("No se pudo cargar el pedido en la web", e);
            return new OrderResult.Rejected("La web no responde. Derivá a un operador.");
        }
    }

    /** Sube a la web la última imagen recibida después del pedido, como comprobante de transferencia. */
    public OrderResult attachReceipt(UUID conversationId) {
        Optional<LocalOrder> order = orders.latest(conversationId)
                .filter(candidate -> PAYMENT_PENDING.equals(candidate.paymentStatus()));
        if (order.isEmpty()) {
            return new OrderResult.Rejected("No hay un pedido pendiente de pago en esta conversación.");
        }
        Optional<ReceivedImage> receipt = conversations.receivedImages(conversationId).stream()
                .filter(image -> image.receivedAt().isAfter(order.get().createdAt().toInstant()))
                .reduce((first, second) -> second);
        if (receipt.isEmpty()) {
            return new OrderResult.Rejected("No llegó ninguna imagen después del pedido. Pedile la foto del comprobante.");
        }
        if (!receipt.get().stored()) {
            return new OrderResult.Rejected("El comprobante todavía se está recibiendo. Probá de nuevo en un momento.");
        }
        try {
            shop.uploadReceipt(order.get().externalOrderId(), conversations.readImage(receipt.get()),
                    "comprobante" + extension(receipt.get().mimeType()), receipt.get().mimeType());
            orders.markReceiptSent(order.get().id());
            return new OrderResult.Placed(order.get().externalOrderId(), order.get().total(), order.get().status(),
                    order.get().paymentStatus(), Map.of(), true);
        } catch (RestClientException e) {
            log.error("No se pudo subir el comprobante del pedido {}", order.get().externalOrderId(), e);
            return new OrderResult.Rejected("No se pudo enviar el comprobante a la web. Derivá a un operador.");
        }
    }

    /** Estado actual según la web; si no responde, el último conocido. */
    public Optional<OrderResult.Placed> currentOrder(UUID conversationId) {
        return orders.latest(conversationId).map(order -> {
            String status = order.status();
            String paymentStatus = order.paymentStatus();
            BigDecimal total = order.total();
            try {
                for (JsonNode remote : shop.myOrders()) {
                    if (order.externalOrderId().equals(remote.path("id").asString())) {
                        status = remote.path("estado").asString(status);
                        paymentStatus = remote.path("estadoPago").asString(paymentStatus);
                        total = remote.path("total").isNumber() ? remote.path("total").decimalValue() : total;
                    }
                }
            } catch (RestClientException e) {
                log.warn("No se pudo consultar el estado del pedido {} en la web", order.externalOrderId());
            }
            return new OrderResult.Placed(order.externalOrderId(), total, status, paymentStatus, Map.of(), true);
        });
    }

    private Optional<String> validate(PlaceOrderRequest request) {
        if (request.customerName() == null || request.customerName().isBlank()) {
            return Optional.of("Falta el nombre del cliente para el pedido.");
        }
        if (!DELIVERY_METHODS.contains(request.deliveryMethod())) {
            return Optional.of("La entrega tiene que ser RETIRO o COORDINAR. El envío a domicilio solo se puede pedir desde la web, pagando con Mercado Pago.");
        }
        if (request.designs().stream().anyMatch(design -> !POSITIONS.contains(design.position()))) {
            return Optional.of("Posición de diseño inválida. Valores: " + POSITIONS + ".");
        }
        return Optional.empty();
    }

    /**
     * Sube a la web las imágenes que usa cada diseño: la posición i usa la imagen i, o la última
     * si el cliente mandó menos imágenes que posiciones. Cada imagen se sube una sola vez.
     *
     * @return la ruta en la web para cada diseño, en el mismo orden; vacía si no hay imágenes
     */
    private List<String> uploadDesigns(List<ReceivedImage> images, List<PlaceOrderRequest.Design> designs) {
        List<String> routes = new ArrayList<>();
        if (images.isEmpty()) {
            return routes;
        }
        Map<UUID, String> uploaded = new HashMap<>();
        for (int i = 0; i < designs.size(); i++) {
            ReceivedImage image = images.get(Math.min(i, images.size() - 1));
            String fileName = "diseno-" + (i + 1) + extension(image.mimeType());
            routes.add(uploaded.computeIfAbsent(image.mediaId(), id ->
                    shop.uploadImage(conversations.readImage(image), fileName, image.mimeType())));
        }
        return routes;
    }

    private Map<String, Object> payload(PlaceOrderRequest request, String externalProductId, List<String> imageRoutes) {
        List<Map<String, Object>> designs = new ArrayList<>();
        for (int i = 0; i < request.designs().size(); i++) {
            Map<String, Object> design = new LinkedHashMap<>();
            design.put("rutaImagen", imageRoutes.isEmpty() ? null : imageRoutes.get(i));
            design.put("posicion", request.designs().get(i).position());
            design.put("tamano", request.designSize());
            design.put("posicionX", 0);
            design.put("posicionY", 0);
            design.put("ancho", 0);
            design.put("alto", 0);
            design.put("observaciones", request.designs().get(i).notes());
            designs.add(design);
        }
        List<Map<String, Object>> details = new ArrayList<>();
        for (QuoteRequest.Line line : request.lines()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("productoId", Long.parseLong(externalProductId));
            detail.put("cantidad", line.quantity());
            detail.put("talle", line.size());
            detail.put("color", line.color());
            detail.put("disenos", designs);
            details.add(detail);
        }
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("nombreCliente", request.customerName().trim());
        order.put("telefono", customers.phoneNumber(request.customerId())
                .map(phone -> "+" + phone)
                .orElse("WhatsApp (número no visible)"));
        order.put("email", null);
        order.put("metodoEntrega", request.deliveryMethod());
        order.put("metodoPago", "TRANSFERENCIA");
        order.put("detalles", details);
        return order;
    }

    private Map<String, String> transferDetails() {
        try {
            Map<String, String> details = new LinkedHashMap<>();
            shop.transferDetails().properties().forEach(entry -> {
                if (!entry.getValue().asString("").isBlank()) {
                    details.put(entry.getKey(), entry.getValue().asString());
                }
            });
            return details;
        } catch (RestClientException e) {
            log.warn("No se pudieron obtener los datos de transferencia de la web");
            return Map.of();
        }
    }

    private static String extension(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }
}
