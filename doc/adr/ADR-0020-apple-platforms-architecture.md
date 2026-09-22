# ADR-0020 — Apple Platforms (iOS, iPadOS, macOS) Native Architecture & Transport Strategy

- **Status:** Accepted
- **Date:** 2026-09-17
- **Authority:** [Cross-Platform Path](../future/CROSS_PLATFORM_PATH.md) Step 4 | [Apple Platforms Specification](../future/APPLE_PLATFORMS_SPECIFICATION.md) | [ADR-0018](ADR-0018-lan-qr-authentication-and-routing.md)
- **Supersedes / Extends:** Fulfills Step 4 of [CROSS_PLATFORM_PATH.md](../future/CROSS_PLATFORM_PATH.md); extends [ADR-0008 (Protocol)](ADR-0008-protocol.md) and [ADR-0018 (LAN/QR Profile 1)](ADR-0018-lan-qr-authentication-and-routing.md) to Apple operating systems (iOS, iPadOS, macOS).

---

## Context

Glide V1 established high-throughput private sharing for Android via Nearby Connections P2P (ADR-0007) and Local LAN TLS 1.3 (ADR-0018), while the companion desktop client (`Glide.Windows`) established interoperability for Windows 10/11 (ADR-0017). Furthermore, the zero-install web gateway (ADR-0019) enables instant browser-based file transfer to and from Safari on iOS and macOS without installing an app.

However, browser-based sharing imposes practical operational boundaries:
1. **No Background Operations:** Safari tabs must remain in the active foreground with screen unlocked to prevent TCP connection termination.
2. **No Native Local Discovery:** Browsers cannot participate in multicast DNS (`_glide._tcp.local.`) peer discovery and require manual physical QR code scanning.
3. **No OS Integration:** Web pages cannot integrate into the iOS Share Sheet (`UIActivityViewController`), macOS Finder Quick Actions / Context Menus, or the system notification center.
4. **Restricted Storage:** Browsers download files sequentially to the browser download cache, lacking direct write access to user photo libraries (`PHPhotoLibrary`) or custom destination directories.

To achieve premier native performance, background reliability, and deep ecosystem integration on Apple devices (iPhone, iPad, Mac) while preserving Glide's zero-cloud, 100% offline security invariants, a native Apple platform architecture must be established.

Apple operating systems enforce distinct architectural invariants:
- **Sandbox & Permissions:** iOS 14+ mandates explicit user consent for local network access via `NSLocalNetworkUsageDescription`. macOS App Sandbox enforces strict network client/server entitlements (`com.apple.security.network.client`, `com.apple.security.network.server`) and scoped file access.
- **Background Execution:** iOS limits background socket execution. Arbitrary long-running TCP sockets are suspended immediately when an app enters the background unless bounded by `beginBackgroundTask(expirationHandler:)`.
- **Modern Networking:** Apple deprecates raw BSD sockets and `CFSocket` in favor of `Network.framework` (`NWListener`, `NWConnection`, `NWBrowser`), which manages Wi-Fi/Ethernet interfaces, multipath routing, power state, and hardware-accelerated TLS.

---

## Options Considered

### 1. Apple MultipeerConnectivity Framework
- *Pros:* Built-in Apple-to-Apple P2P discovery and Wi-Fi Direct / AWDL data transfer.
- *Cons:* **Completely proprietary to Apple.** MultipeerConnectivity cannot discover, connect, or transfer data to Android devices or Windows workstations. Using it would fork the protocol into incompatible silos. **Rejected.**

### 2. CoreBluetooth (BLE) for Bulk Transfer
- *Pros:* Universal wireless availability across all modern devices.
- *Cons:* Severe physical bandwidth constraints (~100–200 KB/s in practice). Multi-gigabyte file transfers would require days, causing thermal throttling and battery drain. **Rejected for data transport; reserved for potential out-of-band presence beaconing only.**

### 3. WebRTC Data Channels (Local PeerConnection)
- *Pros:* Cross-platform data channel abstractions.
- *Cons:* Heavy native dependency footprint (libwebrtc is >50MB binary size), complex ICE negotiation, and still requires local signaling. Excessively bloated compared to lightweight TLS sockets. **Rejected.**

### 4. Apple `Network.framework` over Local Wi-Fi LAN with TLS 1.3 & Bonjour (Adopted)
- *Pros:*
  - **100% Interoperability:** Uses the exact same IETF standards-compliant LAN Profile 1 (ADR-0018) as Android (`NsdManager` + TLS 1.3) and Windows (`TlsSocketListener` + mDNS).
  - **Zero Third-Party Dependencies:** `Network.framework` and `CryptoKit` are built directly into iOS 13+ and macOS 10.15+, adding zero megabytes to the application binary.
  - **Hardware Acceleration:** Hardware-accelerated ChaCha20-Poly1305 / AES-256-GCM wire encryption via Apple Silicon Secure Enclave and Secure Transport.
  - **Native Bonjour Support:** First-class integration with `_glide._tcp.local.` via `NWBrowser` and `NWListener.service`.
  - **Battery & Radio Optimization:** `Network.framework` coordinates with iOS power assertions and low-power modes better than legacy BSD sockets.
- *Cons:* Requires active Wi-Fi or local Ethernet connectivity.

---

## Decision

Adopt **Option 4: Apple `Network.framework` over Local Wi-Fi LAN with TLS 1.3 & Bonjour**, governed by the following architectural and security contracts:

