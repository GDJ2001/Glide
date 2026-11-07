# Glide v1.0.0 — Fast Local Share

**Release Version:** 1.0.0  
**Release Date:** 2026-11-07  
**Git Tag:** `v1.0.0`  
**License:** Apache License 2.0  

---

## Overview

We are thrilled to announce the official **v1.0.0 release of Glide** — a high-performance, zero-cloud, peer-to-peer offline file sharing solution engineered for modern multi-platform environments. Glide allows secure, blazing-fast file and directory transfers directly between local devices without routing data through external servers, cloud storage, or third-party gateways.

Whether transferring large video libraries, code archives, documents, or photos across your local Wi-Fi, Ethernet network, or wireless direct channels, Glide provides end-to-end encrypted transport with zero cloud dependency.

---

## Key Highlights & Architectural Features

- **Multi-Platform Support**: First-class native support across **Android 13+**, **Windows 11 (64-bit)**, and **Linux (Ubuntu/Debian, Fedora/RHEL, openSUSE)**.
- **Dual Transport Engines**:
  - **Local Area Network (LAN)**: High-throughput TLS 1.3 over TCP with zero-configuration mDNS service discovery and instant QR code / SAS pairing.
  - **Nearby Connections**: Seamless peer-to-peer Wi-Fi Direct and Bluetooth discovery on supported Android devices via Google Play Services Nearby Connections.
- **Enterprise-Grade Security & Privacy**:
  - **Zero Cloud / Zero Telemetry**: Transfers occur strictly on the local network; no accounts, no trackers, no external analytics.
  - **Mutual TLS & SAS Verification**: Ephemeral SHA-256 SPKI certificate pinning with 6-digit Short Authentication String (SAS) out-of-band visual comparison to prevent man-in-the-middle attacks.
  - **Quarantine Staging & Streaming Verification**: Inbound files stream into private staging (`.part` files) and undergo streaming SHA-256 verification before atomic commit to the user's destination folder.
  - **Strict Path Traversal Defenses**: Defense-in-depth sanitization rejecting path separators, directory traversal (`..`), drive roots, UNC paths, and reserved DOS device names.
- **Native Performance**: Built with Kotlin 2.4 / Jetpack Compose on Android and .NET 10 on Windows and Linux, delivering gigabit transfer speeds with minimal CPU and memory footprints.

---

## 📱 Google Play Store Early Release Announcement

The Android version of Glide is now published on the **Google Play Store** under the package ID **`dev.glide.app`** as an **Early Release**!

Glide is currently in the **Closed Testing (Alpha)** phase while undergoing Google Play's required 14-day tester opt-in verification before worldwide general production rollout.

### How to Join the Closed Testing Track on Google Play:

