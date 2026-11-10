# ADR-0022 — Remote and Internet Transfer Architecture, NAT Traversal & Relay Threat Model

> **Scope Note:** Active project scope is restricted to local peer-to-peer native sharing across **Android, Windows, Linux, and Apple**. Remote/Internet WAN relay transfers and browser sharing are outside the active project scope.

- **Status:** Accepted (Specification only; deferred / removed from active native local sharing scope across Android, Windows, Linux, and Apple)
- **Date:** 2026-09-17
- **Authority:** [Cross-Platform Path](../future/CROSS_PLATFORM_PATH.md) | [Remote Internet Transfer Specification](../future/REMOTE_INTERNET_TRANSFER_SPECIFICATION.md) | [GLIDE_THREAT_MODEL.md](../security/GLIDE_THREAT_MODEL.md)
- **Supersedes / Extends:** Historical Step 6 of [CROSS_PLATFORM_PATH.md](../future/CROSS_PLATFORM_PATH.md); extends [ADR-0008 (Protocol)](ADR-0008-protocol.md) and [ADR-0018 (LAN/QR Profile 1)](ADR-0018-lan-qr-authentication-and-routing.md) to wide-area network (WAN) and internet environments while strictly preserving zero-cloud data sovereignty.

---

## Context

Glide V1 and subsequent cross-platform milestones established high-throughput, private local file sharing across Android, Windows, Apple iOS/macOS, and Linux on local networks (Nearby Connections P2P and Local LAN Profile 1).

However, users frequently need to transfer files across the public internet when devices are on separate networks:
1. **Heterogeneous Networks:** Sender is on residential fiber Wi-Fi; receiver is on a mobile cellular connection (4G/5G).
2. **Distinct Wi-Fi Subnets:** Devices reside on separate office, campus, or public networks where inter-client communication is firewalled.
3. **Geographic Distance:** Peers are in different cities or countries.

Traditional commercial file sharing services (WeTransfer, Google Drive, Dropbox, AirDrop over iCloud) address this by uploading entire files to centralized cloud storage buckets. This approach:
- **Destroys User Privacy:** File contents, filenames, metadata, and user identities are stored on third-party cloud infrastructure.
- **Incurs High Hosting Costs:** Cloud bandwidth and storage fees scale linearly with user volume, requiring subscription paywalls, ads, or data monetization.
- **Degrades Transfer Speed:** Requires a two-step transfer (Upload to cloud $\rightarrow$ wait $\rightarrow$ download from cloud) which halves effective throughput.

Glide requires a remote transfer architecture that enables direct, internet-wide file sharing while preserving Glide's foundational invariants: **Zero Cloud / 100% Offline by Default, No User Accounts, End-to-End Encryption (E2EE), and Zero Metadata Leakage.**

---

## Options Considered

### 1. Central Cloud Storage Relay (e.g. AWS S3 / Cloudflare R2 / Backblaze B2)
- *Pros:* Simple architecture; high availability; sender can close app after upload completes.
- *Cons:* **Violates Glide's core invariant of Zero Cloud.** Exposes file metadata and encrypted blobs to cloud vendors, introduces ongoing operational hosting liabilities, and contradicts Glide's identity as a private, direct peer-to-peer sharing tool. **Rejected.**

### 2. Centralized User Account & Presence Directory (Phone Numbers / Usernames / APNs)
- *Pros:* Contact-list sharing ("Send to Alice"); background push notifications.
- *Cons:* Requires a central database, user accounts, authentication tokens, contact harvesting, and persistent push servers. Violates Glide's zero-account, zero-tracking guarantee. **Rejected.**

