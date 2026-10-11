# Glide Protocol Profile 2 — Pair-Only Discovery and Trusted Reconnect

**Status**: Authoritative Cross-Platform Specification  
**Applies to**: Desktop (.NET 10 Windows & Linux), Apple (Swift `GlideCoreKit` macOS & iOS), Android (Kotlin `:transfer-core` & `:transport-lan`)  
**Application Version**: `1.0.0` (unchanged; protocol profile evolution does not alter product version)  
**Related Specifications**: [`GLIDE_PROTOCOL_V1.md`](GLIDE_PROTOCOL_V1.md) · [`PERSISTENT_IDENTITY_AND_RESUME_SPECIFICATION.md`](../future/PERSISTENT_IDENTITY_AND_RESUME_SPECIFICATION.md) · [`NATIVE_APP_THREAT_MODEL.md`](../security/NATIVE_APP_THREAT_MODEL.md)

---

## 1. Overview & Security Invariants

Profile 2 separates **persistent device pairing** from **file transfer** while preserving 100% backward compatibility with Profile 1 (`glide://pair/v1` QR invitations and one-time transfers).

1. **Pairing Never Transfers Files**: A `PAIR_ONLY` session cannot carry `OFFER_BEGIN`, `OFFER_PAGE`, `OFFER_END`, `ITEM_START`, `DATA`, or any file-transfer record. Any attempt to send a transfer record during `PAIR_ONLY` immediately terminates the connection (`PROTOCOL_ORDER`).
2. **Every File Offer Requires Explicit Consent**: Saved trust (`DesktopTrustedPeer` / `TrustedPeerRecord` / `PersistentTrustedPeer`) allows a sender to reconnect without scanning a new QR code (`TRUSTED_SEND`), but the receiving user must still explicitly inspect and accept every incoming file offer (unless an explicit CLI headless receive policy is configured for a daemon).
3. **Discovery Is a Candidate List, Never Proof of Identity**: DNS-SD (`_glide._tcp`) advertisements carry only bounded routing/availability hints. They never carry PINs, invitation tokens, trust status, or SPKI fingerprints.
4. **Channel Binding to Live TLS 1.3 Server Certificate**: All Profile 2 authentication proofs (`spake2-pin-v1`, `qr-token-v2`, and `identity-v1`) are cryptographically bound to the 32-byte SHA-256 digest of the X.509 `SubjectPublicKeyInfo` (SPKI) of the TLS 1.3 server certificate actually presented on the active connection.
5. **Mutual Persistent Identity & Dual Approval**: Enrollment into the persistent trust store occurs only after:
   - Cryptographic bootstrap verification (BoringSSL SPAKE2 key confirmation or `glide://pair/v2` one-time token + SPKI pin),
   - Mutual ECDSA P-256 (`SHA256withECDSA`) signature verification over the session transcript by both devices' persistent identity keys, and
   - Explicit user approval (`Approve` / `Pair`) on **both** devices.

---

## 2. Discovery (`_glide._tcp`) and Listener Ownership

### 2.1 Canonical DNS-SD TXT Record Schema

Service type: `_glide._tcp.local.`  
Maximum total TXT payload: $\le 256\text{ bytes}$. Duplicate keys and keys containing control characters are rejected.

| Key | Required | Format / Allowed Values | Semantics |
| :--- | :--- | :--- | :--- |
| `v` | Yes | `"1"` | Discovery schema version |
| `tv` | Yes | `"1"` | Transport framing version |
| `q` | Yes | `"1"` | QR invitation capability |
| `p` | Yes | 32 lowercase hex chars (`^[0-9a-f]{32}$`) | Ephemeral rotating presence ID (128-bit random) |
| `dn` | Yes | 1–64 Unicode code points, $\le 63$ UTF-8 bytes | Sanitized display name (no control characters) |
| `dt` | Yes | `"phone"`, `"tablet"`, `"desktop"`, `"unknown"` | Device form factor |
| `pair` | Optional | `"1"` (omitted when not ready to pair) | Present **only** during an active 120-second **Ready to pair** window |
| `tr` | Optional | `"1"` | Indicates support for Profile 2 `GLD2` (`PAIR_ONLY` and `TRUSTED_SEND`) |

**Forbidden TXT keys**: `spki`, `pin`, `token`, `trust`, `fp`, or any certificate/identity material. Apple's `BonjourService` MUST emit and parse this exact canonical schema and MUST NOT advertise `spki`.

