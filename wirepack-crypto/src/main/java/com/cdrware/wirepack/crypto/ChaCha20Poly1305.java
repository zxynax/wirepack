package com.cdrware.wirepack.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Objects;

/**
 * ChaCha20-Poly1305 AEAD. {@link #seal} / {@link #open} operate on ciphertext||tag only;
 * callers that need nonce||ct+tag should use {@link AeadPayload}.
 */
public final class ChaCha20Poly1305 {
    public static final int KEY_LEN = 32;
    public static final int NONCE_LEN = 12;
    public static final int TAG_LEN = 16;

    private static final String TRANSFORM = "ChaCha20-Poly1305";

    private ChaCha20Poly1305() {
    }

    /** Encrypt; returns ciphertext concatenated with 16-byte tag. */
    public static byte[] seal(byte[] key32, byte[] nonce12, byte[] aad, byte[] plaintext) {
        return crypt(Cipher.ENCRYPT_MODE, key32, nonce12, aad, plaintext);
    }

    /** Decrypt ciphertext||tag; throws on authentication failure. */
    public static byte[] open(byte[] key32, byte[] nonce12, byte[] aad, byte[] ciphertextAndTag) {
        return crypt(Cipher.DECRYPT_MODE, key32, nonce12, aad, ciphertextAndTag);
    }

    private static byte[] crypt(int mode, byte[] key32, byte[] nonce12, byte[] aad, byte[] input) {
        Objects.requireNonNull(key32, "key");
        Objects.requireNonNull(nonce12, "nonce");
        Objects.requireNonNull(input, "input");
        if (key32.length != KEY_LEN) {
            throw new IllegalArgumentException("ChaCha20-Poly1305 key must be 32 bytes");
        }
        if (nonce12.length != NONCE_LEN) {
            throw new IllegalArgumentException("ChaCha20-Poly1305 nonce must be 12 bytes");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(mode, new SecretKeySpec(key32, "ChaCha20"), new IvParameterSpec(nonce12));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(input);
        } catch (Exception e) {
            throw new IllegalStateException("ChaCha20-Poly1305 failed", e);
        }
    }
}
