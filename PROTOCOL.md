# Wirepack Protocol v0

Endianness: **big-endian** throughout.  
Magic: `0xWP` as `u16` = `0x5750` (`'W'<<8|'P'`).

## Goals

- Multipurpose binary transport (not JSON on the data plane)
- Reliable UDP sessions with ACK/REQ and a bounded retransmit buffer
- **Entity streams**: JSON-like logical objects packed as length-prefixed TLVs
- **Type registry**: central-assigned schemas so fixed field sizes stay off the wire
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
| 6–63 | reserved builtins | |
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
| 9 | NESTED | u16 len + entity stream (child entities) |

Fixed numeric types carry **no** per-field length — sizes live in the registry.  
Only STRING/BYTES/NESTED are length-prefixed on the wire.

Redefining the same `type_id` with a different `schema_hash` is a hard error (reject/quarantine).

### Application entity body

For `type_id >= 1024`, `value` is the concatenation of fields in REGISTER order. Optional UID is just another named field (e.g. `uid` as U64 or BYTES).

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
| maxKvDepth / maxNestedDepth | 8 |
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
| **HELLO (UDP)** | PSK-authenticated X25519 exchange; immediately derive traffic AEAD key and **shed PSK** from seal/open. |
| **Traffic + rekey** | ChaCha20-Poly1305 under ECDH-derived keys; periodic piggyback rekey. |

### Algorithms (v1)

- X25519, HKDF-SHA256, ChaCha20-Poly1305
- Sealed payload wire form: `nonce:12 || ciphertext_and_tag`
- AAD = clear frame header bytes (magic..payloadLen), 20 bytes

### v0 (cleartext profile)

- CRC32 integrity; `CryptoSpi.noop()`; no PSK required.

### v1 bootstrap (PSK → immediate ECDH)

1. Both peers hold the same OOB **PSK** and initial `keyId` (call it `K0`).
2. Hello key: `HKDF-SHA256(PSK, info="wirepack-v1-hello")` → 32-byte AEAD key (PSK never seals app DATA).
3. Each peer generates an ephemeral X25519 keypair. Initiator and responder each send **HELLO** with sealed payload = AEAD(`hello_key`, nonce, plaintext=`x25519_public[32]`, AAD=header).
4. On receiving peer pub: `ss = X25519(priv, peer_pub)`;  
   `traffic = HKDF-SHA256(PSK || ss || pub_min || pub_max, info="wirepack-v1-traffic")`  
   (pubs ordered lexicographically for symmetry).
5. Install `traffic` at `keyId = K0+1`. Dual-key: keep hello/`K0` material only until handshake completes, then **stop using PSK for seal/open**.
6. App DATA / ACK/REQ payloads (when non-empty) use AEAD under the current traffic key. Outer CRC32 remains for cheap framing reject.

### In-flight rekey

1. When `packetsSealedWithCurrentKey >= rekeyAfterPackets` (default **10_000**), append `REKEY_OFFER` inside a DATA entity stream still sealed under current `keyId = N`.
2. Peer replies with `REKEY_ACCEPT` (same layout) under `N`.
3. `ss2 = X25519(...)`; `next = HKDF(current_traffic || ss2 || pubs, info="wirepack-v1-rekey")`; install at `N+1`.
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
