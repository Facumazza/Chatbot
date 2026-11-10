package com.chatbot.shared;

import java.security.SecureRandom;
import java.util.UUID;

/** Genera UUID v7: ordenados por tiempo, por lo que se indexan mejor que los aleatorios. */
public final class Uuids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Uuids() {
    }

    public static UUID v7() {
        long mostSignificant = (System.currentTimeMillis() << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
        long leastSignificant = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
