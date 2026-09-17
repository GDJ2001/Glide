# Apple Platforms (iOS, iPadOS & macOS) Interoperability & Transport Specification

- **Document Version:** 1.0.0
- **Status:** Approved Technical Specification
- **Milestone:** M19 — Apple Platforms Native Architecture & Conformance Specification
- **Task Authority:** [CROSS_PLATFORM_PATH.md](CROSS_PLATFORM_PATH.md) Step 4
- **Architectural Decision:** [ADR-0020](../adr/ADR-0020-apple-platforms-architecture.md)
- **Related Documents:** [GLIDE_PROTOCOL_V1.md](../protocol/GLIDE_PROTOCOL_V1.md), [ANDROID_WINDOWS_LAN_SPECIFICATION.md](ANDROID_WINDOWS_LAN_SPECIFICATION.md), [ANDROID_BROWSER_WEB_SPECIFICATION.md](ANDROID_BROWSER_WEB_SPECIFICATION.md), [GLIDE_THREAT_MODEL.md](../security/GLIDE_THREAT_MODEL.md).

---

## 1. Executive Summary & Architectural Invariants

### 1.1 Purpose
This specification defines the complete architectural design, network wire protocols, cryptographic security contracts, lifecycle strategies, and quarantine storage rules for **native Glide clients across Apple platforms (iOS, iPadOS, and macOS)**, delivering seamless, high-speed local file sharing with Android, Windows, and Web Browser peers.

### 1.2 Core Invariants
1. **Zero Cloud / 100% Offline by Default:**
   All discovery, pairing, and payload transfers occur strictly over the shared local Wi-Fi or Ethernet subnet. No Apple Push Notification service (APNs), iCloud sync, WebRTC signaling, or internet relay servers are used or required.
2. **Canonical Protocol Parity:**
   The Apple native implementation strictly adheres to **Glide Protocol V1** envelope schemas, framing rules (`LanRecord`), batch negotiation, and item outcome states without creating proprietary dialect forks.
3. **Hardware-Accelerated Zero-Trust Security:**
   Network connections mandate **TLS 1.3** utilizing Apple's `Network.framework` and `CryptoKit` with hardware acceleration on Apple Silicon (M-series / A-series). Unencrypted or legacy TLS versions are forbidden.
4. **Strict Quarantine & Data Sanctity:**
   Incoming files are streamed directly into isolated application container quarantine storage (`<AppContainer>/Library/Caches/staging/<sessionId>/<itemId>.quarantine`). Files are atomically committed to destination storage (`Downloads`, `Documents`, or `PHPhotoLibrary`) only after single-pass SHA-256 validation succeeds.
5. **App Store & Platform Sandbox Conformance:**
   Fully compliant with Apple App Sandbox rules, App Store Review Guidelines (Guideline 2.5.4 regarding background execution), and modern permission requirements (`NSLocalNetworkUsageDescription`, `NSCameraUsageDescription`).

---

## 2. System Architecture & Native Component Mapping

The Apple platform architecture is structured around a shared, pure Swift package (`GlideCoreKit`) integrated into two platform target applications:

```
+-----------------------------------------------------------------------------------+
|                           Apple Platform Ecosystem                                |
|   +---------------------------------------+   +-------------------------------+   |
|   |         iOS / iPadOS Native App       |   |      macOS Desktop Companion   |   |
|   |          (SwiftUI + ShareSheet)       |   |       (AppKit / Menu Bar)     |   |
|   +---------------------------------------+   +-------------------------------+   |
|   |                      GlideCoreKit (Shared Swift Package)                  |   |
|   |  - Protocol V1 Codec & Envelopes (Validated against Golden Fixtures)      |   |
|   |  - SAS Calculator (RFC 5869 HKDF-SHA256) & QR Codec                       |   |
|   |  - Quarantine Pipeline & CryptoKit SHA-256 Streaming Verifier             |   |
|   |  - POSIX / Darwin Path Sanitizer & Collision Resolver                     |   |
|   +---------------------------------------------------------------------------+   |
|   |                     Apple Network.framework Transport Engine               |   |
|   |  - NWListener (Port 42424 / Ephemeral) & NWConnection (TLS 1.3)           |   |
|   |  - Bonjour mDNS Discovery (_glide._tcp.local.) via NWBrowser              |   |
|   |  - SPKI Public Key Pinning via sec_protocol_options_set_verify_block      |   |
|   +---------------------------------------------------------------------------+   |
+-----------------------------------------------------------------------------------+
                                         ^
                                         | Local Wi-Fi Subnet / LAN Profile 1
                                         | (TLS 1.3 Framing & mDNS)
                                         v
+-----------------------------------------------------------------------------------+
|                          Interoperating Peer Endpoints                            |
|     +-------------------------+  +----------------------+  +------------------+   |
|     |  Android (dev.glide.app)|  | Windows (Glide.slnx) |  | Web Browser (SPA)|   |
|     +-------------------------+  +----------------------+  +------------------+   |
+-----------------------------------------------------------------------------------+
```

