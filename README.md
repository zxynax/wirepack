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
| `wirepack-harness` | Runnable peer (`listen` / `connect` / `activate-then-connect`) |
| `wirepack-bench` | **Optional** wirepack vs REST throughput harness (crypto, loss, multi-session; not required to use wirepack) |
| `wirepack-spring` | **Optional** Spring Boot 4 auto-config: Netty UDP RPC beside Tomcat HTTP |

GroupId: `com.cdrware.wirepack` · Version: `0.1.0-SNAPSHOT`

## Build & test

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn -q test
mvn -q -pl wirepack-harness -am package
```

`mvn test` runs `FrameDecoderFuzzTest` for 20 seconds with the rest of the suite (`JAZZER_FUZZ` is set on the codec module). A crash, or an allocation that gets past `SafetyLimits`, fails the build. The CI job is that same command.

Throughput comparison (separate module — see [wirepack-bench/README.md](wirepack-bench/README.md)):

```bash
mvn -q -pl wirepack-bench -am package
java -jar wirepack-bench/target/wirepack-bench-0.1.0-SNAPSHOT.jar --scenario all
# writes wirepack-bench/target/bench-report.md (+ .csv) with side-by-side speedup
```

## Run harness

The harness is the reference shape for running the library as a standalone service. [`wirepack-harness/harness.yaml`](wirepack-harness/harness.yaml) is a flat `key: value` file (comments allowed). Flags replace the same key. Ctrl-C closes the session, the optional health port, and a local activation bootstrap.

```bash
# Terminal A — listen, and hand the same key out on a local HTTP bootstrap
java -jar wirepack-harness/target/wirepack-harness-0.1.0-SNAPSHOT.jar \
  --mode listen --bind 0.0.0.0:9700 \
  --key 00112233445566778899aabbccddeeff --key-id 1 \
  --activation-server 8080 --health 127.0.0.1:8081

# Terminal B — activate, then open the data-plane session
java -jar wirepack-harness/target/wirepack-harness-0.1.0-SNAPSHOT.jar \
  --config wirepack-harness/harness.yaml \
  --activate-url http://127.0.0.1:8080/activate

# Same jar, shared key, no activation
java -jar wirepack-harness/target/wirepack-harness-0.1.0-SNAPSHOT.jar \
  --mode connect --peer 127.0.0.1:9700 --key 00112233445566778899aabbccddeeff
```

`activate-then-connect` POSTs the device id to `activateUrl`, then connects with the returned key. It uses the first returned endpoint unless `--peer` is set. Point `activateUrl` at the organization HTTPS endpoint in production. `--activation-server` is the local cleartext bootstrap, not that HTTPS server.

`--health host:port` serves `GET /health` (process up) and `GET /ready` (listener bound, or the activated session connected). It is a local JDK HTTP port, not the UDP data plane.

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

    // RPC (route + corrId envelope over KV_TREE):
    record EchoIn(String msg) {}
    record EchoOut(String msg) {}
    session.onRpc("echo", payload -> Map.of("msg", payload.get("msg")));
    EchoOut out = session.request("echo", new EchoIn("hi"), EchoOut.class).join();

    // Or typed registry rows / raw entities:
    session.onEntity(e -> { /* Entity TLV */ });
    session.send(List.of(new Entity(1024, payload))).join();
}
```

### Spring Boot + Tomcat (optional)

Tomcat stays on HTTP. Wirepack binds a **second** listener (Netty UDP) in the same JVM and calls the same handler methods. Add `wirepack-spring` (Spring Boot **4.1.2**) and opt in:

```xml
<dependency>
  <groupId>com.cdrware.wirepack</groupId>
  <artifactId>wirepack-spring</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```properties
wirepack.enabled=true
wirepack.bind-host=0.0.0.0
wirepack.bind-port=9700
wirepack.key-id=1
# Prefer env / secrets file — do not commit live keys
wirepack.shared-key-hex=${WIREPACK_SHARED_KEY_HEX:}
```

Mark existing MVC methods (POJO in / POJO out — no `HttpServletRequest` / `MultipartFile` on the UDP path):

```java
@RestController
public class OrderController {
  @PostMapping("/orders")
  @WirepackMapping("order.create")
  public OrderResponse create(@RequestBody OrderRequest req) {
    return orderService.create(req);
  }
}
```

- HTTP JSON: `POST /orders` with `Content-Type: application/json` → Tomcat / Spring MVC
- HTTP wirepack: the same URL with `Content-Type: application/wirepack` and `Accept: application/wirepack`. The body is the `KV_TREE` of the handler POJO (the RPC payload tree, not the envelope and not a frame). `wirepack-spring` registers that converter whenever Spring MVC is on the classpath. It does not open the UDP port.
- UDP RPC: `session.request("order.create", req, OrderResponse.class)` → Netty wirepack. The same POJO is wrapped in the RPC envelope (`route`, `corrId`) on a reliable session.

Use HTTP wirepack when the caller is already on the servlet stack and wants the binary tree instead of JSON. Use UDP RPC for the data plane: loss recovery, session crypto, and a route name instead of a URL. JSON stays for existing HTTP clients. One `@PostMapping` + `@WirepackMapping` method serves all three when the argument and return value are POJOs.

`@WirepackController` is optional. HTTP cookies/JWT and wirepack session crypto stay separate; map identity in the service layer. Core wirepack does not require Spring.

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
