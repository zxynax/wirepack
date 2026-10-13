package com.cdrware.wirepack.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Objects;

/**
 * AES-256-GCM. {@link #seal} / {@link #open} operate on ciphertext||tag only;
 * nonce-prefixed wire form is {@link AeadPayload}.
 */
public final class Aes256Gcm {
    public static final int KEY_LEN = 32;
    public static final int NONCE_LEN = 12;
    public static final int TAG_BITS = 128;
    public static final int TAG_LEN = TAG_BITS / 8;

    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private Aes256Gcm() {
    }

    public static byte[] seal(byte[] key32, byte[] nonce12, byte[] aad, byte[] plaintext) {
        return crypt(Cipher.ENCRYPT_MODE, key32, nonce12, aad, plaintext);
    }

    public static byte[] open(byte[] key32, byte[] nonce12, byte[] aad, byte[] ciphertextAndTag) {
        return crypt(Cipher.DECRYPT_MODE, key32, nonce12, aad, ciphertextAndTag);
    }

    private static byte[] crypt(int mode, byte[] key32, byte[] nonce12, byte[] aad, byte[] input) {
        Objects.requireNonNull(key32, "key");
        Objects.requireNonNull(nonce12, "nonce");
        Objects.requireNonNull(input, "input");
        if (key32.length != KEY_LEN) {
            throw new IllegalArgumentException("AES-256-GCM key must be 32 bytes");
        }
        if (nonce12.length != NONCE_LEN) {
            throw new IllegalArgumentException("AES-256-GCM nonce must be 12 bytes");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(mode, new SecretKeySpec(key32, "AES"), new GCMParameterSpec(TAG_BITS, nonce12));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(input);
        } catch (Exception e) {
            throw new IllegalStateException("AES-256-GCM failed", e);
        }
    }
}