### 2.2 Pair Readiness & Shared Listener Ownership

- Entering **Ready to pair** starts the shared TLS 1.3 listener (if not already running) and advertises `pair=1,tr=1` for at most **120 seconds**.
- Starting the listener for **Ready to pair** does **not** enable general file receiving if file receiving was off: while only pair-readiness owns the listener, inbound Profile 1 transfer connections without an active hosted-send or receive invitation are rejected, and `TRUSTED_SEND` connections are accepted only when the app is available to receive or prompt for an incoming offer.
- Pair readiness is immediately cancelled (removing `pair=1` and invalidating any active PIN / `v2` invitation) upon:
  1. 120-second timer expiry,
  2. Explicit **Cancel** action,
  3. Leaving the **Pair devices** screen, or
  4. Application shutdown.

### 2.3 Multi-Interface mDNS Discovery

On desktop hosts (Windows and Linux), `MdnsDiscoveryService` MUST:
- Enumerate all eligible active IPv4 network interfaces (OperationalStatus `Up`, non-loopback, non-tunnel, non-link-local `169.254.0.0/16`, with a valid IPv4 unicast address).
- Join the IPv4 mDNS multicast group (`224.0.0.251:5353`) on **every** eligible interface and transmit outbound PTR/ANY queries (`_glide._tcp.local.`) across each eligible interface so wired Ethernet NICs are never shadowed by virtual switches (WSL, Hyper-V, VirtualBox, Docker).
- Surface a typed diagnostic state when UDP 5353 bind, multicast join, or send fails due to firewall or network restrictions.

---

## 3. Versioned Session Dispatch (`GLD2`)

All Glide LAN connections use TLS 1.3 with ALPN `glide-lan/1`.

When a TLS connection is accepted by the listener, the server reads the **first 4 bytes** under the 10–15 second handshake deadline:

1. **Profile 1 Dispatch (Unchanged)**:
   - Profile 1 records begin with a 1-byte `LanRecordKind` (`0x01` Bootstrap, `0x02` BootstrapResult, `0x10` Control, `0x20` Data, `0x21` StreamEnd) followed by a 4-byte big-endian length.
   - Because `0x47` (`'G'`) is not a valid `LanRecordKind`, the first 4 bytes unambiguously distinguish Profile 1 from Profile 2.
   - When byte 0 is a Profile 1 `LanRecordKind`, the 4 already-read bytes are preserved at the front of the stream (via a prefix-buffered stream/channel) and passed directly to the unchanged Profile 1 transfer runtime.
2. **Profile 2 Dispatch (`GLD2`)**:
   - A Profile 2 client begins the TLS stream by writing the 4-byte ASCII magic preamble:
     ```
     0x47 0x4C 0x44 0x32   ("GLD2")
     ```
   - Immediately following `"GLD2"`, Profile 2 messages use a 4-byte unsigned big-endian JSON byte length (1..16,384), followed by that many UTF-8 JSON bytes. The Profile 2 frame has no `LanRecordKind` prefix. The JSON is a flat object with `v=1`, `type`, and `sessionId`; the invitation URI's `v=2` is a separate version number. After Profile 2 authentication succeeds, a `TRUSTED_SEND` session resumes the existing Profile 1 `LanRecord` transfer sequence.
   - The first Profile 2 message sent by the initiator MUST be `SESSION_OPEN`:
     ```json
     {
       "v": 1,
       "type": "SESSION_OPEN",
       "sessionId": "<uuid-v4>",
       "purpose": "PAIR_ONLY",
       "authMethod": "spake2-pin-v1",
       "initiatorNonce": "<32-byte unpadded base64url>",
       "initiatorDeviceName": "<display name>",
       "initiatorPlatform": "windows",
       "initiatorIdentityPublicKey": "<unpadded base64url SPKI>"
     }
     ```
   - Unknown wire versions or purposes fail closed immediately (`UNSUPPORTED_VERSION` or `PROTOCOL_ORDER`).
   - If `purpose == "PAIR_ONLY"`:
     - Routes to the Pair-Only session handler.
     - Headless/daemon listeners without an interactive owner reject `PAIR_ONLY` immediately with `ERROR` (`code: "AUTHENTICATION_FAILED"`).
     - Any non-pairing message type (`OFFER_BEGIN`, `OFFER_PAGE`, `OFFER_END`, `ITEM_START`, `DATA`, `STREAM_END`) is rejected immediately (`PROTOCOL_ORDER`).
   - If `purpose == "TRUSTED_SEND"`:
     - Routes to the Trusted Reconnect receiver handler (Section 6).

