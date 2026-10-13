# Wirepack Protocol v0

Endianness: **big-endian** throughout.  
Magic: `0xWP` as `u16` = `0x5750` (`'W'<<8|'P'`).

## Goals

- Multipurpose binary transport (not JSON on the data plane)
- Reliable UDP sessions with ACK/REQ and a bounded retransmit buffer
- **Entity streams**: TLV entities on DATA frames
- **Type registry**: central-assigned schemas for compact typed records
- **KV_TREE**: parentId-linked free-form nested objects (JSON-like logical trees)
- Multi-GB via piece/stream layer + optional entity fragmentation by UID

## Cleartext frame header

| Field | Type | Notes |
|-------|------|--------|
| magic | u16 | `0x5750` |
| version | u8 | `0` for v0 |
| keyId | u32 | selects key material; not secret |
| msgType | u8 | see below |
| sessionId | u32 | |
| streamId | u16 | |
| seq | u16 | wrapping; DATA only consumes seq |
| lastRcv | u16 | piggyback cumulative receive watermark |
| payloadLen | u16 | bytes after this header (before integrity) |
| crc32 | u32 | CRC of header (crc zeroed) + payload — v0 integrity |

Total fixed header before payload: **20 bytes** (magic..payloadLen) + **4** crc after payload = **24** wire overhead:

Wire order:

```text
magic(2) version(1) keyId(4) msgType(1) sessionId(4) streamId(2)
seq(2) lastRcv(2) payloadLen(2)  payload(payloadLen)  crc32(4)
```

Reject if `24 + payloadLen > maxFrameBytes` **before** allocating payload buffer.

### Frame header rationale (`magic` vs `msgType`)

UDP delivers opaque datagrams. Peers must answer three different questions with three different fields:

| Field | Question |
|-------|----------|
| **magic** (`0x5750`) | Is this Wirepack framing at all? |
| **version** | Which Wirepack dialect? |
| **msgType** | Which packet kind (`DATA` / `ACK` / …)? |

**Keep them separate.** A lone `msgType` byte can look plausible by chance on noise or foreign traffic. A fixed 16-bit magic is a cheap first reject before any payload allocate. Under v1 crypto, magic..payloadLen remain clear **AAD**, so a sealed payload cannot be reinterpreted under another framing. Cost: 2 bytes per frame — intentional, not padding.

Decode order: bad magic → drop; unsupported version → drop; else demux by `msgType`.

### msgType

| Value | Name | Seq? | Payload |
|------:|------|------|---------|
| 1 | DATA | yes | entity stream |
| 2 | ACK | no | optional ranges |
| 3 | REQ | no | missing seq ranges |
| 4 | HELLO | no | capability / window |
| 5 | BYE | no | empty |

ACK/REQ do not advance DATA seq.

## Entity stream (DATA payload)

A DATA payload is zero or more **entities**, optionally ending with EOE.

```text
Entity := type_id:u16  length:u16  value[length]
```

| type_id | Name | Value |
|--------:|------|--------|
| 0 | EOE | empty (`length=0`) |
| 1 | REGISTER | schema advertisement (central-authoritative) |
| 2 | REGISTER_ACK | optional unicast ack of type_id |
| 3 | ENTITY_FRAG | `{uid, fragIndex, fragCount, bytes}` — large logical object slice |
| 4 | REKEY_OFFER | v1: in-flight ECDH offer (see Crypto) |
| 5 | REKEY_ACCEPT | v1: in-flight ECDH accept (see Crypto) |
| 6 | KV_TREE | Free-form nested object tree (parentId K/V; see below) |
| 7–63 | reserved builtins | |
| 1024+ | application types | laid out per registry |

**Rules**

- Unknown `type_id`: skip `length` bytes (forward compatible).
- One TLV entity must fit in one UDP datagram (do not split a TLV across datagrams).
- A **logical** object (JSON-like document with optional UID) may span many packets via `ENTITY_FRAG` or by living inside a multi-piece file/stream — UID ties fragments together.
- Pack multiple entities per datagram until the next entity would exceed MTU/`maxFrameBytes`.

### REGISTER value

Central assigns `type_id`. Stores learn; they do not invent colliding ids.

```text
type_id_assigned: u16
name_len: u8
name: utf8[name_len]
field_count: u8
repeated field_count times:
  key_len: u8
  key: utf8[key_len]
  wire_type: u8
schema_hash: u32
```

**wire_type**

