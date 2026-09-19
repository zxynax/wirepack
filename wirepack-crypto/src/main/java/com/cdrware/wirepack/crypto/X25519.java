package com.cdrware.wirepack.crypto;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.XECPrivateKey;
import java.security.interfaces.XECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Objects;
import javax.crypto.KeyAgreement;

/**
 * X25519 key generation and Diffie–Hellman agree (raw 32-byte keys).
 */
public final class X25519 {
    public static final int KEY_LEN = 32;

    private X25519() {
    }

    public record KeyPair(byte[] privateKey, byte[] publicKey) {
        public KeyPair {
            Objects.requireNonNull(privateKey, "privateKey");
            Objects.requireNonNull(publicKey, "publicKey");
            if (privateKey.length != KEY_LEN || publicKey.length != KEY_LEN) {
                throw new IllegalArgumentException("X25519 keys must be 32 bytes");
            }
            privateKey = privateKey.clone();
            publicKey = publicKey.clone();
        }

        @Override
        public byte[] privateKey() {
            return privateKey.clone();
        }

        @Override
        public byte[] publicKey() {
            return publicKey.clone();
        }

        byte[] privateKeyRaw() {
            return privateKey;
        }

        byte[] publicKeyRaw() {
            return publicKey;
        }
    }

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("X25519");
            kpg.initialize(new NamedParameterSpec("X25519"), new SecureRandom());
            java.security.KeyPair kp = kpg.generateKeyPair();
            XECPrivateKey priv = (XECPrivateKey) kp.getPrivate();
            XECPublicKey pub = (XECPublicKey) kp.getPublic();
            byte[] privateKey = priv.getScalar().orElseThrow(
                    () -> new IllegalStateException("X25519 private scalar unavailable"));
            byte[] publicKey = uCoordinateToBytes(pub.getU());
            return new KeyPair(privateKey, publicKey);
        } catch (Exception e) {
            throw new IllegalStateException("X25519 key generation failed", e);
        }
    }

    public static byte[] agree(byte[] privateKey, byte[] peerPublic) {
        Objects.requireNonNull(privateKey, "privateKey");
        Objects.requireNonNull(peerPublic, "peerPublic");
        if (privateKey.length != KEY_LEN || peerPublic.length != KEY_LEN) {
            throw new IllegalArgumentException("X25519 keys must be 32 bytes");
        }
        try {
            KeyFactory kf = KeyFactory.getInstance("X25519");
            PrivateKey priv = kf.generatePrivate(
                    new XECPrivateKeySpec(new NamedParameterSpec("X25519"), privateKey.clone()));
            PublicKey pub = kf.generatePublic(
                    new XECPublicKeySpec(new NamedParameterSpec("X25519"), bytesToU(peerPublic)));
            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(priv);
            ka.doPhase(pub, true);
            byte[] shared = ka.generateSecret();
            if (shared.length != KEY_LEN) {
                throw new IllegalStateException("unexpected shared secret length: " + shared.length);
            }
            return shared;
        } catch (Exception e) {
            throw new IllegalStateException("X25519 agree failed", e);
        }
    }

    /** Little-endian u-coordinate → 32 bytes. */
    static byte[] uCoordinateToBytes(BigInteger u) {
        byte[] be = u.toByteArray();
        byte[] out = new byte[KEY_LEN];
        // BigInteger is big-endian; strip sign byte and reverse to little-endian.
        int srcPos = 0;
        int len = be.length;
        if (len > 0 && be[0] == 0) {
            srcPos = 1;
            len--;
        }
        if (len > KEY_LEN) {
            srcPos += len - KEY_LEN;
            len = KEY_LEN;
        }
        for (int i = 0; i < len; i++) {
            out[i] = be[srcPos + len - 1 - i];
        }
        return out;
    }

    static BigInteger bytesToU(byte[] le) {
        byte[] be = new byte[KEY_LEN];
        for (int i = 0; i < KEY_LEN; i++) {
            be[i] = le[KEY_LEN - 1 - i];
        }
        return new BigInteger(1, be);
    }
}
