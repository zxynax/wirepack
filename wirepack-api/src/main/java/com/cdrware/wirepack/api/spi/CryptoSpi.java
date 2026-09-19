package com.cdrware.wirepack.api.spi;

import com.cdrware.wirepack.api.SafetyLimits;

/**
 * Payload seal/open. Default noop = cleartext profile.
 *
 * <p>v1 default implementation (wirepack-crypto): PSK-authenticated HELLO, immediate
 * ECDH traffic key, ChaCha20-Poly1305; rekey after {@link SafetyLimits#rekeyAfterPackets()}.
 */
public interface CryptoSpi {
    /**
     * Seal plaintext payload. {@code aad} is the 20-byte clear header (magic..payloadLen)
     * with {@code payloadLen} already set to the sealed length that will be on the wire.
     */
    default byte[] seal(int keyId, byte[] aad, byte[] plaintext) {
        return plaintext;
    }

    /** Open sealed payload; returns plaintext or throws on auth failure. */
    default byte[] open(int keyId, byte[] aad, byte[] sealed) {
        return sealed;
    }

    /** Whether this SPI performs real cryptography (vs cleartext). */
    default boolean enabled() {
        return false;
    }

    static CryptoSpi noop() {
        return new CryptoSpi() {};
    }
}
