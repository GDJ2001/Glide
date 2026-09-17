# Remote and Internet Transfer (WAN, NAT Traversal & Blind Relays) Specification

- **Document Version:** 1.0.0
- **Status:** Approved Technical Specification
- **Milestone:** M21 — Remote & Internet Transfer Architecture & Conformance Specification
- **Task Authority:** [CROSS_PLATFORM_PATH.md](CROSS_PLATFORM_PATH.md) Step 6
- **Architectural Decision:** [ADR-0022](../adr/ADR-0022-remote-internet-transfer.md)
- **Related Documents:** [GLIDE_PROTOCOL_V1.md](../protocol/GLIDE_PROTOCOL_V1.md), [ANDROID_WINDOWS_LAN_SPECIFICATION.md](ANDROID_WINDOWS_LAN_SPECIFICATION.md), [ANDROID_BROWSER_WEB_SPECIFICATION.md](ANDROID_BROWSER_WEB_SPECIFICATION.md), [APPLE_PLATFORMS_SPECIFICATION.md](APPLE_PLATFORMS_SPECIFICATION.md), [LINUX_PLATFORM_SPECIFICATION.md](LINUX_PLATFORM_SPECIFICATION.md), [GLIDE_THREAT_MODEL.md](../security/GLIDE_THREAT_MODEL.md).

---

## 1. Executive Summary & Architectural Invariants

### 1.1 Purpose
This specification defines the complete network protocol, signaling architecture, NAT traversal pipeline, end-to-end cryptographic encryption, and relay anti-abuse controls for **Glide Remote & Internet Transfers**, enabling secure peer-to-peer file sharing across heterogeneous networks, cellular data connections, and firewalled subnets without centralized cloud file storage.

### 1.2 Core Invariants
1. **Zero Cloud File Storage:**
   No file payloads, thumbnails, or temporary chunks are ever stored on cloud servers or third-party buckets (AWS S3, Google Cloud, etc.). All transfers are direct peer-to-peer or transiently forwarded through blind relays.
2. **Zero-Knowledge Signaling & Blind Relays:**
   Signaling servers and relays act strictly as blind bit couriers. All control messages, SDP attributes, and payload streams are End-to-End Encrypted (E2EE) using ephemeral keys. Relays possess zero cryptographic keys and cannot inspect filenames, file sizes, or plaintexts.
3. **Canonical Protocol Parity:**
   Remote transfers adhere strictly to **Glide Protocol V1** envelopes, `LanRecord` framing, offer negotiation, single-pass SHA-256 validation, and verified item receipts.
4. **Strict Local Isolation:**
   Local Area Network (LAN) and Nearby Connections P2P transfers remain 100% offline. They never initiate connections to, or depend on, remote signaling or relay infrastructure.
5. **Metered Cellular Protection:**
   The client automatically detects metered cellular networks and requires explicit user confirmation before initiating remote transfers.

---

## 2. Remote Signaling Architecture & Out-of-Band Invitation Schema

```
+-----------------------------------------------------------------------------------+
|                              Signaling Architecture                               |
|                                                                                   |
|  [Sender Device]  --- (1) Out-of-Band Invitation Link --->  [Receiver Device]     |
|         |             (Signal, WhatsApp, Email, QR)                 |             |
|         |                                                           |             |
|         +------------ (2) E2EE Rendezvous Signaling --------------+             |
|                                     |                                             |
|                                     v                                             |
|                    +---------------------------------+                            |
|                    |     Ephemeral Signaling Broker  |                            |
|                    |     (Stateless WebSocket Relay) |                            |
|                    +---------------------------------+                            |
|                                     |                                             |
|          +--------------------------+--------------------------+                  |
|          |                                                     |                  |
|          v                                                     v                  |
|  [Direct P2P UDP] (75-85% success)               [Blind TURN Relay] (Fallback)    |
|  (STUN Hole Punching / RFC 8489)                 (E2EE Encrypted / RFC 8656)      |
|          |                                                     |                  |
|          +--------------------------+--------------------------+                  |
|                                     v                                             |
|                     Glide Protocol V1 + LanRecord Framing                         |
+-----------------------------------------------------------------------------------+
```

