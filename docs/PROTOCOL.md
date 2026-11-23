# FukReg Protocol v1

## Roles
- **Host** = Android (BLE peripheral, GATT server + L2CAP listener)
- **Client** = iPhone (BLE central)

## Pairing (once)
1. Host generates a long-term X25519 key pair and a random 16-byte `pairId`.
2. Host shows QR: `fukreg://pair?v=1&id=<pairId b64url>&pk=<hostPub b64url>&svc=<serviceUUID>`
3. Client scans it and generates its own X25519 key pair.
4. Client connects and sends `PAIR_REQ {clientPub}`.
5. Both sides derive `pairKey = HKDF-SHA256(X25519(priv, peerPub), salt=pairId, info="fukreg-pair")`.
6. Client sends `PAIR_CONFIRM {HMAC(pairKey, "confirm")}`. Host verifies it and stores the client's public key.

## Session (every connection)
- Each side sends a fresh ephemeral X25519 key and a 32-byte nonce (`HELLO`).
- `sessionKey = HKDF(X25519(eph, peerEph) || pairKey, salt=nonceC||nonceH, info="fukreg-session")`
- Each direction has its own key (`info` suffixed with `-c2h` / `-h2c`).

## Transport
- BLE L2CAP CoC (PSM published in GATT characteristic `PSM_CHAR`).
- Frame: `u32 len | u64 counter | AES-256-GCM(ciphertext||tag)`.
- The counter is used as the GCM nonce (with a 4-byte zero prefix). It must strictly increase, and replays are rejected.
- Plaintext = CBOR/JSON message `{"t": type, ...}`.

## Message types
| t | dir | payload |
|---|---|---|
| `ping`/`pong` | both | – |
| `contacts.sync` | C→H | `{since}` |
| `contacts.list` | H→C | `[{id,name,phones[],photo?}]` |
| `sms.list` | C→H | `{threadId?, before?, limit}` |
| `sms.items` | H→C | `[{id,thread,addr,body,ts,dir,sim}]` |
| `sms.new` | H→C | single item (OTP flagged by `otp` field) |
| `sms.send` | C→H | `{addr, body, sim}` → `sms.sent {id, ok}` |
| `sims` | H→C | `[{slot, carrier, number?}]` |
| `call.*` | phase 3 | |
