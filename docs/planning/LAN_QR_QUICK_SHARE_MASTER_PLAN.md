# Glide — Local LAN, QR Pairing and Quick Share Development Plan

Planning baseline: **2026-09-16**, GitHub main and local HEAD **6e1302f87d5681bc85788a0c1150241c91a12632**. Status: **implementation specification; no feature implemented by this planning run**. Scope: Android LAN/QR release, preserving Nearby and Glide Protocol V1. Owner: the implementation agent, with human approval of release/security gates.

This document is the authoritative next-cycle plan for this scope. Read [ADR-0018](../adr/ADR-0018-lan-qr-authentication-and-routing.md) with it. It overrides the identified LAN assumptions in ADR-0017 and the earlier Windows LAN proposal; it does not replace the working protocol, brand, or application architecture. Historical GLIDE-001–072 completion labels are not evidence of a working Android integration. New work uses LAN-001–033; status lives in section 26.

Navigate: [verified source](#2-verified-current-architecture) · [LAN contract](#6-lan-transport-design) · [QR contract](#8-qr-pairing-design) · [security](#11-security-model) · [phases](#17-implementation-phases) · [task checklist](#26-ordered-development-checklist) · [first task](#27-recommended-first-implementation-task).

## 1. Executive Summary

Extend the Kotlin Android application with one new adapter module, **:transport-lan**, using Android NSD for discovery and TLS 1.3 for a direct local connection. A QR code conveys a temporary, high-entropy connection invitation and the exact server key fingerprint. It bootstraps the same transfer engine; it is neither a file format nor a third transfer protocol. Keep Nearby Point-to-Point STREAM for supported Android devices, particularly where LAN is unavailable.

First close the existing integration gaps: the app currently renders discovery/transfer screens without constructing a production coordinator/transport path, and navigation drops the batch to its first source handle. Do not layer LAN over that incomplete composition. Prove the restored Nearby path on two devices early, then prove one file over authenticated LAN before adding unified discovery and polish.

Security decisions: untrusted mDNS never establishes identity; first-time LAN use requires QR/full invitation pairing; use platform TLS with exact out-of-band SPKI pinning and a one-use 256-bit bearer invitation inside TLS. No custom six-digit SAS, trust-all certificate manager, plaintext fallback, inferred name trust, or automatic receiving. Keep explicit transfer-offer acceptance. A discovered unpaired LAN device can appear in Quick Share but requires scanning its code before transfer.

Preserve SDK min 26 and target/compile 37. Initially gate LAN to **API 29+**, where Android supplies TLS 1.3. API 26–28 retain Nearby; do not silently lower LAN security or raise the whole app's minimum SDK. No new TLS provider, cloud, Windows application, desktop framework, identity service or resume system in this release.

Scope classes: **CORE REQUIRED** = secure working transfer and its evidence; **HIGH-VALUE QoL** = reliable selection, safe retry, honest progress and actionable recovery; **OPTIONAL** = conveniences after gates; **FUTURE** = persistent trust, real resume, other platforms and transports. P0 here means correctness/security prerequisite; P1 release requirement; P2 QoL; P3 future. These labels apply only to this plan, not the older P0–P5 ledger.

## 2. Verified Current Architecture

### Audit method and limits

Read-only git inspection found a clean worktree, main tracking origin/main, with origin https://github.com/GDJ2001/Glide.git. The live result of git ls-remote origin refs/heads/main matched local HEAD at the revision above. Inspected module/build configuration, manifest, runtime callers, navigation, permission/service ownership, core ports/codecs/reducer/coordinator, Nearby bridge, storage, representative behavioral and compliance tests, CI, existing ADRs and future specifications. No application build, emulator, physical transfer, release upload, or Play Console validation was performed in this planning run. Consequently this plan does not certify existing release claims or throughput numbers.

The following are **source facts**, not inferred delivery status. Source references use immutable GitHub links so future edits cannot change the audit evidence.

| ID | Verified fact | Evidence |
| --- | --- | --- |
| S01 | Six modules: app, transfer-core, transport-nearby, data, designsystem, benchmark; no transport-lan | [settings](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/settings.gradle.kts#L18) |
| S02 | Actual port is TransferTransport, with separate control, incoming streams and bulk progress; not TransportPort | [port](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/transfer-core/src/main/kotlin/dev/glide/core/ports/TransferTransport.kt#L61) |
| S03 | V1 uses strict UTF-8 JSON control envelopes and separate binary streams; 16 KiB control and depth 8 limits | [codec](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/transfer-core/src/main/kotlin/dev/glide/core/protocol/ProtocolCodec.kt#L27), [messages](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/transfer-core/src/main/kotlin/dev/glide/core/protocol/ProtocolMessages.kt#L25) |
| S04 | Nearby uses P2P_POINT_TO_POINT; adapter has connection-confirmation and payload gating | [config](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/transport-nearby/src/main/kotlin/dev/glide/transport/nearby/NearbyConfig.kt#L19), [composition](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/transport-nearby/src/main/kotlin/dev/glide/transport/nearby/NearbyTransferTransport.kt#L42) |
| S05 | Discovery ViewModel runs a UI timer; selecting a peer navigates to a synthetic send route. No app main-source construction of TransferCoordinator/NearbyTransferTransport found | [discovery](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/app/src/main/kotlin/dev/glide/app/ui/discovery/DiscoveryViewModel.kt#L24), [navigation](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/app/src/main/kotlin/dev/glide/app/navigation/GlideAppNavDisplay.kt#L146) |
| S06 | Navigation installs the single-source selection callback; selection falls back to the first valid handle. Pairing state defaults to Idle | [selection callback](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/app/src/main/kotlin/dev/glide/app/navigation/GlideAppNavDisplay.kt#L133), [batch fallback](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/app/src/main/kotlin/dev/glide/app/ui/selection/SelectionScreen.kt#L593), [pairing](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/app/src/main/kotlin/dev/glide/app/navigation/GlideAppNavDisplay.kt#L203) |
| S07 | Session manager exists, but ViewModel binding uses viewModelScope; service attaches to an already registered coordinator. Same-session start returns before adopting the new scope | [binding](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/app/src/main/kotlin/dev/glide/app/ui/transfer/TransferViewModel.kt#L157), [manager](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/app/src/main/kotlin/dev/glide/app/service/TransferSessionManager.kt#L47), [service](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/app/src/main/kotlin/dev/glide/app/service/TransferService.kt#L54) |
| S08 | Coordinator checks peer ID and sequencing but does not compare envelope sessionId at the inspected inbound dispatch. Incoming bulk can fall back from an unmatched bulkRef to currentItemState | [control](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/transfer-core/src/main/kotlin/dev/glide/core/coordinator/TransferCoordinator.kt#L379), [stream](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/transfer-core/src/main/kotlin/dev/glide/core/coordinator/TransferCoordinator.kt#L549) |
| S09 | Receiver stages by item ID, independently rehashes disk bytes, journals and renames to a random final key; DI supplies filesDir, despite a comment saying noBackupFilesDir | [store](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/data/src/main/kotlin/dev/glide/data/store/AndroidReceiveStore.kt#L135), [DI](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/app/src/main/kotlin/dev/glide/app/di/DatabaseModule.kt#L94) |
| S10 | Manifest has connectedDevice FGS, ACCESS_LOCAL_NETWORK, nonexported FileProvider and disabled backup; runtime policy currently groups all nearby permissions | [manifest](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/app/src/main/AndroidManifest.xml#L1), [policy](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/app/src/main/kotlin/dev/glide/app/permission/PermissionPolicy.kt#L54) |
| S11 | LAN helper has only record/frame/path helpers; capability default 0x07 and short fingerprint are future assumptions | [LAN contracts](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/transfer-core/src/main/kotlin/dev/glide/core/transport/lan/LanTransportContracts.kt#L6) |
| S12 | TransportSelectionEngine is a static feasibility/tier helper, with speculative speed/hardware ratios; it does not authenticate or probe endpoints | [profiles and selector](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/transfer-core/src/main/kotlin/dev/glide/core/transport/TransportCapability.kt#L48) |
| S13 | LAN compliance tests partly check document strings; pure framing tests do not exercise TLS/NSD. Existing fake-SDK and storage tests do exercise component behavior | [LAN compliance](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/app/src/test/kotlin/dev/glide/app/future/AndroidWindowsLanComplianceTest.kt#L38), [Nearby tests](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/transport-nearby/src/test/kotlin/dev/glide/transport/nearby/NearbyConnectionManagerTest.kt#L78), [storage tests](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/data/src/test/kotlin/dev/glide/data/store/AndroidReceiveStoreTest.kt#L184) |
| S14 | CI assembles, runs JVM/unit tests and lint; no device job. checkArchitecture delegates to app unit tests | [CI](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/.github/workflows/ci.yml#L18), [root task](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/build.gradle.kts#L12) |
| S15 | Coordinator opens a source during preparation and another for hashing; Nearby can transmit the first prepared stream instead. Expected incoming ref is registered before reducer validation | [preparation and registration](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/transfer-core/src/main/kotlin/dev/glide/core/coordinator/TransferCoordinator.kt#L463), [hashing stream](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/transfer-core/src/main/kotlin/dev/glide/core/coordinator/TransferCoordinator.kt#L297), [prepared payload](https://github.com/GDJ2001/Glide/blob/6e1302f87d5681bc85788a0c1150241c91a12632/transport-nearby/src/main/kotlin/dev/glide/transport/nearby/NearbyPayloadBridge.kt#L204) |

Existing language/toolchain: Kotlin application/core, Compose/Material 3, Navigation 3, coroutines/Flow, Hilt, Room, DataStore and Gradle Kotlin DSL. Inspected version catalog declares AGP 9.4.0, Kotlin 2.4.20, Compose BOM 2026.09.00, Nearby 19.5.0; wrapper is 9.6.1. These are **repository declarations**, not newly validated compatibility recommendations. Keep them unless a reproducible build failure requires a separately documented change. Current compile/target 37, min 26, Java 17 are in app/build.gradle.kts.

Contradictions to avoid copying: the GLIDE-068 closure report describes a GLID header/eight binary message types, unlike the actual JSON protocol; the Windows proposal names a nonexistent TransportPort, gives an unreviewed SAS formula, suggests automatic Wi-Fi Direct fallback although no such adapter is included, and makes unmeasured throughput claims. Treat historical reports as reported claims; do not delete them or use their hashes/statuses as fresh release evidence.

## 3. Repository Components Involved

| Component | Reuse | Planned responsibility |
| --- | --- | --- |
| :transfer-core ports/domain/protocol/coordinator | Preserve V1 codec, reducer, item/batch receipts and SHA-256 semantics | Correct exact session/stream binding; add pure discovery/routing/bootstrap models; no Android, sockets or camera imports |
| :transport-nearby | Preserve SDK verification and STREAM bridge | Implement any additive verification-result port change; regression adapter for router |
| New :transport-lan | Only new Gradle module | Network-scoped NSD, TLS listener/client, pinned trust, invitation authority, framing and bounded streams |
| :app DI/service/navigation/ViewModels | Preserve Compose screens and theme | Compose real session factory; permissions, selected batches, unified peer list, QR UI and session-owned lifecycle |
| :data | Preserve source access, private quarantine/catalog, Room and export | Route-independent metadata/history; verified-file actions and recovery |
| :designsystem / asset | Preserve accepted logo, tokens, adaptive components, motion | Reuse components; add only necessary route/status/scanner presentation, no logo replacement |
| :benchmark / tests / CI | Preserve valid existing coverage | Add real transport and composition checks, device run sheets, reproducible performance comparisons |

Do not introduce a feature module for every screen, a parallel LAN database, HTTP server framework, second transfer coordinator, desktop project, or general plugin system. Detailed changes and tests are in section 18.

## 4. Gap Analysis

| Area | State at audited revision | Required delta / gate |
| --- | --- | --- |
| V1 codec, reducer, bounded hash/stream logic | ALREADY IMPLEMENTED as components | Preserve fixtures; prove exact-session and exact-bulk matching before additional adapters |
| Prepared source versus hashed source / receive readiness | NEEDS MODIFICATION | LAN-002 makes the transmitted stream the single counted/hashed stream and delays stream authority until validated readiness |
| Nearby adapter and SAS UI | PARTIALLY IMPLEMENTED end to end | Wire real discovery, authentication and coordinator; two-device evidence |
| Selection, history, settings, export, Share Sheet | PARTIALLY IMPLEMENTED integration | Preserve all selected handles, connect ingress to real transfer, record receipts, verify result actions |
| Foreground service ownership | NEEDS MODIFICATION | One service-owned session scope from construction; no ViewModel-to-service scope transfer illusion |
| LAN sockets/TLS/discovery | MISSING runtime | New adapter with per-network lifecycle and authentication gate |
| LAN framing helpers | NEEDS MODIFICATION | Typed record framing distinguishes control and data without breaking V1 JSON |
| Device identity/capability routing | PARTIALLY IMPLEMENTED concepts | Evidence-based observed peers; no trusted 8-hex prefix or default future flags |
| QR generate/scan/bootstrap | MISSING | Offline encoder/scanner, strict payload, invitation authority, pin check, expiry/replay tests |
| Persistent identity/true resume/folders | FUTURE contracts/helpers | No release capability advertisement; no implementation in this scope |
| Unified Quick Share | MISSING working aggregation | One peer surface and central route choice, exact attempt ownership |
| Permission and background UX | NEEDS MODIFICATION | Per-action policy; LAN works without Bluetooth permissions; camera only when scanning |
| Physical/production proof | NOT REVERIFIED | New tests on the actual change SHA; text compliance cannot satisfy transfer/security gates |

## 5. Target Architecture

~~~mermaid
flowchart TD
  UI[Existing Compose UI: Send / Receive / Quick Share] --> VM[ViewModels: immutable state and actions]
  VM --> DISC[Unified discovery repository]
  DISC --> ND[Nearby observations]
  DISC --> MD[mDNS observations]
  DISC --> QR[Validated QR invitation]
  VM --> START[Session factory and transport router]
  DISC --> START
  START --> AUTH[Verified link / invitation authentication]
  AUTH --> OWNER[Service-owned active session]
  OWNER --> CORE[Existing coordinator + Glide Protocol V1]
  CORE --> PORT[TransferTransport]
  PORT --> NEAR[Nearby STREAM]
  PORT --> LAN[LAN TLS records]
  CORE --> DATA[Existing source access / quarantine / history]
~~~

Discovery, connection/authentication, and transfer are separate state machines. A QR scan creates a route candidate with an out-of-band trust anchor. It cannot create transfer success, bypass offer consent, or inject a source URI. Router selects a route **before** the coordinator runs. The chosen adapter is fixed for the session.

New abstractions must solve a concrete boundary: discovery repository aggregates changing observations; router is a pure policy over evidence; session factory binds one authenticated connection to core; LAN adapter owns network resources. Keep Android Network objects in an adapter-owned map addressed by opaque handles. No core android.net.Network or Nearby endpoint type.

## 6. LAN Transport Design

### Discovery and network scope

Use platform NsdManager DNS-SD, not a custom multicast implementation. DNS-SD service type is **_glide._tcp** at the Android API; the full DNS name is _glide._tcp.local. Use a separate **_glide-debug._tcp** for debug builds. Bind the listener to a selected Wi-Fi/Ethernet local interface on port 0, then advertise its assigned port. Do not assume port 42424 or scan address ranges.

TXT contract for this release: v=1 (Glide protocol); tv=1 (LAN record profile); q=1 (invitation pairing required); p=<random 128-bit availability-presence ID, 32 lowercase hex>; dn=<bounded display name, at most 64 Unicode code points and 128 UTF-8 bytes>; dt=phone|tablet|desktop|unknown. Each DNS-SD TXT entry must fit its format limit; Glide's total TXT budget is 512 bytes. Unknown keys are ignored within that budget. Do not publish tokens, file names, permanent identifiers, full key fingerprints or unsupported folder/resume bits. Presence ID is a correlation hint, not authentication. Refresh it on availability lease restart.

Use NetworkCallback and LinkProperties to track eligible Wi-Fi/Ethernet networks. Internet validation is **not** a prerequisite for local-only Wi-Fi. Bind outbound raw sockets to the selected Network before connect; wrap that connected socket in TLS. Bind listeners to an address belonging to that interface, never a wildcard covering cellular/VPN unintentionally. On older NSD APIs without per-network association, resolve on the selected route and verify the address is on-link before use. API 34+ ServiceInfoCallback supports continuing updates; older APIs use serialized resolve calls with bounded queue/cancellation. Use SDK guards, not reflection or assumed extension availability.

Release VPN policy: if a VPN applicable to the app is active, or route authority is ambiguous, disable LAN browsing/listening/connecting and explain that the network policy prevents a local route. Do not bind around the VPN through an underlying Wi-Fi Network. Observe VPN activation as an invalidating network change. A future explicit VPN-compatible route mode needs its own review; this release does not infer split-tunnel permission. For every accepted TCP socket, independently check the remote address against the selected interface's directly connected routes before TLS; binding a listener address alone does not enforce local-client scope. Revalidate network generation at bootstrap. Test routed off-link inbound clients and IPv4-mapped IPv6 normalization as well as outbound targets.

IPv4 and IPv6: retain multiple addresses and network association, including IPv6 link-local scope. Locally choose the scope/interface; never trust an interface index supplied by a peer. Accept unicast on-link IPv4, ULA or on-link global IPv6, and correctly scoped link-local IPv6. Reject unspecified, loopback, multicast, broadcast, cellular/VPN destinations, and addresses outside the selected network's directly connected routes. Do not use only RFC1918 as the definition of local. Test IPv6-only networks. Try at most two address candidates sequentially under the attempt deadline; no subnet probing or racing token-consuming authentication.

Browse for 60 seconds per explicit Send action; Retry restarts it. Receive availability lasts up to 10 minutes while foreground, with visible renewal. Stop browse/advertisement when leaving the flow or entering background before a session; an active foreground-service session may continue without discovery. Max 64 cached observations, 4 resolutions in flight, 5-second resolve deadline. Service-lost removes only that observation; endpoint updates use a new generation so late callbacks cannot resurrect old routes. Do not prune a healthy service solely because it has not changed its TXT record. On a selected attempt, resolve/probe afresh; availability remains advisory.

QR can bypass blocked multicast, **not AP client isolation or an unreachable network**. Manual hotspot creation, Wi-Fi Direct and Wi-Fi Aware remain future work. A user-established hotspot is eligible only when the OS exposes a suitable reachable interface; otherwise explain the limitation. Never silently disconnect Wi-Fi, bypass VPN policy, or request cellular for LAN. NSD should not need an application MulticastLock; add a bounded lock/permission only if an actual measured adapter requirement is demonstrated.

### Listener, TLS and authenticated-link gate

Platform SSLContext/SSLSocket, TLSv1.3 only, ALPN **glide-lan/1**, one connection for both control and data. Reject missing/mismatched ALPN, TLS 1.2/plaintext, invalid certificate profile or pin mismatch. Use the platform's TLS 1.3 cipher negotiation; do not claim individual TLS 1.3 cipher disabling works on Android. No 0-RTT application data. Fresh invitation is always required even if the TLS provider internally resumes a connection.

Create an EC P-256 signing key/certificate for each Receive availability lease using Android Keystore. It is temporary identity, not persistent device trust. Use a unique alias, non-biometric signing suitable for an active transfer, valid ECDSA/SHA-256 self-signature, random positive 128-bit serial, generic subject CN=Glide temporary peer and validity from creation minus 5 minutes to creation plus 15 minutes. Do not embed a device name, IP or permanent ID in the certificate. Reject unsupported critical extensions; if KeyUsage is present it must allow digitalSignature, and if EKU is present it must allow serverAuth. Keystore-generated extension behavior must be validated on supported devices; do not assert an unavailable extension setter exists. Wall-clock certificate failures get a clock-correction message; invitation expiry still uses the server's monotonic clock. Delete the key on lease end once no active connection needs it; sweep abandoned Glide lease aliases after process death. Do not demand hardware backing when unavailable, and never claim all keys are hardware-backed. Key-generation/OEM compatibility is a physical-device gate, not an excuse to fall back to plaintext.

Client trust is the exact **SHA-256 of DER SubjectPublicKeyInfo** received through QR/full invitation. A per-attempt X509 trust implementation must require a single expected self-signed EC P-256 leaf, valid signature/profile, current validity, supported critical extensions, and the exact 32-byte pin before any application bytes leave the client. Trust cannot be established from TXT or from a certificate fetched on that same unverified network. There is no hostname/CA identity in this private pin profile; its identity is explicitly the out-of-band key. Do not disable validation globally or install a trust-all manager and compare afterward.

Server-authenticated TLS plus the one-use invitation authenticates **possession of an invitation** on the client side. This release does not claim mutual certificate authentication or a persistent sender identity. Receiver still approves the offer. Copying the invitation transfers that temporary authority; warn on the explicit Copy action, and do not auto-copy it. If persistent authenticated peers are later needed, define a separate reviewed mTLS/trust profile.

Listener limits: 2 concurrent pre-auth handshakes, 1 authenticated transfer, 5-second TCP connect and 10-second TLS/bootstrap deadlines (20-second total attempt budget), 5 authentication failures/minute per remote address and 20/minute overall, 30-second cooldown on excess. Rate limits reduce abuse but cannot eliminate local-network denial of service. Reject before opening files or growing metadata. Never advertise a listener that failed bind/TLS initialization.

### Transport records: exact profile 1

TCP packet boundaries are irrelevant. The encrypted stream is a sequence of records: **kind:u8 | payloadLength:u32 big-endian | payload**. ReadExactly handles arbitrary fragmentation/coalescing; validate kind-specific length before allocating. Maximum payload 65,536 bytes. Unknown kind, zero/oversized forbidden length, truncated header/body, invalid ordering or extra bytes after a stream end fails the connection. These records belong to the LAN adapter and do not change Glide JSON message bodies.

| Kind | Payload and bound | When valid |
| --- | --- | --- |
| 0x01 BOOTSTRAP | Strict JSON object, 1..2,048 bytes, depth <=4 | Client's first record, after TLS validation |
| 0x02 BOOTSTRAP_RESULT | Strict JSON object, 1..512 bytes | Server's one bootstrap response |
| 0x10 CONTROL | Existing ProtocolCodec UTF-8 envelope, 1..16,384 bytes | After bootstrap success only |
| 0x20 DATA | bulkRef UUID raw 16 bytes, then 1..65,520 file bytes | Expected item after ITEM_READY only |
| 0x21 STREAM_END | bulkRef UUID raw 16 bytes + actualBytes unsigned 64-bit big-endian (24 bytes total) | Once after all DATA, including zero-byte files |

In this LAN profile bulkRef is a generated UUIDv4 rendered as the existing opaque string in ITEM_START. Binary UUID order is the normal network byte order, not Windows GUID mixed-endian layout. Reject counts above the offered length or protocol ceiling before converting to signed Long. No seek offsets, application chunk ACKs or retransmission protocol; TCP/TLS supplies ordered delivery. Existing LanFrameHeader describes an older four-byte control-only helper; migrate its tests/contracts deliberately, do not silently reinterpret deployed bytes. No implemented LAN peer was found at the baseline.

Sender: prepareStream allocates the UUID and takes the one counted/hashed source described below; ITEM_START maps it to the item; only a reducer-validated ITEM_READY unlocks DATA. A single writer serializes records and prioritizes bounded controls between DATA records. After exactly the offered count, perform a bounded extra-byte read to prove actual source EOF, including when the offered size is zero. Early EOF or any extra source byte fails without STREAM_END or completion. Only exact count plus actual EOF permits STREAM_END to be written/flushed; local BulkProgress completion then enables ITEM_END with the same stream's digest. Never mark delivery verified because a socket write succeeded. Pace outgoing V1 controls within the existing 20/second sustained, burst-40 limit so many small items do not trigger legitimate-peer rate rejection; DATA records are not V1 controls.

**Prepared source ownership, required before the Nearby baseline:** core opens each item once on IO after the relevant reducer-authorized prepare effect, wraps it once with exact-size/counting/SHA-256 behavior, and retains that wrapper's result. Both adapters prepare and later transmit that same wrapper. Change the local port to require prepareStream(provider) followed by sendStream(peerId, bulkRef), with no second source-provider fallback on send. Nearby prepares its SDK payload from that wrapper; LAN stores it under its UUID. Preparation must not read file bytes before ITEM_READY. No independent reopen in startSendingStream. The adapter owns transmitting/disposal; the wrapper's close is idempotent so owner cleanup closes the underlying resource exactly once. Failed prepare/send/cancel/peer loss closes the prepared wrapper. Cache the final digest once, available only after actual EOF; do not call MessageDigest.digest repeatedly and reset it accidentally. A provider returning different bytes on successive opens must still be opened once and produce the digest of the actual transmitted bytes. New preparation or authority side effects must follow reducer validation, not merely successful DTO decoding.

Receiver: an ITEM_START DTO is only a proposal. First validate it through the reducer against the accepted offer, create/open staging successfully, then install the exact session/item/bulkRef/size expectation and perform the ITEM_READY transition. The serialized adapter actor enables that expectation immediately before enqueueing ITEM_READY, so fast valid DATA cannot race missing registration; before this readiness transition DATA is rejected. Do not call registerExpectedStream directly on unvalidated DTO receipt as the current source does. First DATA (or STREAM_END for an empty file) creates one bounded incoming InputStream. STREAM_END with the exact byte count ends that stream and emits transport completion; core still requires stream EOF, ITEM_END digest, disk verification and commit. Socket EOF alone is interruption. The adapter must deliver terminal stream/progress once and ignore stale generation callbacks. A second stream, unknown bulkRef, pre-consent DATA, repeated end or byte overflow is rejected and closed.

Use a maximum 4 x 64 KiB bounded bulk queue, one 64 KiB I/O buffer per active direction, and a bounded 40-entry control queue. Backpressure suspends production. A stalled storage consumer may stop reads; local Cancel closes the socket and wakes every queue/stream immediately. Control prioritization does not make a blocked disk or full TCP receive window disappear: enforce the stall deadline and test cancellation under backpressure. Do not spawn a coroutine per DATA record or expose network frames to Compose.

### Lifecycle and failures

Connection attempt state: Observed → Resolving → NeedsPairing or Connecting → TLSVerified → InvitationAuthenticated → Connected. Failure/cancel is terminal for that attempt. Thereafter use existing SessionPhase: Negotiating → Offering/AwaitingConsent → PreparingItem → TransferringItem → VerifyingItem/AwaitingReceipt → Completed or DeliveryUnconfirmed/Failed/Cancelled/Interrupted. Preserve exact reducer transitions; do not flatten verification into 100% progress.

One session owns socket, writer, reader, stream queue, timeouts and coroutine children. Every close path must be idempotent. Network loss invalidates addresses/invitation, interrupts the session and cleans unfinished data; retries create a new session and resend only unverified items from zero. No socket migration or mid-file switch. Start with 15-second stalled-progress warning, 60-second stall failure, 120-second offer timeout, and 30-second negotiation timeout; final disk verification uses its own liveness watchdog rather than a total size-independent 30-second deadline. Use monotonic clocks for all durations. Keep absolute wall time only for history/display.

## 7. Peer Identity & Capability Design

Do not stretch PeerId into a permanent identity. Retain it as an adapter/connection-scoped identifier. Add pure models with explicit meaning:

| Model | Required fields / invariant |
| --- | --- |
| PeerObservation | observationId, source Nearby/LAN/QR, bounded label, declared platform, endpoint handle, network handle, generation, observation time, advertised capabilities; all untrusted until authenticated |
| PeerCandidate | UI key, list of observations/routes, verification status, availability; never keyed by name/IP alone |
| RouteCandidate | transport type, protocol/profile versions, endpoint/network, permissions/availability, trust evidence, expiry, last probe result; no socket in UI |
| VerifiedPeerBinding | full SPKI hash for LAN lease or Nearby-authenticated connection ID, verification method, lease/generation, local proof timestamp; no claim of permanent identity |
| SelectedRoute | immutable attempt ID, exact adapter, peer ID and network generation; selected once |
| SessionContext | agreed session UUID, attempt ID, authenticated link, sender/receiver roles, negotiated limits and capabilities |

Deduplication rules: merge repeated NSD updates for the same service instance + network; merge QR and its LAN observation only after validating the advertised endpoint against the invitation pin and successful bootstrap. Same full key can merge proven LAN endpoints within one lease. Never merge by label, IP alone, p, device type or an eight-character fingerprint. A name collision stays two distinct candidates. Losing one observation does not delete another valid route.

Nearby and LAN **cannot be securely auto-merged before a common identity proof** at this baseline. Present them in one list with route subtitles; occasional separate cards are preferable to sending to the wrong device. After a future negotiated channel-binding extension proves ownership of the same LAN key through an authenticated Nearby channel and a pinned LAN handshake, merging may occur for that lease. That extension/persistent trust is P3, not a hidden requirement that delays the core release. Document this deliberate UX limit.

Capabilities have three levels: advertised (hint), locally implemented (registry), and authenticated/negotiated (authority). Existing V1 required string capabilities stream-v1 and sha256 remain. Folder/resume/persistent identity flags are false/absent until implemented with conformance evidence. Do not use 0x07 defaults or nominal Mbps/hardwareAvailabilityRatio as live routing evidence. Reject incompatible major/required capability with an update message; optional unknown capabilities cannot enable behavior.

## 8. QR Pairing Design

### Exact invitation profile

Encoding is **glide://pair/v1#<unpadded-base64url(UTF-8 JSON)>**. This is an app-scanned/pasted format; no web request, HTTP redirect, automatic ACTION_VIEW handler or internet URL fetch. Decoded JSON <=2,048 bytes; raw URI <=2,768 ASCII characters; depth <=4; duplicate keys, noncanonical base64url, invalid UTF-8, extra fields or unsupported v are rejected. Encode deterministic compact JSON for fixtures; hash the actual decoded bytes, not a reserialized object.

~~~json
{
  "v": 1,
  "transport": "lan-tls",
  "profile": 1,
  "protocol": 1,
  "invitationId": "00000000-0000-4000-8000-000000000001",
  "presence": "00112233445566778899aabbccddeeff",
  "name": "Glide device",
  "platform": "android",
  "endpoints": [{"address": "192.168.1.24", "port": 49152}],
  "spkiSha256": "<64 lowercase hexadecimal characters>",
  "token": "<43 base64url characters encoding 32 random bytes>",
  "expiresAt": "<Unix epoch seconds as a decimal string>",
  "ttlSeconds": 120
}
~~~

This is a schema example with deliberately invalid placeholders, not a usable credential. invitationId is CSPRNG UUIDv4; presence is 16 random bytes; token is 32 independently generated SecureRandom bytes. Names have the same display bound as peer observations. Platform is android|windows|macos|ios|linux|web|unknown and is only a declaration. endpoints contains 1..4 unique numeric IP/port pairs, no hostname, URI, path, percent-zone, credentials or arbitrary schemes. Port is 1..65535. Prefer one IPv4 and one usable IPv6 endpoint to keep the displayed QR compact; target <=900 decoded bytes. Do not truncate a key/token to fit a QR.

The receiver creates the invitation only after listener readiness and valid selected-network permission. It keeps the exact decoded byte hash, hashed token, invitation ID, key pin, network/listener generation and monotonic expiry in memory. QR countdown is 120 seconds; refresh rotates ID/token immediately, revokes the previous invitation, and updates the display. Stop Receive, background before connection, network/address change, or process death revokes all outstanding invitations. Keystore lease key may stay until the availability lease ends. Expiry affects new bootstrap, not an already authorized active file transfer.

### Bootstrap over pinned TLS

Client validates structure/local endpoint scope first, shows the claimed name and selected connection to the user, then connects only on explicit Continue. It verifies the TLS pin before sending the token. BOOTSTRAP JSON has exactly: v=1, invitationId, token, invitationHash (SHA-256 hex of decoded QR bytes), sessionId (new sender UUIDv4), role="sender". It carries no file names or paths. A server accepts only a live invitation on its bound listener/network generation, constant-time token hash match, matching invitationHash, valid session ID, no active transfer, and correct role.

Under a mutex, the first valid attempt consumes the invitation and binds it to that TLS connection and agreed session ID. Server returns BOOTSTRAP_RESULT {v:1,status:"accepted",sessionId:<same UUID>}. Failures return {v:1,status:"rejected",code:<catalog>} with code AUTHENTICATION_FAILED, INVITATION_EXPIRED, INVITATION_USED, BUSY or UNSUPPORTED_PROFILE; no token detail. Before authentication, prefer generic AUTHENTICATION_FAILED when a more precise response would disclose an invitation's existence. Reconnect after a lost bootstrap response requires a new invitation; never unconsume/reissue the old token. No token is accepted twice, even for the same session ID.

Only after the success response do both sides expose ConnectionEvent.Connected and start normal HELLO. Receiver uses the bootstrap-agreed session ID. For Nearby, the authenticated receiver bootstrap dispatcher must adopt the first valid HELLO session ID once, then bind it immutably; this is a transport-neutral composition concern missing from the current navigation.

There is no custom QR signature scheme. Pinning authenticates the server reached from the trusted screen; the invitationHash comparison detects altered fields when talking to that original server. A completely replaced code can invite the user to a different malicious server: scanning the intended device's displayed code and confirming the offer is the trust assumption. The app must never describe a random downloaded QR as inherently safe. Screenshots or shared invitation text leak temporary invitation possession; explicit Copy explains that risk. Do not persist QR payloads, include them in diagnostics/history, expose them through notifications, or save them through state restoration.

### UI and scanner implementation choice

Receive shows Show QR, remaining lifetime, Refresh and Stop receiving. Scan has permission education, camera preview, viewfinder, flashlight when present, autofocus/retry, invalid-code, expired-code, unavailable-network and cancel states. After one valid decode, stop the analyzer and require confirmation; duplicate frames must not start repeated connections. Stop camera on background/navigation; no camera foreground service. TalkBack labels/actions, high-contrast quiet zone and enlarged QR are mandatory. Offer **Paste invitation** as an in-app accessible camera-free alternative with identical validation; clipboard is read only on an explicit paste gesture and marked sensitive when copying.

Chosen dependency proposal: **CameraX 1.6.2** camera-core/camera-camera2/camera-lifecycle/camera-view and **ZXing core 3.5.4** for both local QR encoding/decoding. No remote model download or Google Play Services required for LAN QR. Restrict decoder to QR_CODE, bound image resolution/analysis rate, and use ImageAnalysis KEEP_ONLY_LATEST with ImageProxy closed in finally. ZXing is a mature maintenance-mode library: measure focus/low-light performance; if inadequate, bundled ML Kit barcode-scanning 17.3.0 is the reviewed alternative, not an additional default SDK. Google Code Scanner 16.1.0 avoids an app camera permission but may need a Play Services module download; rejected as the sole offline/no-GMS path. Revalidate artifact availability/license/security at LAN-017; do not upgrade the existing unrelated dependency stack. Sources are in section 23.

## 9. Unified Quick Share Design

“Quick Share” here is Glide's unified sharing flow, not Google's Quick Share service or branding. Keep Glide's existing visual system and Home/Send/Receive structure. Send selects files, then one peer list combines permitted Nearby observations, LAN services, and a Scan QR/Paste invitation entry. Cards show the bounded name, generic declared device icon, availability and a quiet route subtitle: Nearby, Local network, or Scan to connect. Never show “trusted” from discovery metadata.

Sending: preserve the complete selected TransferBatch and ItemId→SourceHandle map in a session preparation owner; navigation carries an opaque selection ID, not secret tokens or large URI arrays. Tapping a candidate freezes selection and creates one attempt; duplicate taps are ignored. Nearby follows the existing SDK verification UI; LAN requires a valid invitation. After authentication the same offer/consent/progress/results UI runs for both.

Receiving: user explicitly becomes available, sees LAN/Nearby availability independently, and may display a QR. Incoming peer label is unverified display metadata. Show complete batch count/total, file list with bounded thumbnails only where safe, destination/private storage behavior, Accept and Reject. Authentication never implies acceptance. One active transfer; additional requests receive Busy without repeated modal spam.

Empty states distinguish no peers yet, local-network permission denied, no eligible network, Bluetooth unavailable for Nearby, LAN unsupported below API 29, incompatible peer, multicast blocked and possible network isolation. After 10 seconds show Scan QR and a compact troubleshooting link; at browse timeout retain known candidates with recheck/retry. Do not claim to diagnose AP isolation purely from a timeout.

Progress screen shows route, current file, overall bytes, status, smoothed rate/ETA when meaningful and Cancel. 100% sent/received becomes Verifying until verified receipts arrive. Show Delivery unconfirmed when final acknowledgement is lost, preserving receiver-owned verified files. Retry explains that unfinished items restart; no Pause/Resume button. Result screen supports every verified item, not only lastCommittedFileHandle.

Changed controls have at least 48 dp touch targets and visible keyboard focus. Verify contrast at least 4.5:1 for ordinary text and 3:1 for large text/meaningful controls, including dynamic color. Test RTL, magnification, 200% font scale and narrow split-screen widths without clipped acceptance/cancel actions. TalkBack announces phase changes and coarse overall progress, not the five-per-second visual updates: at most one progress announcement per five seconds, on a new 10% boundary. Status has text/icon semantics as well as color. Reuse existing motion and haptics; a single subtle signal for QR detection/connection/success may be used, but honor system settings and suppress repeated decode/device-found vibration. Pairing/consent remain usable with animations disabled.

## 10. Transport Selection Strategy

Create one pure router beside the existing transport policy concepts. UI supplies selected peer/explicit mode; adapters supply current evidence. Order filters: implemented adapter → API/GMS support → user policy/permission → compatible protocol/profile → fresh route/network → authenticated bootstrap availability → bounded reachability attempt. Discovery alone never authorizes transfer.

| Situation | Selection / fallback |
| --- | --- |
| User scanned/pasted a LAN invitation | Only that pinned LAN route; no silent Nearby fallback to a same-name peer |
| Android Nearby candidate with no proven LAN binding | Nearby with full SDK verification |
| LAN-only or future desktop candidate | LAN, require invitation if absent |
| Multiple proven LAN endpoints in the same invitation | Try at most two on the chosen local network within deadline; pin/token identical |
| Both routes proved to one peer in a future identity extension | Prefer explicit choice, then a fresh authenticated reachable LAN route; Nearby next; this branch disabled until proof is implemented |
| Pre-transfer unavailable/timeout | Offer another **verified same-peer** route or return to peer selection; never reroute by label |
| Authentication mismatch, rejected consent, protocol downgrade | Terminal; never turn into automatic fallback |
| File transfer started | Fixed route; disconnect means interrupted, new explicit session for retry |

Do not start both transport transfers speculatively, rank by undocumented nominal Mbps, or use repeated probes to consume one-use invitations. During an active LAN session suspend Nearby discovery/advertising if it can disturb radio connectivity; test this behavior across OEMs. Stop unused adapters and release their resources deterministically.

## 11. Security Model

### Source-grounded threat model

**Summary:** this extends a local, user-driven Android transfer system across a new hostile LAN boundary. Existing core and storage controls are reusable components; runtime composition must be completed before treating them as application guarantees (S05–S09).

**Assets:** selected source bytes and URI grants; private partial/verified received files; offer consent; exact session/stream ownership; receipt/history truth; ephemeral TLS private keys; one-use invitation secrets; device labels/network metadata and battery/storage resources.

**Trust boundaries:** external app/provider → selection; unauthenticated NSD/QR → candidate; local network → TLS trust validator; authenticated invitation holder → explicit offer consent; bounded protocol → private receive store; verified catalog → user-requested external export; UI lifecycle → service-owned session. Actual storage roots are app cache staging_sources and filesDir/receiving plus filesDir/received, with backup exclusions—not automatically noBackupFilesDir.

**Attacker capabilities:** LAN peers can spoof advertisements/names/IPs, send malformed/slow connections, observe discovery and replay leaked invitations. A malicious authorized sender can choose hostile metadata/content and interrupt sessions. Other apps can invoke exported share ingress or provide hostile content URIs. Device-root compromise and a malicious physical recipient are outside the app's confidentiality guarantee; no claim prevents recipients copying accepted files.

**Security objectives:** authenticate before exposing metadata; bind all events to connection/session/item; preserve explicit consent; bound time/memory/disk; never publish unverified files; reject downgrade/replay; keep credentials out of persisted state/logs; fail safely without breaking Nearby.

**Assumptions:** users scan the intended screen or obtain invitation text through a trusted exchange; Android TLS/Keystore and app sandbox work as documented; selected network routes are locally connected; source providers may be untrusted; no persistent identity exists yet; no evidence in this run establishes real production deployment. No SECURITY.md policy was found. Future specifications and test names are not proof of controls.

| Threat / scenario | Priority; likelihood / impact | Requirement and test | Residual risk |
| --- | --- | --- | --- |
| LAN impersonator advertises another device's name/presence | P0; high/high | TXT untrusted; exact QR SPKI; no name merge. Spoof same-name and pin-prefix cases | Users can scan the wrong device |
| MITM terminates two TLS connections | P0; medium/critical | Pin checked inside handshake; reject wrong/self-generated cert, TLS 1.2 and missing ALPN before token/control | Compromised trusted screen/device |
| QR replay/race/refresh replay | P0; high/high | Atomic one-use consumption, monotonic TTL and listener generation; simultaneous two-client/old-code tests | Leaked live bearer can win race and cause denial of service; consent still required |
| QR address injection / private-network probing | P0; high/high | Numeric on-link unicast only, explicit Continue, no DNS/redirect, <=2 attempted addresses | A malicious scanned invitation can direct a bounded local connection |
| Altered QR fields / clock rollback | P0; medium/high | Compare stored decoded-payload hash; server monotonic expiry authoritative; future/past clock tests | Completely substituted code is outside original invitation trust |
| Stale-session control or unsolicited bulk | P0; medium/critical | LAN-001 exact session/bulkRef and generation guard before reducer/storage; reject wrong-session valid JSON | Bug in future adapter; retain core defense |
| Oversized JSON/frame/name / slowloris / spam | P0; high/high | Validate lengths before allocate; depth/rate/handshake limits; bounded queues; fuzz and slow reads | LAN saturation cannot be prevented by Glide |
| Path traversal / Unicode spoof / executable MIME | P0; high/high | Random storage keys; remote names only metadata; sanitize display; never auto-open. Hostile names/MIME tests | Recipient can deliberately open malicious content |
| Disk exhaustion / partial leak | P0; medium/high | Offer space reservation, checked counters, private quarantine, fail/cleanup/journal recovery; low-disk and kill tests | Storage can be consumed by other apps after preflight |
| Fake completion / digest manipulation | P0; medium/critical | Retain streaming + independent disk SHA-256 and exact byte receipts; wrong hash/length, EOF without end, lost final receipt tests | Digest does not make malware safe |
| Notification/clipboard/log disclosure | P1; medium/high | Generic lock-screen text; no token/pin/address/file names in normal logs; explicit sensitive copy; audit reports | Explicit sharing/screen photography leaks invitation |
| Keystore alias leak, backup, compromised remembered peer | P1; low/high | Lease keys/no trust DB; alias sweep; backup exclusions; key-loss fails closed. Trust future requires revocation | Root or OS compromise |
| Malicious intent / forged notification actions | P0; medium/high | Validate source grants; nonexported service; immutable PendingIntent; exact session ID on accept/reject/cancel | User explicitly grants a hostile provider access |
| Network change + automatic weaker fallback | P0; medium/high | Invalidate attempt, close socket, never downgrade/fallback after security error; roaming/VPN tests | Transfer interrupted by network policy |

Security findings above are design requirements grounded in inspected call paths; this document is not a claim that an unwired LAN service is currently exploitable. LAN-001 addresses the existing invariant mismatch before making it reachable through a new adapter.

## 12. File Safety & Integrity

Keep [Glide Protocol V1](../protocol/GLIDE_PROTOCOL_V1.md) as the file semantics authority: 1,000 items, 64 GiB/item and 256 GiB/batch ceilings; decimal-string byte counts; 16 KiB controls and 1 MiB accumulated offer metadata; one file stream at a time. Limits are safety ceilings, not guaranteed storage capacity. Use checked Long arithmetic throughout; test above 2 GiB and 4 GiB. Never readBytes an entire file or turn a file into a ByteArray for transport.

Source metadata can be stale or unknown. Use existing SourceAccess/BoundedSourceStager; inspect granted content URI and query/open failures before making an offer. Snapshot/stage only when a provider cannot provide a stable known-size reopenable stream, with bounded disk quota/cancellation. Selected file bytes can change: the sender hashes exactly what it actually reads; mismatched declared length fails. Do not persist arbitrary content grants unless granted and needed for an explicitly queued operation. Maintain source-handle ownership and close prepared streams exactly once on failure/cancel.

Receiver reserves batch space plus the existing safety margin (max 256 MiB or 5%), rechecks before each item, and handles ENOSPC regardless of preflight. Streaming SHA-256 covers raw original file bytes only—no record headers, compression, file name or URI. Keep the independent staged-disk rehash in AndroidReceiveStore until a measured, reviewed change justifies removal. Distinguish its I/O cost in benchmarks.

Success requires exact offered/actual count, STREAM_END, stream EOF, authenticated ITEM_END SHA-256, durable verified commit and matching ITEM_RESULT/BATCH_RESULT. A socket close, progress=100%, or hash in a QR never substitutes. On mismatch delete unfinished private partials, keep already committed verified items and record the per-item result. Never acknowledge a whole batch before durable receipt accounting.

Remote filename is display metadata. Store partials under controlled item IDs and final files under local random keys; reject malicious paths in any future folder extension. Display/control-character and bidi handling must avoid spoofed UI while retaining a safe user-visible original name. Duplicate exports default to Keep both (name (1).ext), never overwrite. Use private same-filesystem rename plus journal reconciliation; an external SAF provider is not guaranteed to support atomic rename. Export only verified content through MediaStore pending rows or explicit SAF; delete external partials on failure and report if provider deletion fails. Opening uses a granted FileProvider/content URI and validated MIME with application/octet-stream fallback; never file:// or automatic executable launch.

Retry means a new agreed session/offer for unfinished items from byte zero. Preserve verified earlier items and report ambiguous final delivery honestly. Do not deduplicate remote receipts based on matching filenames or pretend to resume retained offsets.

## 13. Android Lifecycle & Permissions

| API / action | Required behavior |
| --- | --- |
| API 26–28 | Keep Nearby supported. Disable LAN/QR connection with clear API limitation; no TLS downgrade. Scanner capability alone does not mean LAN is supported |
| API 29–35 LAN | INTERNET + ACCESS_NETWORK_STATE normal permissions; no location/Bluetooth/media permission merely for NSD/TLS |
| API 36 LAN | Test ordinary behavior and Android 16 opt-in local-network protection using NEARBY_WIFI_DEVICES; isolate compatibility test behavior from the API 37 policy |
| API 37+, target 37 | Check/request ACCESS_LOCAL_NETWORK at LAN Send/Receive. Existing declaration is insufficient. Unified browsing and inbound listener use broad permission; system NSD picker is an optional future limited-send alternative |
| Nearby | Preserve SDK-required version-dependent Nearby/Bluetooth/location permissions; separate them from LAN action policy. Do not make Bluetooth grant a LAN prerequisite |
| Scan | CAMERA only on Scan; camera optional uses-feature required=false. Denial offers Paste invitation; do not repeatedly prompt |
| Display QR / Paste | No camera permission. LAN grant is still needed for the connection; QR does not bypass OS permission |
| Notifications API 33+ | POST_NOTIFICATIONS at point of background explanation; denied notifications do not grant/deny transfer authority, but visibility differs |
| Active external-device transfer | Existing connectedDevice FGS type and FOREGROUND_SERVICE permissions; satisfy a documented connectedDevice prerequisite for LAN as well as Nearby |
| File select/export | SAF/Activity Result grants; no MANAGE_EXTERNAL_STORAGE or broad READ_MEDIA permission for ordinary picker use |

Keep the current CHANGE_WIFI_STATE declaration where it supports the documented connectedDevice prerequisite and existing Nearby behavior; do not request Bluetooth just to satisfy LAN foreground execution. Catch service-start permission/background restrictions and fail before starting a transfer whose lifetime cannot be owned correctly. Inspect both foreground-start failure and the subsequent execution path so it cannot continue after teardown.

Construct the coordinator **inside the service-owned session scope from the start**; ViewModels observe and send actions. Discovery/scanner jobs belong to foreground UI availability; do not use WorkManager for live sockets or endless advertising. Existing WorkManager maintenance remains for cleanup. Active sockets must be cancelled/closed with service destruction; no START_STICKY resurrection, boot receiver or automatic acceptance.

| Event | Contract |
| --- | --- |
| Rotation / pane resize | Reattach state; no duplicate connection, QR consumption or coroutine owner |
| App background / lock during active transfer | FGS continues best effort; minimal lock-screen text. No claim Android guarantees survival |
| Background while merely browsing/showing QR | Stop camera/discovery/advertising, invalidate unused invitation; foreground re-entry makes a new lease |
| Task swipe | No assumption it kills or preserves service on all OEMs; test. If service survives, notification Cancel remains effective |
| Force-stop / process reclaim | Sockets die; next launch reconciles journal and marks unfinished work interrupted; never auto-resume |
| Network/interface change | Cancel attempts/transfer, revoke QR, rediscover only after an explicit retry/foreground action |
| Notification denial | Explain reduced visibility before transfer; still obey FGS requirements; cancel in-app; test Task Manager behavior |
| Battery saver / Doze / thermal | Degrade honestly; no blanket battery exemption request. A measured need may justify a timeout-bounded partial wake lock with release in finally |
| Permission revoked | Close listener/sockets, map to actionable denied state, clean partials; no prompt loop |

Foreground services, wake locks and Wi-Fi locks do not guarantee OEM/OS survival. Add a lock only from measured evidence with a task-specific budget and teardown tests. Do not hold high-performance Wi-Fi or screen-on indefinitely.

## 14. Cross-Platform Contract

The portable contract is UTF-8 JSON V1 + strict codec fixtures + TLS 1.3/ALPN + profile-1 record fixtures + QR invitation/bootstrap schema + fixed failure/receipt rules. SHA-256 is lowercase hex; byte counts are decimal strings or the specified unsigned network-order record count; IDs are canonical UUIDv4. Device/platform labels are informational. No Android URI, parcelable, Nearby payload ID assumption, Keystore alias or Java class name crosses the wire.

Publish fixtures for fragmented/coalesced records, empty item, multi-gigabyte declared count, QR UTF-8/base64url, wrong pin, consumed invitation, invalid length, consent-before-data and lost receipt. Future Windows/macOS/Linux implementations can use native TLS/DNS-SD and the same contracts. Their filesystem and key-store implementations remain platform-specific; no Windows UI/source belongs in this release.

Browser clients cannot open arbitrary TCP/mDNS sockets like native Android. A future browser transport must negotiate the same application semantics over a browser-supported channel; do not claim raw LAN TLS profile automatically works in a browser. iOS local-network privacy, Bonjour declarations and background limits need a separate implementation plan. Protocol evolution requires a negotiated capability/profile or new major, never changing an existing required body field silently. Transport profile changes do not by themselves require changing Glide application major 1.

## 15. Quality-of-Life Recommendations

Every row records benefit, dependency, decision, complexity/risk and release. H/M/L are relative implementation estimates, not delivery dates. All P2 work yields to P0/P1 gates.

| Feature / benefit | Dependency / recommendation | Value | Complexity | Risk | Priority | Release |
| --- | --- | --- | --- | --- | --- | --- |
| Complete multi-file selection; avoid losing chosen files | Existing selection map + LAN-004; required correction | H | M | H | P0 | LAN/QR |
| Sequential batch queue with visible remaining files | Existing reducer; one active batch/item; no parallel transfer | H | M | M | P1 | LAN/QR |
| Multiple queued batches / configurable concurrency | Stable source grants, scheduling, consent; defer; never auto-accept queued inbound | M | H | H | P3 | Future |
| Retry only unfinished items | Existing per-item outcomes + fresh session; explain restart from zero | H | M | M | P1 | LAN/QR |
| True pause/resume | Persistent authenticated identity, immutable-source proofs, protocol extension; defer entirely | H | H | H | P3 | Future |
| History with peer, outcome, time and verified file actions | Reuse Room/history; no QR/IP/token storage, deletion separate from file deletion | H | M | M | P1 | LAN/QR |
| Configurable history retention | Existing maintenance/settings; default local 30-day metadata proposal after user-visible setting/migration review | M | M | M | P2 | Next |
| Keep both duplicate names | Reuse exporter; preserve verified source; collision/revocation tests | H | L | M | P1 | LAN/QR |
| Trusted devices | Keystore/pinned persistent keys, revocation/key-change UX; never name trust or automatic receive | H | H | H | P3 | Future |
| Short numeric manual pairing code | Needs reviewed PAKE and anti-guessing design; reject ad-hoc hash/password TLS; no release implementation | M | H | H | P3 | Future |
| Copy/paste full invitation | Same codec/pin/token rules, sensitive clipboard; explicit action and expiry | H | M | M | P1 | LAN/QR |
| Automatic deep-link handler | Exported-entry validation/security review; defer; in-app paste suffices | M | M | H | P2 | Next |
| Android Share Sheet | Reuse ingress; all selected URIs route to same preparation flow | H | M | M | P1 | LAN/QR |
| Destination-folder preference | SAF tree grants, revoked-provider handling, external commit semantics; private verified storage remains canonical | H | H | M | P2 | Next |
| Open/share verified files and show destination | Reuse catalog/FileProvider; show folder only when provider supports it; no fabricated folder URI | H | M | M | P1 | LAN/QR |
| Progress/cancel/completion notifications | Reuse manager; exact session actions, redact on lock screen, no accept from unauthenticated request | H | M | H | P1 | LAN/QR |
| Clear connection/storage/auth errors | Shared taxonomy section 20; technical details behind explicit diagnostics | H | M | L | P1 | LAN/QR |
| Minimal connection diagnostics | Route, permission, attempt stage, generic error; no token/QR/stable key/file-name dump | H | M | M | P1 | LAN/QR |
| Latency/network quality hints | Measured current connection only; stall message before speculative signal score; avoid promiscuous scans | M | M | M | P2 | Next |
| Smoothed speed/ETA | Monotonic clock, batch bytes, reset at reconnect/item boundary; unknown until stable | H | L | L | P1 | LAN/QR |
| Discovery empty states/QR fallback | Unified observation state; QR helps discovery failure, not isolation | H | L | L | P1 | LAN/QR |
| QR permission/expiry/invalid/flashlight states | Camera feature checks and full invitation alternative | H | M | M | P1 | LAN/QR |
| Platform icons | Peer declaration only; generic fallback; no proprietary copied logos | M | L | L | P2 | LAN/QR if gates pass |
| Favorites | Persistent authenticated identity needed; favorite must never equal trusted | M | M | M | P3 | Future |
| Accessibility / keyboard / non-camera path | Existing adaptive/semantics; TalkBack, 200% text, focus, no color-only status | H | M | L | P1 | LAN/QR |
| Subtle haptics / existing light-dark-dynamic themes | Reuse system-aware design tokens; no new visual identity | M | L | L | P2 | LAN/QR if gates pass |
| Battery-conscious availability | Foreground bounded scans, lease expiry, release locks/camera | H | M | M | P1 | LAN/QR |
| Privacy/discoverability control | Receive only explicitly; stop visible; no stable TXT identifier | H | M | H | P1 | LAN/QR |
| Rename device | Existing settings; label independent of identity; restart advertisement safely only when idle | M | L | M | P1 | LAN/QR |
| Protocol compatibility explanation | Compare authenticated capabilities; update guidance without silent downgrade | H | L | M | P1 | LAN/QR |
| Compression | CPU/privacy/type/size-bomb complexity; no release compression; measure and negotiate later | L | H | H | P3 | Future |
| Concurrent connections / multi-recipient send | Busy response now; later resource quotas and separate consent/session accounting | M | H | H | P3 | Future |
| Desktop drag-and-drop | Future native client; reuse protocol, no Android task | H | H | M | P3 | Future |
| LAN-only privacy mode | Route policy can allow LAN only now without cloud settings; broader mode UI optional | M | L | L | P2 | Next |
| Troubleshooting network isolation/VPN/permissions | Local explanation and settings links; do not auto-disable VPN or change AP | H | L | L | P1 | LAN/QR |
| Developer diagnostics export | Opt-in redacted bounded ring buffer, explicit preview/export, no credential data | M | M | H | P2 | Next |
| Preflight source/disk readiness | Additional high-value improvement: prepare once and reserve before consent | H | M | M | P1 | LAN/QR |
| Accurate “delivery unconfirmed” result | Additional improvement: distinguish receipt loss from verified receiver commit | H | M | H | P1 | LAN/QR |
| Invitation refresh without stale screen state | Additional improvement: generation-bound UI; old QR visibly removed immediately | H | M | H | P0 | LAN/QR |

## 16. Technical Debt to Address

Only debt necessary for this release is scheduled: exact session/bulkRef binding (S08); service-owned creation and cleanup (S07); missing runtime transport composition and full selection mapping (S05/S06); permission action separation (S10); correcting LAN framing/authentication assumptions and capability defaults (S11); replacing nominal-tier routing with evidence (S12); and converting relevant document-only claims into behavioral tests (S13).

LAN-002 also fixes S15 before the real Nearby baseline: prepared payload ownership must be the same stream used for sender hashing, and stream authorization must follow validated readiness. These are correctness prerequisites, not LAN-only optimizations.

ViewModel/file-action I/O and direct DI provider calls need correction **where the changed flow uses them**, through injected application-scoped repositories and IO dispatching; do not undertake an unrelated app-wide refactor. Repair current-item speed accounting to use monotonic batch progress. Existing receive storage's inaccurate noBackupFilesDir comment is documentation debt; do not move user files unnecessarily. Strengthen disposal of prepared source streams and child jobs as part of actual transfer ownership tests.

Do not spend this cycle replacing Hilt/navigation/Room, changing branding, broadly bumping dependencies, implementing future folder sanitizers as a feature, or rewriting the application as Kotlin Multiplatform. Reconcile inaccurate release documents narrowly through an explicit annotation/source reference; do not manufacture new historical evidence or revise old task counts to suggest fresh verification.

## 17. Implementation Phases

All phase gates require behavior at the actual change SHA. No phase is complete in this planning run. A failed gate blocks dependent production behavior, while independent pure tests/documentation may continue. New LAN functionality stays behind a local build/configuration switch until its security gate passes; no remote feature-flag backend is required.

### L0 — Restore and prove the existing runtime boundary (LAN-001–005)

- **Goal/why:** make the existing components form an actual authenticated Android transfer and close session/stream ambiguity before exposing another adapter.
- **Prerequisites:** audited baseline; reproducible toolchain/build check in the first coding session.
- **Affected/new components:** core coordinator; app session factory/owner; Nearby discovery/authentication wiring; selection/navigation; integration tests. Introduce an injected session factory and owned preparation registry, not another reducer.
- **Protocol impact:** no wire change. Receiver binds the first authenticated HELLO's session UUID once; every later control must match. Reject unknown bulk references rather than substituting the current item. Local prepare/send port changes ensure one transmitted-and-hashed source; register stream authority only after reducer validation and successful staging.
- **Security:** preserve both users' Nearby authentication confirmation and independent offer acceptance; close rejected streams; session-bound notification actions.
- **Tests/manual:** core negative invariants, production-composition instrumentation, two physical Android devices send/receive one file, compare SHA-256, open verified output and inspect history; reject/mismatch/cancel cases.
- **Acceptance/completion gate:** source picker → real peer discovery → verification → acceptance → bytes → verified receipt works through the actual launcher UI, with no injected screenshot/test state. Multi-selection passes every selected source to core. Gate cannot be replaced by fixture-only UI tests.
- **Risk/rollback:** OEM/GMS or build failures may surface. Keep LAN disabled; isolate fixes. If a toolchain pin is unavailable/incompatible, record the exact resolution error and make the smallest reviewed correction, not a bulk upgrade.

### L1 — Freeze portable bootstrap, identity and record contracts (LAN-006–008)

- **Goal/why:** prevent TLS, QR and UI implementers from inventing incompatible formats.
- **Prerequisites:** L0 invariant fixes; this plan and ADR-0018. Pure contract tasks may run alongside device evidence, but cannot enable LAN.
- **Affected/new components:** transfer-core pairing/discovery/routing models, additive typed verification result and StreamExpectation; golden QR/bootstrap/record fixtures. No Android dependencies in core.
- **Protocol impact:** V1 JSON preserved; new LAN profile 1 and typed local port contracts. Replace fake authDigits assumptions with NearbyDigits versus InvitationVerified verification evidence.
- **Security:** strict canonical types, exact pin/hash/token bounds, on-link validation inputs, no untrusted capability promotion.
- **Tests/manual:** property/negative parser tests, all old V1 fixtures, recorded independent fixture review. Walk through an expired code and a zero-byte stream on paper and in tests.
- **Acceptance/gate:** both adapter implementations can express verification without fake SAS; framing/QR spec matches fixtures byte for byte; task-specific review resolves ambiguities before networking implementation.
- **Risk/rollback:** port change may regress Nearby; keep compatibility adapter during migration and run its tests. Revise unused future LAN helper tests openly, never keep misleading semantics just to make string assertions pass.

### L2 — Secure single-file LAN vertical slice (LAN-009–013)

- **Goal/why:** prove the difficult TLS/bootstrap/stream/storage path before discovery polish.
- **Prerequisites:** L0 runtime gate and L1 contracts.
- **Affected/new components:** new transport-lan module, network scope owner, temporary Keystore lease identity, pinned TLS client/listener, invitation authority and framed stream bridge; app factory registration. LAN-009 includes minimum action-specific LAN permission and FGS start-failure handling; the broader lifecycle matrix/polish remains LAN-025. No debug permission bypass is allowed for the vertical slice.
- **Protocol impact:** implement profile 1 only; same HELLO/offer/item/batch receipts.
- **Security:** pin validation before bootstrap bytes, one-use invitation, strict expected stream, no automatic accepting. Initially feed invitation through an explicit debug/test entry using the same parser, never a bypass-auth flag.
- **Tests/manual:** real loopback TLS including wrong pin/expiry/replay, Android Keystore instrumentation, two physical API 29+ devices exchange one file and one empty file with Wi-Fi internet disabled; compare source/destination hashes and history.
- **Acceptance/gate:** receive prompt shows only after authenticated invitation; data rejected before consent; screen lock/cancel releases resources; no bytes published on mismatch. Actual device proof required before L3/L4 release integration.
- **Risk/rollback:** TLS provider/certificate/ALPN differences, blocked AP. Fail LAN closed, retain Nearby; do not weaken TLS. Disable adapter via build flag if the gate fails.

### L3 — Network discovery and deterministic route aggregation (LAN-014–016)

- **Goal/why:** reliable local discovery without confusing identity or network routes.
- **Prerequisites:** L2 authenticated transport; L1 peer models.
- **Affected/new components:** NSD advertiser/browser, unified discovery repository, central route selector, network diagnostics events.
- **Protocol impact:** bounded TXT contract; no file-protocol change.
- **Security:** advertisements remain untrusted; no auto-pin/auto-merge; fresh network generation; route policy cannot downgrade.
- **Tests/manual:** lost/reappeared/renamed services, duplicate labels, TTL/lifecycle cleanup, IPv4/IPv6, multicast blocked, Ethernet/Wi-Fi and user-created hotspot where available. No internet validation dependency.
- **Acceptance/gate:** correct unified observations, stable UI ordering, selected route explainable and reproducible, stale endpoint cannot start a session. QR can connect when multicast is blocked but direct TCP is allowed.
- **Risk/rollback:** NSD/OEM variability. Keep QR/direct invitation usable with discovery disabled; report isolation truthfully.

### L4 — QR and Quick Share user flows (LAN-017–020)

- **Goal/why:** deliver the secure route with usable permission, pairing and error states.
- **Prerequisites:** L2 gate; L3 for integrated list. Scanner component can be developed independently after L1.
- **Affected/new components:** app scanner/display/paste screens, receive readiness, peer cards and route messages; existing Compose navigation/design system.
- **Protocol impact:** none beyond frozen invitation profile.
- **Security:** exact generation-bound payload, explicit scan confirmation, no secret saved state or diagnostics, no exported deep-link handler, camera lifecycle release.
- **Tests/manual:** scan from another actual screen in light/dark settings; permission denied/permanent denial; refresh while scanner active; background before connect; TalkBack/paste; large text/landscape/foldable; no duplicate attempt from repeated frames.
- **Acceptance/gate:** complete Send/Receive/QR/Paste flow uses the same coordinator; fallback choices never bypass verification. Existing brand/theme preserved.
- **Risk/rollback:** camera/OEM decoding quality; tune resolution/focus first, evaluate bundled ML Kit only with evidence. Paste remains available. Hide LAN entry behind build flag if transport gate regresses.

### L5 — Reliable batch, background and result experience (LAN-021–025)

- **Goal/why:** make the single-file proof safe and usable for actual sharing.
- **Prerequisites:** L4; existing data/recovery components.
- **Affected/new components:** ingress/preparation owner, batch/result UI, history projections, speed/notification policy, per-action permissions/service failure mapping.
- **Protocol impact:** unchanged one-stream sequential batches; no resume or parallel offers.
- **Security:** exact URI grants and session notification actions, keep-both export, sanitized metadata, no automatic viewers or receive.
- **Tests/manual:** multi-app Share Sheet, 1,000-file batch, duplicate filenames, low storage, inaccessible provider, lock/unlock/background, revoked permission, confirmed/unconfirmed receipt outcomes.
- **Acceptance/gate:** all selected files accounted for; verified items survive partial failure; failed/unconfirmed items not marked completed; rate/ETA honest; foreground execution and cancellation tested on both transports.
- **Risk/rollback:** providers/OEM restrictions and revoked grants. Surface corrective actions and preserve verified content; defer convenience settings rather than widen permissions.

### L6 — Security, reliability, performance and accessibility gates (LAN-026–031)

- **Goal/why:** validate hostile inputs, resource bounds and user-visible behavior under realistic conditions.
- **Prerequisites:** L5 feature integration; test fixtures and device matrix.
- **Affected/new components:** behavioral security suite, network-chaos harness, benchmark/run sheets, accessibility/UX tests, CI jobs. Production changes only to fix demonstrated failures.
- **Protocol impact:** none expected; incompatible correction returns to ADR/fixture review.
- **Security:** all section 20 negative cases; independent review of handshake/pin code and exact source state.
- **Tests/manual:** API/device/network matrix, 5+ GiB files, 1,000 small files, >2/4 GiB counters, disk pressure, thermal/Doze, reference Nearby regression, camera-free accessibility.
- **Acceptance/gate:** every P0/P1 acceptance criterion satisfied with real evidence; no suppressed failing tests or empty compliance assertions. All critical/high security concerns resolved or LAN remains disabled.
- **Risk/rollback:** large-file/media tests take time and physical coverage is limited. Mark unavailable tests blocked, never pass. Revert the smallest failing slice or ship without LAN rather than waive integrity/authentication.

### L7 — Controlled LAN/QR release (LAN-032–033)

- **Goal/why:** ship only the verified feature with reproducible artifacts and clear support limits.
- **Prerequisites:** L0–L6 gates and approved release decision.
- **Affected/new components:** existing release docs, Data Safety/privacy/permission declarations, local release configuration, changelog/help, dependency notices and evidence index.
- **Protocol impact:** freeze profile/version and published fixtures; future changes use compatibility policy.
- **Security:** separate signing custody; no QR secrets/test private keys in artifacts/reports; confirm camera/local-network declarations reflect actual code.
- **Tests/manual:** install/update signed RC on supported devices, both transports, disabled-LAN rollback configuration, verify checksums and artifact identity.
- **Acceptance/gate:** human approves staged publication based on evidence; agent planning or passing CI alone never authorizes store rollout. Monitor only approved privacy-safe operational data; no new telemetry SDK by default.
- **Risk/rollback:** policy/OEM regressions. Halt rollout and publish a higher-version known-good build with LAN disabled if necessary; installed Android app downgrade is not a general rollback method. Preserve received files/history and do not delete user data to roll back.

### Phase dependency graph

~~~mermaid
flowchart LR
  A[L0 runtime boundary] --> B[L1 contracts]
  B --> C[L2 secure LAN file proof]
  C --> D[L3 discovery and routing]
  B --> E1[L4 scanner component work]
  D --> E[L4 integrated QR / Quick Share]
  E1 --> E
  E --> F[L5 batch and lifecycle]
  F --> G[L6 quality gates]
  G --> H[L7 release]
~~~

## 18. File-Level Change Plan

Paths below are repository-relative. New names are implementation guidance, not already existing files. Keep Android-specific implementations in adapter/app/data. Use the actual port TransferTransport. Related task IDs provide dependencies and tests in section 26.

Path shorthand in this table is exact: transfer-core/.../ means transfer-core/src/main/kotlin/dev/glide/core/; transport-nearby/.../ means transport-nearby/src/main/kotlin/dev/glide/transport/nearby/; app/.../ means app/src/main/kotlin/dev/glide/app/; data/.../ means data/src/main/kotlin/dev/glide/data/; new transport-lan/.../ means transport-lan/src/main/kotlin/dev/glide/transport/lan/. Build/manifest/resource paths are shown literally. Expand these prefixes when creating or editing files.

| Existing/new file or group | Current role → required change/new responsibility | Dependencies and verification |
| --- | --- | --- |
| transfer-core/.../coordinator/TransferCoordinator.kt | Protocol executor → compare session ID before sequence processing; exact incoming bulk mapping; dispose rejected streams; explicit monotonic duration clock and child-job cleanup | LAN-001/003; wrong-session/ref, duplicate start, stale callback tests |
| transfer-core/.../stream/StreamHasher.kt, coordinator preparation effects and both adapter send paths | Open/count/hash/transmit one owned source; exact EOF proof and cached digest; preparation/expectation only after validated effects | LAN-002; differing successive opens, early/late EOF, zero/nonempty mismatch and cleanup tests |
| transfer-core/.../ports/TransferTransport.kt | Control/stream port → additive VerificationEvidence and typed StreamExpectation(sessionId,itemId,bulkRef,sizeBytes); exact-scoped events | LAN-006/007; implement/test both adapters; no fake QR authDigits |
| transfer-core/.../coordinator/TransferReducer.kt and ReducerState.kt | Preserve state transitions and commit predicates; change only if binding tests expose a defect | Existing reducer suite plus LAN receipt/EOF mappings |
| transfer-core/.../protocol/ProtocolCodec.kt, NegotiationValidator.kt, ProtocolMessages.kt | Preserve V1 JSON semantics and limits; bootstrap is separate adapter profile, not new unnegotiated message types | All existing golden fixtures must remain compatible |
| transfer-core/.../transport/TransportCapability.kt | Speculative profile selector → isolate descriptive profiles; new deterministic implemented-route policy uses live evidence | LAN-008/016; no arbitrary speed/availability ranking |
| transfer-core/.../transport/lan/LanTransportContracts.kt | Pure future helpers → replace/clarify old control-only framing, service API/full DNS spelling and disabled future flags | LAN-007; versioned fixture change, old compliance tests updated honestly |
| New transfer-core/.../pairing/LanInvitation.kt, InvitationCodec.kt | Pure schema/parser, bounds and immutable parsed invitation; no secret-bearing toString | LAN-006; QR mutation/fuzz fixtures |
| New transfer-core/.../discovery/PeerObservation.kt, PeerCandidate.kt and routing/RouteSelector.kt | Pure evidence/identity and route decision models | LAN-008/015/016; duplicate-name/generation/denial tests |
| New transport-lan/build.gradle.kts, src/main/.../LanTransferTransport.kt | Android adapter implementing the existing port | LAN-009/012; depends on core/platform/coroutines only; no app/data/design dependency |
| New transport-lan/.../network/LanNetworkScope.kt | Selected Network, local address validation, socket binding, network callbacks | LAN-009; IPv4/IPv6/VPN/on-link tests plus device instrumentation |
| New transport-lan/.../tls/LanLeaseIdentity.kt, PinnedTlsConnector.kt, LanListener.kt | Keystore lease, SSLContext/key manager, exact pin/profile validator, limited listener | LAN-010; real handshakes and Keystore OEM test |
| New transport-lan/.../pairing/InvitationAuthority.kt | In-memory authority: token hash, payload hash, atomic consumption, monotonic expiry/generation | LAN-011; simultaneous consume/expiry/refresh/process-reset tests |
| New transport-lan/.../framing/LanRecordReader.kt, LanRecordWriter.kt, LanStreamBridge.kt | Framing, serialized control priority, bounded stream queues and exact completion mapping | LAN-012; partial reads, saturation, empty/many/large streams, cancellation |
| New transport-lan/.../discovery/NsdDiscoveryAdapter.kt | Register/resolve/update/unregister with API guards | LAN-014; fake NSD and physical multicast tests |
| transport-nearby/.../NearbyTransferTransport.kt, NearbyConnectionManager.kt, NearbyPayloadBridge.kt | Preserve security/STREAM path; adapt typed verification/expected-stream port and lifecycle ownership | LAN-003/006/007/031; fake SDK and two-device regression |
| New app/.../di/TransferModule.kt and service/TransferSessionFactory.kt | Actual injected composition: source/store/history/adapter and agreed session bootstrap | LAN-003/004; instrumented real production graph, no screenshot shortcuts |
| app/.../service/TransferSessionManager.kt, TransferService.kt and transfer/ActiveSessionTracker.kt | Current singleton glue → one service scope owns creation, commands and observable session; session action IDs checked uniformly | LAN-003/025; rotation, service fail/start/stop, stale action, process loss |
| New app/.../discovery/UnifiedDiscoveryRepository.kt | Merge observations under proven rules; route handles not Android objects in UI | LAN-015; stable keys/lost route/generation tests |
| app/.../ui/discovery/* and navigation/GlideAppNavDisplay.kt, GlideRoute.kt | State-only screens/navigation → observed live discovery, selected preparation ID, QR entries and active session attachment | LAN-004/018/019; UI state and live composition tests |
| app/.../ui/selection/SelectionScreen.kt and new preparation/SelectedTransferStore.kt | Single-handle fallback → complete batch + ItemId/source map, grant lifecycle and source preflight | LAN-004/021/022; >=2 files, provider failure, revoked grants |
| New app/.../ui/pairing/QrPairingViewModel.kt, QrScannerScreen.kt, QrInvitationSheet.kt | Offline scanner/display/paste and typed verification status; no network logic inside Composables | LAN-017/018; CameraX lifecycle, decode duplicate, expiry and semantics |
| app/.../ui/transfer/*, ui/TransferScreen.kt, notification/TransferNotificationManager.kt | Same screens for both routes; every verified result, monotonic speed and protected actions | LAN-019/023/024; receipt truth, 200% text and lock-screen tests |
| app/.../ingress/ShareIngressParser.kt, MainActivity.kt | Existing SEND/SEND_MULTIPLE → route to same complete preparation flow | LAN-021; malicious intent/grant and multiple-file instrumentation |
| app/.../permission/PermissionPolicy.kt and PermissionState.kt | Nearby-only grouping → action-specific LAN/Scan/Nearby/Notify evaluation | LAN-025; table-driven API/grant/revocation tests |
| app/src/main/AndroidManifest.xml | Add CAMERA and optional camera feature for chosen scanner only; retain FGS/backup/provider constraints; no broad storage or new exported pairing entry | LAN-017/025; merged-manifest test |
| data/.../store, source, export, db | Preserve private storage/recovery; add only necessary route/verified-result projections and safe URI handling | LAN-022/023/027; migration if schema changes, low-space/provider/crash tests |
| gradle/libs.versions.toml, settings.gradle.kts, app/build.gradle.kts | Add one LAN module and isolated QR dependencies when their task starts; keep current stack | LAN-009/017; dependency review/lock/checksum and clean build |
| .github/workflows/ci.yml, benchmark/src/... and test source sets | Extend real tests/reports, benchmark both transports and device-run evidence | LAN-026–031; no claim from markdown grep alone |

Exact verification-result migration: replace mandatory digit-string assumptions at the application connection boundary with NearbyDigits(value) or InvitationVerified(invitationId, serverPin). Nearby still uses actual SDK digits; LAN never manufactures digits or bypasses a shared “verified” boolean. Keep secrets out of this evidence object. A rejection/cancel always closes the exact attempt.

Exact expected-stream migration: core registers a StreamExpectation only after an accepted offer, reducer-validated ITEM_START and successful staging preparation, at the serialized ITEM_READY transition; adapter enforces byte limit/ref, while core independently enforces session/item consent. Nearby retains its SDK bulk ID mapping, LAN uses UUID bulk refs. New default interface shims may aid migration only if they cannot skip validation in production; remove unsafe no-op defaults from authenticated paths. LAN-002 fixes existing readiness timing; LAN-007 enriches the port metadata and fixtures for LAN.

## 19. Testing Plan

Build a layered suite before implementing the transport. Tests identify boundary, expected behavior, deterministic clock/network controls and evidence location. Never assert only that a document contains the words TLS, verified or COMPLETE.

| Suite | Required cases | Execution / evidence |
| --- | --- | --- |
| Core bindings | Correct peer/wrong session, valid wrong-session sequence, unknown/old/duplicate bulkRef, terminal callback, second active session, rejected-stream close | JVM unit tests with controlled flows; failure leaves no staging write |
| Source identity/readiness | Provider differs on second open; exactly one open/underlying close; actual transmitted hash; early EOF, overlong source, offered zero/nonempty; invalid ITEM_START/ITEM_READY/OFFER_DECISION creates no new authority or source side effect | Core plus actual Nearby adapter fake-SDK path; repeat on LAN integration |
| Invitation codec | Round-trip canonical fixture; >2,048 decoded bytes; invalid UTF-8/base64url/UUID/hex/types; duplicate/unknown keys; IPv6 and forbidden host/scheme/path; length/overflow | Pure property/fuzz tests, fixed seeds and reviewed fixtures |
| Invitation authority | Exact monotonic expiry boundary; refresh/stop/network generation; two simultaneous valid requests only one succeeds; replay after success; lost response; clock rollback; consumed ID cannot revive | Deterministic clock + concurrency tests; no wall-clock sleep dependence |
| Frame reader/writer | One-byte reads, coalesced frames, all header truncations, signed-length overflow, unsupported kind, huge declared length, empty file, overrun, duplicate end, interleaved control | Binary golden vectors; bounded allocation counters |
| Peer/router | Same names/different keys, same service network variants, stale route, denied LAN/allowed Nearby and inverse, missing GMS, incompatible versions, pin failure no fallback, no mid-transfer switching | Pure table-driven tests plus repository flow tests |
| Real TLS integration | Actual loopback SSL sockets with independent test keys; valid pin; wrong key/cert; expired/profile-invalid cert; ALPN/TLS version mismatch; slow handshake; bootstrap replay | JVM loopback where provider supports profile; Android instrumentation for platform/Keystore differences |
| Transfer integration | Two coordinator instances over real LAN adapter → offer accept/reject, zero byte, streamed file, multi-file, cancel, wrong digest, lost receipt, storage failure | Fake bounded storage for fast CI; Android receive store/device run for durable output |
| Storage/recovery | Failure before/during/after rename and catalog update; low disk, revoked URI, duplicate name, malicious filename, unavailable viewer, export delete failure | Existing suites extended; device/provider checks where framework behavior matters |
| Compose/navigation | Every discovery/scanner/consent/progress/result state; selection preserved across routes; stale QR removed; repeated tap; task recreation; real DI smoke | Instrumented Compose tests, semantics assertions and reviewed screenshots |
| Nearby regression | SDK digit match/mismatch ordering, payload readiness, full selected batch, background cancel, same protocol receipts | Fake SDK suite on every PR; actual devices before gate |
| Physical network | IPv4-only, dual-stack, IPv6-only; Wi-Fi without internet; Ethernet peer; multicast denied but TCP works; isolation; network loss/change; VPN policy | Device/run sheet with OS/build/OEM/network/change SHA and actual outcome |

Required physical set before release: two API 29+ Android devices from different manufacturers, one current API 37 device, one lower-performance device, and an API 26–28 Nearby regression device where available. Expand to at least three OEM families for release confidence. Emulator tests supplement but do not prove multicast/radio/OEM behavior. Missing hardware cases remain explicit unresolved release coverage, not assumed passes.

Manual scenario IDs: M01 real Nearby baseline; M02 pinned LAN one file/empty; M03 QR scan/paste/refresh; M04 all selected files and 1,000 items; M05 >5 GiB; M06 lock/Doze/background/cancel; M07 permission revoke/process kill/low disk; M08 multicast/isolation/VPN/IPv6; M09 accessible/adaptive UI; M10 signed RC install/update. Save redacted raw logs, source/received hashes, device identifiers without personal IDs, elapsed times and screenshot evidence under a dated task evidence directory. Never include live invitations/private keys.

## 20. Security Validation Plan

Release-blocking negative tests must prove **where rejection occurs** and what resources remain afterward:

1. Wrong server key/certificate/version/ALPN: zero BOOTSTRAP token bytes sent; no HELLO, UI trusted state, file metadata or source stream opened.
2. Invalid/replayed/expired/refreshed token: no coordinator/offer authority created; exactly one success under a two-client race; new invitation required after uncertain bootstrap.
3. Unknown network destination: rejected before TCP connect; test loopback, multicast, IPv4-mapped IPv6, textual alternate encodings and off-link IPv6. Parser must not invoke DNS to interpret a numeric literal.
4. Same peer with another session ID/stream: rejected before reducer delivery/staging, inbound stream disposed; old generation ignored; normal valid session unaffected by unrelated observation loss.
5. Pre-consent payload or unsolicited second stream: no writes or publication; bounded reject/close with tests against both adapters.
6. Oversized/truncated/slow frames and TLS handshake flood: allocation/thread/socket limits hold, application stays responsive and local Cancel works.
7. Malicious names/MIME/intents: no path traversal, external URI exfiltration, uncontrolled intent launch, overwritten user file, executable auto-open or leaked grant.
8. Digest/length/receipt mismatch: no success until actual on-disk commit; receiver can retain verified content while sender reports unconfirmed delivery accurately.
9. Notification action replay: accept/reject/cancel require exact active session, correct state and explicit immutable PendingIntent; stale actions cannot accept a new offer.
10. Secrets/privacy: static/log/export review for token, QR URI, keystore material, full pins, IP/SSID and filenames; default production logs contain only error category, attempt-local random correlation and stage. Review backups and crash reports if any.

Independent review must examine TLS trust manager, bootstrap consumption, session bootstrap, record parser, stream completion and terminal cleanup using actual source/callers/tests. Re-run attacks after fixes. No custom SAS or cryptographic protocol may enter through a “temporary” debug shortcut. Application-level user verification/offer consent tests remain separate from TLS encryption tests.

### Error contract and user remedies

LAN adapter-local failures map to the existing TransferFailure categories where semantics match; do not invent V1 wire codes without version review. Preserve detailed local causes behind safe diagnostic enums.

| Local failure | Core category / retry | User wording and action |
| --- | --- | --- |
| LAN permission denied/revoked | PermissionDenied / after grant | Allow local-network access to connect; Settings after permanent denial |
| No local network / route lost | DiscoveryUnavailable or PeerLost / new attempt | Connect both devices to a reachable local network; Retry |
| Pin/certificate mismatch | AuthenticationMismatch or authentication failure / new verified invitation only | Could not verify this device. Scan a new code from its screen |
| Invitation expired/used | Authentication failure before session / new invitation | This code has expired or was used. Ask the receiver to refresh it |
| Unsupported LAN profile/protocol | UnsupportedProtocol / after compatible update | Devices use incompatible Glide versions; no insecure fallback |
| Connect/TLS/offer timeout | Timeout / explicit retry | Connection timed out; check receiver and network isolation |
| Busy | Existing BUSY semantics / later | Device is already transferring; try again when it finishes |
| Bad frame / wrong session/ref | MalformedMessage or ProtocolOrder / fresh session after investigation | Connection stopped because the data was invalid |
| Source deleted/revoked | SourceUnavailable / choose file again | This file is no longer accessible |
| Disk full/destination denied | InsufficientStorage or DestinationUnavailable / free space/reselect | Free space or choose an available destination |
| Socket/permission/process interruption | TransferInterrupted/SystemCancelled / unfinished files restart | Transfer interrupted. Verified files are kept |
| Digest mismatch | IntegrityMismatch / resend unfinished item from zero | This file could not be verified and was not saved as complete |
| Final receipt lost | DeliveryUnconfirmed state / reconcile with recipient | Delivery could not be confirmed. Check the receiving device before resending |

Logging records category/stage/timing only, never raw remote exception text that embeds metadata. No default telemetry. A later diagnostic export is opt-in, previewable and redacted.

## 21. Performance Validation Plan

Numbers below are **engineering budgets to validate**, not measured results or product speed claims. Compare the same device pair, files and environment against the proven Nearby baseline and a minimal TLS copy harness with identical hashing/storage semantics.

| Measure | Initial gate / method |
| --- | --- |
| Main thread | No socket, file/hash, certificate generation or blocking DB work; StrictMode + profiler |
| Streaming memory | Queue/buffer bounds from section 6; no growth proportional to file size. Target <=8 MiB added Java heap for active LAN transport excluding camera/TLS provider caches; investigate native/TLS RSS separately |
| UI | Meet device refresh budget (16.7 ms at 60 Hz, 8.3 ms at 120 Hz), no artificial 60 Hz cap; target <1% missed-frame events during steady transfer on reference midrange |
| Startup | No eager scanner/TLS/listener initialization on Home; <=10% regression against actual baseline median/p95 with same build mode |
| Discovery | Record median/p95 first peer and usable endpoint; initial target p95 <5 seconds on uncomplicated reference LAN, report misses rather than hide them |
| Connection | Record TCP/TLS/bootstrap separately; initial target p95 <3 seconds excluding user verification/consent on reference LAN |
| Throughput | No universal MB/s promise. Investigate >15% regression against same-path reference after accounting for independent disk rehash and source staging |
| Progress | UI <=5 updates/sec, notification <=1/sec, no control message per DATA record; EMA rate over monotonic batch bytes; ETA withheld until stable |
| Cancel | Local UI reacts <250 ms; blocked sockets/streams close and stop new writes within 2 seconds on reference devices; disk reconciliation can finish asynchronously |
| Resource release | No listener/discovery/camera/worker leak after 50 start/stop cycles; session jobs return to baseline; orphan cleanup evidence |
| Power/thermal | 30-minute sustained transfer/soak: record CPU, battery, temperature/throttling and correctness, compare same device baseline; no blanket efficiency claim |
| Size | Record APK/AAB delta from CameraX/ZXing and LAN module; justify all native/models/dependencies |

File sets: 10 MB, 100 MB, 1 GB, >5 GB where actual storage supports it; synthetic streamed counts around 2^31/2^32 and max limits; 1,000 small files; mixed Unicode names and MIME; incompressible data so compression cannot inflate speed. Run each common scenario at least 5 times after one warm-up; label cold and warm separately. Record verified useful bytes / total elapsed and payload-only rate, setup/consent/verification/commit times, peak versus sustained throughput, CPU/heap/RSS/GC, free space, battery/thermal and failures.

Use Macrobenchmark/Baseline Profile work already in the repository for changed routes; profile scanner start/stop separately from transfer. Test slow ContentResolver providers and slow destination storage, not only tmpfs/byte-array sources. Measure the receiver's second hash pass instead of removing it to make a graph look faster. Tune buffers/queue depth only after a controlled comparison and retain limits in fixtures/tests.

## 22. CI Plan

Extend the existing pinned-action workflow; do not replace it with a new CI platform. Fast PR gates: module architecture dependency checks, core/adapter/data/app unit tests, real loopback TLS tests, golden protocol/QR/record vectors, debug assemble and Android lint. Keep doc-link checks separate from behavioral tests. Relevant starting commands after modules exist:

~~~text
./gradlew :transfer-core:test :transport-nearby:testDebugUnitTest :data:testDebugUnitTest
./gradlew :transport-lan:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
~~~

Use gradlew.bat on Windows. Discover exact instrumentation/build task names from the actual Gradle configuration; do not claim tests ran from a copied command. Add a bounded emulator job for permission/navigation/Keystore/lifecycle smoke, with an explicit API 29 and current target matrix as runner resources allow. Loopback CI does not replace two-device NSD/radio tests. Nightly/manual jobs cover fuzz seeds, sustained bounded-stream tests and benchmarks where reliable hardware exists; never assume GitHub hosted runners have a usable mDNS LAN.

All new dependencies enter the version catalog with reviewed license/provenance/checksum and isolated usage. Preserve wrapper validation, least-privilege contents:read and untrusted-PR separation from signing credentials. Include transport-lan reports in artifact upload; fail if expected test reports are absent instead of uploading an empty success. Retain redacted CI reports for 14 days initially and archive RC evidence by immutable artifact hash. Release signing remains a protected workflow/environment, never PR secrets or repository source.

Loopback TLS tests inject a test-source-set network scope/fixture that permits loopback; the production address validator still rejects it. Do not add a production allow-any-address, skip-pin or skip-consent switch to make tests convenient. Android instrumentation exercises the real production validator and Keystore path on an eligible interface.

Do not make every PR run a 5 GiB/physical matrix. Physical gate run sheets must record the tested SHA and cannot be replaced by a checked checkbox. Tests that previously grepped old unsafe SAS/framing text must be revised as documented specification checks, not bypassed or left to force unsafe decisions back into code.

## 23. Documentation / ADR Plan

This planning change adds this master plan and [ADR-0018](../adr/ADR-0018-lan-qr-authentication-and-routing.md), links them from the portal/ADR index, and annotates the earlier LAN proposal. It creates no application code, icon variants, readiness report or fabricated test evidence. ADR-0018 records the inseparable authentication/framing/routing decision set; do not create a separate ADR for every helper class.

During implementation, maintain a focused LAN appendix or linked specification under [Glide Protocol V1](../protocol/GLIDE_PROTOCOL_V1.md) with record/bootstrap golden vectors; keep application envelope authority there. Update [permission matrix](../android/PERMISSION_MATRIX.md), [storage/background](../android/STORAGE_AND_BACKGROUND.md), [threat model](../security/GLIDE_THREAT_MODEL.md), [user flows](../design/GLIDE_USER_FLOWS.md), [test strategy](../testing/GLIDE_TEST_STRATEGY.md) and [performance plan](../performance/GLIDE_PERFORMANCE_PLAN.md) only for behavior changed by the completed task. Preserve the accepted [design system](../design/GLIDE_DESIGN_SYSTEM.md) and assets.

Use [change control](../development/CHANGE_CONTROL.md) and [Gemini implementation guide](../development/GEMINI_IMPLEMENTATION_GUIDE.md). Before each task read its section-26 row, linked relevant sections, ADR-0018 and actual source; inspect worktree; implement only that scope; run real build/tests; attach evidence; update status; leave a [session handoff](../development/SESSION_HANDOFF_TEMPLATE.md). A plan assumption that fails becomes a documented local blocker/ADR correction; never quietly substitute insecure behavior. For this cycle the next task is LAN-001, not the portal's historical GLIDE-001 label.

### Current primary-source research (checked 2026-09-16)

These references support platform facts; the budgets/schemas in this plan are Glide design decisions. Recheck time-sensitive API/library behavior when its implementation task starts.

| Reference | Conclusion used / selection boundary |
| --- | --- |
| [Android NSD guide](https://developer.android.com/develop/connectivity/wifi/use-nsd) | Platform DNS-SD discovery/registration and dynamic port; no custom mDNS stack needed |
| [NsdManager API](https://developer.android.com/reference/android/net/nsd/NsdManager) | API-specific callbacks and service updates; isolate older/newer paths, release callbacks |
| [NsdServiceInfo API](https://developer.android.com/reference/android/net/nsd/NsdServiceInfo) | Address/network/TXT information is adapter data, not authenticated identity |
| [Network API](https://developer.android.com/reference/android/net/Network) | Bind selected network sockets before connection; avoid process-wide network rebinding |
| [Android local-network permission](https://developer.android.com/privacy-and-security/local-network-permission) | API 37 target enforcement; broad access permission versus system-mediated picker; API 36 opt-in differs |
| [Android SSLSocket](https://developer.android.com/reference/javax/net/ssl/SSLSocket) | TLS 1.3 support from API 29; Android TLS 1.3 cipher configuration constraints |
| [Android Keystore key generation](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec) | Generated certificate/signing authorization constraints; prove lease certificate on devices |
| [FGS service types](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device) | connectedDevice covers network-connected external devices and has specific prerequisites |
| [RFC 8446 TLS 1.3](https://www.rfc-editor.org/rfc/rfc8446) | Use established TLS; avoid replayable early data and low-entropy ad-hoc PSKs |
| [RFC 6763 DNS-SD](https://www.rfc-editor.org/rfc/rfc6763) | Standard service discovery/TXT encoding; records are discovery hints |
| [CameraX releases](https://developer.android.com/jetpack/androidx/releases/camera) | Select stable 1.6.2 for proposed scanner, not preview 1.7 artifacts; isolate to app scanner |
| [ZXing 3.5.4 release](https://github.com/zxing/zxing/releases/tag/zxing-3.5.4) | Local encode/decode library selection; mature maintenance profile; verify artifact at coding time |
| [Bundled ML Kit barcode scanner](https://developers.google.com/ml-kit/vision/barcode-scanning/android) | 17.3.0 bundled model is fallback if measured ZXing scan quality is insufficient |
| [Google Code Scanner](https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner) | 16.1.0 uses Play Services/module availability; unsuitable as the sole first-use offline/no-GMS scanner |

No external source supports the old project's quoted hardware coverage or 300–800+ MB/s promise; those are not carried into route policy or product claims. New dependencies are proposals, not installed by this run.

## 24. Risks & Mitigations

| ID | Risk / likelihood × impact | Owner; trigger | Mitigation / fallback |
| --- | --- | --- | --- |
| R01 | Existing app integration incomplete; high × high | Runtime owner; L0 real flow fails | Bounded composition repair first; no LAN on simulated UI |
| R02 | Unreviewed old SAS/framing copied; medium × critical | Security/protocol owner; implementation cites ADR-0017 verbatim | ADR-0018 precedence; fixture/security review; fail closed |
| R03 | TLS/Keystore incompatibility on OEM/API; medium × high | LAN owner; device handshake failure | API 29 gate, real platform tests, Nearby fallback by explicit selection; no insecure provider shortcut |
| R04 | NSD/multicast/AP isolation; high × medium | Discovery owner; peers missing/unreachable | QR bypasses discovery only; actionable diagnosis; preserve Nearby |
| R05 | Background/Doze/OEM termination; high × high | Android owner; lock/soak interruption | Correct FGS lifetime, cleanup/retry from zero; no guarantee of survival |
| R06 | Secret QR leaked/replayed; medium × high | Pairing owner; duplicate/expired invite attempts | One-use 120-second token, redaction, explicit consent, refresh |
| R07 | Incorrect dedup sends to wrong peer; medium × critical | Discovery/security owner; same-name peers | Evidence-based merge only; separate cards when unproven |
| R08 | Slow/large/provider data causes RAM/disk exhaustion; medium × high | Storage/transport owner; quota or allocation breach | Bounded queues, checked size, preflight and real ENOSPC handling |
| R09 | Network change or VPN misroutes local traffic; medium × high | Network owner; generation/interface changes | Selected Network/on-link validation, reject ambiguous scope, close/re-pair |
| R10 | False release confidence from compliance documents; high × high | Reviewer/release owner; no raw behavioral evidence | Source-SHA/device/hash evidence, real TLS and UI path tests |
| R11 | New camera dependency/quality regressions; medium × medium | QR owner; offline/low-light scan failures | Isolate dependency, paste alternative, measured fallback decision |
| R12 | Version/Play policy drift; medium × high | Release owner; task revalidation differs | Recheck official sources, smallest ADR change, no speculative upgrades |
| R13 | Real resume/trust expands scope; high × medium | Planner/maintainer; offset or autoaccept PR | Keep P3 explicitly; profile capabilities false; separate reviewed design |
| R14 | On-link restriction excludes routed enterprise LAN; medium × medium | Product/network owner; legitimate off-link peer | State release boundary: same directly connected local network; future explicit routed-network policy review |
| R15 | Same-device duplicate Nearby/LAN cards; high × low | UX owner; no common proof | Explain route subtitles; postpone cryptographic cross-route identity rather than unsafe merge |

Open validation questions are concrete: API/OEM certificate/ALPN compatibility (LAN-010/013); exact foreground behavior on tested OEMs (LAN-025/027); source-provider behavior above 4 GiB (LAN-022/028); scanner low-light/offline quality (LAN-017/029); physical matrix availability (LAN-005/027/032). Each has a safe default above. No user answer is required to finish planning. A failing validation blocks the affected feature gate, not unrelated documentation or pure tests.

## 25. Definition of Done

**Task:** scoped code integrated through its intended caller, acceptance tests pass, build/lint relevant to changed modules pass, no boundary/secret violation, failure/cancel/lifecycle behavior tested, docs/status updated with actual SHA/commands/outcomes. A helper existing or a screenshot rendering is insufficient. Status REVIEW_REQUIRED means evidence exists but review is pending; it is not COMPLETE.

**LAN vertical slice:** two actual supported Android devices perform pinned invitation pairing, explicit offer consent, one-file/zero-file-size transfer, independent integrity validation, verified open and history. Wrong pin, reject, cancel and interruption must fail safely. Same core semantics work on Nearby.

**Feature:** discovery/QR/paste/permissions/batch/background/error/result paths satisfy the plan with accessible light/dark/adaptive UI; one-use invitation and exact session/stream guarantees hold; resource bounds demonstrated; no false “resume,” “trusted,” or speed claims.

**Release:** all P0/P1 tasks COMPLETE, optional P2 explicitly delivered/deferred, physical matrix and security/performance/accessibility gates signed off, signed RC identified by hash, install/update/rollback tested, policy/privacy/dependency notices correct, and human publication approval obtained. No approval is needed to continue ordinary implementation tasks; the release approval is for the concrete tested artifact. This planning run does not start development or publish anything.

## 26. Ordered Development Checklist

Statuses: NOT_STARTED, READY, BLOCKED, IN_PROGRESS, REVIEW_REQUIRED, COMPLETE, DEFERRED. Only LAN-001 is initially READY; all other release tasks are NOT_STARTED. A task becomes READY only when its listed dependencies are COMPLETE and evidence exists. BLOCKED names a concrete unmet condition. Do not retroactively mark the historical GLIDE ledger incomplete/complete from this new cycle.

Progress: **4/33 tasks complete, 0/8 phases complete (12%)**. Current next task: **LAN-005**. L0=4/5; L1=0/3; L2=0/5; L3=0/3; L4=0/4; L5=0/5; L6=0/6; L7=0/2. Update these counts from evidence when status changes.

Every task below inherits: preserve V1/brand; no main-thread I/O; bounded/cancellable work; explicit source scope and proof; no unreviewed dependencies. UI/security/performance requirements reference sections 9/11–13/21. A completion report includes changed files, source SHA, commands and results, actual versus unrun manual tests, deviations/ADR updates and next readiness. Complexity S/M/L refers to one or a few focused coding sessions, not deadlines.

| Order / task | Phase / priority / initial status | Dependencies; likely files | Implementation instructions and acceptance / required tests | Size / parallelism / evidence |
| --- | --- | --- | --- | --- |
| [x] **LAN-001 — Enforce exact inbound session and stream ownership** | L0 / P0 / COMPLETE | None; TransferCoordinator and coordinator tests | Check envelope.sessionId before sequencer/dispatch; unknown incoming bulkRef never falls back to current item; close rejected/late streams. Valid transfer still completes. Tests: valid peer/wrong session, stale ref, unknown ref, pre-consent data, terminal stream cleanup | S; sequential foundation; core test command/output + regression fixture evidence; no UI/dependency change |
| [x] **LAN-002 — Unify prepared source hashing and validated readiness** | L0 / P0 / COMPLETE | 001; coordinator effects, stream wrapper, port and Nearby bridge | One open/count/hash/transmit source; prepare then send by ref without second provider; prove actual EOF, cache digest, close once. Only validated reducer effects open sources/register expectations/unlock readiness | M; sequential foundation; adversarial changing provider, wrong-ready/ref, early/late EOF and zero-size tests through actual adapter; update ownership contract |
| [x] **LAN-003 — Compose a service-owned transfer session** | L0 / P0 / COMPLETE | 002; factory/DI, manager/service, core ownership | Construct coordinator and adapter in one owned scope; receiver bootstrap binds first authenticated HELLO once; adopt no ViewModel-owned scope; idempotent start/terminal cleanup and session-bound actions | M; precedes UI wire-up; production graph/instrumentation, rotation/start-failure/stale action tests; update ownership docs |
| [x] **LAN-004 — Wire Nearby discovery, pairing and full selection** | L0 / P0 / COMPLETE | 003; Discovery/Receive ViewModels, nav, selection, Nearby | Replace timer-only flow with actual observations; preserve batch plus complete source map; actual SDK digits and offer confirmation; reject duplicate taps | M; sequential; >=2-file production-path instrumentation, grant/cancel/mismatch tests; update flows |
| [ ] **LAN-005 — Establish real two-device baseline** | L0 / P0 / READY | 004; device run sheet/test harness | M01 through launcher UI on two Android devices; bytes/hash/open/history, reject/cancel; record observed gaps and fix only this path | M; can overlap pure L1 work after 004; raw redacted evidence/test SHA mandatory; missing devices = BLOCKED |
| [ ] **LAN-006 — Add typed pairing evidence and strict invitation codec** | L1 / P0 / NOT_STARTED | 001,003; core pairing/port and Nearby bridge | Freeze exact section-8 format; introduce verification evidence without fake digits; preserve Nearby confirmation; no token in toString/state restoration | M; parallel with 007/008 after deps; positive/negative/fuzz/old-adapter tests, fixture review; document schema |
| [ ] **LAN-007 — Freeze record profile and stream expectation contract** | L1 / P0 / NOT_STARTED | 001,003; LAN contracts/port/fixtures | Specify typed records and expected session/item/ref/size; adapt Nearby; partial/coalesced/empty/end/overrun vectors; preserve V1 JSON fixtures | M; parallel with 006/008; golden binary review and core/Nearby regression; update profile docs |
| [ ] **LAN-008 — Model observations, proof and route decisions** | L1 / P0 / NOT_STARTED | 001; core discovery/routing models | Separate observed hints, verified lease identities and selected route; remove runtime reliance on default flags/nominal throughput | S; parallel with 006/007; duplicate-name/network/generation/capability table tests; model documentation |
| [ ] **LAN-009 — Introduce LAN module and network-scoped socket ownership** | L2 / P1 / NOT_STARTED | 005,006,007,008; settings/catalog/new transport-lan, permission/service glue | Add only required module; Wi-Fi/Ethernet callbacks, inbound/outbound on-link validator, VPN fail-closed policy, bound socket ownership; minimum LAN runtime grant and FGS start-failure cleanup independent of Bluetooth before device slice | M; 010 can follow module foundation; IPv4/IPv6/VPN/off-link inbound/permission-denial/cleanup tests, build/lint and boundary proof |
| [ ] **LAN-010 — Implement temporary key and pinned TLS handshake** | L2 / P0 / NOT_STARTED | 009; TLS/Keystore adapter files | Lease P-256 key, restricted pin trust, TLS1.3 + ALPN, bounded listener; token cannot leave before validation | M; parallel with 011 after 009; real wrong-pin/version/cert/ALPN loopback plus Android Keystore tests; security review evidence |
| [ ] **LAN-011 — Implement atomic invitation authority/bootstrap** | L2 / P0 / NOT_STARTED | 009,006; pairing authority | Mint/hash/store/expire/consume/revoke in memory; exact payload hash and network generation; one authenticated client, no replay/unconsume | M; parallel with 010; deterministic clock/race/lost-response/refresh tests; bootstrap catalog docs |
| [ ] **LAN-012 — Implement framed LAN TransferTransport** | L2 / P0 / NOT_STARTED | 010,011,007; adapter/reader/writer/bridge | One serialized record writer, bounded incoming stream, typed expectations, exact end mapping and teardown; controls remain V1 | L; sequential; actual TLS two-coordinator integration, zero/large/cancel/bad-end/backpressure tests; allocation evidence |
| [ ] **LAN-013 — Prove secure LAN one-file transfer on devices** | L2 / P0 / NOT_STARTED | 012,005; factory registration and device test entry | M02 with same production parser/pin/consent; API29+ two-device file/empty/reject/hash/open/history/lock/cancel | M; sequential gate; raw source/received hashes, screenshots/logs and tested SHA; no screenshot-state injection |
| [ ] **LAN-014 — Implement scoped NSD discovery and registration** | L3 / P1 / NOT_STARTED | 013,008; NSD adapter | Exact TXT/service contract, bounded resolve cache, modern callbacks/legacy fallback, generation/lost/stop behavior | M; parallel with aggregation model work; fake NSD tests and multicast/IPv6 physical checks; no secret TXT |
| [ ] **LAN-015 — Aggregate the unified peer list safely** | L3 / P1 / NOT_STARTED | 014,008; discovery repository/ViewModel | Merge only authorized observation relations; retain distinct ambiguous peers; stable UI order/expiry/network removal | M; before router integration; adversarial duplicate-name/presence/key tests and UI snapshots; document intentional duplicates |
| [ ] **LAN-016 — Integrate deterministic transport routing** | L3 / P0 / NOT_STARTED | 015,012; router/session factory | Apply section-10 policy, explicit route/permission/support filters; pretransfer alternatives only; fixed route thereafter | M; sequential; denied/missing-GMS/QR-exclusive/security-fail/no-midfile-switch tests; route decision evidence |
| [ ] **LAN-017 — Add offline QR encode/scan component** | L4 / P1 / NOT_STARTED | 006,009; app scanner/dependencies/manifest | Verify and pin CameraX/ZXing; local QR decode/encode, bounded frame analysis, lifecycle/flashlight, camera optional and paste alternative | M; may overlap 014–016; offline/no-GMS, repeat-frame, camera-release and accessibility tests; dependency/license record |
| [ ] **LAN-018 — Integrate Receive QR/paste pairing lifecycle** | L4 / P0 / NOT_STARTED | 013,017; readiness/pairing ViewModel/sheet | Mint after listener ready, generation-bound countdown/refresh/revoke; explicit scan/paste confirmation; no saved-state secret | M; sequential after scanner; expiry/refresh/background/rotation/replay UI and real M03 evidence |
| [ ] **LAN-019 — Complete unified Send/Receive/Quick Share UX** | L4 / P1 / NOT_STARTED | 016,018; nav/cards/transfer UI | One peer surface, real authentication variants, same offer/progress/results, route subtitle and busy state; no core calls in Composables | M; sequential; E2E Nearby and LAN paths with multi-selection and consent semantics, light/dark screenshots |
| [ ] **LAN-020 — Add actionable discovery and pairing errors** | L4 / P1 / NOT_STARTED | 019; failure presentation/diagnostic events | Section-20 taxonomy, empty/denied/no-network/unsupported/expired states, QR troubleshooting without promising to fix isolation | S; can overlap 021; redaction/mapping/keyboard/TalkBack tests; verify no raw exception exposure |
| [ ] **LAN-021 — Connect Share Sheet to the same preparation flow** | L5 / P1 / NOT_STARTED | 019,004; ingress/MainActivity/preparation | Reuse parser; validate every URI/grant, retain full set, handle onNewIntent/task recreation without duplicate transfer | M; parallel with 022/023 after deps; malicious intent/duplicate/multiple source instrumentation and M04 evidence |
| [ ] **LAN-022 — Harden sequential batches and source/storage preflight** | L5 / P0 / NOT_STARTED | 019; preparation/core/data | All item/source pairs, checked sizes, stable source/staging, space reservation, provider failure, safe unfinished retry; no concurrency/resume | M; parallel with 021/023; 1,000-item, zero-byte, unknown-size, low-space/revoked provider tests; bounded memory report |
| [ ] **LAN-023 — Complete verified results, history and safe file actions** | L5 / P1 / NOT_STARTED | 019; result/history/catalog/export | Show every verified item, safe MIME/open/share/export/keep-both, separate history deletion, unconfirmed outcome; preserve source on export failure | M; parallel with 021/022; catalog/migration if needed, duplicate/path/provider/no-viewer tests; result UX evidence |
| [ ] **LAN-024 — Correct progress, ETA and notification actions** | L5 / P1 / NOT_STARTED | 022,023; session projections/notifications | Monotonic batch-rate EMA, <=5Hz UI/1Hz notification, verifying state, exact active-session actions and lock-screen redaction | S; overlaps 025; virtual clock/item boundary/stale-action and rendering tests; privacy/performance capture |
| [ ] **LAN-025 — Finish per-action permissions and background behavior** | L5 / P0 / NOT_STARTED | 019,003; policy/service/manifest | API matrix, LAN independent of Bluetooth, foreground prerequisite/start-fail path, notification denial, scanner background stop, cleanup on revocation | M; overlaps 024; API29/33/36opt-in/37 device tests, M06 and M07, no background survival guarantee |
| [ ] **LAN-026 — Execute adversarial TLS/bootstrap/protocol review** | L6 / P0 / NOT_STARTED | 020,022,025; security tests | All section-20 negative cases through actual adapters; inspect pin trust, token consumption and file authority; fix demonstrated failures | M; parallel with 027–030; independent review plus regression cases; unresolved high risk blocks release |
| [ ] **LAN-027 — Validate network/process/storage chaos** | L6 / P0 / NOT_STARTED | 021,022,023,025; integration/device suites | M07/M08 kill/revoke/rename/catalog crash/network switch/isolation/VPN/IPv6; no lost verified data/leaked listeners | M; parallel 026/028/029; redacted reproducible scenario logs and actual cleanup observations |
| [ ] **LAN-028 — Measure large-file, thermal and resource budgets** | L6 / P1 / NOT_STARTED | 022,024,025; benchmark/tests | Section-21 corpus and same-device baseline, >2/4 GiB boundaries, >5 GiB real when feasible, 1,000 small files, slow source/disk | M; parallel 026/027/029; p50/p95/heap/RSS/CPU/thermal/raw hashes, no invented throughput claims |
| [ ] **LAN-029 — Review accessibility/adaptive and scanner quality** | L6 / P1 / NOT_STARTED | 020,023,024,025; UI tests/design | TalkBack, noncamera pairing, 200% text, keyboard/landscape/foldable, light/dark/dynamic, low-light QR, reduced motion | M; parallel other gates; M09 real screenshots/semantics manual evidence; fix only changed flows |
| [ ] **LAN-030 — Extend CI with behavioral LAN gates** | L6 / P1 / NOT_STARTED | 012,017,021; CI/report collectors | Real loopback/vector/module tests, emulator smoke, report presence; separate documentation checks; no signing in PR | S; can begin before other L6 gates; clean CI run on exact SHA and redacted artifact links |
| [ ] **LAN-031 — Re-run Nearby and complete regression gate** | L6 / P0 / NOT_STARTED | 026,027,028,029,030; existing/new tests | Prove all adapter/core port changes preserve actual Nearby selection/auth/batch/background; resolve all P0/P1 failures | M; final L6 gate; full relevant suite + M01 after final fixes, reviewed evidence index |
| [ ] **LAN-032 — Prepare and verify signed LAN/QR release candidate** | L7 / P1 / NOT_STARTED | 031; release docs/config/artifacts | Update permission/privacy/licenses/support boundaries, signed RC/install/update, source/artifact identity, local disable-LAN rollback validation | M; sequential; M10 and complete gate dossier; human release decision remains pending |
| [ ] **LAN-033 — Controlled rollout and handoff** | L7 / P1 / NOT_STARTED | 032 + explicit publication approval; existing release workflow | Publish approved artifact in stages, watch approved privacy-safe metrics/support, halt on integrity/auth/OEM regressions; leave maintenance handoff | S; not parallel; release/version/hash/approval/rollout evidence; never infer approval from task status |

Task graph beyond phases: 006/007/008 may run independently after their prerequisites; 010 and 011 can run together; 017 can run beside NSD/router work; 021/022/023 can run together with coordination on shared preparation files; 026–030 are separate validation streams; 031 waits for all of them. Prefer separate branches or coordinated file ownership when parallel implementation is authorized. Only checked COMPLETE prerequisites permit starting dependent code.

Optional/future rows in section 15 are explicitly not extra release tasks. A new convenience task requires its own accepted scope/dependency/evidence row; do not overload LAN-033 with resume, desktop, trust or cloud features.

## 27. Recommended First Implementation Task

**LAN-001 — Enforce exact inbound session and stream ownership.**

Why first: the current coordinator consumes decoded messages from the expected peer without checking that the envelope belongs to its session, and can associate an unrecognized incoming stream with the current item (S08). Adding LAN would create another entry path into that assumption. This is a small, testable correction to the existing architecture and protocol invariant, with no dependency, UI or branding change.

Read TransferCoordinator, ProtocolSequencer, TransferReducer, TransferTransport and existing coordinator loopback tests. Add negative tests first for valid-peer/wrong-session control and unknown/stale bulkRef; prove no reducer state advance or staging write, and prove rejected streams close. Enforce exact matches before dispatch/opening storage; retain ordinary valid-transfer behavior and existing fixtures. Run :transfer-core:test and relevant Nearby tests, record real results, and leave the task REVIEW_REQUIRED until reviewed. Do not start implementing QR, sockets or discovery in that task.

After LAN-001 is reviewed COMPLETE, LAN-002 is next. A new Gemini session can begin from this document and section 26 without inventing a new architecture. **No implementation task was started during this planning run.**

Planning checks performed: 27 required sections, 33 unique ordered tasks, eight phase totals, existing prerequisite IDs in acyclic order, one READY task, relative documentation-link resolution, and documentation-only diff inspection. An independent source-backed architecture/security review prompted the VPN, prepared-source ownership, actual-EOF, readiness-order and early-permission corrections recorded above. Application build/tests and physical transfer evidence remain implementation tasks; none is claimed as run by this planning change.
