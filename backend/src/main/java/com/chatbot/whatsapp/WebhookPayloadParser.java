package com.chatbot.whatsapp;

import com.chatbot.conversation.DeliveryUpdate;
import com.chatbot.conversation.InboundMessage;
import com.chatbot.conversation.InboundMessage.MediaAttachment;
import com.chatbot.conversation.Message;
import com.chatbot.customer.CustomerContact;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Traduce el formato de Meta a los tipos propios del sistema. */
@Component
class WebhookPayloadParser {

    private static final Logger log = LoggerFactory.getLogger(WebhookPayloadParser.class);
    private static final Set<String> MEDIA_TYPES = Set.of("image", "document", "audio", "video", "sticker");
    private static final String UNKNOWN_MIME_TYPE = "application/octet-stream";

    private static final Map<String, Message.Status> DELIVERY_STATUSES = Map.of(
            "sent", Message.Status.SENT,
            "delivered", Message.Status.DELIVERED,
            "read", Message.Status.READ,
            "failed", Message.Status.FAILED);

    private final JsonMapper jsonMapper;

    WebhookPayloadParser(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    List<ParsedChange> parse(String payload) {
        List<ParsedChange> changes = new ArrayList<>();
        for (JsonNode entry : jsonMapper.readTree(payload).path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                if ("messages".equals(change.path("field").asString(""))) {
                    changes.add(parseValue(change.path("value")));
                }
            }
        }
        return changes;
    }

    private ParsedChange parseValue(JsonNode value) {
        List<InboundMessage> messages = new ArrayList<>();
        for (JsonNode message : value.path("messages")) {
            CustomerContact contact = contactOf(message, value.path("contacts"));
            if (contact.waUserId() == null && contact.phoneNumber() == null) {
                log.warn("Mensaje sin identificador de usuario: se ignora");
                continue;
            }
            messages.add(toInbound(message, contact));
        }
        List<DeliveryUpdate> updates = new ArrayList<>();
        for (JsonNode status : value.path("statuses")) {
            Message.Status mapped = DELIVERY_STATUSES.get(status.path("status").asString(""));
            if (mapped != null) {
                updates.add(toDeliveryUpdate(status, mapped));
            }
        }
        return new ParsedChange(text(value.path("metadata"), "phone_number_id"), messages, updates);
    }

    private static InboundMessage toInbound(JsonNode message, CustomerContact contact) {
        String type = message.path("type").asString("unknown");
        MediaAttachment media = null;
        String body = null;
        if (MEDIA_TYPES.contains(type)) {
            JsonNode content = message.path(type);
            String mimeType = text(content, "mime_type");
            media = new MediaAttachment(
                    text(content, "id"),
                    mimeType == null ? UNKNOWN_MIME_TYPE : mimeType,
                    text(content, "filename"));
            body = text(content, "caption");
        } else {
            body = textBody(type, message);
        }
        return new InboundMessage(
                text(message, "id"), contact, type.toUpperCase(Locale.ROOT), body, media, timestampOf(message));
    }

    private static String textBody(String type, JsonNode message) {
        return switch (type) {
            case "text" -> text(message.path("text"), "body");
            case "button" -> text(message.path("button"), "text");
            case "interactive" -> {
                JsonNode interactive = message.path("interactive");
                String button = text(interactive.path("button_reply"), "title");
                yield button != null ? button : text(interactive.path("list_reply"), "title");
            }
            default -> null;
        };
    }

    private static CustomerContact contactOf(JsonNode message, JsonNode contacts) {
        String userId = text(message, "from_user_id");
        String phone = text(message, "from");
        return new CustomerContact(userId, phone, profileName(contacts, userId, phone));
    }

    private static String profileName(JsonNode contacts, String userId, String phone) {
        for (JsonNode contact : contacts) {
            boolean sameUser = userId != null && userId.equals(text(contact, "user_id"));
            boolean samePhone = phone != null && phone.equals(text(contact, "wa_id"));
            if (sameUser || samePhone) {
                return text(contact.path("profile"), "name");
            }
        }
        return null;
    }

    private static DeliveryUpdate toDeliveryUpdate(JsonNode status, Message.Status mapped) {
        JsonNode error = status.path("errors").path(0);
        return new DeliveryUpdate(
                text(status, "id"),
                mapped,
                timestampOf(status),
                text(error, "code"),
                text(error, "title"));
    }

    private static Instant timestampOf(JsonNode node) {
        String seconds = text(node, "timestamp");
        return seconds == null ? Instant.now() : Instant.ofEpochSecond(Long.parseLong(seconds));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }

    record ParsedChange(String phoneNumberId, List<InboundMessage> messages, List<DeliveryUpdate> deliveryUpdates) {
    }
}
