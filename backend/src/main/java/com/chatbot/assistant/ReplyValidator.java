package com.chatbot.assistant;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Comprueba que todo importe de la respuesta haya salido de una herramienta en este turno. */
final class ReplyValidator {

    /** "$16.900", "$ 16900", "$16.900,50" en formato argentino. */
    private static final Pattern MONEY = Pattern.compile("\\$\\s?(\\d{1,3}(?:\\.\\d{3})+|\\d+)(?:,(\\d{1,2}))?");

    /** La web muestra precios sin decimales: se tolera el redondeo. */
    private static final BigDecimal ROUNDING_TOLERANCE = BigDecimal.ONE;

    private ReplyValidator() {
    }

    static List<BigDecimal> amounts(String text) {
        List<BigDecimal> amounts = new ArrayList<>();
        Matcher matcher = MONEY.matcher(text);
        while (matcher.find()) {
            String integer = matcher.group(1).replace(".", "");
            String decimals = matcher.group(2);
            amounts.add(new BigDecimal(decimals == null ? integer : integer + "." + decimals));
        }
        return amounts;
    }

    static boolean onlyKnownAmounts(String text, Collection<BigDecimal> known) {
        return amounts(text).stream().allMatch(amount -> known.stream()
                .anyMatch(value -> value.subtract(amount).abs().compareTo(ROUNDING_TOLERANCE) < 0));
    }
}
