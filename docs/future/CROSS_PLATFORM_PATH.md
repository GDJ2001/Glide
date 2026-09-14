# Future cross-platform path
Strategic only. [Protocol](../protocol/GLIDE_PROTOCOL_V1.md) · [architecture](../architecture/GLIDE_ARCHITECTURE.md) · [future tasks](../TODO_MASTER.md). No non-Android implementation is authorized by V1 planning.

1. Android↔Android: validate protocol and user trust/storage model on real phones.
2. Android↔Windows: LAN discovery and a reviewed standard authenticated encrypted transport; implement protocol independently in native Windows stack, run shared fixtures. Do not assume Windows supports Android Nearby.
3. Android↔Browser: explicit browser pairing/signaling design, local-network/browser permission and file-save constraints. Evaluate WebRTC with reviewed signaling; browser is not a raw socket client.
4. iOS/iPadOS and macOS: native lifecycle/background/sandbox/permissions and authentication UX; evaluate official platform transport support.
5. Linux: LAN adapter and desktop integration using same conformance suite.
6. Remote/internet: separate consent, identity, NAT traversal, signaling/relay abuse, privacy, cost and backend threat model. Local V1 needs no server.

Portable contracts: envelope/major negotiation, UUID format, decimal byte counts, capability limits, SHA256 raw-byte digest, metadata validation, session/item outcomes, wire errors, receipts, cancel semantics and golden fixtures. Portable conceptual domain is not a commitment to share Kotlin binaries.

Android-specific: GMS Nearby, runtime permissions, FGS/lifecycle, ContentResolver/SAF/MediaStore/FileProvider, Room/DataStore, Keystore bindings, Compose and notification system. Transport security contract must be met independently on each platform; TLS certificate validation and human pairing require a standard reviewed design, not custom short-code crypto.

Folder transfer/trust/resume are versioned extensions after explicit review, not implied by transport abstraction. Existing iOS/web/desktop icons are brand exports only. Never add KMP, WebRTC, sockets or a cloud backend to Android V1 merely to prepare this future.

