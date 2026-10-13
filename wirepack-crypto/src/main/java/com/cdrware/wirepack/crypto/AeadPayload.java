package com.cdrware.wirepack.crypto;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * Wire AEAD form: {@code nonce:12 || ciphertext_and_tag}.
 * ChaCha20-Poly1305 and AES-256-GCM share this layout (12-byte nonce, 16-byte tag).
 */
public final class AeadPayload {
    public static final int NONCE_LEN = 12;
    public static final int TAG_LEN = 16;
    public static final int OVERHEAD = NONCE_LEN + TAG_LEN;

    private static final SecureRandom RANDOM = new SecureRandom();

    private AeadPayload() {
    }

    /** ChaCha20-Poly1305. HELLO always uses this so the AEAD offer is readable before negotiation. */
    public static byte[] seal(byte[] key, byte[] aad, byte[] plaintext) {
        return seal(AeadSuite.CHACHA20_POLY1305, key, aad, plaintext);
    }

    public static byte[] open(byte[] key, byte[] aad, byte[] sealed) {
        return open(AeadSuite.CHACHA20_POLY1305, key, aad, sealed);
    }

    public static byte[] seal(AeadSuite suite, byte[] key, byte[] aad, byte[] plaintext) {
        Objects.requireNonNull(suite, "suite");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(plaintext, "plaintext");
        byte[] nonce = new byte[NONCE_LEN];
        RANDOM.nextBytes(nonce);
        byte[] ct = sealRaw(suite, key, nonce, aad, plaintext);
        byte[] out = new byte[nonce.length + ct.length];
        System.arraycopy(nonce, 0, out, 0, nonce.length);
        System.arraycopy(ct, 0, out, nonce.length, ct.length);
        return out;
    }

    public static byte[] open(AeadSuite suite, byte[] key, byte[] aad, byte[] sealed) {
        Objects.requireNonNull(suite, "suite");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(sealed, "sealed");
        if (sealed.length < OVERHEAD) {
            throw new IllegalArgumentException("sealed payload too short");
        }
        byte[] nonce = Arrays.copyOfRange(sealed, 0, NONCE_LEN);
        byte[] ct = Arrays.copyOfRange(sealed, NONCE_LEN, sealed.length);
        return openRaw(suite, key, nonce, aad, ct);
    }

    private static byte[] sealRaw(AeadSuite suite, byte[] key, byte[] nonce, byte[] aad, byte[] plaintext) {
        return switch (suite) {
            case CHACHA20_POLY1305 -> ChaCha20Poly1305.seal(key, nonce, aad, plaintext);
            case AES_256_GCM -> Aes256Gcm.seal(key, nonce, aad, plaintext);
        };
    }

    private static byte[] openRaw(AeadSuite suite, byte[] key, byte[] nonce, byte[] aad, byte[] ciphertextAndTag) {
        return switch (suite) {
            case CHACHA20_POLY1305 -> ChaCha20Poly1305.open(key, nonce, aad, ciphertextAndTag);
            case AES_256_GCM -> Aes256Gcm.open(key, nonce, aad, ciphertextAndTag);
        };
    }
}
