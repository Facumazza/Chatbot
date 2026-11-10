package com.chatbot.order;

import com.chatbot.conversation.ConversationService;
import com.chatbot.order.OrderRepository.LocalOrder;
import com.chatbot.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Consulta en la web los pedidos abiertos y le avisa al cliente cuando cambian. Los textos son
 * fijos, sin IA. Si la ventana de 24 horas está cerrada, el aviso queda pendiente hasta que el
 * cliente vuelva a escribir (las plantillas de Meta quedan para más adelante).
 */
@Component
public class OrderStatusWatcher {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusWatcher.class);

    private final OrderRepository orders;
    private final GrenlusShopClient shop;
    private final ConversationService conversations;

    OrderStatusWatcher(OrderRepository orders, GrenlusShopClient shop, ConversationService conversations) {
        this.orders = orders;
        this.shop = shop;
        this.conversations = conversations;
    }

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "${orders.status-interval}")
    public void checkOpenOrders() {
        List<LocalOrder> open = orders.open();
        if (open.isEmpty() || !shop.enabled()) {
            return;
        }
        Map<String, JsonNode> remote = new HashMap<>();
        try {
            shop.myOrders().forEach(order -> remote.put(order.path("id").asString(), order));
        } catch (RestClientException e) {
            log.warn("No se pudo consultar el estado de los pedidos en la web");
            return;
        }
        for (LocalOrder order : open) {
            JsonNode current = remote.get(order.externalOrderId());
            if (current != null) {
                TenantContext.runAs(order.tenantId(), () -> apply(order, current));
            }
        }
    }

    private void apply(LocalOrder order, JsonNode current) {
        String status = current.path("estado").asString(order.status());
        String paymentStatus = current.path("estadoPago").asString(order.paymentStatus());
        BigDecimal total = current.path("total").isNumber() ? current.path("total").decimalValue() : null;
        if (status.equals(order.status()) && paymentStatus.equals(order.paymentStatus())) {
            return;
        }
        for (String notice : notices(order, status, paymentStatus)) {
            if (!conversations.deliverSystemNotice(order.conversationId(), notice)) {
                log.info("Aviso del pedido {} pendiente: la ventana de 24 horas está cerrada", order.externalOrderId());
                return;
            }
        }
        orders.updateStatus(order.id(), status, paymentStatus, total);
    }

    static List<String> notices(LocalOrder order, String status, String paymentStatus) {
        String number = "#" + order.externalOrderId();
        List<String> notices = new ArrayList<>();
        if (!paymentStatus.equals(order.paymentStatus())) {
            switch (paymentStatus) {
                case "APROBADO" -> notices.add("¡Listo! Recibimos el pago y tu pedido " + number
                        + " quedó *confirmado*. Te avisamos cuando esté listo.");
                case "RECHAZADO" -> notices.add("No pudimos validar la transferencia del pedido " + number
                        + ". ¿Podés revisar el comprobante y mandarlo de nuevo por acá?");
                default -> {
                    // Otros cambios de pago no se avisan.
                }
            }
        }
        if (!status.equals(order.status())) {
            switch (status) {
                case "LISTO" -> notices.add("Tu pedido " + number + " está listo. "
                        + ("RETIRO".equals(order.deliveryMethod())
                                ? "Ya podés pasar a retirarlo."
                                : "Te vamos a escribir para coordinar la entrega."));
                case "CANCELADO" -> notices.add("Tu pedido " + number
                        + " fue cancelado. Si tenés alguna duda, escribinos por acá.");
                default -> {
                    // En preparación y otros estados intermedios no se avisan.
                }
            }
        }
        return notices;
    }
}
