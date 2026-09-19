package com.cdrware.wirepack.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * HKDF-SHA256 extract-then-expand (RFC 5869). Salt is empty → HashLen zeros.
 */
public final class HkdfSha256 {
    private static final int HASH_LEN = 32;
    private static final String HMAC = "HmacSHA256";

    private HkdfSha256() {
    }

    /** Extract with zero salt, then expand to {@code length} bytes. */
    public static byte[] expand(byte[] ikm, byte[] info, int length) {
        Objects.requireNonNull(ikm, "ikm");
        if (info == null) {
            info = new byte[0];
        }
        if (length < 0 || length > 255 * HASH_LEN) {
            throw new IllegalArgumentException("invalid HKDF length: " + length);
        }
        byte[] salt = new byte[HASH_LEN];
        byte[] prk = hmac(salt, ikm);
        try {
            return expandFromPrk(prk, info, length);
        } finally {
            Arrays.fill(prk, (byte) 0);
        }
    }

    public static byte[] expand(byte[] ikm, String info, int length) {
        return expand(ikm, info.getBytes(StandardCharsets.UTF_8), length);
    }

    private static byte[] expandFromPrk(byte[] prk, byte[] info, int length) {
        int n = (length + HASH_LEN - 1) / HASH_LEN;
        byte[] okm = new byte[length];
        byte[] t = new byte[0];
        int offset = 0;
        for (int i = 1; i <= n; i++) {
            byte[] input = new byte[t.length + info.length + 1];
            System.arraycopy(t, 0, input, 0, t.length);
            System.arraycopy(info, 0, input, t.length, info.length);
            input[input.length - 1] = (byte) i;
            t = hmac(prk, input);
            int copy = Math.min(HASH_LEN, length - offset);
            System.arraycopy(t, 0, okm, offset, copy);
            offset += copy;
        }
        return okm;
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 failed", e);
        }
    }
}
