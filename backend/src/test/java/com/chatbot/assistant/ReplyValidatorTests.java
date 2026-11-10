package com.chatbot.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReplyValidatorTests {

    @Test
    void readsArgentineFormats() {
        assertThat(ReplyValidator.amounts("Sale $16.900, o $ 16900, o $16.900,50 y $1.234.567"))
                .containsExactly(new BigDecimal("16900"), new BigDecimal("16900"), new BigDecimal("16900.50"),
                        new BigDecimal("1234567"));
    }

    @Test
    void acceptsAmountsReturnedByTools() {
        assertThat(ReplyValidator.onlyKnownAmounts("Total $33.800", Set.of(new BigDecimal("33800.00")))).isTrue();
    }

    @Test
    void toleratesRoundingToWholePesos() {
        assertThat(ReplyValidator.onlyKnownAmounts("Sale $13.410", Set.of(new BigDecimal("13410.40")))).isTrue();
    }

    @Test
    void rejectsUnknownAmount() {
        assertThat(ReplyValidator.onlyKnownAmounts("Sale $9.999", Set.of(new BigDecimal("16900")))).isFalse();
    }

    @Test
    void textWithoutAmountsIsValid() {
        assertThat(ReplyValidator.onlyKnownAmounts("Te paso el enlace del producto.", Set.of())).isTrue();
    }
}
