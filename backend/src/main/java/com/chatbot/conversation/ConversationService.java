package com.chatbot.conversation;

import com.chatbot.customer.CustomerService;
import com.chatbot.shared.Uuids;
import com.chatbot.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final int MAX_TEXT_LENGTH = 4096;

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final MediaQueue mediaQueue;
    private final TurnQueue turnQueue;
    private final CustomerService customers;
    private final EntityManager entityManager;

    ConversationService(
            ConversationRepository conversations,
            MessageRepository messages,
            MediaQueue mediaQueue,
            TurnQueue turnQueue,
            CustomerService customers,
            EntityManager entityManager) {
        this.conversations = conversations;
        this.messages = messages;
        this.mediaQueue = mediaQueue;
        this.turnQueue = turnQueue;
        this.customers = customers;
        this.entityManager = entityManager;
    }

    /** Guarda el mensaje y deja pendiente su respuesta. Es seguro repetirlo con el mismo mensaje. */
    @Transactional
    public void registerInbound(UUID accountId, InboundMessage inbound) {
        // Sin esta comprobación, un reenvío tardío abriría una conversación vacía.
        if (messages.existsByWamid(inbound.providerMessageId())) {
            return;
        }
        UUID tenantId = TenantContext.require();
        UUID customerId = customers.resolveInbound(inbound.contact(), inbound.sentAt());
        Conversation conversation = openConversation(tenantId, accountId, customerId);

        UUID messageId = Uuids.v7();
        int inserted = messages.insertInboundIfAbsent(
                messageId, tenantId, conversation.getId(), inbound.providerMessageId(),
                inbound.type(), inbound.body(), inbound.sentAt());
        if (inserted == 0) {
            return;
        }
        if (inbound.media() != null) {
            mediaQueue.enqueue(tenantId, messageId, inbound.media());
        }
        conversation.registerInbound(inbound.sentAt());
        if (inbound.expectsReply() && conversation.getStatus() == Conversation.Status.ACTIVE) {
            turnQueue.enqueue(tenantId, conversation.getId());
        }
    }

    @Transactional
    public void applyDeliveryUpdate(DeliveryUpdate update) {
        messages.findByWamid(update.providerMessageId()).ifPresentOrElse(
                message -> message.applyDeliveryUpdate(update),
                () -> log.debug("Estado recibido para un mensaje desconocido"));
    }

    /** Encola una respuesta de texto. La envía {@link OutboundDispatcher}. */
    @Transactional
    public UUID enqueueReply(UUID conversationId, Message.SenderType senderType, String body) {
        if (body == null || body.isBlank() || body.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("El texto debe tener entre 1 y " + MAX_TEXT_LENGTH + " caracteres");
        }
        Conversation conversation = conversations.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("La conversación no existe"));
        Instant now = Instant.now();
        if (!conversation.isCustomerServiceWindowOpen(now)) {
            throw new CustomerServiceWindowClosedException();
        }
        Message message = Message.pendingText(Uuids.v7(), conversationId, senderType, body, now);
        entityManager.persist(message);
        conversation.registerActivity(now);
        return message.getId();
    }

    private Conversation openConversation(UUID tenantId, UUID accountId, UUID customerId) {
        return conversations.findOpen(accountId, customerId).orElseGet(() -> {
            conversations.insertIfAbsent(Uuids.v7(), tenantId, customerId, accountId);
            return conversations.findOpen(accountId, customerId)
                    .orElseThrow(() -> new IllegalStateException("La conversación no pudo abrirse"));
        });
    }
}