---

## 4. Six-Digit Desktop Pairing (`PAIR_ONLY` + `spake2-pin-v1`)

### 4.1 Code Generation, Expiry, and Attempt Limits

- When a desktop enters **Ready to pair**, it generates a cryptographically random integer in $[0, 999999]$ formatted as a 6-character ASCII digit string preserving leading zeroes (e.g., `"042819"`), displayed with a middle space (`042 819`).
- **TTL**: 120 seconds from generation.
- **One-Use**: A successful SPAKE2 exchange consumes the code immediately.
- **Brute-Force Ceiling**: At most **3 failed pairing attempts** are permitted across the 120-second window. On the 3rd failed attempt, the pairing window and code are immediately invalidated, `pair=1` is withdrawn, and the UI displays **Code expired — get a new code**.

### 4.2 Pinned BoringSSL SPAKE2 Wire Profile

Glide uses a single pinned BoringSSL SPAKE2 implementation (commit `3fed4f207543470b3d639db6ed40737e30308b51`, Ed25519 curve group from `crypto/curve25519/spake25519.c`) exposed behind a private C ABI (`glide_spake2`):

- **Curve & Primitives**: Ed25519 / SHA-512 internal PAKE state (`SPAKE2_CTX`), 32-byte public share messages (`SPAKE2_MAX_MSG_SIZE = 32`), 64-byte raw shared secret output (`K_shared`).
- **Fixed SPAKE2 Role Names**:
  - Initiator (client entering the 6-digit code): `my_name = "glide-spake2-v1-initiator"`, `their_name = "glide-spake2-v1-responder"`, `role = spake2_role_alice`.
  - Responder (target displaying the 6-digit code): `my_name = "glide-spake2-v1-responder"`, `their_name = "glide-spake2-v1-initiator"`, `role = spake2_role_bob`.
- **Password Input**: The exact 6-byte ASCII representation of the 6-digit code (`"000000"`..`"999999"`). Never transmitted or logged.
- **Key Schedule (HKDF-SHA256)**:
  From the 64-byte `K_shared` output of `SPAKE2_process_msg`:
  - `salt` = `SHA-256("GLIDE-SPAKE2-SALT-V1\0" || sessionId_utf8 || tlsServerSpkiSha256_bytes)` (32 bytes)
  - `PRK` = `HKDF-Extract(SHA-256, salt, IKM = K_shared)`
  - `K_confirm_init` = `HKDF-Expand(PRK, info = "glide-pair-v2-kc-initiator", L = 32)`
  - `K_confirm_resp` = `HKDF-Expand(PRK, info = "glide-pair-v2-kc-responder", L = 32)`
  - `K_shared` and `PRK` are zeroed immediately after key derivation.

### 4.3 Canonical Pairing Transcript (`GLIDE-PAIR-V2`)

Both key confirmations and persistent-identity ECDSA signatures are computed over the identical length-prefixed binary transcript `T_pair`:

1. ASCII `"GLIDE-PAIR-V2"` followed by `0x00` (14 bytes)
2. Length-prefixed UTF-8 string: `method` (`"spake2-pin-v1"` or `"qr-token-v2"`)
3. Length-prefixed UTF-8 string: `sessionId` (canonical lowercase UUIDv4)
4. Length-prefixed UTF-8 string: `"initiator"`
5. Length-prefixed UTF-8 string: `"responder"`
6. Length-prefixed UTF-8 string: `initiatorNonceHex` (64 lowercase hex chars = 32 random bytes)
7. Length-prefixed UTF-8 string: `responderNonceHex` (64 lowercase hex chars = 32 random bytes)
8. Length-prefixed UTF-8 string: `initiatorIdentityFingerprintHex` (64 lowercase hex chars)
9. Length-prefixed UTF-8 string: `initiatorIdentityPublicKeyDerHex` (lowercase hex SPKI DER)
10. Length-prefixed UTF-8 string: `responderIdentityFingerprintHex` (64 lowercase hex chars)
11. Length-prefixed UTF-8 string: `responderIdentityPublicKeyDerHex` (lowercase hex SPKI DER)
12. Length-prefixed UTF-8 string: `responderPresenceHex` (32 lowercase hex chars, or 32 zeroes if manual IP entry)
13. Length-prefixed UTF-8 string: `tlsServerSpkiSha256Hex` (64 lowercase hex chars of the actual TLS server certificate SPKI)
14. Length-prefixed UTF-8 string: `"PAIR_ONLY"`

