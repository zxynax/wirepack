package com.cdrware.wirepack.crypto;

import com.cdrware.wirepack.api.Entity;

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
        assertEquals(AeadSuite.CHACHA20_POLY1305, alice.aeadSuite());
        assertEquals(AeadSuite.CHACHA20_POLY1305, bob.aeadSuite());
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

    @Test
    void aesOnlyWhenBothPrefer() {
        byte[] psk = "shared-psk-material".getBytes(StandardCharsets.UTF_8);
        byte[] aadA = "hello-aad-a".getBytes(StandardCharsets.UTF_8);
        byte[] aadB = "hello-aad-b".getBytes(StandardCharsets.UTF_8);
        byte[] trafficAad = "traffic-aad".getBytes(StandardCharsets.UTF_8);
        byte[] msg = "app-payload".getBytes(StandardCharsets.UTF_8);

        SessionCrypto alice = SessionCrypto.create(psk, 1, 100, true);
        SessionCrypto bob = SessionCrypto.create(psk, 1, 100, true);
        bob.acceptPeerHello(aadA, alice.sealHello(aadA));
        alice.acceptPeerHello(aadB, bob.sealHello(aadB));
        assertEquals(AeadSuite.AES_256_GCM, alice.aeadSuite());
        assertEquals(AeadSuite.AES_256_GCM, bob.aeadSuite());
        byte[] sealed = alice.sealTraffic(trafficAad, msg);
        assertArrayEquals(msg, bob.openTraffic(alice.currentKeyId(), trafficAad, sealed));

        SessionCrypto aes = SessionCrypto.create(psk, 1, 100, true);
        SessionCrypto chacha = SessionCrypto.create(psk, 1, 100, false);
        chacha.acceptPeerHello(aadA, aes.sealHello(aadA));
        aes.acceptPeerHello(aadB, chacha.sealHello(aadB));
        assertEquals(AeadSuite.CHACHA20_POLY1305, aes.aeadSuite());
        assertEquals(AeadSuite.CHACHA20_POLY1305, chacha.aeadSuite());
        byte[] mixed = aes.sealTraffic(trafficAad, msg);
        assertArrayEquals(msg, chacha.openTraffic(aes.currentKeyId(), trafficAad, mixed));
    }

    @Test
    void aesRekeyKeepsSuite() {
        byte[] psk = "shared-psk-material".getBytes(StandardCharsets.UTF_8);
        SessionCrypto alice = SessionCrypto.create(psk, 1, 1, true);
        SessionCrypto bob = SessionCrypto.create(psk, 1, 1, true);
        byte[] aad = "hello".getBytes(StandardCharsets.UTF_8);
        bob.acceptPeerHello(aad, alice.sealHello(aad));
        alice.acceptPeerHello(aad, bob.sealHello(aad));

        Entity offer = alice.buildRekeyOffer();
        Entity accept = bob.buildRekeyAccept(offer.valueRaw());
        alice.onRekeyAccept(accept.valueRaw());

        assertEquals(AeadSuite.AES_256_GCM, alice.aeadSuite());
        assertEquals(alice.currentKeyId(), bob.currentKeyId());
        byte[] trafficAad = "traffic-aad".getBytes(StandardCharsets.UTF_8);
        byte[] msg = "after-rekey".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = alice.sealTraffic(trafficAad, msg);
        assertArrayEquals(msg, bob.openTraffic(alice.currentKeyId(), trafficAad, sealed));
    }

    @Test
    void aesAndChachaAreNotInterchangeable() {
        byte[] key = HexFormat.of().parseHex(
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        byte[] aad = "header-aad".getBytes(StandardCharsets.UTF_8);
        byte[] plain = "hello wirepack".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = AeadPayload.seal(AeadSuite.AES_256_GCM, key, aad, plain);
        assertArrayEquals(plain, AeadPayload.open(AeadSuite.AES_256_GCM, key, aad, sealed));
        assertThrows(IllegalStateException.class,
                () -> AeadPayload.open(AeadSuite.CHACHA20_POLY1305, key, aad, sealed));
    }

    @Test
    void negotiateRequiresBoth() {
        assertEquals(AeadSuite.AES_256_GCM, AeadSuite.negotiate(true, true));
        assertEquals(AeadSuite.CHACHA20_POLY1305, AeadSuite.negotiate(true, false));
        assertEquals(AeadSuite.CHACHA20_POLY1305, AeadSuite.negotiate(false, true));
        assertEquals(AeadSuite.CHACHA20_POLY1305, AeadSuite.negotiate(false, false));
    }
}
