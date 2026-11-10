package com.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import com.chatbot.conversation.ConversationService;
import com.chatbot.conversation.DeliveryUpdate;
import com.chatbot.conversation.Message;
import com.chatbot.tenant.TenantContext;
import com.chatbot.whatsapp.WebhookEventProcessor;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WhatsAppWebhookTests extends IntegrationTest {

    @Autowired
    private WebhookEventProcessor processor;

    @Autowired
    private ConversationService conversations;

    @Test
    void verificationEchoesChallengeWithValidToken() throws Exception {
        HttpResponse<String> response = verify("subscribe", "test-verify-token", "12345");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("12345");
    }

    @Test
    void verificationRejectsWrongToken() throws Exception {
        assertThat(verify("subscribe", "incorrecto", "12345").statusCode()).isEqualTo(403);
    }

    @Test
    void unsignedRequestIsRejectedAndNotStored() throws Exception {
        assertThat(postWebhook("{}", null).statusCode()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM webhook_events")).isZero();
    }

    @Test
    void tamperedBodyIsRejected() throws Exception {
        assertThat(postWebhook("{\"manipulado\":true}", sign("{}")).statusCode()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM webhook_events")).isZero();
    }

    @Test
    void oversizedBodyIsRejected() throws Exception {
        String body = "x".repeat(4 * 1024 * 1024 + 1);

        assertThat(postSigned(body).statusCode()).isEqualTo(413);
    }

    @Test
    void signedMalformedBodyIsPreservedForDiagnosis() throws Exception {
        assertThat(postSigned("no es json").statusCode()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM webhook_events WHERE status = 'DEAD'")).isEqualTo(1);
    }

    @Test
    void inboundTextCreatesCustomerConversationMessageAndTurn() throws Exception {
        assertThat(postSigned(textEvent("wamid.1", "AR.1", "5491155550000", "Hola, ¿cuánto sale?")).statusCode())
                .isEqualTo(200);
        processor.processPending();

        assertThat(jdbc.queryForMap("SELECT wa_user_id, phone_number, wa_profile_name FROM customers"))
                .containsEntry("wa_user_id", "AR.1")
                .containsEntry("phone_number", "5491155550000")
                .containsEntry("wa_profile_name", "Ana");
        assertThat(jdbc.queryForObject("SELECT body FROM messages", String.class)).isEqualTo("Hola, ¿cuánto sale?");
        assertThat(count("SELECT count(*) FROM conversations WHERE status = 'ACTIVE' AND last_inbound_at IS NOT NULL"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM conversation_turns WHERE status = 'PENDING'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM webhook_events WHERE status = 'DONE'")).isEqualTo(1);
    }

    @Test
    void duplicateEventIsProcessedOnlyOnce() throws Exception {
        String event = textEvent("wamid.1", "AR.1", "5491155550000", "Hola");

        postSigned(event);
        processor.processPending();
        jdbc.update("UPDATE conversation_turns SET status = 'DONE'");
        postSigned(event);
        processor.processPending();

        assertThat(count("SELECT count(*) FROM messages")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM conversation_turns WHERE status = 'PENDING'")).isZero();
    }

    @Test
    void burstOfMessagesSharesConversationAndTurn() throws Exception {
        postSigned(textEvent("wamid.1", "AR.1", "5491155550000", "Hola"));
        postSigned(textEvent("wamid.2", "AR.1", "5491155550000", "Quería una remera"));
        processor.processPending();

        assertThat(count("SELECT count(*) FROM conversations")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM messages")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM conversation_turns")).isEqualTo(1);
    }

    @Test
    void singleEventMayCarrySeveralMessages() throws Exception {
        postSigned(envelope(PHONE_NUMBER_ID, """
                "contacts":[{"profile":{"name":"Ana"},"user_id":"AR.1"}],
                "messages":[%s,%s]
                """.formatted(
                textMessage("wamid.1", "AR.1", null, "uno"),
                textMessage("wamid.2", "AR.1", null, "dos"))));
        processor.processPending();

        assertThat(count("SELECT count(*) FROM messages")).isEqualTo(2);
    }

    @Test
    void customerWithUsernameIsIdentifiedWithoutPhone() throws Exception {
        postSigned(textEvent("wamid.1", "AR.1", null, "Hola"));
        processor.processPending();

        assertThat(jdbc.queryForObject("SELECT phone_number FROM customers", String.class)).isNull();
        assertThat(count("SELECT count(*) FROM conversations")).isEqualTo(1);
    }

    @Test
    void phoneLearnedLaterIsLinkedToTheSameCustomer() throws Exception {
        postSigned(textEvent("wamid.1", "AR.1", null, "Hola"));
        postSigned(textEvent("wamid.2", "AR.1", "5491155550000", "Sigo acá"));
        processor.processPending();

        assertThat(count("SELECT count(*) FROM customers WHERE phone_number = '5491155550000'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM customers")).isEqualTo(1);
    }

    @Test
    void eventForUnknownNumberIsDiscarded() throws Exception {
        postSigned(envelope("000", """
                "messages":[%s]
                """.formatted(textMessage("wamid.1", "AR.1", null, "Hola"))));
        processor.processPending();

        assertThat(count("SELECT count(*) FROM customers")).isZero();
        assertThat(count("SELECT count(*) FROM webhook_events WHERE status = 'DONE'")).isEqualTo(1);
    }

    @Test
    void reactionIsStoredWithoutWaitingForReply() throws Exception {
        postSigned(envelope(PHONE_NUMBER_ID, """
                "messages":[{"from_user_id":"AR.1","id":"wamid.1","timestamp":"1700000000","type":"reaction",
                "reaction":{"message_id":"wamid.0","emoji":"x"}}]
                """));
        processor.processPending();

        assertThat(count("SELECT count(*) FROM messages WHERE type = 'REACTION'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM conversation_turns")).isZero();
    }

    @Test
    void sameUserInTwoCompaniesStaysSeparate() throws Exception {
        UUID otherTenant = UUID.randomUUID();
        createAccount(otherTenant, "otra", "999");

        postSigned(textEvent("wamid.1", "AR.1", null, "Hola"));
        postSigned(envelope("999", """
                "messages":[%s]
                """.formatted(textMessage("wamid.1", "AR.1", null, "Hola"))));
        processor.processPending();

        assertThat(count("SELECT count(*) FROM customers")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM customers WHERE tenant_id = ?", otherTenant)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM messages")).isEqualTo(2);
    }

    @Test
    void deliveryStatusesOnlyMoveForward() throws Exception {
        givenOutboundMessage("wamid.out");

        postStatus("wamid.out", "delivered", "");
        postStatus("wamid.out", "read", "");
        postStatus("wamid.out", "delivered", "");
        processor.processPending();

        assertThat(statusOf("wamid.out")).isEqualTo("READ");
    }

    @Test
    void failedStatusStoresError() throws Exception {
        givenOutboundMessage("wamid.out");

        postStatus("wamid.out", "failed", ",\"errors\":[{\"code\":131026,\"title\":\"Undeliverable\"}]");
        processor.processPending();

        assertThat(jdbc.queryForMap("SELECT status, error_code, error_detail FROM messages WHERE wamid = 'wamid.out'"))
                .containsEntry("status", "FAILED")
                .containsEntry("error_code", "131026")
                .containsEntry("error_detail", "Undeliverable");
    }

    @Test
    void companyCannotUpdateMessagesOfAnother() throws Exception {
        givenOutboundMessage("wamid.out");
        UUID otherTenant = UUID.randomUUID();
        createAccount(otherTenant, "otra", "999");

        TenantContext.runAs(otherTenant, () -> conversations.applyDeliveryUpdate(
                new DeliveryUpdate("wamid.out", Message.Status.READ, Instant.now(), null, null)));

        assertThat(statusOf("wamid.out")).isEqualTo("SENT");
    }

    private void givenOutboundMessage(String wamid) throws Exception {
        postSigned(textEvent("wamid.in", "AR.1", null, "Hola"));
        processor.processPending();
        jdbc.update("""
                INSERT INTO messages (id, tenant_id, conversation_id, direction, sender_type, wamid, status)
                SELECT ?, tenant_id, id, 'OUTBOUND', 'ASSISTANT', ?, 'SENT' FROM conversations
                """, UUID.randomUUID(), wamid);
    }

    private void postStatus(String wamid, String status, String extra) throws Exception {
        postSigned(envelope(PHONE_NUMBER_ID, """
                "statuses":[{"id":"%s","status":"%s","timestamp":"1700000000","recipient_user_id":"AR.1"%s}]
                """.formatted(wamid, status, extra)));
    }

    private String statusOf(String wamid) {
        return jdbc.queryForObject("SELECT status FROM messages WHERE wamid = ?", String.class, wamid);
    }

    private HttpResponse<String> verify(String mode, String token, String challenge) throws Exception {
        return send(request("/webhooks/whatsapp?hub.mode=" + mode
                + "&hub.verify_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                + "&hub.challenge=" + challenge).GET().build());
    }
}
