# Android & Windows to Web Browser Zero-Install Transfer Specification

- **Document Version:** 1.0.0
- **Status:** Approved Technical Specification
- **Milestone:** M14 — Cross-Platform Browser & Web Client Expansion
- **Task Authority:** [ADR-0019](../adr/ADR-0019-browser-web-local-transfer.md)
- **Roadmap Precedence:** Step 3 of [CROSS_PLATFORM_PATH.md](CROSS_PLATFORM_PATH.md)
- **Related Documents:** [GLIDE_PROTOCOL_V1.md](../protocol/GLIDE_PROTOCOL_V1.md), [ANDROID_WINDOWS_LAN_SPECIFICATION.md](ANDROID_WINDOWS_LAN_SPECIFICATION.md), [GLIDE_THREAT_MODEL.md](../security/GLIDE_THREAT_MODEL.md).

---

## 1. Executive Summary & Architectural Invariants

### 1.1 Purpose
This specification defines the complete architectural design, network wire protocols, cryptographic onboarding contracts, browser security sandbox policies, and quarantine storage rules for **zero-install file sharing between Glide devices (Android & Windows) and any external device equipped with a modern web browser** (Apple iOS / Safari, macOS, Linux workstations, ChromeOS, and guest computers).

### 1.2 Core Invariants
1. **Zero Cloud / 100% Offline by Default:**
   All signaling, web page delivery, and bulk payload transfers occur strictly across the local Wi-Fi or Ethernet subnet. No cloud signaling servers, user account databases, WebRTC STUN/TURN relays, or external internet connections are used or required.
2. **Zero External CDN Dependencies:**
   The browser web client is a self-contained Single-Page Application (SPA) embedded directly in Glide's application binary. The client makes zero requests to Google Fonts, external CDN scripts, analytics trackers, or third-party stylesheets. It functions seamlessly on air-gapped local networks.
3. **Out-of-Band Physical QR Onboarding:**
   Because web browsers cannot participate in native mDNS (`_glide._tcp.local.`) peer discovery, connection establishment is initiated out-of-band by scanning an ephemeral QR code encoding a local URL with a high-entropy 256-bit bearer token.
4. **Strict Quarantine & Data Sanctity Invariant:**
   Files uploaded from the web browser are written directly to Glide's private quarantine staging store (`%LocalAppData%\Glide\staging\<sessionId>\` on Windows, `noBackupFilesDir/staging/<sessionId>/` on Android). Files are atomically committed to destination storage only after end-of-stream SHA-256 digest validation succeeds.
5. **Universal Browser Compatibility:**
   The web client strictly targets standard W3C / WHATWG standards (HTML5, modern CSS3, ES2020 JavaScript, Fetch API, Streams API, and WebSockets), ensuring 100% out-of-the-box compatibility across Apple Mobile Safari (iOS 14+), desktop Safari (macOS), Google Chrome / Chromium (Linux, Windows, ChromeOS), Mozilla Firefox, and Microsoft Edge.

---

## 2. System Architecture & Component Mapping

```
+-------------------------------------------------------------+
|               Glide Native Host Device                      |
|             (Android App or Windows Desktop)                |
+-------------------------------------------------------------+
|  Glide Core Engine (:transfer-core / Glide.Core)            |
|  - Manifest Generator, Quarantine Staging, SHA-256 Verifier |
+-------------------------------------------------------------+
|  Embedded Web Transfer Gateway                              |
|  - Ephemeral HTTP/1.1 & WebSocket Server                    |
|  - Embedded Single-Page Application (HTML/CSS/JS Assets)    |
|  - Bearer Token Authenticator & Session Manager             |
+-------------------------------------------------------------+
                              ^
                              | Local Wi-Fi / Ethernet Subnet
                              | (HTTP / WebSocket)
                              v
+-------------------------------------------------------------+
|               External Client Device                        |
|       (iOS iPhone/iPad, macOS, Linux, ChromeOS)             |
+-------------------------------------------------------------+
|  Native Mobile/Desktop Web Browser                          |
|  - Apple Safari, Google Chrome, Mozilla Firefox, Edge       |
+-------------------------------------------------------------+
|  Sandboxed Web Client Application                           |
|  - Zero-Install Single Page Application served by Glide     |
|  - Chunked File Downloader / Streaming Multipart Uploader   |
+-------------------------------------------------------------+
```

