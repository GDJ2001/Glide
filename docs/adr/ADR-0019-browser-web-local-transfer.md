# ADR-0019 — Browser and Web Client Zero-Install Local Transfer Gateway

- **Status:** Approved Future Architecture Decision
- **Date:** 2026-09-17
- **Authority:** [Cross-Platform Path](../future/CROSS_PLATFORM_PATH.md) | [Browser Web Specification](../future/ANDROID_BROWSER_WEB_SPECIFICATION.md) | [ADR-0018](ADR-0018-lan-qr-authentication-and-routing.md)
- **Supersedes / Extends:** Fulfills Step 3 of [CROSS_PLATFORM_PATH.md](../future/CROSS_PLATFORM_PATH.md); extends [ADR-0008 (Protocol)](ADR-0008-protocol.md) and [ADR-0018 (LAN/QR)](ADR-0018-lan-qr-authentication-and-routing.md) to web browser runtimes.

---

## Context

Glide V1 established high-throughput private sharing for Android via Nearby Connections P2P (ADR-0007) and Local LAN TLS 1.3 (ADR-0018), while the companion desktop client (`Glide.Windows`) established interoperability for Windows 10/11 (ADR-0017). However, users frequently need to transfer files to and from secondary platforms—such as **Apple iOS (iPhone/iPad)**, **macOS**, **Linux desktops**, and **ChromeOS**—where installing a native companion application is either impractical (e.g. guest/unmanaged devices, locked-down environments) or introduces unnecessary friction for one-off transfers.

Every modern client device possesses an advanced, hardware-accelerated web browser (Apple Safari, Google Chrome, Mozilla Firefox, Microsoft Edge). Nevertheless, the web browser execution sandbox imposes fundamental constraints:
1. **No Raw Sockets:** Web browsers cannot bind raw TCP/UDP listener sockets or initiate raw TLS socket streams (`net.Socket`).
2. **No Native mDNS Discovery:** Web scripts cannot participate in multicast DNS (`_glide._tcp.local.`) queries or responses.
3. **Restricted Local Network Access:** Modern browsers enforce strict Private Network Access (PNA), Mixed Content restrictions, and Cross-Origin Resource Sharing (CORS) rules.
4. **Sandboxed Filesystem:** Browsers cannot arbitrarily inspect or write to host filesystem directories without explicit user interaction (e.g. HTML5 `<input type="file">`, File System Access API, or `<a download>`).

To enable seamless cross-platform sharing without compromising Glide's core security invariants, a standards-compliant, zero-install browser gateway architecture must be formally specified.

---

## Options Considered

1. **Public Cloud Signaling & Internet Relay (e.g. WebRTC STUN/TURN, AWS S3, or WebSocket Cloud Relays):**
   - *Pros:* Works across different networks and NATs.
   - *Cons:* **Violates Glide's Core Invariant of Zero Cloud / 100% Offline by Default.** Exposes user metadata, file transfers, and device locations to external infrastructure, imposes continuous operational hosting costs, and fails completely when internet connectivity is degraded or absent. **Rejected.**

2. **Mandating Native Client Applications for Every OS (iOS App Store, macOS DMG, Linux Flatpak):**
   - *Pros:* Full access to native sockets, background daemons, and system notifications.
   - *Cons:* High installation friction for recipient devices, long App Store review delays, developer account licensing costs, and complete barrier for guest devices. While native clients remain long-term roadmap targets (Steps 4 & 5), this does not address zero-install needs. **Deferred as distinct future steps.**

3. **Zero-Install Ephemeral Local Web Gateway (Adopted):**
   - *Pros:*
     - Works instantly on 100% of devices equipped with a browser and camera (iOS, macOS, Linux, ChromeOS, Android, Windows).
     - 100% local, zero cloud infrastructure, zero internet bandwidth consumption.
     - Ephemeral, self-contained single-page application (SPA) embedded directly in Glide memory—zero external CDN script dependencies.
     - Out-of-band QR onboarding providing instant bootstrap and cryptographic bearer authentication.
     - Full bi-directional transfer: browser can both download offered files and upload files to Glide.
   - *Cons:* Requires active Wi-Fi or local router connection; browser transfers are foreground-dependent (browser tab must remain open).

