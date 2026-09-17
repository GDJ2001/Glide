# Future cross-platform path
Strategic only. [Protocol](../protocol/GLIDE_PROTOCOL_V1.md) · [architecture](../architecture/GLIDE_ARCHITECTURE.md) · [future tasks](../TODO_MASTER.md). No non-Android implementation is authorized by V1 planning.

1. Android↔Android: validate protocol and user trust/storage model on real phones (Delivered: Nearby STREAM + LAN Profile 1).
2. Android↔Windows: LAN discovery and standard authenticated encrypted transport; native Windows client (`Glide.Windows` in `windows/Glide.slnx`) conforming to golden fixtures and LAN Profile 1 (Delivered: [Specification](ANDROID_WINDOWS_LAN_SPECIFICATION.md) · [Release Report](../release/GLIDE_WINDOWS_COMPANION_RELEASE_AND_VERIFICATION.md)).
3. Android↔Browser: zero-install local web transfer gateway; embedded offline HTTP/1.1 and WebSocket server, one-use QR onboarding, zero-CDN SPA, and chunked streaming (Approved: [ADR-0019](../adr/ADR-0019-browser-web-local-transfer.md) · [Specification](ANDROID_BROWSER_WEB_SPECIFICATION.md)).
4. iOS/iPadOS and macOS: native lifecycle/background/sandbox/permissions and authentication UX; evaluate official platform transport support (Approved: [ADR-0020](../adr/ADR-0020-apple-platforms-architecture.md) · [Specification](APPLE_PLATFORMS_SPECIFICATION.md)).
5. Linux: LAN adapter and desktop integration using same conformance suite (`Glide.Core` and `Glide.App` .NET 10 cross-platform runtime) (Approved: [ADR-0021](../adr/ADR-0021-linux-platform-architecture.md) · [Specification](LINUX_PLATFORM_SPECIFICATION.md)).
6. Remote/internet: separate consent, identity, NAT traversal, signaling/relay abuse, privacy, cost and backend threat model. Local V1 needs no server.


Portable contracts: envelope/major negotiation, UUID format, decimal byte counts, capability limits, SHA256 raw-byte digest, metadata validation, session/item outcomes, wire errors, receipts, cancel semantics and golden fixtures. Portable conceptual domain is not a commitment to share Kotlin binaries.

Android-specific: GMS Nearby, runtime permissions, FGS/lifecycle, ContentResolver/SAF/MediaStore/FileProvider, Room/DataStore, Keystore bindings, Compose and notification system. Transport security contract must be met independently on each platform; TLS certificate validation and human pairing require a standard reviewed design, not custom short-code crypto.

Folder transfer/trust/resume are versioned extensions after explicit review, not implied by transport abstraction. Existing iOS/web/desktop icons are brand exports only. Never add KMP, WebRTC, sockets or a cloud backend to Android V1 merely to prepare this future.

