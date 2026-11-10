package com.chatbot.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RecipientPhoneFormatTests {

    @Test
    void removesNineFromArgentineMobile() {
        assertThat(RecipientPhoneFormat.forSending("5491155550000")).isEqualTo("541155550000");
    }

    @Test
    void keepsArgentineMobileFromInterior() {
        assertThat(RecipientPhoneFormat.forSending("5493515550000")).isEqualTo("543515550000");
    }

    @Test
    void leavesOtherCountriesUntouched() {
        assertThat(RecipientPhoneFormat.forSending("15551851870")).isEqualTo("15551851870");
    }

    @Test
    void leavesUnexpectedLengthsUntouched() {
        assertThat(RecipientPhoneFormat.forSending("549115555")).isEqualTo("549115555");
    }
}
