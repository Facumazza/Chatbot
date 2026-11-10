package com.chatbot.whatsapp;

/**
 * Formato de envío del teléfono. Meta informa los celulares argentinos como 549 + área + número,
 * pero rechaza enviarles con ese 9 (error 131030 en números de prueba). Sin el 9 sí los acepta
 * y los asocia a la misma cuenta. Verificado el 2026-11-10 con el número de prueba de Meta.
 */
final class RecipientPhoneFormat {

    private static final String ARGENTINA_MOBILE_PREFIX = "549";
    private static final int ARGENTINA_MOBILE_LENGTH = 13;

    private RecipientPhoneFormat() {
    }

    static String forSending(String phone) {
        if (phone.startsWith(ARGENTINA_MOBILE_PREFIX) && phone.length() == ARGENTINA_MOBILE_LENGTH) {
            return "54" + phone.substring(ARGENTINA_MOBILE_PREFIX.length());
        }
        return phone;
    }
}
