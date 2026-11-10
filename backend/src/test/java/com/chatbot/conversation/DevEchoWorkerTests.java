package com.chatbot.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.chatbot.IntegrationTest;
import com.chatbot.whatsapp.WebhookEventProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class DevEchoWorkerTests extends IntegrationTest {

    @Autowired
    private WebhookEventProcessor processor;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ConversationService conversations;

    @Test
    void answersDueTurnsOnce() throws Exception {
        postSigned(textEvent("wamid.1", "AR.1", null, "Hola"));
        processor.processPending();
        jdbc.update("UPDATE conversation_turns SET run_after = now() - interval '1 second'");
        DevEchoWorker worker = new DevEchoWorker(jdbcClient, conversations);

        worker.answerDueTurns();
        worker.answerDueTurns();

        assertThat(jdbc.queryForObject(
                "SELECT body FROM messages WHERE direction = 'OUTBOUND' AND status = 'PENDING'", String.class))
                .isEqualTo(DevEchoWorker.REPLY);
        assertThat(count("SELECT count(*) FROM conversation_turns WHERE status = 'DONE'")).isEqualTo(1);
    }
}