*(Each length-prefixed string is encoded as a 4-byte unsigned big-endian byte length followed by exact UTF-8 bytes, matching `IdentityResumeTranscript`.)*

### 4.4 `spake2-pin-v1` Handshake Sequence

1. **Initiator → Responder**: `GLD2` preamble + `GLD2_OPEN` (`purpose: "PAIR_ONLY"`).
2. **Initiator → Responder**: `PAIR_PAKE_INIT` (`seq=2`):
   - `method`: `"spake2-pin-v1"`
   - `pakeMessageHex`: 64 lowercase hex chars (32-byte SPAKE2 Alice message)
   - `initiatorNonceHex`: 64 lowercase hex chars (32 random bytes)
   - `initiatorDisplayName`: 1..64 code points
   - `initiatorPlatform`: `"windows" | "linux" | "macos"`
   - `presenceHex`: 32 lowercase hex chars (advertised presence ID or 32 zeroes for manual address)
3. **Responder → Initiator**: `PAIR_PAKE_RESP` (`seq=1`):
   - If the responder is busy (`BUSY`), not in **Ready to pair** (`PAIR_NOT_READY`), or expired/locked out (`CODE_EXPIRED`), responder sends `PAIR_REJECT` (`code`, `message`) and closes.
   - Otherwise responder generates its SPAKE2 Bob message, computes `K_shared`, and sends:
     - `method`: `"spake2-pin-v1"`
     - `pakeMessageHex`: 64 lowercase hex chars (32-byte SPAKE2 Bob message)
     - `responderNonceHex`: 64 lowercase hex chars (32 random bytes)
     - `responderDisplayName`: 1..64 code points
     - `responderPlatform`: `"windows" | "linux" | "macos"`
     - `responderPresenceHex`: 32 lowercase hex chars
4. **Initiator → Responder**: `PAIR_IDENT_INIT` (`seq=3`):
   - Initiator processes `pakeMessageHex`, derives `K_confirm_init` and `K_confirm_resp`, and sends its identity + HMAC over `initiator_kc_input = SHA-256("GLIDE-KC-INIT-V2\0" || sessionId || initiatorNonceHex || responderNonceHex || tlsServerSpkiSha256Hex || initiatorFingerprintHex || initiatorPublicKeyDerHex)`:
     - `initiatorFingerprintHex`: 64 lowercase hex
     - `initiatorPublicKeyDerHex`: lowercase hex P-256 SPKI DER
     - `macHex`: 64 lowercase hex (`HMAC-SHA256(K_confirm_init, initiator_kc_input)`)
5. **Responder Verifies & Replies**: `PAIR_IDENT_RESP` (`seq=2`):
   - Responder verifies `macHex` in constant time using `K_confirm_init`.
   - If `macHex` fails (wrong 6-digit PIN), responder increments its failed-attempt counter; if attempts $\ge 3$, invalidates the code and sends `PAIR_REJECT` (`code: "CODE_EXPIRED"`); otherwise sends `PAIR_REJECT` (`code: "WRONG_CODE"`).
   - If `macHex` succeeds, the one-use PIN is consumed! Responder builds the full `T_pair` transcript, signs `T_pair` with its persistent P-256 identity key, computes `responder_mac = HMAC-SHA256(K_confirm_resp, T_pair)`, and sends:
     - `responderFingerprintHex`: 64 lowercase hex
     - `responderPublicKeyDerHex`: lowercase hex P-256 SPKI DER
     - `macHex`: 64 lowercase hex (`responder_mac`)
     - `signatureHex`: lowercase hex DER ECDSA signature over `T_pair`
6. **Initiator Verifies & Signs**: `PAIR_CONFIRM_INIT` (`seq=4`):
   - Initiator verifies `responder_mac` using `K_confirm_resp` and verifies the responder's ECDSA P-256 signature over `T_pair`.
   - Initiator signs `T_pair` with its persistent P-256 identity key and sends:
     - `signatureHex`: lowercase hex DER ECDSA signature over `T_pair`
7. **Mutual User Approval & Commit**:
   - Responder verifies initiator's ECDSA signature over `T_pair`.
   - Responder prompts the target user: **`[device name] wants to pair`** (**Approve** / **Decline**).
   - Initiator (having already clicked **Pair** to initiate, or awaiting target approval) and Responder exchange `PAIR_DECISION` (`approved: true | false`).
   - Only when both sides have verified all cryptographic proofs **and** both sides send `approved: true` does each side commit the peer's `VerifiedDesktopPeerIdentity` / `VerifiedPeerIdentity` into its persistent trust store and display **`Paired with [device name]`**.

