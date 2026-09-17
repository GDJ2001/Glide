# Glide — High-Speed, Zero-Cloud, Cross-Platform Local File Sharing

[![Android SDK](https://img.shields.io/badge/Android-SDK%2026..37-3DDC84?style=flat&logo=android&logoColor=white)](app/)
[![.NET](https://img.shields.io/badge/.NET-10.0-512BD4?style=flat&logo=dotnet&logoColor=white)](windows/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?style=flat&logo=kotlin&logoColor=white)](transfer-core/)
[![Zero Cloud](https://img.shields.io/badge/Privacy-100%25%20Offline%20%E2%80%A2%20Zero%20Cloud-success)](docs/security/GLIDE_THREAT_MODEL.md)
[![Protocol](https://img.shields.io/badge/Protocol-V1%20Conforming-blue)](docs/protocol/GLIDE_PROTOCOL_V1.md)
[![Tests](https://img.shields.io/badge/Conformance%20Tests-152%2F152%20Pass%20(100%25)-brightgreen)](windows/Glide.Tests/)

**Glide** (`dev.glide.app`) is an open-source, high-speed, private local file sharing system designed to transfer multi-gigabyte files effortlessly across mobile, desktop, and web devices without cloud intermediaries, accounts, tracking, or internet bandwidth consumption.

---

## 1. Supported Platform Matrix

Glide connects heterogeneous platforms across local Wi-Fi, Ethernet, and direct wireless links:

| Platform | Interface | Transport Protocol | Pairing Mechanism | Status |
|:---|:---|:---|:---|:---:|
| **Android** | Native Jetpack Compose UI | Nearby Connections STREAM + LAN Profile 1 TLS 1.3 | 6-Digit SAS & QR Code | **Production Shipped** ✅ |
| **Windows 10/11** | WinUI 3 / WPF Desktop & CLI | Native TLS 1.3 Socket Listener + mDNS Discovery | QR Scanning & 6-Digit SAS | **Production Shipped** ✅ |
| **Web Browser** | Zero-Install Embedded SPA | Local HTTP/1.1 REST + Chunked Streaming | One-Use QR Scan (Bearer Token) | **Delivered & Certified** ✅ |
| **Apple iOS / macOS** | SwiftUI App & Menu Bar Agent | Apple `Network.framework` TLS 1.3 + Bonjour mDNS | QR Scan & 6-Digit SAS | **Specified ([ADR-0020](docs/adr/ADR-0020-apple-platforms-architecture.md))** ✅ |
| **Linux (Workstation/Server)**| Shared .NET 10 CLI & systemd Daemon | Cross-Platform Socket Listener + mDNS | Terminal Block QR & SAS | **Specified ([ADR-0021](docs/adr/ADR-0021-linux-platform-architecture.md))** ✅ |
| **Remote Internet / WAN** | P2P NAT Traversal Pipeline | Noise IK / TLS 1.3 over ICE/STUN + Blind Relays | Out-of-Band Invitation Links | **Specified ([ADR-0022](docs/adr/ADR-0022-remote-internet-transfer.md))** ✅ |

---

## 2. Core Invariants & Security Guarantees

1. **Zero Cloud / 100% Offline by Default:**
   All discovery, pairing, and payload transfers execute strictly across local networks. No user accounts, phone numbers, cloud file buckets (S3/GCS), or tracking telemetry exist.
2. **Canonical Wire Protocol Parity ([`GLIDE_PROTOCOL_V1.md`](docs/protocol/GLIDE_PROTOCOL_V1.md)):**
   All platforms strictly interchange standard Glide Protocol V1 JSON envelopes framed with 5-byte length prefixes (`LanRecord`), guaranteeing zero protocol divergence across Kotlin, C#, and Swift.
3. **Mutual Cryptographic Authentication:**
   Connections mandate **TLS 1.3** or **Noise IK** authenticated encryption with hardware acceleration. Unpaired sessions display a mutual 6-digit Short Authentication String (SAS) derived via RFC 5869 HKDF-SHA256.
4. **Out-of-Band Physical Pairing (`glide://pair/v1#...`):**
   Scanning an ephemeral host QR code extracts the server's SubjectPublicKeyInfo (SPKI) SHA-256 fingerprint and bearer token, establishing mutual trust with zero manual prompts.
5. **Strict Sandboxed Quarantine & Atomic Commit:**
   Inbound file streams are written directly to sandboxed quarantine storage (`staging/<sessionId>/<itemId>.quarantine`). Incoming bytes are hashed incrementally on the fly ($O(1)$ memory). Files are atomically committed to destination storage only after end-of-stream SHA-256 digest validation succeeds; corrupted or interrupted files are immediately purged.
6. **Cross-Platform Filesystem Sanitization:**
   Path traversal sequences (`..`), leading slashes, null bytes, Windows reserved DOS device names (`CON`, `PRN`, `AUX`, `NUL`), and control characters are stripped and sanitized, protecting receiver filesystems.

---

## 3. Quick Start & Building

### Prerequisites
- **Android:** Android Studio Ladybug / Meerkat, JDK 17, Android SDK 37 (API 26 min).
- **Desktop / CLI (.NET):** [.NET 10 SDK](https://dotnet.microsoft.com/download/dotnet/10.0) (10.0.100 or higher), PowerShell 7+ (`pwsh`).

### Android Build & Tests
```bash
# Build debug APK
./gradlew :app:assembleDebug

# Run unit tests across all Android modules
./gradlew testDebugUnitTest

# Run full local verification and architecture audit
pwsh -File tools/verify-local.ps1
```

### Windows & Linux .NET Desktop Companion (`windows/Glide.slnx`)
```powershell
# Build entire .NET solution (Core, Transport, Windows UI, App, Tests)
dotnet build windows/Glide.slnx -c Release

# Run all 152 cross-platform golden fixture and integration unit tests
dotnet test windows/Glide.Tests/Glide.Tests.csproj -c Release

# Run the Windows Companion Auditor
pwsh -File tools/verify-windows-companion.ps1
```

### Running the CLI Companion (`Glide.App`)
```powershell
# Start listening for incoming transfers (renders pairing invitation and QR):
dotnet run --project windows/Glide.App/Glide.App.csproj -- receive

# Send files to a discovered peer via pairing link:
dotnet run --project windows/Glide.App/Glide.App.csproj -- send --pair "glide://pair/v1#..." "vacation.jpg"

# Share files with any browser on the local Wi-Fi (zero install on client):
dotnet run --project windows/Glide.App/Glide.App.csproj -- web "presentation.pdf"
```

---

## 4. Repository Architecture

```
Glide/
├── app/                        # Android application entry, Compose UI, ViewModels, Hilt DI
├── transfer-core/              # Pure Kotlin JVM core protocol engine, codecs, reducers, fixtures
├── transport-lan/              # Android LAN Profile 1 adapter (NsdManager, TLS 1.3, Web Gateway)
├── transport-nearby/           # Android Google Nearby Connections P2P transport adapter
├── data/                       # Room database, receive staging journal, preferences
├── designsystem/               # Material 3 design tokens, theme, typography, components
├── docs/                       # Authoritative engineering documentation
│   ├── adr/                    # 22 Architecture Decision Records (ADR-0001 through ADR-0022)
│   ├── future/                 # Cross-platform path and platform specifications
│   ├── protocol/               # Glide Protocol V1 wire specification
│   └── security/               # Threat model, security checklists, risk registers
├── tools/                      # 27 automated PowerShell verification and audit scripts
└── windows/                    # .NET 10 Cross-Platform Desktop & CLI Solution
    ├── Glide.Core/             # Pure C# protocol engine, codecs, SAS calculator, quarantine
    ├── Glide.Transport/        # Sockets (TLS 1.3), mDNS discovery, embedded Web Gateway
    ├── Glide.Windows.UI/       # Modern WPF desktop application
    ├── Glide.App/              # Cross-platform CLI & headless daemon executable
    └── Glide.Tests/            # 152 unit tests validating golden fixtures and E2E transfers
```

---

## 5. Specification & Architectural Decisions Catalog

| Document | Topic & Authority | Status |
|:---|:---|:---:|
| [ADR-0001 — Android Application Architecture](docs/adr/ADR-0001-android-architecture.md) | Single-activity, unidirectional data flow, pure JVM core | Accepted |
| [ADR-0007 — Initial Android Transport](docs/adr/ADR-0007-transport.md) | Google Nearby Connections P2P (STREAM payload mode) | Accepted |
| [ADR-0008 — Application Protocol](docs/adr/ADR-0008-protocol.md) | Strict binary envelope codecs, sequence tracking, receipts | Accepted |
| [ADR-0010 — Integrity Verification](docs/adr/ADR-0010-integrity.md) | Single-pass streaming SHA-256 verification and failure quarantine | Accepted |
| [ADR-0014 — Safe Folder Transfer Extension](docs/adr/ADR-0014-safe-folder-transfer-extension.md) | Hierarchical directory streaming and collision safety | Accepted |
| [ADR-0015 — Persistent Identity & True Resume](docs/adr/ADR-0015-persistent-identity-and-true-resume.md) | Android Keystore TOFU pairing and Merkle chunk true resume | Accepted |
| [ADR-0017 — Android-to-Windows LAN Slice](docs/adr/ADR-0017-android-windows-interoperable-lan-slice.md) | mDNS discovery and TLS 1.3 socket transport | Accepted |
| [ADR-0018 — LAN QR Authentication & Unified Routing](docs/adr/ADR-0018-lan-qr-authentication-and-routing.md) | QR-pinned TLS 1.3 mutual handshake and LAN Profile 1 framing | Accepted |
| [ADR-0019 — Browser Web Local Transfer Gateway](docs/adr/ADR-0019-browser-web-local-transfer.md) | Zero-install offline SPA gateway for any web browser | Accepted |
| [ADR-0020 — Apple Platforms Native Architecture](docs/adr/ADR-0020-apple-platforms-architecture.md) | Apple `Network.framework` TLS 1.3 + Bonjour mDNS for iOS & macOS | Accepted |
| [ADR-0021 — Linux Platform Architecture & Daemon](docs/adr/ADR-0021-linux-platform-architecture.md) | Cross-platform .NET 10 runtime, XDG directories, systemd service | Accepted |
| [ADR-0022 — Remote Internet Transfer Architecture](docs/adr/ADR-0022-remote-internet-transfer.md) | Zero-knowledge E2EE NAT traversal (ICE/STUN/TURN) & blind relays | Accepted |

---

## 6. Automated Audit & Verification Tooling

Glide enforces strict, automated verification gates across all platform layers:

```powershell
# Full Android build, lint, and unit test pass:
pwsh -File tools/verify-local.ps1

# Windows companion & .NET conformance audit (152 tests):
pwsh -File tools/verify-windows-companion.ps1

# Browser Web Transfer Gateway audit (Phases W1–W5):
pwsh -File tools/verify-web-transfer-spec.ps1

# Apple Platforms (iOS/macOS) specification auditor:
pwsh -File tools/verify-apple-platforms-spec.ps1

# Linux Platform specification auditor:
pwsh -File tools/verify-linux-platform-spec.ps1

# Remote Internet WAN & Relay threat model auditor:
pwsh -File tools/verify-remote-transfer-spec.ps1

# Android architectural boundaries and zero forbidden imports:
pwsh -File tools/check-architecture.ps1
```

---

## 7. License

Glide is released under the **Apache License 2.0**. See [`LICENSE`](LICENSE) for details.
