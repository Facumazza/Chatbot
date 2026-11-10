package com.chatbot.whatsapp;

import com.chatbot.conversation.ConversationService;
import com.chatbot.tenant.TenantContext;
import com.chatbot.whatsapp.WebhookEventStore.ClaimedEvent;
import com.chatbot.whatsapp.WebhookPayloadParser.ParsedChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Procesa los eventos guardados. Cada mensaje usa su propia transacción dentro del contexto
 * de su empresa; si algo falla, reprocesar es seguro porque guardar un mensaje es idempotente.
 */
@Component
public class WebhookEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventProcessor.class);
    private static final int BATCH_SIZE = 20;

    private final WebhookEventStore store;
    private final WebhookPayloadParser parser;
    private final WhatsAppAccountRepository accounts;
    private final ConversationService conversations;

    WebhookEventProcessor(
            WebhookEventStore store,
            WebhookPayloadParser parser,
            WhatsAppAccountRepository accounts,
            ConversationService conversations) {
        this.store = store;
        this.parser = parser;
        this.accounts = accounts;
        this.conversations = conversations;
    }

    @Scheduled(fixedDelayString = "${app.workers.webhook-delay}")
    public void processPending() {
        store.claim(BATCH_SIZE).forEach(this::process);
    }

    @Scheduled(cron = "0 0 4 * * *")
    public void purgeExpired() {
        int purged = store.purgeExpired();
        log.info("Eventos de webhook eliminados por antigüedad: {}", purged);
    }

    private void process(ClaimedEvent event) {
        try {
            parser.parse(event.payload()).forEach(this::apply);
            store.markDone(event.id());
        } catch (Exception e) {
            log.warn("Falló el evento {} ({}), intento {}", event.id(), e.getClass().getSimpleName(), event.attempts());
            store.markFailure(event, e);
        }
    }

    private void apply(ParsedChange change) {
        accounts.findByPhoneNumberIdAndActiveTrue(change.phoneNumberId()).ifPresentOrElse(
                account -> TenantContext.runAs(account.getTenantId(), () -> {
                    change.messages().forEach(message -> conversations.registerInbound(account.getId(), message));
                    change.deliveryUpdates().forEach(conversations::applyDeliveryUpdate);
                }),
                () -> log.warn("Evento recibido para un número no registrado"));
    }
}
