package com.chatbot.customer;

/** @param returning ya tuvo conversaciones anteriores con la empresa */
public record CustomerProfile(String displayName, String locality, boolean returning) {
}
