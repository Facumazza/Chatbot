package com.chatbot;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chatbot.conversation.ConversationService;
import com.chatbot.conversation.CustomerServiceWindowClosedException;
import com.chatbot.conversation.MediaDownloadWorker;
import com.chatbot.conversation.Message;
import com.chatbot.conversation.OutboundDispatcher;
import com.chatbot.tenant.TenantContext;
import com.chatbot.whatsapp.WebhookEventProcessor;
import com.github.tomakehurst.wiremock.http.Fault;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WhatsAppOutboundTests extends IntegrationTest {

    private static final String SEND_PATH = "/v27.0/" + PHONE_NUMBER_ID + "/messages";
    private static final String SEND_OK = "{\"messaging_product\":\"whatsapp\",\"messages\":[{\"id\":\"wamid.out\"}]}";

    @Autowired
    private WebhookEventProcessor processor;

    @Autowired
    private OutboundDispatcher dispatcher;

    @Autowired
    private MediaDownloadWorker mediaWorker;

    @Autowired
    private ConversationService conversations;

    @Test
    void sendsToPhoneWithDecryptedTokenAndStoresProviderId() throws Exception {
        META.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(okJson(SEND_OK)));
        reply(conversationWith("AR.1", "5491155550000"), "¿Qué diseño tenés? ñandú");

        dispatcher.dispatchPending();

        META.verify(postRequestedFor(urlEqualTo(SEND_PATH))
                .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                .withRequestBody(equalToJson("""
                        {"messaging_product":"whatsapp","recipient_type":"individual","to":"541155550000",
                         "type":"text","text":{"body":"¿Qué diseño tenés? ñandú","preview_url":false}}
                        """)));
        assertThat(outbound()).containsEntry("status", "SENT").containsEntry("wamid", "wamid.out");
    }

    @Test
    void usesBusinessScopedIdWhenPhoneIsUnknown() throws Exception {
        META.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(okJson(SEND_OK)));
        reply(conversationWith("AR.1", null), "Hola");

        dispatcher.dispatchPending();

        META.verify(postRequestedFor(urlEqualTo(SEND_PATH)).withRequestBody(equalToJson("""
                {"messaging_product":"whatsapp","recipient_type":"individual","recipient":"AR.1",
                 "type":"text","text":{"body":"Hola","preview_url":false}}
                """)));
    }

    @Test
    void throughputLimitIsRetriedLater() throws Exception {
        stubSendError(429, 130429);
        reply(conversationWith("AR.1", "549"), "Hola");

        dispatcher.dispatchPending();

        assertThat(outbound()).containsEntry("status", "PENDING").containsEntry("error_code", "130429");
        assertThat(count("SELECT count(*) FROM messages WHERE direction = 'OUTBOUND' AND next_attempt_at > now()"))
                .isEqualTo(1);
    }

    @Test
    void retriesStopAfterLimit() throws Exception {
        stubSendError(429, 130429);
        reply(conversationWith("AR.1", "549"), "Hola");
        jdbc.update("UPDATE messages SET send_attempts = 4 WHERE direction = 'OUTBOUND'");

        dispatcher.dispatchPending();

        assertThat(outbound()).containsEntry("status", "FAILED");
    }

    @Test
    void closedWindowErrorFromMetaIsFinal() throws Exception {
        stubSendError(400, 131047);
        reply(conversationWith("AR.1", "549"), "Hola");

        dispatcher.dispatchPending();

        assertThat(outbound()).containsEntry("status", "FAILED").containsEntry("error_code", "131047");
    }

    @Test
    void connectionDroppedMidRequestIsNotRetried() throws Exception {
        META.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        reply(conversationWith("AR.1", "549"), "Hola");

        dispatcher.dispatchPending();
        dispatcher.dispatchPending();

        assertThat(outbound()).containsEntry("status", "UNKNOWN");
        META.verify(1, postRequestedFor(urlEqualTo(SEND_PATH)));
    }

    @Test
    void serverErrorWithoutCodeIsNotRetried() throws Exception {
        META.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(serverError().withBody("<html>")));
        reply(conversationWith("AR.1", "549"), "Hola");

        dispatcher.dispatchPending();

        assertThat(outbound()).containsEntry("status", "UNKNOWN");
    }

    @Test
    void abandonedSendBecomesUnknownInsteadOfResending() throws Exception {
        reply(conversationWith("AR.1", "549"), "Hola");
        jdbc.update("""
                UPDATE messages SET status = 'SENDING', updated_at = now() - interval '5 minutes'
                WHERE direction = 'OUTBOUND'
                """);

        dispatcher.dispatchPending();

        assertThat(outbound()).containsEntry("status", "UNKNOWN");
        META.verify(0, postRequestedFor(urlEqualTo(SEND_PATH)));
    }

    @Test
    void freeTextIsBlockedAfterTwentyFourHours() throws Exception {
        UUID conversation = conversationWith("AR.1", "549");
        jdbc.update("UPDATE conversations SET last_inbound_at = now() - interval '25 hours'");

        assertThatThrownBy(() -> reply(conversation, "Hola"))
                .isInstanceOf(CustomerServiceWindowClosedException.class);
    }

    @Test
    void conversationOfAnotherCompanyIsNotVisible() throws Exception {
        UUID conversation = conversationWith("AR.1", "549");
        UUID otherTenant = UUID.randomUUID();
        createAccount(otherTenant, "otra", "999");

        assertThatThrownBy(() -> TenantContext.runAs(otherTenant, () ->
                conversations.enqueueReply(conversation, Message.SenderType.ASSISTANT, "Hola")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void imageIsDownloadedAndStored() throws Exception {
        byte[] content = "contenido-del-diseño".getBytes();
        stubMediaDownload(content);
        postSigned(mediaEvent("image", "image/png"));
        processor.processPending();

        mediaWorker.downloadPending();

        Map<String, Object> media = jdbc.queryForMap("SELECT * FROM message_media");
        assertThat(media).containsEntry("download_status", "STORED").containsEntry("size_bytes", (long) content.length);
        String expectedSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        assertThat(media).containsEntry("sha256", expectedSha);
        assertThat(Files.readAllBytes(Path.of("target/test-media", (String) media.get("storage_key")))).isEqualTo(content);
        META.verify(getRequestedFor(urlEqualTo("/files/x")).withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN)));
        assertThat(jdbc.queryForObject("SELECT body FROM messages", String.class)).isEqualTo("mi diseño");
    }

    @Test
    void fileAboveLimitIsRejected() throws Exception {
        stubMediaDownload(new byte[2048]);
        postSigned(mediaEvent("image", "image/png"));
        processor.processPending();

        mediaWorker.downloadPending();

        assertThat(jdbc.queryForObject("SELECT download_status FROM message_media", String.class)).isEqualTo("REJECTED");
    }

    @Test
    void disallowedTypeIsRejectedWithoutDownloading() throws Exception {
        postSigned(mediaEvent("video", "video/mp4"));
        processor.processPending();

        mediaWorker.downloadPending();

        assertThat(jdbc.queryForObject("SELECT download_status FROM message_media", String.class)).isEqualTo("REJECTED");
        META.verify(0, getRequestedFor(urlEqualTo("/v27.0/media-1")));
    }

    @Test
    void failedDownloadIsRetriedLater() throws Exception {
        META.stubFor(get(urlEqualTo("/v27.0/media-1")).willReturn(serverError()));
        postSigned(mediaEvent("image", "image/png"));
        processor.processPending();

        mediaWorker.downloadPending();

        assertThat(jdbc.queryForObject("SELECT download_status FROM message_media", String.class)).isEqualTo("PENDING");
        assertThat(count("SELECT attempts FROM message_media")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM message_media WHERE next_attempt_at > now()")).isEqualTo(1);
    }

    private void stubMediaDownload(byte[] content) {
        META.stubFor(get(urlEqualTo("/v27.0/media-1")).willReturn(okJson("""
                {"messaging_product":"whatsapp","url":"%s/files/x","mime_type":"image/png","id":"media-1"}
                """.formatted(META.baseUrl()))));
        META.stubFor(get(urlEqualTo("/files/x")).willReturn(aResponse().withBody(content)));
    }

    private static String mediaEvent(String type, String mimeType) {
        return envelope(PHONE_NUMBER_ID, """
                "messages":[{"from_user_id":"AR.1","id":"wamid.media","timestamp":"1700000000","type":"%s",
                "%s":{"id":"media-1","mime_type":"%s","sha256":"abc","caption":"mi diseño"}}]
                """.formatted(type, type, mimeType));
    }

    private UUID conversationWith(String userId, String phone) throws Exception {
        postSigned(textEvent("wamid.in", userId, phone, "Hola"));
        processor.processPending();
        return jdbc.queryForObject("SELECT id FROM conversations", UUID.class);
    }

    private void reply(UUID conversation, String body) {
        TenantContext.runAs(tenantId, () ->
                conversations.enqueueReply(conversation, Message.SenderType.ASSISTANT, body));
    }

    private Map<String, Object> outbound() {
        return jdbc.queryForMap("SELECT * FROM messages WHERE direction = 'OUTBOUND'");
    }

    private void stubSendError(int httpStatus, int code) {
        META.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse()
                .withStatus(httpStatus)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"Error de prueba\",\"code\":%d}}".formatted(code))));
    }
}
