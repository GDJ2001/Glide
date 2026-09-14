# ADR-0017 — Android-to-Windows interoperable LAN slice

- Status: Accepted (future specification); validation pending implementation evidence.
- Date: 2026-09-14.
- Authority: [Android-to-Windows LAN specification](../future/ANDROID_WINDOWS_LAN_SPECIFICATION.md); [decision index](README.md); [advanced transports evaluation](ADR-0016-advanced-android-transports.md); [future path](../future/CROSS_PLATFORM_PATH.md); [threat model](../security/GLIDE_THREAT_MODEL.md).

## Context
Glide V1 established high-throughput, private-quarantine sharing between Android devices using Google Play Services (GMS) Nearby Connections P2P (ADR-0007). Seamless cross-platform sharing with Windows desktop computers represents the highest-priority ecosystem expansion for users. However, Microsoft Windows does not natively support GMS Nearby Connections P2P. Google's closed-source Windows Nearby Share binary is proprietary, unmaintained for third-party integration, and unsuitable as an open engineering dependency.

To achieve fast, reliable, and private Android-to-Windows file sharing without compromising Glide's core invariants, an open, standards-compliant transport architecture must be planned.

## Options considered
1. **Reverse-Engineered Nearby Connections for Windows:** Attempting to interface with Google's proprietary Windows Nearby Share binaries or protocols. Rejected due to extreme maintenance fragility, absence of public APIs, terms-of-service violations, and risk of silent upstream breakage.
2. **Cloud Relay / WebRTC Signaling:** Routing discovery and transfer through internet servers or WebRTC signaling relays. Rejected because it violates Glide's fundamental invariant of **100% offline, local peer-to-peer operation**, introduces external server hosting costs, adds internet latency, and compromises user data privacy.
3. **Native Windows Adapter with Standard LAN Transport (mDNS + TLS 1.3):** Operating over the shared local Wi-Fi or Ethernet network using Multicast DNS (RFC 6762 / RFC 6763) for discovery and direct TCP stream sockets with application-layer TLS 1.3 (RFC 8446) for mutual authentication and encryption. Both platforms communicate using identical Glide Protocol V1 JSON envelopes verified against canonical golden fixtures.

## Decision
Adopt **Option 3: Native Windows Adapter with Standard LAN Transport (mDNS + TLS 1.3)**, operating 100% locally with zero cloud backend by default.

## Reasoning
- **Zero-backend privacy:** Operating entirely over the local subnet ensures user data never leaves the local network, requires zero internet access, and incurs zero infrastructure costs.
- **Protocol conformance:** Reuses the battle-tested Glide Protocol V1 state machine, sequence rules, receipt accounting, and golden JSON test fixtures without protocol fork.
- **High throughput:** Modern 802.11ax/be Wi-Fi routers and Gigabit Ethernet adapters deliver 300 to 800+ MB/s practical throughput with negligible CPU overhead.
- **Robust security:** Application-layer TLS 1.3 with mutual ephemeral certificate exchange ensures zero-trust transport security, bound directly to Glide's 6-digit Short Authentication String (SAS) or persistent TOFU key pinning (ADR-0015).

## Consequences
- **Independent Windows implementation:** Windows client (`Glide.Windows`) will be implemented as a native desktop application (C# .NET / WinUI 3 or Rust core) implementing the transport and protocol independently, validated against the 20+ canonical golden fixtures (`transfer-core/src/test/resources/fixtures/`).
- **Windows filesystem sanitization:** Windows path handling mandates strict security sanitization: rejecting parent directory traversal (`..`), drive letter prefixes (`C:`), UNC paths (`\\`), and reserved DOS device names (`CON`, `PRN`, `AUX`, `NUL`, `COM1-9`, `LPT1-9`).
- **Two-stage atomic staging:** Windows downloads must stage incoming chunks in private app data (`%LocalAppData%\Glide\staging\<session_id>\`) before atomic rename into `%USERPROFILE%\Downloads\`, guaranteeing the **User Data Sanctity Invariant**.
- **Android modularity:** Android LAN support will be implemented as a modular `:transport-lan` library implementing the Glide Core `TransportPort` interface, leaving the V1 Nearby transport untouched.

## Alternatives and migration/fallback
If peers are not on the same local network or if AP client isolation is enabled on enterprise Wi-Fi, the connection falls back to Tier 3 (Autonomous Wi-Fi Direct via BLE/QR out-of-band signaling per ADR-0016).