### 3. Zero-Knowledge E2EE Peer-to-Peer NAT Traversal with Ephemeral Blind Relays (Adopted)
- *Pros:*
  - **Zero-Knowledge Architecture:** Signaling servers and relays act strictly as blind packet couriers. They have zero cryptographic keys and zero capability to decrypt payload or metadata.
  - **No Accounts or Directories:** Connection bootstrap uses out-of-band invitation URIs (`glide://remote/v1#...`) exchanged via existing user messaging channels (Signal, WhatsApp, iMessage, Matrix, email).
  - **Direct P2P Throughput:** Leverages Interactive Connectivity Establishment (ICE / RFC 8445) and STUN (RFC 8489) UDP hole punching, achieving direct peer-to-peer transmission in ~75–85% of real-world consumer NAT topologies with zero relay bandwidth consumption.
  - **Blind Fallback Relays:** For symmetric NATs or restrictive corporate firewalls where direct hole punching fails, traffic routes through blind TURN/Derp relays (RFC 8656) with end-to-end encrypted packets.
  - **Strict Local Isolation:** Local LAN and Nearby P2P transfers remain 100% offline and never contact remote signaling or relay servers.
- *Cons:* Both devices must be online concurrently; fallback relaying incurs modest hosting costs requiring anti-abuse rate limits.

---

## Decision

Adopt **Option 3: Zero-Knowledge E2EE Peer-to-Peer NAT Traversal with Ephemeral Blind Relays**, governed by the following architectural, cryptographic, and threat model contracts:

### 1. Out-of-Band Remote Invitation Bootstrap
- Remote transfers initiate out-of-band. The host generates a canonical Remote Invitation URI:
  ```
  glide://remote/v1#id=<RendezvousUUID>&key=<EphemeralPublicKeyHex>&sig=<SignalingEndpoint>&token=<RelayBlindToken>&exp=<UnixTimestamp>
  ```
- **`RendezvousUUID`:** Ephemeral 128-bit rendezvous identifier used by the signaling broker to match two peers.
- **`EphemeralPublicKeyHex`:** 256-bit ephemeral public key (X25519) for establishing the E2EE Noise handshake (`Noise_IK_25519_ChaChaPoly_BLAKE2s`) or TLS 1.3 channel.
- **`SignalingEndpoint`:** Ephemeral public WebSocket signaling broker URL (e.g. `wss://relay.glide.app/signal`).
- **`RelayBlindToken`:** Cryptographic anti-abuse token granting bounded relay bandwidth.
- **`UnixTimestamp`:** Monotonic 120-second expiration window.

### 2. Signaling Broker Isolation & Zero-Knowledge Invariant
- The signaling broker performs ephemeral rendezvous routing only:
  1. Peer A connects and sends: `{ "action": "listen", "rendezvousId": "<UUID>" }`.
  2. Peer B connects and sends: `{ "action": "join", "rendezvousId": "<UUID>", "offer": "<EncryptedSDP>" }`.
  3. The broker forwards encrypted messages between Peer A and Peer B and immediately drops all state once both peers disconnect.
- **Zero-Knowledge Guarantee:** All signaling payloads (SDP candidates, ICE parameters, session negotiation) are encrypted end-to-end with the recipient's ephemeral public key. The signaling broker **never** sees plaintext IP addresses of endpoints beyond socket connection level, file names, file sizes, or session keys.

### 3. NAT Traversal & Blind Relay Pipeline
- **Interactive Connectivity Establishment (ICE / RFC 8445):**
  Peers gather host candidates (local IP), server-reflexive candidates via public STUN servers (RFC 8489), and relay candidates via TURN (RFC 8656).
- **Direct P2P Priority:** ICE connectivity checks prioritize direct host-to-host or reflexive UDP paths. Relays are utilized only as a last resort.
- **Blind Relay Architecture:**
  - Relays receive length-prefixed, end-to-end encrypted `LanRecord` frames.
  - Relays do not possess session keys and cannot inspect or tamper with payload contents. Any payload alteration causes AEAD authentication failure at the receiver.

