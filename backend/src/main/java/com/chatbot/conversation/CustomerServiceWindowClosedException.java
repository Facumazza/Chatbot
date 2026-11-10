package com.chatbot.conversation;

/** Pasaron más de 24 horas desde el último mensaje del cliente: solo se admiten plantillas. */
public class CustomerServiceWindowClosedException extends RuntimeException {

    public CustomerServiceWindowClosedException() {
        super("La ventana de atención de 24 horas está cerrada");
    }
}
