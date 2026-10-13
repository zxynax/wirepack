package com.cdrware.wirepack.api;

import com.cdrware.wirepack.api.spi.ActivationProvider;
import com.cdrware.wirepack.api.spi.ActivationRequest;
import com.cdrware.wirepack.api.spi.ActivationResult;
import com.cdrware.wirepack.api.spi.CryptoSpi;
import com.cdrware.wirepack.api.spi.TransportFactory;

import java.util.Objects;

/**
 * Configuration for {@link Wirepack#open(WirepackConfig)}.
 *
 * <p>Non-empty {@link #sharedKey()} enables v1 PSK→HELLO→ECDH AEAD
 * ({@code rekeyAfterPackets} on {@link SafetyLimits}). Omit shared key for cleartext.
 * Traffic AEAD is ChaCha20-Poly1305 unless {@link Builder#preferAesGcm(boolean)} is set
 * on <em>both</em> peers, in which case the session uses AES-256-GCM.
 */
public final class WirepackConfig {
    private final byte[] sharedKey;
    private final int keyId;
    private final boolean preferAesGcm;
    private final ActivationProvider activationProvider;
    private final ActivationRequest activationRequest;
    private final ActivationResult activationResult;
    private final TransportFactory transportFactory;
    private final SafetyLimits limits;
    private final CryptoSpi crypto;
    private final int windowSize;

    private WirepackConfig(Builder b) {
        this.sharedKey = b.sharedKey == null ? null : b.sharedKey.clone();
        this.keyId = b.keyId;
        this.preferAesGcm = b.preferAesGcm;
        this.activationProvider = b.activationProvider;
        this.activationRequest = b.activationRequest;
        this.activationResult = b.activationResult;
        this.transportFactory = b.transportFactory;
        this.limits = Objects.requireNonNullElse(b.limits, SafetyLimits.defaults());
        this.crypto = Objects.requireNonNullElse(b.crypto, CryptoSpi.noop());
        this.windowSize = b.windowSize > 0 ? b.windowSize : SafetyLimits.DEFAULT_WINDOW_SIZE;
    }

    public byte[] sharedKey() {
        return sharedKey == null ? null : sharedKey.clone();
    }

    public byte[] sharedKeyRaw() {
        return sharedKey;
    }

    public int keyId() {
        return keyId;
    }

    /**
     * Local willingness to use AES-256-GCM for traffic. The session uses AES only when
     * the peer advertises the same preference in HELLO; otherwise ChaCha20-Poly1305.
     */
    public boolean preferAesGcm() {
        return preferAesGcm;
    }

    public ActivationProvider activationProvider() {
        return activationProvider;
    }

    public ActivationRequest activationRequest() {
        return activationRequest;
    }

    public ActivationResult activationResult() {
        return activationResult;
    }

    public TransportFactory transportFactory() {
        return transportFactory;
    }

    public SafetyLimits limits() {
        return limits;
    }

    public CryptoSpi crypto() {
        return crypto;
    }

    public int windowSize() {
        return windowSize;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private byte[] sharedKey;
        private int keyId;
        private boolean preferAesGcm;
        private ActivationProvider activationProvider;
        private ActivationRequest activationRequest;
        private ActivationResult activationResult;
        private TransportFactory transportFactory;
        private SafetyLimits limits;
        private CryptoSpi crypto;
        private int windowSize = SafetyLimits.DEFAULT_WINDOW_SIZE;

        public Builder sharedKey(byte[] key) {
            this.sharedKey = key == null ? null : key.clone();
            return this;
        }

        public Builder keyId(int keyId) {
            this.keyId = keyId;
            return this;
        }

        /**
         * Offer AES-256-GCM. Traffic uses it only when the peer also sets this;
         * a single peer opting in keeps ChaCha20-Poly1305.
         */
        public Builder preferAesGcm(boolean preferAesGcm) {
            this.preferAesGcm = preferAesGcm;
            return this;
        }

        public Builder activation(ActivationProvider provider) {
            this.activationProvider = provider;
            return this;
        }

        public Builder activationRequest(ActivationRequest request) {
            this.activationRequest = request;
            return this;
        }

        public Builder activationResult(ActivationResult result) {
            this.activationResult = result;
            if (result != null) {
                this.keyId = result.keyId();
                this.sharedKey = result.keyMaterialRaw().clone();
            }
            return this;
        }

        public Builder transport(TransportFactory factory) {
            this.transportFactory = factory;
            return this;
        }

        public Builder limits(SafetyLimits limits) {
            this.limits = limits;
            return this;
        }

        public Builder crypto(CryptoSpi crypto) {
            this.crypto = crypto;
            return this;
        }

        public Builder windowSize(int windowSize) {
            this.windowSize = windowSize;
            return this;
        }

        public WirepackConfig build() {
            return new WirepackConfig(this);
        }
    }
}