### 4. Relay Anti-Abuse, Bandwidth Quotas & Cost Protection
To protect public or community-hosted relays from denial-of-service, traffic amplification, and hosting cost runaway:
1. **Blind Token Authorization:** Clients present a cryptographic blind token generated during QR/link creation.
2. **Relay Bandwidth Ceiling:**
   - **Direct P2P Transfers:** Uncapped (governed only by device storage budget and Protocol V1 64 GiB limit).
   - **Relayed Transfers:** Capped at **100 MB per transfer** on free community relays. For larger files, the client prompts the user to connect to the same Wi-Fi or configure a private/self-hosted relay.
3. **Session Rate Limiting:** Maximum 3 concurrent remote transfer sessions per client IP; maximum 10 connection attempts per minute.

### 5. Consent, Privacy & Metered Data Protection
- **Metered Connection Warning:** If either sender or receiver is connected via a metered cellular connection (Android `NetworkCapabilities.NET_CAPABILITY_NOT_METERED == false`, Windows `Cost = Metered`, iOS `nw_path_is_expensive == true`), the UI displays an explicit warning before assembling or accepting the transfer:
  > *"Warning: You are on a cellular connection. Transferring vacation.mp4 (450 MB) may consume mobile data. Continue?"*
- **Explicit Offer Inspection:** Receiver must review item names, count, total size, and remote peer identity before confirming transfer. Auto-accept is strictly forbidden.

---

## Remote Transfer Threat Model Additions

The following threats are formally incorporated into [GLIDE_THREAT_MODEL.md](../security/GLIDE_THREAT_MODEL.md):

| ID | Threat | Likelihood | Impact | Mitigation | Implementation Requirement |
|:---|:---|:---:|:---:|:---|:---|
| **R01** | Unauthorized Remote Ingress / Spam | High | High | Ephemeral 120s rendezvous UUID; out-of-band pairing URI; explicit human offer consent. | No public user directory; unknown rendezvous IDs rejected. |
| **R02** | Signaling Relay Snooping / Traffic Analysis | Medium | Critical | All signaling payloads E2EE encrypted with X25519 ephemeral keys; relays see opaque ciphertext only. | Broker rejects unencrypted SDP/offer payloads; zero logging. |
| **R03** | Blind Relay Bandwidth Abuse / DoS | High | High | Cryptographic blind tokens, 100 MB relay transfer cap, IP connection rate limits. | Relay drops connections exceeding byte ceiling or connection quotas. |
| **R04** | MITM on Remote Wire | Medium | Critical | Mandatory TLS 1.3 or Noise IK mutual authenticated encryption; SPKI public key pinning. | Reject unauthenticated or downgraded cipher suites. |
| **R05** | Metered Data Bill Shock | High | Medium | OS metered connection detection; explicit user prompt with file size disclosure. | Query `NetworkCapabilities` / `nw_path_is_expensive` before transfer. |
| **R06** | Stale Remote Invitation Replay | Medium | High | Monotonic 120s TTL; one-use rendezvous consumption; state wiped on disconnect. | Broker purges rendezvous token after initial pairing. |
| **R07** | Remote Malware / Executable Delivery | Medium | High | Sandboxed quarantine staging; SHA-256 verification; no auto-execution or thumbnail auto-render. | Remote files quarantined identically to local LAN items. |
| **R08** | Cloud Dependency Creep | Medium | Critical | Hard isolation: Local LAN and Nearby P2P never touch signaling or relay servers. | Offline test assertions verifying zero remote socket connections during LAN sharing. |

---

## Consequences

- **Complete Cross-Platform Path:** Concludes the final step (Step 6) of [`CROSS_PLATFORM_PATH.md`](../future/CROSS_PLATFORM_PATH.md), providing a comprehensive, secure remote transfer model without sacrificing local-first privacy.
- **Zero Cloud Invariant Preserved:** Relays act purely as blind, stateless bit couriers; no user accounts, no central database, and no cloud file storage buckets.
- **Cost & Abuse Controlled:** Strict 100 MB relay quotas and rate limiting prevent infrastructure cost runaway while encouraging direct P2P or local LAN transfers for large files.
- **Local Autonomy:** Local LAN and Nearby P2P modes remain completely decoupled and continue to function in total isolation from the public internet.
