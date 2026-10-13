package com.cdrware.wirepack.crypto;

import com.cdrware.wirepack.api.BuiltinTypeId;
import com.cdrware.wirepack.api.Entity;
import com.cdrware.wirepack.api.spi.CryptoSpi;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wirepack v1 session crypto: PSK HELLO (always ChaCha20-Poly1305) → X25519 traffic key,
 * then the negotiated AEAD. AES-256-GCM is used only when both peers advertise it;
 * otherwise ChaCha20-Poly1305. In-flight REKEY stays on the suite locked at handshake.
 */
public final class SessionCrypto implements CryptoSpi {
    private static final String INFO_HELLO = "wirepack-v1-hello";
    /** HELLO plaintext: {@code x25519_public[32] || aeadFlags:u8}. */
    static final int HELLO_PLAIN_LEN = X25519.KEY_LEN + 1;
    private static final int REKEY_VALUE_LEN = 4 + 4 + X25519.KEY_LEN;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] psk;
    private final int baseKeyId;
    private final byte[] helloKey;
    private final X25519.KeyPair ephemeral;
    private final int rekeyAfterPackets;
    private final boolean preferAesGcm;

    private volatile byte[] peerPublic;
    private volatile boolean peerOffersAesGcm;
    private volatile AeadSuite aeadSuite;
    private final ConcurrentHashMap<Integer, byte[]> trafficKeys = new ConcurrentHashMap<>();
    private volatile int currentKeyId;
    private volatile boolean handshakeComplete;
    private long packetsSealedWithCurrent;
    private volatile boolean rekeyInFlight;
    private X25519.KeyPair pendingRekey;
    private int pendingRekeyNextKeyId;
    private int pendingRekeyNonce;

    private SessionCrypto(byte[] psk, int baseKeyId, int rekeyAfterPackets, boolean preferAesGcm) {
        Objects.requireNonNull(psk, "psk");
        if (psk.length == 0) {
            throw new IllegalArgumentException("psk must be non-empty");
        }
        if (rekeyAfterPackets < 1) {
            throw new IllegalArgumentException("rekeyAfterPackets must be >= 1");
        }
        this.psk = psk.clone();
        this.baseKeyId = baseKeyId;
        this.rekeyAfterPackets = rekeyAfterPackets;
        this.preferAesGcm = preferAesGcm;
        this.helloKey = HkdfSha256.expand(this.psk, INFO_HELLO, 32);
        this.ephemeral = X25519.generateKeyPair();
        this.currentKeyId = baseKeyId;
    }

    public static SessionCrypto create(byte[] psk, int keyId, int rekeyAfterPackets) {
        return create(psk, keyId, rekeyAfterPackets, false);
    }

    /**
     * @param preferAesGcm local willingness to use AES-256-GCM. The session uses it
     *     only when the peer's HELLO sets the same flag.
     */
    public static SessionCrypto create(byte[] psk, int keyId, int rekeyAfterPackets, boolean preferAesGcm) {
        return new SessionCrypto(psk, keyId, rekeyAfterPackets, preferAesGcm);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    public byte[] localPublic() {
        return ephemeral.publicKey();
    }

    public boolean preferAesGcm() {
        return preferAesGcm;
    }

    /** Negotiated traffic suite, or null until HELLO completes. */
    public AeadSuite aeadSuite() {
        return aeadSuite;
    }

    public byte[] sealHello(byte[] aad) {
        byte[] plain = new byte[HELLO_PLAIN_LEN];
        System.arraycopy(ephemeral.publicKeyRaw(), 0, plain, 0, X25519.KEY_LEN);
        plain[X25519.KEY_LEN] = (byte) AeadSuite.helloFlags(preferAesGcm);
        return AeadPayload.seal(helloKey, aad, plain);
    }

    public synchronized void acceptPeerHello(byte[] aad, byte[] sealedHello) {
        byte[] plain = AeadPayload.open(helloKey, aad, sealedHello);
        if (plain.length != HELLO_PLAIN_LEN) {
            throw new IllegalArgumentException(
                    "peer HELLO must be " + HELLO_PLAIN_LEN + " bytes, got " + plain.length);
        }
        byte[] peerPub = Arrays.copyOf(plain, X25519.KEY_LEN);
        this.peerPublic = peerPub;
        this.peerOffersAesGcm = AeadSuite.offersAesGcm(plain[X25519.KEY_LEN] & 0xff);
        if (!handshakeComplete) {
            deriveTraffic();
        }
    }

    public synchronized void deriveTraffic() {
        if (handshakeComplete) {
            return;
        }
        if (peerPublic == null) {
            throw new IllegalStateException("peer public key not set");
        }
        AeadSuite suite = AeadSuite.negotiate(preferAesGcm, peerOffersAesGcm);
        byte[] ss = X25519.agree(ephemeral.privateKeyRaw(), peerPublic);
        byte[] localPub = ephemeral.publicKeyRaw();
        byte[] traffic = hkdfFromEcdh(psk, ss, localPub, peerPublic, suite.trafficInfo());
        Arrays.fill(ss, (byte) 0);

        this.aeadSuite = suite;
        int trafficKeyId = baseKeyId + 1;
        trafficKeys.put(trafficKeyId, traffic);
        currentKeyId = trafficKeyId;
        packetsSealedWithCurrent = 0;
        handshakeComplete = true;
    }

    public boolean handshakeComplete() {
        return handshakeComplete;
    }

    public int currentKeyId() {
        return currentKeyId;
    }

    public synchronized byte[] sealTraffic(byte[] aad, byte[] plaintext) {
        requireHandshake();
        byte[] key = trafficKeys.get(currentKeyId);
        if (key == null) {
            throw new IllegalStateException("no traffic key for keyId=" + currentKeyId);
        }
        byte[] sealed = AeadPayload.seal(aeadSuite, key, aad, plaintext);
        packetsSealedWithCurrent++;
        return sealed;
    }

    public byte[] openTraffic(int keyId, byte[] aad, byte[] sealed) {
        byte[] key = trafficKeys.get(keyId);
        if (key == null) {
            throw new IllegalStateException("unknown traffic keyId=" + keyId);
        }
        AeadSuite suite = aeadSuite;
        if (suite == null) {
            throw new IllegalStateException("handshake not complete");
        }
        return AeadPayload.open(suite, key, aad, sealed);
    }

    public synchronized boolean shouldRekey() {
        return handshakeComplete
                && packetsSealedWithCurrent >= rekeyAfterPackets
                && !rekeyInFlight;
    }

    public synchronized Entity buildRekeyOffer() {
        requireHandshake();
        if (rekeyInFlight && pendingRekey != null) {
            throw new IllegalStateException("rekey already in flight");
        }
        pendingRekey = X25519.generateKeyPair();
        pendingRekeyNextKeyId = currentKeyId + 1;
        pendingRekeyNonce = RANDOM.nextInt();
        rekeyInFlight = true;
        return new Entity(BuiltinTypeId.REKEY_OFFER.id(),
                encodeRekeyValue(pendingRekeyNextKeyId, pendingRekeyNonce, pendingRekey.publicKeyRaw()));
    }

    public synchronized Entity buildRekeyAccept(byte[] offerValue) {
        requireHandshake();
        RekeyFields offer = decodeRekeyValue(offerValue);
        X25519.KeyPair local = X25519.generateKeyPair();
        byte[] current = requireCurrentTrafficKey();
        byte[] next = hkdfFromEcdh(current, X25519.agree(local.privateKeyRaw(), offer.publicKey()),
                local.publicKeyRaw(), offer.publicKey(), aeadSuite.rekeyInfo());
        installRekey(offer.nextKeyId(), next);
        rekeyInFlight = false;
        pendingRekey = null;
        return new Entity(BuiltinTypeId.REKEY_ACCEPT.id(),
                encodeRekeyValue(offer.nextKeyId(), offer.nonce(), local.publicKeyRaw()));
    }

    public synchronized void onRekeyOffer(byte[] value) {
        requireHandshake();
        RekeyFields offer = decodeRekeyValue(value);
        if (pendingRekey != null) {
            byte[] current = requireCurrentTrafficKey();
            byte[] next = hkdfFromEcdh(current,
                    X25519.agree(pendingRekey.privateKeyRaw(), offer.publicKey()),
                    pendingRekey.publicKeyRaw(), offer.publicKey(), aeadSuite.rekeyInfo());
            installRekey(Math.max(offer.nextKeyId(), pendingRekeyNextKeyId), next);
            pendingRekey = null;
            rekeyInFlight = false;
        } else {
            pendingRekeyNextKeyId = offer.nextKeyId();
            pendingRekeyNonce = offer.nonce();
            rekeyInFlight = true;
        }
    }

    public synchronized void onRekeyAccept(byte[] value) {
        requireHandshake();
        if (pendingRekey == null) {
            throw new IllegalStateException("no pending rekey offer");
        }
        RekeyFields accept = decodeRekeyValue(value);
        byte[] current = requireCurrentTrafficKey();
        byte[] next = hkdfFromEcdh(current,
                X25519.agree(pendingRekey.privateKeyRaw(), accept.publicKey()),
                pendingRekey.publicKeyRaw(), accept.publicKey(), aeadSuite.rekeyInfo());
        installRekey(accept.nextKeyId(), next);
        pendingRekey = null;
        rekeyInFlight = false;
    }

    public synchronized void shedOldKeysExcept(int keepId, int previousId) {
        trafficKeys.keySet().removeIf(id -> id != keepId && id != previousId);
    }

    @Override
    public byte[] seal(int keyId, byte[] aad, byte[] plaintext) {
        return sealTraffic(aad, plaintext);
    }

    @Override
    public byte[] open(int keyId, byte[] aad, byte[] sealed) {
        return openTraffic(keyId, aad, sealed);
    }

    private byte[] requireCurrentTrafficKey() {
        byte[] current = trafficKeys.get(currentKeyId);
        if (current == null) {
            throw new IllegalStateException("missing current traffic key");
        }
        return current;
    }

    private void installRekey(int nextKeyId, byte[] nextKey) {
        trafficKeys.put(nextKeyId, nextKey);
        currentKeyId = nextKeyId;
        packetsSealedWithCurrent = 0;
    }

    private static byte[] hkdfFromEcdh(
            byte[] root, byte[] ss, byte[] localPub, byte[] peerPub, String info) {
        byte[] pubMin;
        byte[] pubMax;
        if (Arrays.compareUnsigned(localPub, peerPub) <= 0) {
            pubMin = localPub;
            pubMax = peerPub;
        } else {
            pubMin = peerPub;
            pubMax = localPub;
        }
        byte[] ikm = concat(root, ss, pubMin, pubMax);
        try {
            return HkdfSha256.expand(ikm, info, 32);
        } finally {
            Arrays.fill(ss, (byte) 0);
            Arrays.fill(ikm, (byte) 0);
        }
    }

    private void requireHandshake() {
        if (!handshakeComplete) {
            throw new IllegalStateException("handshake not complete");
        }
    }

    private static byte[] encodeRekeyValue(int nextKeyId, int nonce, byte[] publicKey) {
        ByteBuffer buf = ByteBuffer.allocate(REKEY_VALUE_LEN);
        buf.putInt(nextKeyId);
        buf.putInt(nonce);
        buf.put(publicKey);
        return buf.array();
    }

    private static RekeyFields decodeRekeyValue(byte[] value) {
        Objects.requireNonNull(value, "value");
        if (value.length != REKEY_VALUE_LEN) {
            throw new IllegalArgumentException("rekey value must be " + REKEY_VALUE_LEN + " bytes");
        }
        ByteBuffer buf = ByteBuffer.wrap(value);
        int nextKeyId = buf.getInt();
        int nonce = buf.getInt();
        byte[] pub = new byte[X25519.KEY_LEN];
        buf.get(pub);
        return new RekeyFields(nextKeyId, nonce, pub);
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    private record RekeyFields(int nextKeyId, int nonce, byte[] publicKey) {
    }
}