---

## Decision

Adopt **Option 3: Zero-Install Ephemeral Local Web Gateway**, governed by the following core architectural contracts:

### 1. Ephemeral Local Server Lifecycle
- When the user selects "Share to Web" (sender mode) or "Receive from Web" (receiver mode), Glide launches an embedded, lightweight HTTP/1.1 server (with WebSocket upgrade support) dynamically bound to port `0` (ephemeral port) on the active local network interface.
- The server binds strictly to the local Wi-Fi / Ethernet IP address, never to cellular or ambiguous interfaces.
- The server shuts down immediately when the transfer concludes, when the user exits the screen, or when the 10-minute availability lease expires.

### 2. Out-of-Band QR Onboarding & Bearer Token Authentication
- Discovery is performed out-of-band: Glide displays a QR code containing an HTTP URL with a high-entropy one-use authentication token:
  $$\text{URL} = \text{http://}\langle\text{Local-IP}\rangle\text{:}\langle\text{Port}\rangle\text{/?token=}\langle\text{TokenHex}\rangle$$
- The token is a 256-bit cryptographic random bearer credential (`SecureRandom` / `RandomNumberGenerator`).
- The token is valid for a single client session with a 120-second connection timeout and monotonic TTL. Unauthorized HTTP requests without a valid token receive HTTP 403 Forbidden.

### 3. Embedded Zero-CDN Single-Page Application (SPA)
- Glide serves an embedded HTML5 / modern CSS / ES6 web application compiled directly into the application binary.
- **Zero External Network Requests:** The web page makes zero calls to Google Fonts, external CDN scripts, analytics, or third-party styles. It is 100% functional in total isolation from the public internet.
- Branded with Glide's visual design system (dark/light theme tokens, responsive layout, accessible typography).

### 4. Wire Transfer Protocol & Browser Streaming
- **Glide $\rightarrow$ Browser (Send to Web):**
  - Web client displays offered items with names, MIME types, and formatted sizes.
  - User clicks "Download" or "Download All"; files are served via HTTP streaming with `Content-Disposition: attachment; filename="sanitized_name.ext"` and chunked transfer encoding (`Transfer-Encoding: chunked`).
- **Browser $\rightarrow$ Glide (Receive from Web):**
  - Web client provides drag-and-drop or file picker inputs.
  - Uploads stream to Glide via chunked HTTP POST (`multipart/form-data` or raw binary octet-stream) with progress reporting.
  - Inbound bytes are written directly to Glide's private quarantine staging store (`QuarantineStagingStore` / `AndroidReceiveStore`), hashed in a single pass with SHA-256, and atomically committed to user storage only upon digest validation.

### 5. Browser Security & Sandbox Isolation
- Enforces strict RFC 6265 cookie/session isolation, disables `Server` identification banners, sets `X-Content-Type-Options: nosniff`, and applies a strict `Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self' ws:;`.
- Applies POSIX and Windows filename sanitization on all uploaded files, preventing directory traversal (`..`), drive letters, and reserved device names.

---

## Consequences

- **Broad Ecosystem Coverage:** Android and Windows Glide users can instantly transfer files to any iPhone, iPad, Mac, Linux workstation, or Chromebook without requiring the recipient to install any application.
- **Zero Cloud Dependence:** Retains Glide's fundamental commitment to privacy, zero-tracking, and 100% local operation.
- **Security Boundary:** The browser client is authenticated via the physical QR code scan. Anyone on the local network who does not possess the QR token is rejected.
- **Foreground Constraint:** Unlike native-to-native background transfers, web browser transfers require the recipient device to keep the browser tab open during active streaming.