1. **Web Opt-In Link (Recommended)**:  
   👉 [https://play.google.com/apps/testing/dev.glide.app](https://play.google.com/apps/testing/dev.glide.app)  
   Open the link with your Google Account, click **"Become a tester"**, and accept the testing invitation.

2. **Google Play Store App Listing**:  
   👉 [https://play.google.com/store/apps/details?id=dev.glide.app](https://play.google.com/store/apps/details?id=dev.glide.app)  
   Once opted in, install or update Glide directly from the Play Store to receive automatic alpha updates.

### Direct APK Sideload (Immediate Access — No Waiting Required):

For users who wish to run Glide immediately without waiting for Play Store tester approval:
- Download the official standalone production APK attached to this release: **`glide-1.0.0-vc1-release.apk`**.
- Open the `.apk` on your Android device and allow "Install unknown apps" if prompted.
- The APK is signed with our official release keystore (RSA-4096, v2/v3 signature schemes), fully functional, and identical in security and performance to the Google Play Store build.

---

## 📦 Deliverables & Platform Packages

### Windows (64-bit x64)
- **Setup Installer (`Glide-Setup-1.0.0-win-x64.exe`)**: Graphical installer built with NSIS. Creates Start Menu shortcuts, desktop icons, uninstallation support, and custom protocol handlers.
- **Portable Archive (`glide-windows-win-x64.zip`)**: Fully self-contained portable distribution. Extract and run `Glide.exe` or `glide.cmd` anywhere without administrative privileges.

### Linux (64-bit x64)
- **Debian / Ubuntu Package (`glide_1.0.0_amd64.deb`)**: Native `.deb` package with desktop integration and optional systemd background service.
  ```bash
  sudo dpkg -i glide_1.0.0_amd64.deb
  sudo apt-get install -f # resolve any runtime dependencies
  ```
- **Red Hat / Fedora / openSUSE Package (`glide-1.0.0-1.x86_64.rpm`)**: Native `.rpm` package for RPM-based distributions.
  ```bash
  sudo rpm -i glide-1.0.0-1.x86_64.rpm
  ```
- **Portable Tarball (`glide-1.0.0-linux-x64.tar.gz`)**: Standalone portable archive for any modern Linux x64 distribution.
  ```bash
  tar -xzf glide-1.0.0-linux-x64.tar.gz
  cd glide-1.0.0-linux-x64
  ./Glide
  ```

### Android (API 26+)
- **Production APK (`glide-1.0.0-vc1-release.apk`)**: Standalone release package for direct sideloading.
- **Production App Bundle (`glide-1.0.0-vc1-release.aab`)**: Official Google Play Store upload bundle.

---

## 🔒 Verification & SHA-256 Checksums

Every release asset is cryptographically verified and signed. You can verify the integrity of downloaded files using standard command-line tools:

```bash
# Linux / macOS
sha256sum <filename>

# Windows PowerShell
Get-FileHash -Algorithm SHA256 <filename>
```

### Official SHA-256 Checksum Table

| Platform | Deliverable | Size (Bytes) | SHA-256 Checksum |
| :--- | :--- | :--- | :--- |
| **Android** | `glide-1.0.0-vc1-release.apk` | 5,100,534 | `2b7eab83e221da53d4c657ba1bcb7776dda8d9be6a60aa3d164449973041bcf5` |
| **Android** | `glide-1.0.0-vc1-release.aab` | 9,892,663 | `6a1ff169b36576b609b76ead73493e0f9e629fc80a9e85d35d05dd6d0e9e792c` |
| **Windows** | `Glide-Setup-1.0.0-win-x64.exe` | 43,791,903 | `4e59fc570772263f14555d8ec5600d3d6019e31f36c9b501977c636d26e88e12` |
| **Windows** | `glide-windows-win-x64.zip` | 62,039,937 | `642634c5012984c4dfe4b744754f576ea358adbf0336f3c495904e54b1b1d5f5` |
| **Linux** | `glide_1.0.0_amd64.deb` | 62,863,304 | `5c425b0ec2d3a2ef1f4dad8c56358687ea67f97722d983cc704b111a1fc1409d` |
| **Linux** | `glide-1.0.0-1.x86_64.rpm` | 76,961,666 | `693ce6497ac2c6c157f8a705efc303e59591ae661111311047b6175b87cf8368` |
| **Linux** | `glide-1.0.0-linux-x64.tar.gz` | 77,382,717 | `d43ce0492a8fdf443f1b4d010c919e8b2275c0e9be91633c57b45095be200b5f` |
| **Manifest**| `RELEASE_MANIFEST.json` | 9,594 | `848dabef80d943c553a6a11a78c9c1b3431a6286ee36952936967f2ea6f22d5b` |
| **Catalog** | `RELEASE_CATALOG.md` | 3,986 | `9796300e5c9df9fd78ed7b36353227883ab132e74acf434460aba92a6cc9c1bf` |

Detailed build provenance, toolchain specifications, and platform installation gate records are published in:
- [`build/distributions/RELEASE_MANIFEST.json`](RELEASE_MANIFEST.json)
- [`build/distributions/RELEASE_CATALOG.md`](RELEASE_CATALOG.md)
- [`doc/release/RELEASE_PROCEDURE_V1.md`](doc/release/RELEASE_PROCEDURE_V1.md)

---

## 🤝 Open Source & Community

Glide is fully open source under the **Apache License 2.0**. We welcome contributions from developers worldwide!

- **Source Code**: [https://github.com/GDJ2001/Glide](https://github.com/GDJ2001/Glide)
- **Contributing Guidelines**: [`CONTRIBUTING.md`](CONTRIBUTING.md)
- **Code of Conduct**: [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md)
- **Security Policy & Vulnerability Reporting**: [`SECURITY.md`](SECURITY.md)
- **Third-Party Open Source Notices**: [`doc/release/OPEN_SOURCE_LICENSES.md`](doc/release/OPEN_SOURCE_LICENSES.md)

---
*Glide — Blazing fast, completely local, zero cloud.*
