package com.chatbot.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class CredentialCipherTests {

    private final CredentialCipher cipher =
            new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void decryptsWhatItEncrypted() {
        assertThat(cipher.decrypt(cipher.encrypt("token-secreto"))).isEqualTo("token-secreto");
    }

    @Test
    void sameValueEncryptsDifferently() {
        assertThat(cipher.encrypt("token")).isNotEqualTo(cipher.encrypt("token"));
    }

    @Test
    void rejectsAlteredData() {
        byte[] data = Base64.getDecoder().decode(cipher.encrypt("token"));
        data[data.length - 1] ^= 1;
        String altered = Base64.getEncoder().encodeToString(data);

        assertThatThrownBy(() -> cipher.decrypt(altered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsKeyOfWrongLength() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new CredentialCipher(shortKey)).isInstanceOf(IllegalArgumentException.class);
    }
}
