package com.cdrware.wirepack.crypto;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * Wire AEAD form: {@code nonce:12 || ciphertext_and_tag}.
 */
public final class AeadPayload {
    private static final SecureRandom RANDOM = new SecureRandom();

    private AeadPayload() {
    }

    public static byte[] seal(byte[] key, byte[] aad, byte[] plaintext) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(plaintext, "plaintext");
        byte[] nonce = new byte[ChaCha20Poly1305.NONCE_LEN];
        RANDOM.nextBytes(nonce);
        byte[] ct = ChaCha20Poly1305.seal(key, nonce, aad, plaintext);
        byte[] out = new byte[nonce.length + ct.length];
        System.arraycopy(nonce, 0, out, 0, nonce.length);
        System.arraycopy(ct, 0, out, nonce.length, ct.length);
        return out;
    }

    public static byte[] open(byte[] key, byte[] aad, byte[] sealed) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(sealed, "sealed");
        if (sealed.length < ChaCha20Poly1305.NONCE_LEN + ChaCha20Poly1305.TAG_LEN) {
            throw new IllegalArgumentException("sealed payload too short");
        }
        byte[] nonce = Arrays.copyOfRange(sealed, 0, ChaCha20Poly1305.NONCE_LEN);
        byte[] ct = Arrays.copyOfRange(sealed, ChaCha20Poly1305.NONCE_LEN, sealed.length);
        return ChaCha20Poly1305.open(key, nonce, aad, ct);
    }
}