### 1. Transport & Wire Protocol Mapping
- **Listener:** `NWListener` bound to port `42424` (or dynamic ephemeral port `0` if unavailable) on active Wi-Fi / Ethernet interfaces.
- **Client Connection:** `NWConnection` targeting discovered peer IP/port endpoints.
- **TLS 1.3 Configuration:**
  - Ephemeral self-signed X.509 certificate generated in-memory using `Security.framework` (`SecKeyCreateRandomKey`, `SecCertificateCreateWithData`) with a 24-hour expiration.
  - Custom TLS verification block (`sec_protocol_options_set_verify_block`) enforcing SPKI SHA-256 public key pinning for QR-bootstrapped sessions.
  - Rejection of unencrypted or TLS 1.2 fallbacks.
- **Wire Framing:** Conforms strictly to Glide's `LanRecord` framing (1-byte Kind, 3-byte payload length, payload bytes) and V1 JSON Envelope codecs.

### 2. Bonjour Discovery & Cross-Platform Parity
- Register and advertise service type `_glide._tcp` in the `local.` domain.
- TXT records encode:
  - `v`: Protocol version (`1`).
  - `name`: User-facing display name (Unicode, 1–64 code points, $\le$ 256 bytes).
  - `spki`: Lowercase hex SHA-256 digest of the server's SubjectPublicKeyInfo.
  - `presence`: 16-byte random presence token.
- Discovered peers display in the native UI identically across Android, Windows, iOS, and macOS.

### 3. Authentication & Pairing Contracts
- **Unpaired / Ambient LAN Discovery:**
  - Upon connection, peers execute the Short Authentication String (SAS) calculation:
    $$\text{SAS} = \text{HKDF-Extract-Expand}(\text{LocalPubKey} \parallel \text{RemotePubKey} \parallel \text{SessionNonce}) \pmod{1{,}000{,}000}$$
  - Both devices display the identical 6-digit verification code. User confirmation required before offer presentation.
- **Out-of-Band QR Pairing (`glide://pair/v1#...`):**
  - Host generates and displays high-contrast QR code using `CoreImage` (`CIQRCodeGenerator`).
  - Joining peer scans QR code using `AVFoundation` (`AVCaptureSession` + `AVMetadataMachineReadableCodeObject`).
  - Establishes pinned TLS 1.3 connection without SAS prompt by verifying SPKI hash and 256-bit bearer token via `LanBootstrapMessage`.

### 4. Background Execution & Lifecycle Management
- **iOS / iPadOS Lifecycle:**
  - Active transfers are foreground-primary. The UI displays an active progress surface with screen-dimming prevention (`UIApplication.shared.isIdleTimerDisabled = true`).
  - When the app transitions to the background during an active transfer, Glide invokes `UIApplication.shared.beginBackgroundTask(expirationHandler:)` to obtain a temporary background execution extension (~30 seconds).
  - If the background task approaches expiration before the current batch completes, Glide performs an orderly disconnect: completes the in-flight file if possible, commits verified receipts, sends `CANCEL(code=SYSTEM_CANCELLED)`, and shuts down the socket.
  - No unlawful background audio, location, or VoIP entitlement hacks are permitted.
- **macOS Companion Lifecycle:**
  - Runs as a lightweight menu bar companion (`LSUIElement = true` / `NSStatusItem`).
  - Operates continuously in the user login session without background execution restrictions.

### 5. Sandboxed Storage, Quarantine Pipeline & Filesystem Sanitization
- **Quarantine Staging Store:**
  - Incoming streams are written directly to sandboxed quarantine files:
    - iOS: `<AppContainer>/Library/Caches/staging/<sessionId>/<itemId>.quarantine`
    - macOS: `~/Library/Caches/dev.glide.companion/staging/<sessionId>/<itemId>.quarantine`
  - Streaming SHA-256 digest computed in real-time via `CryptoKit.SHA256` ($O(1)$ memory).
- **Atomic Commitment & Purge:**
  - On verified match: atomically moved (`FileManager.default.moveItem`) to destination (`Downloads` on macOS; app Documents or `PHPhotoLibrary` for photos/videos on iOS).
  - On mismatch or cancellation: staging file is purged immediately.
- **Filesystem Sanitization:**
  - Filenames sanitized to POSIX/Darwin standards: stripped directory traversal (`..`), forward slashes (`/`), colons (`:`), control characters, and capped at 255 UTF-8 bytes.

### 6. App Permissions & Entitlements
- **iOS / iPadOS (`Info.plist`):**
  - `NSLocalNetworkUsageDescription`: Clear explanation for Bonjour and local peer transfer.
  - `NSCameraUsageDescription`: Required for scanning pairing QR codes.
  - `NSPhotoLibraryAddUsageDescription`: Required for saving shared photos and videos directly to the Photos app.
- **macOS (`Glide.entitlements`):**
  - `com.apple.security.network.client`: Outbound socket connections.
  - `com.apple.security.network.server`: Inbound listener on port 42424.
  - `com.apple.security.files.user-selected.read-write`: User-chosen download directories.

---

## Consequences

- **Ecosystem Unification:** Apple devices participate directly in the Glide peer mesh using native Bonjour discovery and high-throughput TLS 1.3 LAN transport.
- **Zero Cloud Invariant Preserved:** Operates 100% offline on local Wi-Fi / Ethernet subnets without Apple cloud relays, APNs, or third-party servers.
- **Performance & Battery:** Native `Network.framework` delivers maximum transfer speed (50–100+ MB/s) with optimal power efficiency and zero third-party framework overhead.
- **Strict Compliance:** Adheres to all Apple App Store Review Guidelines, App Sandbox boundaries, and iOS background execution limits.
