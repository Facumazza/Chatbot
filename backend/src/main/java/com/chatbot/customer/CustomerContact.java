package com.chatbot.customer;

/** Datos con los que WhatsApp identifica a quien escribe. El teléfono puede faltar. */
public record CustomerContact(String waUserId, String phoneNumber, String profileName) {
}
