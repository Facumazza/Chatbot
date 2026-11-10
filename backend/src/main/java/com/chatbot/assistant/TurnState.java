package com.chatbot.assistant;

import com.chatbot.catalog.Quote;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Lo que va ocurriendo durante un turno: importes vistos, último presupuesto y derivación. */
final class TurnState {

    final AssistantInput input;
    final Set<BigDecimal> knownAmounts = new HashSet<>();
    final List<String> toolNames = new ArrayList<>();
    Quote.Ok lastQuote;
    String lastQuoteJson;
    String handoffReason;

    TurnState(AssistantInput input) {
        this.input = input;
    }

    String lastCustomerText() {
        for (int i = input.history().size() - 1; i >= 0; i--) {
            if (input.history().get(i).fromCustomer()) {
                return input.history().get(i).text();
            }
        }
        return "";
    }

    UUID lastCustomerMessageId() {
        for (int i = input.history().size() - 1; i >= 0; i--) {
            if (input.history().get(i).fromCustomer()) {
                return input.history().get(i).messageId();
            }
        }
        return null;
    }
}
