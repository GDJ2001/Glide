# ADR-0018 — Local LAN authentication, QR bootstrap and unified routing

- Status: **Accepted for planning; implementation and security validation pending**.
- Date: 2026-09-16.
- Baseline: GitHub main 6e1302f87d5681bc85788a0c1150241c91a12632.
- Authority: [LAN/QR/Quick Share master development plan](../planning/LAN_QR_QUICK_SHARE_MASTER_PLAN.md).
- Supersedes for the Android LAN/QR release: ADR-0017's custom SAS, mutual-certificate assumption, framing ambiguity, unsupported fallback and performance claims; associated sections of the [earlier Windows LAN proposal](../future/ANDROID_WINDOWS_LAN_SPECIFICATION.md). Does not supersede [V1 JSON protocol](../protocol/GLIDE_PROTOCOL_V1.md) or implement ADR-0015 persistent identity/resume.

## Context

The repository contains reusable Nearby/core/storage implementations but no wired LAN adapter or QR bootstrap. The source audit also found incomplete app transfer composition and exact session/stream invariant gaps. Existing future helpers are not evidence of TLS, discovery, persistent identity or resumability. Unauthenticated DNS-SD records and self-signed certificates alone cannot establish the intended device's identity.

The prior LAN proposal derived a short code by hashing public keys and a nonce without a reviewed authenticated key-exchange/SAS construction. Its continuous bulk stream description did not define safe control/data demultiplexing. Adopting those assumptions would make the next agent invent security-critical behavior.

## Options considered

1. Trust advertised names, fingerprint prefixes or the first certificate: rejected; discovery is attacker-controlled and TOFU without an authenticated first encounter does not solve impersonation.
2. Custom six-digit key hash/SAS: rejected; do not design a cryptographic authentication protocol around a short displayed hash.
3. Established PAKE or reviewed authenticated-channel binding for manually entered codes: potentially appropriate future work, but adds implementation/library/review requirements not needed for a QR-first release.
4. QR/full-invitation out-of-band server SPKI pin plus high-entropy one-use bearer invitation over platform TLS 1.3: selected for this release. Explicit receiver offer consent remains mandatory.
5. Bundled alternate TLS provider to support API 26–28 LAN: deferred; platform TLS 1.3 on API 29+ reduces provider/security maintenance while Nearby preserves older-device functionality.
6. HTTP/WebSocket or multiple independent control/data connections: feasible alternatives, but additional framework or connection-binding complexity is unnecessary for the first direct native LAN slice. Select one bounded, typed-record TLS stream.

## Decision

Implement one Android :transport-lan adapter behind the actual TransferTransport port, sharing the existing coordinator, JSON protocol, sequential item streaming, storage verification and receipts. First complete the existing runtime/session boundary with LAN-001–005.

Use NsdManager for bounded foreground DNS-SD. Its observations are hints. First-time LAN pairing uses the versioned invitation in master-plan section 8: exact SHA-256 SPKI pin, 256-bit one-use token, 120-second monotonic authority expiry and explicit local-network scope. TLS validates the pinned server before token or metadata transmission; the server authenticates invitation possession. Do not claim persistent sender identity or mutual certificate authentication. Do not allow plaintext/TLS1.2 fallback, global trust-all validation or application 0-RTT.

The LAN profile uses TLS 1.3, ALPN glide-lan/1, and typed records with explicit bounds for bootstrap, existing JSON control, DATA and STREAM_END. Completion still requires the existing per-item and batch verification/commit receipts. No offset resume or mid-session transport switch. The exact profile is specified once in master-plan sections 6 and 8 and frozen with golden fixtures before networking code.

Keep minSdk 26 and target/compile 37; gate platform LAN to API 29+. Request permissions by action so LAN does not require Bluetooth. Keep existing connectedDevice foreground service responsibility, but construct sessions within its scope instead of reattaching ViewModel-owned work. Keep discovery/scanning foreground and bounded.

A central router selects only implemented, supported, permitted, compatible routes with current endpoint/trust evidence. QR pins route choice. Never merge peers by name, IP or fingerprint prefix. Nearby and LAN observations may remain separate cards until an authenticated common binding exists. Future persistent trust/channel-binding is a separate decision; it is not silently implemented through favorite labels or TXT bits.

## Reasoning

This provides an implementable first encounter without inventing cryptography, keeps the file protocol portable, and isolates Android network/camera APIs. Temporary lease keys avoid a persistent tracking identity. A fully offline scanner and explicit paste alternative keep LAN independent of GMS availability. Reusing quarantine/receipt rules avoids a second, weaker file-publication path.

## Consequences

- Users must scan or paste a full invitation for an unpaired LAN route; mDNS discovery alone is insufficient.
- A copied live invitation grants temporary connection authority. One-use consumption, expiry, redaction and receiver consent limit misuse but do not prevent all denial of service.
- API 26–28 use Nearby in this release. A separate provider decision would be needed for secure LAN there.
- Same-device Nearby/LAN duplicates are possible until common ownership is proved. This is safer than incorrect merging.
- LAN record helpers and their document-compliance tests need intentional migration; no deployed LAN compatibility was established at the audit baseline.
- CameraX/ZXing dependencies are proposed only for the scanner task; no unrelated stack upgrade is authorized by this ADR.
- Browser support requires a separate browser-capable transport even though application semantics remain portable.

## Migration, fallback and validation

Follow the eight phases and 33 tasks in the master plan. Keep LAN disabled until the physical single-file and security gates pass. On reachability failure, return to selection or offer a separately authenticated same-peer route before transfer; authentication failure never triggers downgrade. After transfer starts, interrupt and offer a fresh-session retry from zero for unfinished items. LAN is disabled when an applicable VPN is active or route authority is ambiguous; do not bind around VPN policy. Validate accepted clients as well as outbound destinations against the selected directly connected network.

Retain historical documents with a precedence notice instead of rewriting evidence. Preserve Nearby wire fixtures. Freeze new profile fixtures, test real loopback TLS/Android Keystore, and record two-device evidence on the actual commit. Persistent identity, PAKE, true resume, Wi-Fi Direct and desktop/browser implementation remain future scope. Use [change control](../development/CHANGE_CONTROL.md) if a documented assumption fails; do not replace it with a security shortcut.

References: master-plan section 23 contains the dated official Android, TLS, DNS-SD and scanner research. This decision is a plan, not proof of a deployed or verified implementation.
