package com.cdrware.wirepack.crypto;

import com.cdrware.wirepack.api.spi.CryptoSpi;

/**
 * Factory / {@link CryptoSpi} adapter around {@link SessionCrypto}.
 */
public final class DefaultCryptoSpi implements CryptoSpi {
    private final SessionCrypto session;

    public DefaultCryptoSpi(SessionCrypto session) {
        this.session = session;
    }

    public static DefaultCryptoSpi create(byte[] psk, int keyId, int rekeyAfterPackets) {
        return new DefaultCryptoSpi(SessionCrypto.create(psk, keyId, rekeyAfterPackets));
    }

    public SessionCrypto session() {
        return session;
    }

    @Override
    public boolean enabled() {
        return session.enabled();
    }

    @Override
    public byte[] seal(int keyId, byte[] aad, byte[] plaintext) {
        return session.seal(keyId, aad, plaintext);
    }

    @Override
    public byte[] open(int keyId, byte[] aad, byte[] sealed) {
        return session.open(keyId, aad, sealed);
    }
}
