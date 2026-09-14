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
• Secure Numeric SAS Pairing: Verify connections using a 4-digit mutual authentication code to prevent unwanted connections.
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
• Notifications: Keeps you informed of background transfer progress and completion.

Glide is open, transparent, and built with integrity. No accounts, no ads, no trackers—just seamless, private local sharing.
```
*(Length: ~3,180 characters)*

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

- [x] **Store Icon:** `docs/release/store_assets/icon_512.png` (512×512 px, 32-bit PNG, opaque).
- [x] **Feature Graphic:** `docs/release/store_assets/feature_graphic_1024x500.png` (1024×500 px, PNG, safe zones respected).
- [x] **Phone Screenshots:** `docs/release/store_assets/screenshots/phone/` (6 real screenshots, 1080×2424 px).
- [x] **Tablet Screenshots:** `docs/release/store_assets/screenshots/tablet/` (2 real screenshots, landscape & responsive).