---

## 3. Network.framework Transport & Wire Protocol Mapping

Apple's modern `Network.framework` provides socket-level control without the overhead or security risks of deprecated BSD sockets.

### 3.1 Listener Configuration (`NWListener`)
```swift
let tcpOptions = NWProtocolTCP.Options()
tcpOptions.enableKeepalive = true
tcpOptions.keepaliveIdle = 10

let tlsOptions = NWProtocolTLS.Options()
sec_protocol_options_set_min_tls_protocol_version(tlsOptions.securityProtocolOptions, .TLSv13)
sec_protocol_options_set_max_tls_protocol_version(tlsOptions.securityProtocolOptions, .TLSv13)

// Bind identity containing ephemeral self-signed X.509 certificate
sec_protocol_options_set_local_identity(tlsOptions.securityProtocolOptions, localSecIdentity)

let parameters = NWParameters(tls: tlsOptions, tcp: tcpOptions)
parameters.includePeerToPeer = true // Enables Wi-Fi Direct / Local link-local discovery
let listener = try NWListener(using: parameters, on: 42424)
```

### 3.2 TLS 1.3 Public Key Pinning (`sec_protocol_options_set_verify_block`)
For QR-bootstrapped pairing sessions, mutual trust is established by verifying the peer certificate's SubjectPublicKeyInfo (SPKI) SHA-256 hash:
```swift
sec_protocol_options_set_verify_block(tlsOptions.securityProtocolOptions, { (metadata, sec_trust, completionHandler) in
    guard let serverCert = SecTrustGetCertificateAtIndex(sec_trust, 0) else {
        completionHandler(false)
        return
    }
    let spkiData = extractSubjectPublicKeyInfo(from: serverCert)
    let computedHash = SHA256.hash(data: spkiData).map { String(format: "%02x", $0) }.joined()
    let isValid = (computedHash == expectedSpkiSha256)
    completionHandler(isValid)
}, dispatchQueue)
```

### 3.3 Framing & Record Serialization
All wire traffic adheres strictly to Glide LAN Profile 1 (`LanRecord`):
- **Header:** 4 bytes
  - Byte 0: Record Kind (`0x01` = Control Envelope, `0x02` = Data Chunk, `0x03` = Stream End, `0x04` = Bootstrap, `0x05` = Bootstrap Result).
  - Bytes 1–3: 24-bit unsigned big-endian payload length (maximum $16{,}777{,}215$ bytes; control bounded to $16{,}384$ bytes; data chunks bounded to $65{,}536$ bytes).
- **Payload:** Raw record bytes.

---

## 4. Bonjour Discovery & Cross-Platform Parity

### 4.1 Bonjour Service Registration
The listener registers as a Bonjour service in the `local.` domain:
```swift
listener.service = NWListener.Service(
    name: hostDisplayName,
    type: "_glide._tcp",
    domain: "local.",
    txtRecord: [
        "v": "1",
        "name": hostDisplayName,
        "spki": spkiSha256Hex,
        "presence": presenceTokenHex
    ]
)
```