| Value | Name | On-wire size |
|------:|------|----------------|
| 1 | U8 | 1 |
| 2 | U16 | 2 |
| 3 | U32 | 4 |
| 4 | U64 | 8 |
| 5 | F32 | 4 |
| 6 | F64 | 8 |
| 7 | STRING | u16 len + bytes |
| 8 | BYTES | u16 len + bytes |
| 9 | NESTED | u16 len + **opaque** child entity stream bytes |

Fixed numeric types carry **no** per-field length — sizes live in the registry.  
Only STRING/BYTES/NESTED are length-prefixed on the wire.

`NESTED` is a typed blob for registry layouts (pre-encoded child TLVs). It does **not** rebuild free-form maps like `{"a":{"b":{}}}`. Use **`KV_TREE`** for that.

Redefining the same `type_id` with a different `schema_hash` is a hard error (reject/quarantine).

### Application entity body

For `type_id >= 1024`, `value` is the concatenation of fields in REGISTER order. Optional UID is just another named field (e.g. `uid` as U64 or BYTES).

### Payload models (registry vs parentId K/V)

Wirepack ships **two** application payload styles:

| Model | Builtin / type | Use |
|-------|----------------|-----|
| **Registered records** | `REGISTER` + `type_id ≥ 1024` | Fixed schemas; compact numerics; `NESTED` = opaque child stream |
| **KV_TREE** | `type_id = 6` | Free-form nested objects/arrays (JSON-like), parent-linked entries |

v0 originally emphasized the registry path. **`KV_TREE` is the plan’s parentId K/V model**: protocol entity ids exist only on the wire for tree assembly — they are **not** fields inside the logical object.

### KV_TREE value (parentId K/V)

Logical root is **protocol entity id `0`** (ancestor; not sent as an entry). Each on-wire entry is a property under some parent.

```text
entry_count: u32
repeated entry_count times:
  id: u32              // protocol entity id within this tree; unique; >= 1
  parent_id: u32       // parent protocol entity; must be < id; 0 = root
  key_len: u16
  key: utf8[key_len]
  kind: u8             // see KvValueKind
  kind-specific payload (see below)
```

**KvValueKind**

| Value | Name | Payload |
|------:|------|---------|
| 0 | NULL | empty |
| 1 | BOOL | u8 (0/1) |
| 2 | I64 | i64 |
| 3 | F64 | f64 |
| 4 | STRING | u16 len + utf8 |
| 5 | BYTES | u16 len + bytes |
| 6 | OBJECT | empty — children are entries with `parent_id = this id` |
| 7 | ARRAY | empty — children as above; keys SHOULD be decimal indices |

**Rules**

- Protocol ids are **monotonic** at encode time; entries MUST appear in **increasing `id` order**.
- **`parent_id < id`** always (cycle-free trees). Decoder rejects violations.
- Rebuild is **iterative** (attach by parent id / explicit stack) — not recursive descent on the call stack.
- Caps (see SafetyLimits): **`maxKvEntries`**, **`maxKvBytes`** (primary DoS bounds); **`maxKvDepth`** optional depth bound. Wide flat trees are limited by entries/bytes, not depth alone.
- Large trees that exceed one datagram use `ENTITY_FRAG` around the `KV_TREE` entity value (same as other large logical objects).

### ENTITY_FRAG value

```text
uid_len: u8
uid: bytes[uid_len]
frag_index: u32
frag_count: u32   // 0 = unknown total
data_len: u16
data: bytes[data_len]
```

### REKEY_OFFER / REKEY_ACCEPT value (v1)

Piggybacked on an existing DATA (or ACK) entity stream while app traffic continues under the **current** `keyId`. Not used in v0 (unknown-type skip still applies if seen).

```text
next_key_id: u32          // proposed keyId after cutover (typically current+1)
nonce: u32                // anti-replay / pairing of offer↔accept
x25519_public: bytes[32]
```

Steady-state packets carry **no** rekey TLVs — only the offer/accept packets during a rekey window.

## Reliability (summary)

- Per-stream retransmit buffer keyed by `seq`
- Receiver tracks `lastRcv` and gaps; sends REQ for missing ranges
- Sender ACKs / advances buffer on lastRcv and selective ack
- Default window: **2048** seqs; u16 wrap with sliding window math
- Profiles: BestEffort / Reliable / ReliableOrdered (v0 implements Reliable)

## SafetyLimits (defaults)

