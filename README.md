# wirepack

Multipurpose binary transport library for Java 21 (protocol v0). Reliable UDP sessions, TLV entity streams, type registry, pluggable activation, Netty **4.2** data plane.

See [PROTOCOL.md](PROTOCOL.md) for the wire format and [PLAN.md](PLAN.md) for design notes.

## Modules

| Artifact | Role |
|----------|------|
| `wirepack-api` | Public API, SPI (`ActivationProvider`, `CryptoSpi`, `TransportFactory`), limits |
| `wirepack-crypto` | X25519, HKDF-SHA256, ChaCha20-Poly1305, optional AES-256-GCM, SessionCrypto |
| `wirepack-core` | Retransmit buffer, reliable session, `Wirepack.open` runtime |
| `wirepack-netty` | Netty UDP transport (shared event loop) |
| `wirepack-activate-http` | HTTPS activate-device client + tiny JDK HttpServer helper |
| `wirepack-harness` | Runnable peer (`listen` / `connect`) |
| `wirepack-bench` | **Optional** wirepack vs REST throughput harness (crypto, loss, multi-session; not required to use wirepack) |

GroupId: `com.cdrware.wirepack` · Version: `0.1.0-SNAPSHOT`

## Build & test

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn -q test
mvn -q -pl wirepack-harness -am package
```

Throughput comparison (separate module — see [wirepack-bench/README.md](wirepack-bench/README.md)):

```bash
mvn -q -pl wirepack-bench -am package
java -jar wirepack-bench/target/wirepack-bench-0.1.0-SNAPSHOT.jar --scenario all
# writes wirepack-bench/target/bench-report.md (+ .csv) with side-by-side speedup
```

## Run harness

```bash
# Terminal A — listen
java -jar wirepack-harness/target/wirepack-harness-0.1.0-SNAPSHOT.jar \
  --mode listen --bind 0.0.0.0:9700 --key 00112233445566778899aabbccddeeff

# Terminal B — connect
java -jar wirepack-harness/target/wirepack-harness-0.1.0-SNAPSHOT.jar \
  --mode connect --peer 127.0.0.1:9700 --key 00112233445566778899aabbccddeeff
```

## Embed

```java
import com.cdrware.wirepack.api.*;
import com.cdrware.wirepack.netty.NettyUdpTransportFactory;

byte[] sharedKey = /* 16+ bytes */;
WirepackConfig config = WirepackConfig.builder()
    .sharedKey(sharedKey)
    .keyId(1)
    .limits(SafetyLimits.defaults())
    .transport(new NettyUdpTransportFactory())
    .build();

try (WirepackRuntime runtime = Wirepack.open(config)) {
    Session session = runtime.connect(new PeerAddress("127.0.0.1", 9700)).join();

    // Free-form trees (auto ENTITY_FRAG when large):
    session.onTree(tree -> { /* Map/List graph */ });
    session.sendTree(Map.of("object", Map.of("object", Map.of()))).join();

    // POJO / record binder (Jackson-shaped use without Jackson):
    record Order(String id, long qty) {}
    session.onObject(Order.class, order -> { /* domain object */ });
    session.sendObject(new Order("o1", 3)).join();

    // Or typed registry rows / raw entities:
    session.onEntity(e -> { /* Entity TLV */ });
    session.send(List.of(new Entity(1024, payload))).join();
}
```

Optional activation:

```java
config = WirepackConfig.builder()
    .activation(new HttpsActivateDeviceProvider("https://org.example/activate"))
    .activationRequest(new ActivationRequest("device-1", "acme"))
    .transport(new NettyUdpTransportFactory())
    .limits(SafetyLimits.defaults())
    .build();
```

`ActivationProvider` may be null when a shared key is already configured.

**Crypto (v1):** When `sharedKey` / activation PSK is set, peers run a PSK-AEAD HELLO
(always ChaCha20-Poly1305), immediately ECDH to a traffic key (PSK shed from seal path),
then seal DATA with the negotiated AEAD. Default is ChaCha20-Poly1305. Call
`preferAesGcm(true)` on both peers to use AES-256-GCM; if only one side opts in, the
session stays on ChaCha. In-flight rekey after `SafetyLimits.rekeyAfterPackets`
(default 10000) keeps the suite chosen at HELLO. Omit the shared key for the
cleartext/CRC profile. See [PROTOCOL.md](PROTOCOL.md).