---

## 5. Pair-Only QR and Link (`glide://pair/v2#...`)

### 5.1 URI Format & Schema

Prefix: `glide://pair/v2#` followed by unpadded base64url-encoded UTF-8 JSON.

Bounds (identical to v1 safety ceilings):
- Raw URI length $\le 2,768$ ASCII characters.
- Decoded JSON byte length $\le 2,048$ bytes.
- JSON nesting depth $\le 4$.
- Canonical base64url encoding required (no `=` padding, re-encoding must match input byte-for-byte).
- Duplicate JSON keys and unknown JSON keys are strictly rejected.

Required Root JSON Keys (14 keys):
```json
{
  "v": 2,
  "transport": "lan-tls",
  "profile": 2,
  "protocol": 1,
  "purpose": "pair-only",
  "invitationId": "550e8400-e29b-41d4-a716-446655440000",
  "presence": "0123456789abcdef0123456789abcdef",
  "name": "Office Desktop",
  "platform": "windows",
  "endpoints": [
    { "address": "192.168.1.42", "port": 42424 }
  ],
  "spkiSha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "token": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  "expiresAt": "1791700000",
  "ttlSeconds": 120
}
```

Field validation rules:
- `v`: integer `2`
- `transport`: `"lan-tls"`
- `profile`: integer `2`
- `protocol`: integer `1`
- `purpose`: `"pair-only"` (any other value rejected)
- `invitationId`: canonical lowercase UUIDv4
- `presence`: 32 lowercase hex characters (`^[0-9a-f]{32}$`)
- `name`: non-blank, 1..64 Unicode code points, $\le 128$ UTF-8 bytes
- `platform`: one of `"android"`, `"windows"`, `"macos"`, `"ios"`, `"linux"`, `"unknown"`
- `endpoints`: 1..4 unique objects `{ "address", "port" }`, valid local/private IPv4/IPv6 literal, port $1..65535$
- `spkiSha256`: 64 lowercase hex characters (SHA-256 of the listener's TLS 1.3 server certificate SPKI)
- `token`: 43 unpadded base64url characters decoding to 32 random bytes
- `expiresAt`: positive decimal epoch seconds string
- `ttlSeconds`: integer `1..120`

### 5.2 Scanner Routing & Version Compatibility

- `glide://pair/v1#...` routes to the existing Profile 1 file-transfer flow.
- `glide://pair/v2#...` routes to the Profile 2 `PAIR_ONLY` flow.
- If a v1-only decoder encounters `glide://pair/v2#` (or `glide://pair/v<N>#` where $N > 1$), it reports the actionable user message: **`Update Glide to pair this device`**.

### 5.3 `qr-token-v2` Pair-Only Handshake Sequence

1. Initiator (phone or desktop that scanned/pasted `glide://pair/v2#...`) connects to an endpoint in `endpoints`, pinning `spkiSha256` during the TLS 1.3 handshake.
2. Initiator sends `"GLD2"` preamble + `GLD2_OPEN` (`purpose: "PAIR_ONLY"`).
3. Initiator sends `PAIR_TOKEN_INIT` (`seq=2`):
   - `method`: `"qr-token-v2"`
   - `invitationId`: UUIDv4 from invitation
   - `token`: 43-char base64url token from invitation
   - `invitationHash`: 64 lowercase hex SHA-256 of decoded invitation JSON bytes
   - `initiatorNonceHex`: 64 lowercase hex
   - `initiatorDisplayName`: 1..64 code points
   - `initiatorPlatform`: platform string
   - `initiatorFingerprintHex`: 64 lowercase hex
   - `initiatorPublicKeyDerHex`: lowercase hex P-256 SPKI DER
4. Responder validates and consumes `(invitationId, token, invitationHash)` one-time from its active pair-only invitation authority (checking 120s expiry and single-use). If invalid/expired/replayed, responder sends `PAIR_REJECT` (`code: "CODE_EXPIRED"`) and closes.
5. Responder generates `responderNonceHex`, builds the canonical `T_pair` transcript (`method = "qr-token-v2"`), signs `T_pair` with its persistent P-256 identity key, and sends `PAIR_TOKEN_RESP` (`seq=1`):
   - `responderNonceHex`: 64 lowercase hex
   - `responderDisplayName`: 1..64 code points
   - `responderPlatform`: platform string
   - `responderPresenceHex`: 32 lowercase hex
   - `responderFingerprintHex`: 64 lowercase hex
   - `responderPublicKeyDerHex`: lowercase hex P-256 SPKI DER
   - `signatureHex`: lowercase hex DER ECDSA signature over `T_pair`
6. Initiator verifies responder's ECDSA signature over `T_pair`, signs `T_pair` with its own persistent P-256 identity key, and sends `PAIR_CONFIRM_INIT` (`seq=3`) with `signatureHex`.
7. Responder verifies initiator's ECDSA signature over `T_pair`. Both sides prompt/confirm user approval (`PAIR_DECISION`) and commit the verified peer identity to their persistent trust stores.

---

## 6. Saved-Device Reconnect (`GLD2` + `TRUSTED_SEND`)

When a user selects a saved peer under **Paired device** on **Send**:

1. **Endpoint Resolution**:
   - The sender resolves the target's current local endpoint `(address, port)` from active `_glide._tcp` discovery candidates or a user-supplied/cached local LAN address validated by `LanAddressValidator` / local-network policy.
   - Display names and IP addresses are treated strictly as routing hints, never as identity proof.
2. **TLS 1.3 Connection**:
   - The sender connects via TLS 1.3 (`ALPN: glide-lan/1`) and records the server certificate's actual `RemoteSpkiSha256` (32-byte SHA-256 of the server's X.509 SPKI).
   - Because desktop/mobile TLS listener certificates are ephemeral or rotated independently of persistent identity keys, the connection is authenticated by the mandatory `identity-v1` mutual proof bound to `RemoteSpkiSha256` before any file metadata is sent.
