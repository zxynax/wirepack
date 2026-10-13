package com.cdrware.wirepack.crypto;

/**
 * Traffic AEAD negotiated at HELLO. ChaCha20-Poly1305 is always available.
 * AES-256-GCM is used only when both peers advertise it.
 */
public enum AeadSuite {
    CHACHA20_POLY1305("chacha"),
    AES_256_GCM("aes-gcm");

    /** HELLO flag bit: local peer is willing to use AES-256-GCM. */
    public static final int FLAG_AES_GCM = 0x01;

    private final String hkdfLabel;

    AeadSuite(String hkdfLabel) {
        this.hkdfLabel = hkdfLabel;
    }

    public String hkdfLabel() {
        return hkdfLabel;
    }

    /** HKDF info for the traffic key. Suite is bound so the two ciphers never share key material. */
    public String trafficInfo() {
        return "wirepack-v1-traffic-" + hkdfLabel;
    }

    /** HKDF info for in-flight rekey; stays on the suite locked at handshake. */
    public String rekeyInfo() {
        return "wirepack-v1-rekey-" + hkdfLabel;
    }

    public static int helloFlags(boolean preferAesGcm) {
        return preferAesGcm ? FLAG_AES_GCM : 0;
    }

    public static boolean offersAesGcm(int flags) {
        return (flags & FLAG_AES_GCM) != 0;
    }

    /** AES only when both sides opted in; otherwise ChaCha. */
    public static AeadSuite negotiate(boolean localPrefersAesGcm, boolean peerOffersAesGcm) {
        return localPrefersAesGcm && peerOffersAesGcm ? AES_256_GCM : CHACHA20_POLY1305;
    }
}
