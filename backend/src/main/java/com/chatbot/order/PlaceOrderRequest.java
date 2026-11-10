package com.chatbot.order;

import com.chatbot.catalog.QuoteRequest;
import java.util.List;
import java.util.UUID;

/**
 * Pedido confirmado por el cliente en la conversación.
 *
 * @param designSize     tamaño del diseño más grande: CHICA, MEDIA o GRANDE
 * @param designs        una entrada por cada posición estampada; la i-ésima usa la i-ésima imagen recibida
 * @param deliveryMethod RETIRO o COORDINAR: con transferencia la web no admite envío a domicilio
 */
public record PlaceOrderRequest(
        UUID conversationId,
        UUID customerId,
        UUID productId,
        String designSize,
        List<QuoteRequest.Line> lines,
        List<Design> designs,
        String customerName,
        String deliveryMethod) {

    public record Design(String position, String notes) {
    }
}
