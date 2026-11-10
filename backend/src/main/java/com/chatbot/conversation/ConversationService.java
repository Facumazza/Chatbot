package com.chatbot.conversation;

import com.chatbot.customer.CustomerService;
import com.chatbot.shared.Uuids;
import com.chatbot.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
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
    private final ConversationTransitions transitions;
    private final ConversationHistory history;
    private final MediaStorage mediaStorage;
    private final EntityManager entityManager;

    ConversationService(
            ConversationRepository conversations,
            MessageRepository messages,
            MediaQueue mediaQueue,
            TurnQueue turnQueue,
            CustomerService customers,
            ConversationTransitions transitions,
            ConversationHistory history,
            MediaStorage mediaStorage,
            EntityManager entityManager) {
        this.conversations = conversations;
        this.messages = messages;
        this.mediaQueue = mediaQueue;
        this.turnQueue = turnQueue;
        this.customers = customers;
        this.transitions = transitions;
        this.history = history;
        this.mediaStorage = mediaStorage;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public Conversation get(UUID conversationId) {
        return conversations.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("La conversación no existe"));
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

    /** Pasa la conversación a la cola de operadores y deja registro del motivo. */
    @Transactional
    public boolean requestAgent(UUID conversationId, String reason) {
        Conversation conversation = conversations.findForUpdate(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("La conversación no existe"));
        if (!conversation.requestAgent()) {
            return false;
        }
        transitions.record(conversationId, Conversation.Status.ACTIVE, Conversation.Status.WAITING_FOR_AGENT,
                "ASSISTANT", reason);
        log.info("Conversación {} derivada a un operador", conversationId);
        return true;
    }

    /**
     * Control final antes de enviar una respuesta automática. Se descarta si un operador tomó la
     * conversación, si llegó un mensaje nuevo después de empezar el turno o si venció la ventana.
     *
     * @param afterHandoff la respuesta avisa una derivación hecha en este mismo turno
     */
    @Transactional
    public boolean deliverAutomaticReply(UUID conversationId, String body, Instant turnStartedAt, boolean afterHandoff) {
        Conversation conversation = conversations.findForUpdate(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("La conversación no existe"));
        Conversation.Status expected = afterHandoff ? Conversation.Status.WAITING_FOR_AGENT : Conversation.Status.ACTIVE;
        Instant now = Instant.now();
        if (conversation.getStatus() != expected
                || (!afterHandoff && messages.existsInboundAfter(conversationId, turnStartedAt))
                || !conversation.isCustomerServiceWindowOpen(now)) {
            return false;
        }
        entityManager.persist(Message.pendingText(Uuids.v7(), conversationId, Message.SenderType.ASSISTANT, body, now));
        conversation.registerActivity(now);
        return true;
    }

    /**
     * Aviso del sistema, por ejemplo un cambio de estado del pedido. Se envía aunque un operador
     * esté atendiendo; solo lo impide la ventana de 24 horas.
     */
    @Transactional
    public boolean deliverSystemNotice(UUID conversationId, String body) {
        Conversation conversation = conversations.findForUpdate(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("La conversación no existe"));
        Instant now = Instant.now();
        if (!conversation.isCustomerServiceWindowOpen(now)) {
            return false;
        }
        entityManager.persist(Message.pendingText(Uuids.v7(), conversationId, Message.SenderType.SYSTEM, body, now));
        conversation.registerActivity(now);
        return true;
    }

    @Transactional(readOnly = true)
    public List<ReceivedImage> receivedImages(UUID conversationId) {
        return history.receivedImages(conversationId);
    }

    public byte[] readImage(ReceivedImage image) {
        try {
            return mediaStorage.read(image.storageKey());
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer la imagen guardada", e);
        }
    }

    @Transactional(readOnly = true)
    public boolean alreadySent(UUID conversationId, String body) {
        return messages.existsByConversationIdAndDirectionAndBody(conversationId, Message.Direction.OUTBOUND, body);
    }

    private Conversation openConversation(UUID tenantId, UUID accountId, UUID customerId) {
        return conversations.findOpen(accountId, customerId).orElseGet(() -> {
            conversations.insertIfAbsent(Uuids.v7(), tenantId, customerId, accountId);
            return conversations.findOpen(accountId, customerId)
                    .orElseThrow(() -> new IllegalStateException("La conversación no pudo abrirse"));
        });
    }
}