### 4.2 Bonjour Browsing (`NWBrowser`)
Discovered endpoints on the local subnet are monitored continuously:
```swift
let descriptor = NWBrowserDescriptor.bonjour(type: "_glide._tcp", domain: "local.")
let browser = NWBrowser(for: descriptor, using: NWParameters())
browser.browseResultsChangedHandler = { results, changes in
    for result in results {
        // Parse TXT record and endpoint; present peer in SwiftUI discovery list
    }
}
```

---

## 5. Pairing & Authentication UX

### 5.1 Short Authentication String (SAS) Verification
For ambient, unauthenticated LAN connections:
1. Both peers extract the raw bytes of each other's ephemeral public keys after TLS 1.3 handshake completion.
2. An ephemeral 32-byte session nonce is exchanged in the initial record.
3. Both sides compute the 6-digit SAS code:
   $$\text{PRK} = \text{HKDF-Extract}(\text{salt}=\text{sessionNonce}, \text{IKM}=\text{localPubKey} \parallel \text{remotePubKey})$$
   $$\text{OKM} = \text{HKDF-Expand}(\text{PRK}, \text{info}="GLIDE-SAS-V1", L=4)$$
   $$\text{SAS} = (\text{uint32}(\text{OKM}) \land 0x7FFFFFFF) \pmod{1{,}000{,}000}$$
4. Both devices render the code (e.g. `[ 492 815 ]`). Both users must tap "Confirm" before offer metadata is assembled or presented.

### 5.2 Out-of-Band QR Code Pairing
- **Host (QR Display):**
  Renders the standard canonical pairing URI:
  `glide://pair/v1#invitationId=...&presence=...&name=MacBook+Pro&platform=macos&endpoints=192.168.1.120:42424&spkiSha256=...&token=...&expiresAt=...`
  Rendered using `CIFilter(name: "CIQRCodeGenerator")` at high contrast.
- **Client (Camera Scan):**
  Uses `AVCaptureSession` with `AVCaptureMetadataOutput` configured for `.qr`.
  Instantly connects to the specified endpoint, validates the pinned SPKI hash during TLS handshake, and submits the `LanBootstrapMessage`. Transfers begin immediately with zero SAS prompts.

---

## 6. iOS Lifecycle & macOS Companion Lifecycle Management

### 6.1 iOS / iPadOS Lifecycle Strategy
1. **Active Screen Retention:** During active transfer streaming, `UIApplication.shared.isIdleTimerDisabled = true` keeps the device awake.
2. **Background Transition Handling:**
   When the user locks the phone or switches apps during an active batch, the app requests background time:
   ```swift
   var bgTaskId = UIBackgroundTaskIdentifier.invalid
   bgTaskId = UIApplication.shared.beginBackgroundTask(withName: "GlideActiveTransfer") {
       // Expiration handler: executed when system is about to terminate background time (~30 seconds)
       Task {
           await sessionCoordinator.handleSystemBackgroundExpiration()
           UIApplication.shared.endBackgroundTask(bgTaskId)
           bgTaskId = .invalid
       }
   }
   ```
3. **Orderly Cancellation with Receipts:**
   If the transfer cannot complete within the 30-second window, Glide completes the currently streaming file if possible, records its verified receipt in local history, sends `CANCEL(code=SYSTEM_CANCELLED)`, and shuts down the connection gracefully.
4. **App Store Compliance Invariant:**
   The app **never** registers fake audio, VoIP, or location background modes to stay alive indefinitely. All transfers adhere to standard iOS sandbox limits.

### 6.2 macOS Companion Lifecycle Strategy
1. **Menu Bar Agent Mode:**
   macOS app configures `LSUIElement = true` in `Info.plist`, presenting a clean menu bar icon (`NSStatusItem`).
2. **Continuous Reception:**
   Runs continuously in the user's login session, listening on port `42424` and advertising via Bonjour.
3. **Finder & Drag-and-Drop Integration:**
   Supports dragging files directly onto the menu bar icon or dock to initiate an immediate transfer to discovered peers.

---

## 7. Sandboxed Storage, Quarantine Pipeline & Filesystem Sanitization