3. **`GLD2` Bootstrap**:
   - Sender writes `"GLD2"` (4 bytes) followed by `GLD2_OPEN`:
     ```json
     {
       "v": 1,
       "type": "GLD2_OPEN",
       "sessionId": "<new-uuid-v4>",
       "messageId": "<uuid-v4>",
       "seq": 1,
       "body": {
         "profile": 2,
         "purpose": "TRUSTED_SEND"
       }
     }
     ```
   - Receiver replies with `GLD2_OPEN_ACK` (`seq=1`, `accepted: true`, `sessionId`) or `GLD2_OPEN_ACK` (`accepted: false`, `code: "BUSY" | "AUTHENTICATION_FAILED"`).
4. **Mandatory `HELLO` + Mutual `identity-v1` Proof Before Offer**:
   - Following `GLD2_OPEN_ACK`, both sides reset/enter the standard `DesktopTransferEngine` / `GlideTransferEngine` control sequence for `sessionId`:
   - Sender sends `HELLO` requiring `["stream-v1", "sha256", "identity-v1"]`.
   - Receiver replies with `HELLO_ACK` including `"identity-v1"`. If the receiver omits `"identity-v1"`, the sender aborts immediately (`AUTHENTICATION_FAILED` — capability downgrade rejected).
   - Sender and Receiver execute the mutual `identity-v1` challenge/response (`IDENTITY_CHALLENGE` / `IDENTITY_RESPONSE` in both directions), bound to `channelBindingHex = tlsServerSpkiSha256Hex` and `capabilityHashHex`.
   - **Sender Verification**: The receiver's `VerifiedDesktopPeerIdentity.FingerprintHex` and `PublicKeyDerHex` MUST match the selected saved device's `ExpectedPeerFingerprintHex`. If the receiver presents a different identity key, the sender aborts immediately and surfaces **`Identity changed — pair again`**.
   - **Receiver Verification**: The receiver requires the sender's `VerifiedDesktopPeerIdentity` to be an already-enrolled peer in the receiver's persistent trust store (`RequirePeerIsEnrolled`). If the sender was revoked or is unknown on the receiver, the receiver aborts immediately with `AUTHENTICATION_FAILED` (`Identity changed — pair again`).
5. **Standard Offer & Consented Transfer**:
   - Once mutual `identity-v1` proof succeeds, the sender proceeds directly into `OFFER_BEGIN` / `OFFER_PAGE` / `OFFER_END`.
   - The receiver validates the offer and prompts the local user for explicit consent (`Accept` / `Decline`), then streams and verifies all items into `QuarantineStagingStore` with streaming SHA-256 verification before atomic commit.