---

## 3. QR Onboarding, URI Schema & Session Security

### 3.1 Onboarding URI Format
When the user opens "Share to Web" or "Receive from Web" on Glide, the host displays a high-contrast QR code encoding the following canonical URI:

$$\text{http://}\langle\text{Local-IP}\rangle\text{:}\langle\text{Port}\rangle\text{/?token=}\langle\text{TokenHex}\rangle$$

- **`Local-IP`:** Direct numeric IPv4 literal (e.g. `192.168.1.145`) or bracketed numeric IPv6 literal (e.g. `[fe80::1ff:fe23:4567:890a]`) belonging to the selected Wi-Fi/Ethernet interface. Hostnames, loopback (`127.0.0.1`), and cellular interface addresses are strictly forbidden.
- **`Port`:** Dynamically assigned ephemeral port (port `0` at bind time; typical range $1024..65535$).
- **`TokenHex`:** 64-character lowercase hex string representing 256 bits of cryptographically secure random entropy (`SecureRandom` / `RandomNumberGenerator`).

### 3.2 Token Lifecycle & Session Issuance
1. **Monotonic Expiry:** The onboarding token is valid for 120 seconds from creation. If unused, it expires and the server regenerates a fresh token.
2. **One-Use Token Consumption:** Upon the browser's first `GET /?token=<TokenHex>` request, the host verifies the token against memory, consumes it immediately (preventing replay attacks), and issues an ephemeral session cookie.
3. **Session Cookie Security Attributes:**
   ```http
   Set-Cookie: glide_web_session=<SessionUUID>; Path=/; SameSite=Strict; HttpOnly; Max-Age=1800
   ```
4. **Unauthorized Requests:** Any request lacking a valid token or active session cookie is rejected with `HTTP 403 Forbidden` and a friendly "Session Expired or Unauthorized" static notice.

---

## 4. Web Gateway Wire Protocol Specification

The embedded gateway exposes a RESTful HTTP/1.1 API paired with an optional WebSocket endpoint for real-time progress events:

### 4.1 Endpoint Catalog

| Method | Path | Description | Content-Type |
|:---|:---|:---|:---|
| `GET` | `/` | Serves the embedded HTML5 Single-Page Application | `text/html; charset=utf-8` |
| `GET` | `/app.js` | Bundled client JavaScript logic | `application/javascript` |
| `GET` | `/style.css` | Bundled Fluent/Material CSS stylesheet | `text/css; charset=utf-8` |
| `GET` | `/api/v1/session` | Retrieves session mode (`send` or `receive`) and peer info | `application/json` |
| `GET` | `/api/v1/manifest` | Retrieves the list of offered files (Send Mode) | `application/json` |
| `GET` | `/api/v1/download/{itemId}` | Streams binary file payload to browser | `application/octet-stream` |
| `POST` | `/api/v1/upload` | Streams uploaded files from browser to Glide | `multipart/form-data` |
| `GET` | `/api/v1/ws` | Real-time WebSocket connection for progress and consent | N/A (WebSocket Upgrade) |

---

### 4.2 Send to Web Flow (Glide $\rightarrow$ Browser)

1. **Offer Presentation:**
   The browser calls `GET /api/v1/manifest` and receives the V1 item manifest:
   ```json
   {
     "sessionId": "b4e88f8d-4e4b-4b2a-89a7-9c98b671a5a1",
     "device": {
       "name": "Pixel 9 Pro",
       "platform": "android"
     },
     "items": [
       {
         "itemId": "8a32a67e-071a-4d23-92b4-3ec9fef39db3",
         "fileName": "vacation_photo.jpg",
         "fileSize": 4194304,
         "mimeType": "image/jpeg",
         "sha256": "3a7acb4f9e1d52...c89012"
       }
     ]
   }
   ```
2. **Chunked Streaming Download:**
   When the user clicks "Download", the browser issues:
   ```http
   GET /api/v1/download/8a32a67e-071a-4d23-92b4-3ec9fef39db3 HTTP/1.1
   Host: 192.168.1.145:42424
   Cookie: glide_web_session=c9b0e...
   ```
   Glide responds with chunked binary data and an explicit attachment disposition:
   ```http
   HTTP/1.1 200 OK
   Content-Type: image/jpeg
   Content-Length: 4194304
   Content-Disposition: attachment; filename="vacation_photo.jpg"
   Transfer-Encoding: chunked
   Cache-Control: no-store
   
   <raw binary chunk 1>
   <raw binary chunk 2>
   ...
   ```

---

### 4.3 Receive from Web Flow (Browser $\rightarrow$ Glide)

1. **Offer Consent on Native Device:**
   When the browser selects files to upload, the web client posts an upload proposal. Glide displays an incoming transfer notification to the user ("iPhone 15 requests to send 3 files (45 MB) - Accept / Decline").
2. **Chunked Upload Streaming:**
   Upon native user acceptance, the browser streams payload bytes via `POST /api/v1/upload`.
3. **Quarantine Staging & Single-Pass Verification:**
   - Inbound bytes are written directly to `%LocalAppData%\Glide\staging\<sessionId>\<itemId>.quarantine`.
   - The SHA-256 digest is calculated incrementally on the fly ($O(1)$ memory).
   - On completion, the actual hash is verified against the declared hash.
   - If verified: atomically committed to `Downloads/`.
   - If aborted or digest mismatch: quarantine file is instantly purged.

---

## 5. Browser Security, Sandbox & Header Policy

### 5.1 Content Security Policy (CSP)
Every HTML response emitted by Glide includes a strict, zero-trust CSP header preventing any cross-origin resource leakage:

```http
Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self' ws:; frame-ancestors 'none';
```

### 5.2 Security Headers
- **`X-Content-Type-Options: nosniff`**: Prevents browser MIME-type confusion and execution of arbitrary uploaded content.
- **`X-Frame-Options: DENY`**: Eliminates clickjacking risks by forbidding iframe embedding.
- **`Cache-Control: no-store, no-cache, must-revalidate`**: Prevents caching of private user data or file listings in public browser history.
- **`Server` header suppression**: The server emits no vendor or framework identification banner.

### 5.3 Filesystem Sanitization for Web Uploads
All uploaded filenames received from the browser are processed through Glide's sanitization engine (`WindowsPathSanitizer` on Windows, `FilenameSanitizer` on Android):
- Strips directory traversal sequences (`..`).
- Strips absolute path prefixes and drive letters (`C:`).
- Replaces illegal characters (`< > : " / \ | ? *`).
- Rejects or escapes reserved DOS device names (`CON`, `PRN`, `AUX`, `NUL`, `COM1-9`, `LPT1-9`).
- Enforces a 255-character maximum filename length.

---

## 6. Multi-Phase Implementation Roadmap

- **Phase W1: Pure Embedded HTTP/1.1 Engine & Assets**
  - Implement zero-dependency lightweight HTTP server (`GlideHttpServer`) in `:transfer-core` / `Glide.Transport`.
  - Embed compiled HTML5/CSS3/ES6 Single-Page Application assets as embedded resources.
- **Phase W2: QR Onboarding & One-Use Bearer Token Authority**
  - Integrate token generation, expiration, and one-use consumption into `InvitationAuthority`.
  - Add "Share to Web" QR display cards in Android Compose and Windows WPF UI.
- **Phase W3: Streaming File Downloader (Send to Web)**
  - Implement chunked streaming file handlers for outbound items.
  - Test browser downloads across Apple Safari, Chrome, and Firefox.
- **Phase W4: Quarantine Uploader (Receive from Web)**
  - Implement streaming multipart upload handler with direct quarantine staging and SHA-256 hashing.
  - Implement user consent prompts on native sender before upload starts.
- **Phase W5: Cross-Platform Browser Conformance & Security Audit**
  - Conformance test suite testing CSP enforcement, token replay rejection, path traversal sanitization, and automated PowerShell validation (`tools/verify-web-transfer-spec.ps1`).