### 2.1 Out-of-Band Remote Invitation URI
Remote transfers are initiated out-of-band by generating an ephemeral pairing URI:
```
glide://remote/v1#id=<RendezvousUUID>&key=<EphemeralPublicKeyHex>&sig=<SignalingEndpoint>&token=<RelayBlindToken>&exp=<UnixTimestamp>
```
- **`RendezvousUUID`:** Random 128-bit UUIDv4 used solely as a temporary lookup key on the signaling broker.
- **`EphemeralPublicKeyHex`:** 32-byte X25519 public key in lowercase hex for authenticated key exchange.
- **`SignalingEndpoint`:** Ephemeral WebSocket endpoint (e.g. `wss://relay.glide.app/v1/signal`).
- **`RelayBlindToken`:** Base64url cryptographic authorization token granting bounded relay bandwidth.
- **`UnixTimestamp`:** Expiration timestamp (strictly bounded to 120 seconds from creation).

### 2.2 Signaling Protocol (JSON-RPC over Secure WebSocket)
1. **Host Listening:**
   ```json
   { "action": "listen", "rendezvousId": "8a32a67e-071a-4d23-92b4-3ec9fef39db3" }
   ```
2. **Client Rendezvous & Encrypted Key Exchange:**
   ```json
   {
     "action": "rendezvous",
     "rendezvousId": "8a32a67e-071a-4d23-92b4-3ec9fef39db3",
     "clientPublicKey": "4b6f12...e8",
     "encryptedPayload": "<ChaCha20-Poly1305 ciphertext of ICE candidates>"
   }
   ```
3. **Broker State Purge:**
   Upon matching two endpoints, the broker routes the encrypted handshake messages and immediately destroys the rendezvous ID.

---

## 3. NAT Traversal Pipeline & Candidate Gathering

The client implements the Interactive Connectivity Establishment (ICE / RFC 8445) state machine:

### 3.1 Candidate Priorities
1. **Host Candidates (Priority 1):** Direct interface IP addresses (used if peers happen to share an un-isolated WAN subnet or VPN).
2. **Server-Reflexive Candidates (Priority 2 - STUN / RFC 8489):** Public IP/port bindings discovered via STUN queries (`stun:stun.glide.app:3478` or public STUN infrastructure). Direct UDP hole punching succeeds in 75–85% of consumer NAT deployments (Full-Cone, Restricted-Cone, and Port-Restricted Cone NATs).
3. **Relay Candidates (Priority 3 - TURN / RFC 8656):** Blind TURN packet forwarding over UDP/TCP port 443 used only when Symmetric NAT prevents direct hole punching.

---

## 4. End-to-End Cryptographic Wire Framing

All data traversing the remote connection is encrypted end-to-end between the two devices:

### 4.1 Cryptographic Handshake
- **Handshake Pattern:** `Noise_IK_25519_ChaChaPoly_BLAKE2s` or mutual TLS 1.3 with SPKI public key pinning.
- **Forward Secrecy:** Ephemeral Diffie-Hellman keys are generated per transfer batch and erased immediately upon session teardown.
- **Authentication:** Both peers verify that the remote public key matches the public key specified in the out-of-band invitation link.

### 4.2 Wire Framing
Inside the encrypted session, records adhere to standard Glide LAN Profile 1 framing:
- Control envelopes (`LanRecord.Control`, 16 KiB maximum).
- Bulk data chunks (`LanRecord.Data`, 64 KiB bounded chunks).
- Stream termination (`LanRecord.StreamEnd`).

---

## 5. Blind Relay Architecture & Anti-Abuse Controls

When relay fallback is required, the relay server operates under strict constraints:

### 5.1 Blind Forwarding Invariant
The relay is an opaque bit forwarder. It receives ciphertext packets, reads the destination channel ID, and forwards the packet to the paired peer. The relay:
- Cannot decrypt packet contents.
- Cannot inspect file names, MIME types, or item counts.
- Cannot modify packet contents without triggering AEAD decryption failure at the receiving client.

### 5.2 Bandwidth Quotas & Anti-Abuse Policies
1. **Relay Bandwidth Ceiling:**
   - **Direct P2P Transfers (ICE UDP hole punching):** **Uncapped** up to Protocol V1 limits (64 GiB per item, 256 GiB per batch).
   - **Relayed Transfers (Public / Community Relay):** Capped at **100 MB per transfer**.
   - If an offered transfer requires relaying and exceeds 100 MB, the client displays:
     > *"Direct peer-to-peer connection could not be established across firewalls. Relayed transfer is limited to 100 MB. To transfer larger files, connect both devices to the same local Wi-Fi network or configure a private relay."*