### 7.1 Quarantine Staging Store
- Incoming streams are written directly to sandboxed quarantine files:
  - iOS: `FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("staging/\(sessionId)/\(itemId).quarantine")`
  - macOS: `~/Library/Caches/dev.glide.companion/staging/<sessionId>/<itemId>.quarantine`
- Single-pass streaming incremental SHA-256 calculation using `CryptoKit.SHA256`:
  ```swift
  var hasher = SHA256()
  // As chunks arrive:
  hasher.update(data: chunk)
  fileHandle.write(chunk)
  // End of stream:
  let computedDigest = hasher.finalize().map { String(format: "%02x", $0) }.joined()
  ```

### 7.2 Atomic Commitment & Purge
- **Verified Match:**
  `FileManager.default.moveItem(at: quarantineUrl, to: destinationUrl)` moves the file atomically.
  - On macOS: Saved to `~/Downloads` with collision resolution (`vacation (1).jpg`).
  - On iOS: Saved to application `Documents` or directly exported to the user's Photos library via `PHPhotoLibrary.shared().performChanges` if media type is image/video.
- **Mismatch or Abort:**
  Quarantine file is immediately deleted (`FileManager.default.removeItem(at: quarantineUrl)`). Zero corrupted or unverified bytes ever touch user-visible storage.

### 7.3 Filesystem Sanitization Rules
All incoming filenames are sanitized before commitment:
- Strips directory traversal sequences (`..`).
- Strips forward slashes (`/`), colons (`:`), and backslashes (`\`).
- Normalizes Unicode to Form C (`precomposedStringWithCanonicalMapping`).
- Strips non-printable ASCII and bidirectional control characters.
- Truncates filename to 255 UTF-8 bytes while preserving the file extension.

---

## 8. App Permissions & Entitlements

### 8.1 iOS / iPadOS `Info.plist`
```xml
<key>NSLocalNetworkUsageDescription</key>
<string>Glide requires local network access to discover nearby devices and transfer files at high speed over Wi-Fi.</string>

<key>NSBonjourServices</key>
<array>
    <string>_glide._tcp</string>
</array>

<key>NSCameraUsageDescription</key>
<string>Glide requires camera access to scan pairing QR codes on sender devices.</string>

<key>NSPhotoLibraryAddUsageDescription</key>
<string>Glide requires permission to save received photos and videos directly to your Photos library.</string>
```

### 8.2 macOS App Sandbox Entitlements (`Glide.entitlements`)
```xml
<dict>
    <key>com.apple.security.app-sandbox</key>
    <true/>
    <key>com.apple.security.network.client</key>
    <true/>
    <key>com.apple.security.network.server</key>
    <true/>
    <key>com.apple.security.files.user-selected.read-write</key>
    <true/>
    <key>com.apple.security.files.downloads.read-write</key>
    <true/>
</dict>
```

---

## 9. Multi-Phase Implementation Roadmap & Conformance Test Plan

- **Phase A1: Shared Swift Core Engine (`GlideCoreKit`)**
  - Implement V1 protocol envelopes, JSON codecs, SAS calculator, and `LanRecord` framing in pure Swift.
  - Validate against the 20+ canonical golden fixtures.
- **Phase A2: Network.framework Transport Engine**
  - Implement `NWListener` and `NWConnection` with TLS 1.3 and Bonjour service registration (`_glide._tcp`).
  - Implement SPKI hash pinning and SAS pairing.
- **Phase A3: Quarantine Staging & Digest Verification**
  - Implement streaming quarantine writer with `CryptoKit.SHA256`, atomic commitment, and path sanitization.
- **Phase A4: iOS SwiftUI Application & Share Extension**
  - SwiftUI interface matching Glide design tokens (dark/light themes, active progress, transfer history).
  - Share Sheet Extension (`UIActivityViewController`) and QR camera scanner.
- **Phase A5: macOS Menu Bar Desktop Companion**
  - `LSUIElement` menu bar app with drag-and-drop file target and local network peer drawer.
- **Phase A6: Cross-Platform Interoperability & Conformance Certification**
  - Automated loopback tests and end-to-end integration tests with Android and Windows endpoints.
