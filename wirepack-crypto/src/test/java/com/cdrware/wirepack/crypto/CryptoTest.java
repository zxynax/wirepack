package com.cdrware.wirepack.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoTest {

    @Test
    void hkdfDeterministic() {
        byte[] ikm = "wirepack-psk".getBytes(StandardCharsets.UTF_8);
        byte[] a = HkdfSha256.expand(ikm, "wirepack-v1-hello", 32);
        byte[] b = HkdfSha256.expand(ikm, "wirepack-v1-hello", 32);
        assertArrayEquals(a, b);
        assertEquals(32, a.length);
        // Different info → different OKM
        byte[] c = HkdfSha256.expand(ikm, "wirepack-v1-traffic", 32);
        assertFalse(java.util.Arrays.equals(a, c));
    }

    @Test
    void x25519AgreeSymmetric() {
        X25519.KeyPair a = X25519.generateKeyPair();
        X25519.KeyPair b = X25519.generateKeyPair();
        byte[] ssAb = X25519.agree(a.privateKey(), b.publicKey());
        byte[] ssBa = X25519.agree(b.privateKey(), a.publicKey());
        assertArrayEquals(ssAb, ssBa);
        assertEquals(32, ssAb.length);
    }

    @Test
    void aeadRoundtripAndWrongKeyFails() {
        byte[] key = HexFormat.of().parseHex(
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        byte[] aad = "header-aad".getBytes(StandardCharsets.UTF_8);
        byte[] plain = "hello wirepack".getBytes(StandardCharsets.UTF_8);

        byte[] sealed = AeadPayload.seal(key, aad, plain);
        assertArrayEquals(plain, AeadPayload.open(key, aad, sealed));

        byte[] wrong = key.clone();
        wrong[0] ^= 0x01;
        assertThrows(IllegalStateException.class, () -> AeadPayload.open(wrong, aad, sealed));
    }

    @Test
    void sessionHandshakeAndTrafficRoundtrip() {
        byte[] psk = "shared-psk-material".getBytes(StandardCharsets.UTF_8);
        int baseKeyId = 7;
        SessionCrypto alice = SessionCrypto.create(psk, baseKeyId, 10_000);
        SessionCrypto bob = SessionCrypto.create(psk, baseKeyId, 10_000);

        byte[] aadA = "hello-aad-a".getBytes(StandardCharsets.UTF_8);
        byte[] aadB = "hello-aad-b".getBytes(StandardCharsets.UTF_8);

        byte[] aliceHello = alice.sealHello(aadA);
        byte[] bobHello = bob.sealHello(aadB);

        bob.acceptPeerHello(aadA, aliceHello);
        alice.acceptPeerHello(aadB, bobHello);

        assertTrue(alice.handshakeComplete());
        assertTrue(bob.handshakeComplete());
        assertEquals(baseKeyId + 1, alice.currentKeyId());
        assertEquals(baseKeyId + 1, bob.currentKeyId());

        byte[] trafficAad = "traffic-aad".getBytes(StandardCharsets.UTF_8);
        byte[] msg = "app-payload".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = alice.sealTraffic(trafficAad, msg);
        byte[] opened = bob.openTraffic(alice.currentKeyId(), trafficAad, sealed);
        assertArrayEquals(msg, opened);

        byte[] sealed2 = bob.sealTraffic(trafficAad, msg);
        assertArrayEquals(msg, alice.openTraffic(bob.currentKeyId(), trafficAad, sealed2));
    }

    @Test
    void wrongPskHelloOpenFails() {
        byte[] pskA = "psk-alice".getBytes(StandardCharsets.UTF_8);
        byte[] pskB = "psk-bob!!".getBytes(StandardCharsets.UTF_8);
        SessionCrypto alice = SessionCrypto.create(pskA, 1, 100);
        SessionCrypto bob = SessionCrypto.create(pskB, 1, 100);

        byte[] aad = "aad".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = alice.sealHello(aad);
        assertThrows(IllegalStateException.class, () -> bob.acceptPeerHello(aad, sealed));
        assertFalse(bob.handshakeComplete());
    }
}