2. **Rate Limits:**
   - Maximum 3 concurrent transfer sessions per IP.
   - Maximum 10 connection attempts per minute per IP.
   - Excess traffic triggers immediate TCP connection termination (`RST`).

---

## 6. Consent, User Privacy & Metered Data Protection

### 6.1 Metered Connection Detection
The client queries native OS networking APIs before assembling or accepting a remote transfer:
- **Android:** `ConnectivityManager.getNetworkCapabilities().hasCapability(NET_CAPABILITY_NOT_METERED) == false`
- **Windows:** `NetworkInformation.GetInternetConnectionProfile().GetConnectionCost().NetworkCostType == Metered`
- **Apple (iOS/macOS):** `nw_path_is_expensive(path) == true` or `nw_path_is_constrained(path) == true`
- **Linux:** NetworkManager D-Bus property `org.freedesktop.NetworkManager.Device.Metered`

### 6.2 User Warning Dialog
When a metered network is detected, the transfer is suspended until the user explicitly confirms:
```
+-------------------------------------------------------------+
|               Metered Cellular Connection                   |
+-------------------------------------------------------------+
|  Transferring "project_archive.zip" (340 MB) will use your   |
|  mobile cellular data. Carrier charges may apply.           |
|                                                             |
|          [ Cancel Transfer ]      [ Proceed on Cellular ]    |
+-------------------------------------------------------------+
```

---

## 7. Remote Transfer Threat Model Additions

The following threat catalog entries are incorporated into [GLIDE_THREAT_MODEL.md](../security/GLIDE_THREAT_MODEL.md):

| Threat ID | Description | Likelihood | Impact | Countermeasures & Verifications |
|:---|:---|:---:|:---:|:---|
| **R01** | Unauthorized Remote Ingress / Spam | High | High | Ephemeral 120s rendezvous UUID; out-of-band link; explicit human consent prompt. |
| **R02** | Signaling Relay Snooping & Metadata Leak | Medium | Critical | E2EE encryption of all signaling payloads; broker stores zero state. |
| **R03** | Blind Relay Bandwidth Abuse / DDoS | High | High | Cryptographic blind tokens; 100 MB relay transfer ceiling; IP rate limiting. |
| **R04** | Man-in-the-Middle Attack | Medium | Critical | Ephemeral X25519 key exchange; SPKI hash pinning from out-of-band invitation URI. |
| **R05** | Metered Data Bill Shock | High | Medium | OS metered connection detection; explicit user warning with byte disclosure. |
| **R06** | Stale / Replayed Invitation Links | Medium | High | Monotonic 120-second TTL; one-use rendezvous token consumption. |
| **R07** | Remote Executable / Malware Ingress | Medium | High | Sandboxed quarantine staging; single-pass SHA-256 validation; no auto-execution. |
| **R08** | Cloud Dependency Creep | Medium | Critical | Strict isolation: Local LAN and Nearby P2P never touch signaling or relay servers. |

---

## 8. Multi-Phase Implementation Roadmap

- **Phase R1: Ephemeral Signaling Broker & Out-of-Band Invitation Codec**
  - Implement URI encoder/decoder for `glide://remote/v1#...`.
  - Lightweight WebSocket signaling protocol client.
- **Phase R2: STUN / ICE Direct P2P Hole Punching**
  - Implement ICE agent (RFC 8445) and STUN binding discovery (RFC 8489).
  - Hole punching verification across NAT topologies.
- **Phase R3: Blind TURN Relay Fallback & Anti-Abuse Engine**
  - Implement blind packet forwarding client for TURN (RFC 8656).
  - Enforce 100 MB relay bandwidth cap and blind authorization token verification.
- **Phase R4: Metered Data UX & Platform Integration**
  - Integrate native metered network detection across Android, Windows, Apple, and Linux.
  - Implement cellular data warning prompts.
- **Phase R5: End-to-End Remote Conformance Certification**
  - Automated integration testing verifying E2EE wire encryption, relay fallback, and strict local LAN isolation.
