package com.chatbot.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.chatbot.conversation.InboundMessage;
import com.chatbot.conversation.Message;
import com.chatbot.whatsapp.WebhookPayloadParser.ParsedChange;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WebhookPayloadParserTests {

    private final WebhookPayloadParser parser = new WebhookPayloadParser(JsonMapper.builder().build());

    private static String value(String fields) {
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"field":"messages","value":{
                "metadata":{"phone_number_id":"123"},%s}}]}]}
                """.formatted(fields);
    }

    private InboundMessage single(String fields) {
        return parser.parse(value(fields)).getFirst().messages().getFirst();
    }

    @Test
    void parsesTextWithPhoneAndBusinessScopedId() {
        InboundMessage message = single("""
                "contacts":[{"profile":{"name":"Ana"},"wa_id":"549","user_id":"AR.1"}],
                "messages":[{"from":"549","from_user_id":"AR.1","id":"w1","timestamp":"1700000000",
                "type":"text","text":{"body":"Hola"}}]
                """);

        assertThat(message.providerMessageId()).isEqualTo("w1");
        assertThat(message.type()).isEqualTo("TEXT");
        assertThat(message.body()).isEqualTo("Hola");
        assertThat(message.contact().waUserId()).isEqualTo("AR.1");
        assertThat(message.contact().phoneNumber()).isEqualTo("549");
        assertThat(message.contact().profileName()).isEqualTo("Ana");
        assertThat(message.sentAt()).isEqualTo(Instant.ofEpochSecond(1700000000));
    }

    @Test
    void acceptsMissingPhone() {
        InboundMessage message = single("""
                "contacts":[{"profile":{"name":"Ana","username":"ana"},"user_id":"AR.1"}],
                "messages":[{"from_user_id":"AR.1","id":"w1","timestamp":"1","type":"text","text":{"body":"Hola"}}]
                """);

        assertThat(message.contact().phoneNumber()).isNull();
        assertThat(message.contact().profileName()).isEqualTo("Ana");
    }

    @Test
    void parsesDocumentWithFilenameAndCaption() {
        InboundMessage message = single("""
                "messages":[{"from_user_id":"AR.1","id":"w1","timestamp":"1","type":"document",
                "document":{"id":"m1","mime_type":"application/pdf","filename":"logo.pdf","caption":"mi logo"}}]
                """);

        assertThat(message.media().providerMediaId()).isEqualTo("m1");
        assertThat(message.media().mimeType()).isEqualTo("application/pdf");
        assertThat(message.media().filename()).isEqualTo("logo.pdf");
        assertThat(message.body()).isEqualTo("mi logo");
    }

    @Test
    void parsesButtonReplyTitle() {
        InboundMessage message = single("""
                "messages":[{"from_user_id":"AR.1","id":"w1","timestamp":"1","type":"interactive",
                "interactive":{"type":"button_reply","button_reply":{"id":"b1","title":"Sí"}}}]
                """);

        assertThat(message.body()).isEqualTo("Sí");
    }

    @Test
    void parsesFailedStatusWithError() {
        ParsedChange change = parser.parse(value("""
                "statuses":[{"id":"w1","status":"failed","timestamp":"1",
                "errors":[{"code":131026,"title":"No entregable"}]}]
                """)).getFirst();

        assertThat(change.deliveryUpdates().getFirst().status()).isEqualTo(Message.Status.FAILED);
        assertThat(change.deliveryUpdates().getFirst().errorCode()).isEqualTo("131026");
        assertThat(change.deliveryUpdates().getFirst().errorDetail()).isEqualTo("No entregable");
    }

    @Test
    void ignoresUnsupportedStatusAndOtherFields() {
        assertThat(parser.parse(value("""
                "statuses":[{"id":"w1","status":"played","timestamp":"1"}]
                """)).getFirst().deliveryUpdates()).isEmpty();
        List<ParsedChange> other = parser.parse("""
                {"entry":[{"changes":[{"field":"account_update","value":{}}]}]}
                """);
        assertThat(other).isEmpty();
    }

    @Test
    void skipsMessageWithoutAnyUserIdentifier() {
        assertThat(parser.parse(value("""
                "messages":[{"id":"w1","timestamp":"1","type":"text","text":{"body":"x"}}]
                """)).getFirst().messages()).isEmpty();
    }
}
