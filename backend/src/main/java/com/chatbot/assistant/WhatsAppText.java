package com.chatbot.assistant;

import java.util.regex.Pattern;

/**
 * Adapta el Markdown que suelen generar los modelos al formato de WhatsApp,
 * que usa un solo asterisco para la negrita y no tiene títulos.
 */
final class WhatsAppText {

    private static final Pattern DOUBLE_ASTERISK = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern DOUBLE_UNDERSCORE = Pattern.compile("__(.+?)__");
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}\\s+");

    private WhatsAppText() {
    }

    static String normalize(String text) {
        String result = DOUBLE_ASTERISK.matcher(text).replaceAll("*$1*");
        result = DOUBLE_UNDERSCORE.matcher(result).replaceAll("_$1_");
        return HEADING.matcher(result).replaceAll("").trim();
    }
}
