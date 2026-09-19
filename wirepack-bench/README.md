# wirepack-bench

Optional **test-only** throughput harness comparing **wirepack** (UDP entities) vs **REST**
(HTTP) for:

| Scenario | Default | Wirepack | REST |
|----------|---------|----------|------|
| `small-many` | 5 000 × 64 B | TLV entities (batched) | `POST /batch` JSON |
| `large-few` | 20 × 32 KiB | `ENTITY_FRAG` + bench reassembly | `POST /entity` octet-stream |
| `multi-session` | 8 × 500 × 64 B | N independent UDP connects → one listen | N independent HttpClients → one server |

This module depends on wirepack; **wirepack does not depend on it**.

## Crypto & loss

- **`--crypto true`** (default): wirepack uses ChaCha20-Poly1305 AEAD with a built-in test PSK (`--key` to override). REST stays plain HTTP so the report is AEAD-vs-HTTP, not AEAD-vs-TLS.
- **`--loss 0.05`**: drop 5% of **DATA** frames (HELLO/ACK/REQ kept) to exercise retransmit. Wirepack only.

## Multi-conversation model

Simulates **many client systems → one receiver**, not one app with a shared HTTP pool:

- **Wirepack:** N separate `WirepackRuntime` connects (each its own UDP source port/session) to one listen socket.
- **REST:** N separate `HttpClient` instances (no cross-client connection sharing). Each client may still reuse connections for *its own* sequential POSTs.

Modern servers handle many TCP clients well; the interesting comparison is demux/session cost and encoding, not “one pooled client pretending to be many machines.”

## Build & run

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn -q -pl wirepack-bench -am package

java -jar wirepack-bench/target/wirepack-bench-0.1.0-SNAPSHOT.jar --scenario all
# with loss + crypto (crypto is already default):
java -jar wirepack-bench/target/wirepack-bench-0.1.0-SNAPSHOT.jar \
  --scenario small-many --loss 0.02 --timeout-ms 180000
```

Report (default):

- `wirepack-bench/target/bench-report.md`
- `wirepack-bench/target/bench-report.csv`

Override with `--report PATH`.
