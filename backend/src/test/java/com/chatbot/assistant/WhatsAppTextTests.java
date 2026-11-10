package com.chatbot.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WhatsAppTextTests {

    @Test
    void convertsMarkdownBoldToWhatsAppBold() {
        assertThat(WhatsAppText.normalize("- **Indumentaria:** remeras y **buzos**"))
                .isEqualTo("- *Indumentaria:* remeras y *buzos*");
    }

    @Test
    void removesHeadings() {
        assertThat(WhatsAppText.normalize("## Productos\nRemeras")).isEqualTo("Productos\nRemeras");
    }

    @Test
    void keepsWhatsAppFormattingAndPrices() {
        assertThat(WhatsAppText.normalize("Sale *$16.900* por unidad")).isEqualTo("Sale *$16.900* por unidad");
    }
}
