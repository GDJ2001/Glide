# Google Play Store Listing Metadata — Glide

**Application Name:** Glide  
**Package Identifier:** `dev.glide.app` (configurable for release publication)  
**Target Category:** Tools / Productivity  
**Content Rating:** Everyone (IARC)  
**Monetization / Ads:** Free, 100% Ad-Free, No In-App Purchases  

---

## 1. Store Text Metadata

### App Title (Max 30 characters)
```text
Glide — Fast Local Share
```
*(Length: 24 characters)*

---

### Short Description (Max 80 characters)
```text
Fast, private, offline peer-to-peer file sharing between Android devices.
```
*(Length: 74 characters)*

---

### Full Description (Max 4,000 characters)
```text
Glide is the modern, private, high-speed file sharing app designed exclusively for Android. Share high-resolution photos, 4K videos, large documents, and entire folders directly between devices—completely offline, with zero data usage, zero cloud uploads, and zero tracking.

Whether you are traveling off the grid, sharing files in a crowded room, or transferring massive video projects between your phone and tablet, Glide connects devices locally and securely using peer-to-peer Wi-Fi Direct and Bluetooth.

⚡ LIGHTNING FAST OFFLINE TRANSFERS
• High-Throughput Streaming: Experience high-speed direct transfer rates up to 30+ MB/s over direct device-to-device Wi-Fi.
• Zero Data Consumption: Transfers never touch the internet or consume mobile data plans.
• Massive File & Batch Support: Send large files up to 64 GB each and batches of up to 1,000 items in a single uninterrupted session.

🔒 UNCOMPROMISING PRIVACY & ZERO CLOUD
• Pure Peer-to-Peer: Your files never transit external servers or cloud storage. Files travel straight from device A to device B over local radio.
• Secure Numeric SAS Pairing: Verify connections using a 6-digit mutual authentication code to prevent unwanted connections.
• Unconditional Receiver Consent: You maintain full control. Review the incoming file list, sizes, and sender details before accepting any payload.
• Cryptographic Verification: Every received file is checked with on-the-fly SHA-256 cryptographic hashing to ensure zero corruption or byte alteration.

📁 APP-PRIVATE STORAGE & EASY EXPORT
• Private Quarantine Sandbox: In-flight files are isolated in an app-private quarantine directory. Incomplete or corrupted transfers never clutter your gallery or storage.
• One-Tap Export: Save completed transfers directly to your device's public Downloads or Documents folder whenever you choose.
• Full Storage Management: View exact storage usage, manage transfer history, or purge received files directly within the app settings.

🎨 BEAUTIFUL MATERIAL 3 EXPERIENCE
• Modern Design: Clean, responsive interface built with Jetpack Compose and Material Design 3.
• Dynamic Theming & Dark Mode: Adapts automatically to your system theme and dynamic wallpaper palette.
• Foldable & Tablet Ready: Optimized for phones, foldables, and tablets with adaptive split-pane layouts.
• Accessible by Design: Fully supports screen readers (TalkBack), minimum 48dp touch targets, high-contrast states, and up to 200% font scaling.

📋 SYSTEM REQUIREMENTS & PERMISSIONS
• Android 8.0 (API 26) or higher.
• Google Play Services enabled (powers secure local Nearby Connections).
• Wi-Fi and Bluetooth enabled for local discovery and direct transfer.

Why Glide requests specific permissions:
• Nearby Wi-Fi Devices / Location: Required by Android to discover and establish direct Wi-Fi Direct connections with nearby peers (Glide never tracks or collects your physical location).
• Bluetooth: Used to discover and negotiate handshakes with nearby devices.
• Camera: Used strictly and optionally to scan QR codes for rapid device pairing (never records or saves images).
• Notifications: Keeps you informed of background transfer progress and completion.

Glide is open, transparent, and built with integrity. No accounts, no ads, no trackers—just seamless, private local sharing.
```
*(Length: ~3,180 characters)*

---

### Release Notes (en-US, Max 500 characters)
```text
Glide 1.0.0 initial release!
• High-speed peer-to-peer file transfer over Wi-Fi Direct and encrypted local Wi-Fi.
• 100% offline, zero-cloud architecture with on-the-fly SHA-256 verification.
• Mutual 6-digit numeric verification and optional QR code pairing.
• Full Material 3 adaptive design with dynamic theming and tablet support.
• App-private quarantine staging ensuring only complete, verified files are saved.
```

---

## 2. Content Rating & Policy Declarations

| Category | Response | Justification |
|---|---|---|
| **Violence / Blood** | None | Utility tool; contains no violent media or imagery. |
| **Sexuality / Nudity** | None | General productivity utility. |
| **Language / Profanity** | None | Neutral technical interface. |
| **Controlled Substances** | None | None present. |
| **User Interaction** | Local Only | Connects only to physically proximate peer devices via explicit mutual consent. |
| **Location Sharing** | No | Location permission is used strictly for Wi-Fi scanning on Android 12 and below; no location data is recorded or transmitted. |
| **Advertising** | No Ads | Glide contains zero advertising SDKs. |
| **Financial / Payments** | None | Free utility with no in-app purchases or subscriptions. |

---

## 3. Graphical Asset Checklist

- [x] **Store Icon:** `doc/release/store_assets/icon_512.png` (512×512 px, 24-bit RGB PNG, opaque, no alpha).
- [x] **Feature Graphic:** `doc/release/store_assets/feature_graphic_1024x500.png` (1024×500 px, 24-bit RGB PNG, safe zones respected, no alpha).
- [x] **Phone Screenshots (6 items):** `doc/release/store_assets/screenshots/phone/` (1080×1920 px, 24-bit RGB, opaque, portrait, no alpha).
- [x] **7-Inch Tablet Screenshots (6 items):** `doc/release/store_assets/screenshots/tablet_7inch/` (1920×1200 px, 16:10 landscape, 24-bit RGB, opaque, no alpha).
- [x] **10-Inch Tablet Screenshots (6 items):** `doc/release/store_assets/screenshots/tablet_10inch/` (2560×1600 px, 16:10 landscape, 24-bit RGB, opaque, no alpha).
- [x] **General Tablet Screenshots (6 items):** `doc/release/store_assets/screenshots/tablet/` (1920×1200 px, 16:10 landscape, 24-bit RGB, opaque, no alpha).

### Curated Tablet Screenshot Mapping
| # | Filename | Key Flow & Description |
|---|---|---|
| 1 | `01_home_discovery.png` | Home dashboard with active peer discovery radar, connection status, quick send/receive actions, and security overview. |
| 2 | `02_file_selection.png` | Staged multi-file batch review, media/document pickers, 4K video/deck/PDF/archive payload summary, and transfer ceilings. |
| 3 | `03_active_transfer.png` | High-throughput Wi-Fi Direct active streaming, 34.2 MB/s live speed, gradient progress bar, and queued batch items. |
| 4 | `04_receive_consent.png` | Inbound transfer offer card, sender identity, and prominent 6-digit mutual SAS verification code (`4 8 2 9 1 6`). |
| 5 | `05_transfer_history.png` | Two-pane adaptive transfer activity, category filters (All/Sent/Received), SHA-256 verification status, and one-tap public export. |
| 6 | `06_settings_privacy.png` | Two-column tablet settings: device identity, Material You theme mode, app-private quarantine sandbox storage, and zero-cloud guarantee. |