| Limit | Default |
|-------|---------|
| maxFrameBytes | 1400 |
| maxPayloadBytes | 1376 (= maxFrame − header − crc) |
| maxKvEntries | 4096 — max entries in one KV_TREE |
| maxKvBytes | 262144 — max encoded KV_TREE value bytes |
| maxKvDepth | 64 — optional nesting depth for KV_TREE (entries/bytes are primary) |
| maxNestedDepth | 8 — reserved for registry `NESTED` blob policy |
| maxEntitiesPerFrame | 256 |
| maxFieldsPerRegister | 64 |
| maxStringBytes | 65535 |
| maxRetransmitEntries | 2048 |
| maxRetransmitBytes | 4 MiB |
| maxSessions | 256 |
| sessionIdleMs | 120000 |
| maxActivateBodyBytes | 65536 |
| rekeyAfterPackets (v1) | 10000 — DATA sealed under current `keyId` before in-flight rekey |

All decoders check remaining bytes before every read; integer length math uses checked overflow.

`rekeyAfterPackets` is on `SafetyLimits` (default 10000).

## Crypto

### Roles

| Layer | Job |
|-------|-----|
| **Activation (out of band)** | Delivers **PSK** + initial `keyId` (HTTPS activate, stand-alone add-device, or config). Authenticity root — not the long-lived traffic key. |
| **HELLO (UDP)** | PSK-authenticated X25519 exchange plus an AEAD preference byte. Always sealed with ChaCha20-Poly1305 so either peer can read the offer before the traffic suite is chosen. |
| **Traffic + rekey** | Negotiated AEAD under ECDH-derived keys; periodic piggyback rekey on that same suite. |

### Algorithms (v1)

- X25519, HKDF-SHA256
- Traffic AEAD: **ChaCha20-Poly1305** by default. **AES-256-GCM** only when both peers set `preferAesGcm` (HELLO flag bit 0). A single opt-in stays on ChaCha.
- Frame `magic` is not a cipher selector. It only answers “is this Wirepack?”
- Sealed payload wire form (both suites): `nonce:12 || ciphertext_and_tag` (16-byte tag)
- AAD = clear frame header bytes (magic..payloadLen), 20 bytes
- Suite is bound into HKDF info (`…-chacha` or `…-aes-gcm`) so the two ciphers never share traffic keys.

### v0 (cleartext profile)

- CRC32 integrity; `CryptoSpi.noop()`; no PSK required.

### v1 bootstrap (PSK → immediate ECDH)

1. Both peers hold the same OOB **PSK** and initial `keyId` (call it `K0`).
2. Hello key: `HKDF-SHA256(PSK, info="wirepack-v1-hello")` → 32-byte AEAD key (PSK never seals app DATA).
3. Each peer generates an ephemeral X25519 keypair. Initiator and responder each send **HELLO** sealed with ChaCha20-Poly1305 under `hello_key`. Plaintext:

   ```text
   x25519_public: bytes[32]
   aeadFlags:     u8          // bit 0 (0x01) = willing to use AES-256-GCM
   ```

   Other flag bits are reserved and ignored. ChaCha is always implied; it is not a flag.
4. On receiving peer HELLO: `ss = X25519(priv, peer_pub)`. Traffic suite:

   | Local `preferAesGcm` | Peer flag bit 0 | Suite |
   |----------------------|-----------------|-------|
   | set | set | AES-256-GCM |
   | anything else | | ChaCha20-Poly1305 |

   `traffic = HKDF-SHA256(PSK || ss || pub_min || pub_max, info="wirepack-v1-traffic-" + suite)`  
   (`chacha` or `aes-gcm`; pubs ordered lexicographically for symmetry).
5. Install `traffic` at `keyId = K0+1`. Dual-key: keep hello/`K0` material only until handshake completes, then **stop using PSK for seal/open**. The suite is locked for the session.
6. App DATA / ACK/REQ payloads (when non-empty) use that AEAD under the current traffic key. Outer CRC32 remains for cheap framing reject.

### In-flight rekey

1. When `packetsSealedWithCurrentKey >= rekeyAfterPackets` (default **10_000**), append `REKEY_OFFER` inside a DATA entity stream still sealed under current `keyId = N`.
2. Peer replies with `REKEY_ACCEPT` (same layout) under `N`.
3. `ss2 = X25519(...)`; `next = HKDF(current_traffic || ss2 || pubs, info="wirepack-v1-rekey-" + suite)`; install at `N+1`. Suite does not change on rekey.
4. **Dual-key window** until retransmit/in-flight under `N` drains.
5. No continuous per-packet ratchet.

### REKEY value (reminder)

```text
next_key_id: u32
nonce: u32
x25519_public: bytes[32]
```

## Activation (out of band)

Not on the data plane. `ActivationProvider` SPI; HTTPS activate-device returns `keyId` + **PSK** (`key` / `keyBase64`) + endpoints. Stand-alone “add device” uses the same SPI.
