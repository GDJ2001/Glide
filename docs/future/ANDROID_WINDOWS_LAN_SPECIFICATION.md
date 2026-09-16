# Android-to-Windows Interoperable LAN Transport Specification

> 2026-09-16 precedence notice: do not implement the custom SAS or infer working LAN functionality from this future proposal. The [LAN/QR/Quick Share master development plan](../planning/LAN_QR_QUICK_SHARE_MASTER_PLAN.md) and [ADR-0018](../adr/ADR-0018-lan-qr-authentication-and-routing.md) define the current Android LAN scope, QR-pinned TLS authentication, explicit transport framing, API gates and verified source gaps. The actual existing port is TransferTransport. Windows implementation, persistent identity and true resume are outside this release.

- **Document Version:** 1.0.0
- **Status:** Approved Future Specification
- **Milestone:** M13 — Future work (outside V1)
- **Task Authority:** [GLIDE-072](../TODO_MASTER.md#glide-072)
- **Architectural Decision:** [ADR-0017: Android-to-Windows interoperable LAN slice](../adr/ADR-0017-android-windows-interoperable-lan-slice.md)
- **Related Documents:** [ADR-0008 (Application protocol)](../adr/ADR-0008-protocol.md), [ADR-0015 (Persistent identity & true resume)](../adr/ADR-0015-persistent-identity-and-true-resume.md), [ADR-0016 (Advanced transports evaluation)](../adr/ADR-0016-advanced-android-transports.md), [GLIDE_PROTOCOL_V1.md](../protocol/GLIDE_PROTOCOL_V1.md), [GLIDE_THREAT_MODEL.md](../security/GLIDE_THREAT_MODEL.md), [CROSS_PLATFORM_PATH.md](CROSS_PLATFORM_PATH.md).

---

## 1. Executive Summary & Architectural Goals

### 1.1 Purpose
This specification defines the complete architectural design, network wire protocol, cryptographic security contracts, Windows filesystem quarantine rules, and multi-phase implementation plan for **Android-to-Windows interoperable file sharing** in **Glide** (`dev.glide.app`).

### 1.2 Invariants & Principles
1. **Zero Cloud / 100% Offline by Default:** Discovery and transfer operate strictly over the shared Local Area Network (LAN) subnet. No cloud signaling servers, user account systems, WebRTC STUN/TURN relays, or external network connections are permitted.
2. **Canonical Protocol Parity:** The Windows implementation strictly reuses the **Glide Protocol V1** envelope schema, sequence rules, offer validation, and item receipt states without creating a protocol fork.
3. **Application-Layer Zero-Trust Security:** Network sockets mandate **TLS 1.3 mutual authentication** with ephemeral X.509 certificates and 6-digit Short Authentication String (SAS) human comparison or persistent hardware TOFU pinning (ADR-0015).
4. **Windows Filesystem Sanitization:** Inbound file paths must undergo strict POSIX-to-Windows sanitization, rejecting path traversal (`..`), drive letter injections (`C:`), UNC paths (`\\`), and reserved legacy DOS device names (`CON`, `PRN`, `AUX`, `NUL`, etc.).
5. **User Data Sanctity Invariant:** Incoming files are quarantined in private application storage (`%LocalAppData%\Glide\staging\<session_id>\`) and atomically moved to destination only after end-of-stream SHA-256 digest verification succeeds.

---

## 2. System Architecture & Component Mapping

The cross-platform LAN slice consists of two interoperating endpoints communicating over standard TCP/IP:

```
+------------------------------------+          +------------------------------------+
|          Android Endpoint          |          |      Windows Desktop Endpoint      |
|         (dev.glide.app)            |          |          (Glide.Windows)           |
+------------------------------------+          +------------------------------------+
|  Glide UI & Coordinator (:app)     |          |  WinUI 3 / Modern Desktop UI       |
+------------------------------------+          +------------------------------------+
|  Protocol Engine (:transfer-core)  |          |  Native Windows Protocol Engine    |
|  - Validated by 20+ Golden Fixtures|          |  - Validated by Same Fixtures      |
+------------------------------------+          +------------------------------------+
|  LAN Transport Port (:transport-lan|          |  Windows LAN Transport Adapter     |
|  - NsdManager (mDNS / DNS-SD)      |          |  - Windows.Networking.Sockets      |
|  - Java SSLSocket (TLS 1.3)        |          |  - Windows.Security.Cryptography   |
+------------------------------------+          +------------------------------------+
                  ^                                                ^
                  |                                                |
                  +========= [ Local Subnet / Wi-Fi Router ] =======+
                             mDNS Discovery: _glide._tcp
                             Data: Length-Prefixed TLS 1.3
```

---

## 3. mDNS Service Discovery & Announcement Specification

### 3.1 Service Type & Addressing
- **Service Type:** `_glide._tcp.local.`
- **Transport Protocol:** TCP IPv4 and IPv6.
- **Port Assignment:** Dynamically bound ephemeral port advertised in the mDNS SRV record (default candidate `42424` if available).

### 3.2 TXT Record Attributes
The mDNS announcement must publish standard TXT attributes encoded in UTF-8 key-value format:

| Key | Example Value | Description |
|:---|:---|:---|
| `v` | `1` | Protocol major version. Must be `1` for Glide Protocol V1. |
| `dn` | `Desktop-9A7F` | Friendly device name displayed in peer discovery UI ($\le 32$ characters). |
| `dt` | `desktop` | Device type: `desktop`, `mobile`, or `tablet`. |
| `alpn`| `glide-v1` | Application-Layer Protocol Negotiation token. |
| `fp` | `e3b0c442` | First 8 hex characters of the TLS certificate SHA-256 fingerprint for fast deduplication. |
| `cap`| `0x07` | Capability bitmask: `0x01` (Folder Transfer), `0x02` (Persistent Identity), `0x04` (True Resume). |

### 3.3 Discovery Lifecycle
1. **Advertising (Receive Mode):** When the user opens the Receive screen, the device registers the `_glide._tcp.local.` service with its dynamic port and TXT attributes.
2. **Browsing (Send Mode):** When the user selects files and initiates Send, the device browses for `_glide._tcp.local.` services, resolving SRV and TXT records.
3. **Teardown:** Registration and browsing are immediately cancelled when exiting the transfer flow, releasing network resources.

---

## 4. Transport Stream Framing & Wire Protocol

### 4.1 Length-Prefixed Control Framing
Because raw TCP is a byte stream rather than a message-oriented transport, control messages use standard **length-prefixed framing**:

```
+--------------------------+-------------------------------------------------------+
|  Length Header (4 bytes) |                JSON Envelope Body                     |
|  Big-Endian UInt32 (L)   |  Exact UTF-8 JSON String (L <= 16384 bytes / 16 KiB)  |
+--------------------------+-------------------------------------------------------+
```

#### Framing Rules:
- **Maximum Frame Size:** $L \le 16384\text{ bytes}$ ($16\text{ KiB}$). If an incoming header indicates $L > 16384$ or $L \le 0$, the receiver must immediately terminate the TCP connection.
- **Envelope Content:** The body must be an exact JSON envelope matching Glide Protocol V1 (`hello`, `offer_begin`, `offer_page`, `offer_decision`, `item_ready`, `item_result`, etc.).

### 4.2 Bulk Data Streaming
Once an item is accepted via `offer_decision` and confirmed via `item_ready`, bulk payload transmission transitions to continuous stream mode:
- **Chunk Size:** $64\text{ KiB}$ ($65,536\text{ bytes}$) bounded read/write buffers ($O(1)$ memory consumption).
- **End-of-Item Delimitation:** End of item is determined by the total byte count declared in the manifest, confirmed by receipt of an `item_end` control envelope.
- **Pipelined SHA-256 Digest:** The receiver computes SHA-256 continuously across incoming bytes in a single pass without storing full payloads in memory.

---

## 5. Security, Mutual Authentication & SAS Derivation

### 5.1 TLS 1.3 Mandatory Application Security
All TCP socket connections mandate **TLS 1.3** (RFC 8446). Plaintext or TLS 1.2 connections are strictly rejected.
- **Cipher Suites:** `TLS_AES_128_GCM_SHA256`, `TLS_AES_256_GCM_SHA384`, `TLS_CHACHA20_POLY1305_SHA256`.
- **Certificates:** Ephemeral self-signed X.509 certificates generated in-memory using EC P-256 (NIST secp256r1) or Ed25519.
- **Mutual Authentication:** Both client and server must exchange and validate certificates (`ClientAuth.REQUIRE`).

### 5.2 Short Authentication String (SAS) Derivation
To protect against Man-In-The-Middle (MITM) attacks on untrusted local networks, both peers derive an identical 6-digit Short Authentication String from the TLS session:
$$\text{Digest} = \text{SHA-256}(K_{\text{client\_pub}} \mathbin{\Vert} K_{\text{server\_pub}} \mathbin{\Vert} \text{SessionNonce})$$
$$\text{SAS} = \text{ExtractDecimal6}(\text{Digest}) = (\text{Digest}[0..3] \bmod 900,000) + 100,000$$

Both users must compare and confirm this 6-digit number on their respective screens before the receiver accepts transfer offers.

### 5.3 Persistent TOFU Key Pinning (ADR-0015 Integration)
If persistent identity is enabled:
- The public key is pinned in the local database (Room on Android, Windows Credential Manager / DPAPI on Windows).
- If the pinned key matches, the 6-digit manual confirmation prompt is bypassed.
- If the pinned key differs from a known peer, the connection is aborted immediately and an untrusted identity alert is displayed.

---

## 6. Windows File System, Quarantine Staging & User Data Sanctity

### 6.1 Safe Windows Path Sanitizer
Windows filesystem semantics introduce distinct vulnerabilities (drive letters, UNC paths, reserved DOS device names, alternative data streams). The Windows receiver must enforce the following sanitization pipeline on all relative paths:

```
Input Relative Path
       │
       ▼
[ Normalize Slashes: replace '\' with '/' ]
       │
       ▼
[ Tokenize by '/' and Reject '..' or '.' ]
       │
       ▼
[ Reject Root Prefixes ('/', '\'), Drive Letters ('C:'), and UNC ('\\') ]
       │
       ▼
[ Strip Illegal Windows Characters: < > : " / \ | ? * and Control Chars (0x00-0x1F) ]
       │
       ▼
[ Reject Reserved DOS Device Names: CON, PRN, AUX, NUL, COM1-9, LPT1-9 ]
       │
       ▼
[ Enforce Component Length <= 255 and Total Path <= 260 characters ]
```

### 6.2 Staging Directory Quarantine Lifecycle
1. **Quarantine Staging:** During transfer, partial bytes are written to:
   `%LocalAppData%\Glide\staging\<session_id>\<item_id>.quarantine`
2. **Verification Gate:** Upon completion, the pipelined SHA-256 digest is compared against `manifest.sha256`.
3. **Atomic Commit:** Only upon digest match is the file moved to:
   `%USERPROFILE%\Downloads\<destination_relative_path>`
4. **Collision Handling:** If a file already exists at the destination, non-destructive monotonic renaming is applied: `Report (1).pdf`.
5. **Abort Purge:** If the session is canceled, disconnected, or digest verification fails, the quarantine file is deleted immediately. Zero unverified bytes ever touch the user's Downloads folder.

---

## 7. Cross-Platform Codec Conformance & Golden Fixture Plan

### 7.1 Golden Fixture Conformance Suite
To guarantee that the native Windows client and Android client have 100% wire interoperability, both platforms must pass an identical test suite executing against the 20+ canonical JSON test fixtures in `transfer-core/src/test/resources/fixtures/`:
1. `hello.json` & `hello_ack.json`
2. `offer_begin.json`, `offer_page.json`, `offer_end.json`, `offer_decision.json`
3. `item_start.json`, `item_ready.json`, `item_end.json`, `item_end_zero_byte.json`
4. `item_result_verified.json`, `item_result_failed.json`
5. `batch_end.json`, `batch_result.json`
6. `cancel.json`, `cancel_ack.json`, `error.json`
7. `ping.json`, `pong.json`, `message_with_valid_extensions.json`

### 7.2 Negative Fixture Conformance Suite
Both implementations must assert proper rejection of all invalid fixtures:
- `invalid_bom.bin` (Byte Order Mark rejection)
- `invalid_malformed_utf8.bin` (Malformed UTF-8 rejection)
- `invalid_decimal_overflow.json` (Overflow protection on byte counts)
- `invalid_depth_exceeded.json` (JSON recursion depth limit $\le 8$)
- `invalid_duplicate_keys.json` (RFC 8259 duplicate key rejection)
- `invalid_exceeds_16kib.json` (Frame size bound enforcement)
- `invalid_negative_seq.json` (Monotonic sequence integrity)
- `invalid_non_canonical_uuid.json` (Strict UUID lowercase RFC 4122 formatting)
- `invalid_unknown_body_field.json` (Unknown property strict rejection)
- `invalid_unsupported_version.json` (Protocol version mismatch)

---

## 8. Cross-Platform Implementation Task Plan

The Android-to-Windows LAN slice is planned across 5 sequential implementation phases:

### Phase 1: Native Windows Protocol Codec & Conformance
- Port Glide Protocol V1 JSON envelope serializers to native Windows stack (e.g. `System.Text.Json` or `serde_json`).
- Implement golden fixture test runner passing 100% of canonical positive and negative fixtures.

### Phase 2: Windows Network Adapter (mDNS + TLS 1.3)
- Implement mDNS advertiser and browser for `_glide._tcp.local.` using Windows native DNS-SD or modern mDNS library.
- Implement TLS 1.3 stream socket server and client with mutual certificate authentication.
- Implement length-prefixed frame reader/writer with 16 KiB limit.

### Phase 3: Android `:transport-lan` Adapter Module
- Implement `LanTransportAdapter` in `:transport-lan` conforming to `dev.glide.core.ports.TransportPort`.
- Bind Android `NsdManager` to the discovery interface and `SSLSocketFactory` to the transport connection interface.
- Ensure bounded 64 KiB STREAM buffers and quarantine commit integration.

### Phase 4: Windows Desktop UI & Shell Integration
- Build a lightweight native Windows desktop UI (WinUI 3 / Windows App SDK) featuring Send (drag-and-drop file target) and Receive views.
- Implement 6-digit SAS comparison dialog and live transfer progress bar.
- Windows Notification Service (WNS) integration for background transfer completion.

### Phase 5: End-to-End Interoperability Verification & Security Proofs
- Run physical cross-platform transfers between Android device and Windows PC.
- Validate cancellation, network drop recovery, collision rename, and SHA-256 byte-for-byte fidelity across 10 MB, 1 GB, and multi-file batches.

---

## 9. Threat Model & Security Proof Matrix

| Threat ID | Threat Description | LAN Slice Mitigation |
|:---|:---|:---|
| **T01** | LAN Impersonation & Spoofing | Mutual TLS 1.3 certificate exchange bound to 6-digit SAS or Keystore TOFU key. |
| **T02** | Wi-Fi Eavesdropping & Packet Sniffing | End-to-end TLS 1.3 session encryption prevents payload or metadata interception. |
| **T03** | Stale / Replay Attacks | Fresh mutual 32-byte session nonces; sequential monotonic message IDs. |
| **T04** | Windows Path Traversal Injection | `WindowsPathSanitizer` strictly strips drive letters, UNC, `..`, and reserved DOS names. |
| **T05** | Port Scanning & Connection Flooding | Unknown connections dropped immediately before TLS negotiation; single active session limit. |
| **T06** | Local Network Tracking | Ephemeral mDNS service instances and randomized session identifiers. |
| **T07** | Malformed Packet & Buffer Overflow | Strict 4-byte length prefix bounded to 16 KiB; $O(1)$ 64 KiB streaming buffer. |
| **T08** | Incomplete File Truncation | End-of-stream EOF validated against manifest byte count and SHA-256 root digest. |
| **T10** | Public Storage Leaks | Private quarantine staging in %LocalAppData%\Glide\staging\; atomic rename on verify. |
| **T11** | Windows Path Traversal Injection | WindowsPathSanitizer strictly strips drive letters, UNC, .., and reserved DOS names. |
| **T12** | Partial Download Infiltration | Zero unverified partial bytes committed to user's Downloads directory. |
| **T13** | File Collision Overwrite | Non-destructive monotonic collision rename e.g. "Report (1).pdf". |
| **T14** | Zombie File Leaks on Abort | Synchronous purge of quarantine file on transport disconnect or cancellation. |
| **T15** | Concurrent Transfer Race Conditions | Single active transfer session enforced at transport and coordinator levels. |
| **T17** | Memory & CPU Denial of Service | Zero in-memory buffering of payload streams; bounded JSON parser recursion depth (<= 8). |
