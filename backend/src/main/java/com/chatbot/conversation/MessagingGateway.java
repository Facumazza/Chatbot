package com.chatbot.conversation;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/** Puerto hacia el canal de mensajería. El resto del sistema no conoce a Meta. */
public interface MessagingGateway {

    SendResult sendText(OutboundText message);

    /**
     * Copia un archivo recibido en {@code target}.
     *
     * @throws MediaTooLargeException si supera {@code maxBytes}
     * @throws IOException si la descarga falla y puede reintentarse
     */
    void downloadMedia(UUID accountId, String providerMediaId, OutputStream target, long maxBytes) throws IOException;

    /** Destinatario y texto. Se prefiere el teléfono; si falta, se usa el identificador del canal. */
    record OutboundText(UUID accountId, String phoneNumber, String waUserId, String body) {
    }

    sealed interface SendResult {

        record Sent(String providerMessageId) implements SendResult {
        }

        /** El canal lo rechazó sin procesarlo y conviene volver a intentar. */
        record Retryable(String code, String detail) implements SendResult {
        }

        /** El canal lo rechazó y reintentar no cambiaría el resultado. */
        record Rejected(String code, String detail) implements SendResult {
        }

        /** No se sabe si salió: reintentar podría duplicar el mensaje. */
        record Uncertain(String detail) implements SendResult {
        }
    }

    class MediaTooLargeException extends IOException {

        public MediaTooLargeException() {
            super("El archivo supera el tamaño permitido");
        }
    }
}
