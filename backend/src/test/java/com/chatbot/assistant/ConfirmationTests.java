package com.chatbot.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfirmationTests {

    @Test
    void acceptsCommonWaysOfSayingYes() {
        for (String text : new String[] {"Sí", "si, confirmo", "Dale!", "ok", "Listo, avanzá", "de acuerdo", "Perfecto"}) {
            assertThat(AssistantTools.isExplicitConfirmation(text)).as(text).isTrue();
        }
    }

    @Test
    void rejectsQuestionsDoubtsAndNegatives() {
        for (String text : new String[] {"Quiero una remera M", "no", "Sí, pero no en rojo", "esperá que lo pienso",
                "¿Cuánto sale?", "visita", "", "todavía no"}) {
            assertThat(AssistantTools.isExplicitConfirmation(text)).as(text).isFalse();
        }
    }
}
