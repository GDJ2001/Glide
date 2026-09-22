# Glide.Windows — Companion Desktop Client for Glide

**Glide.Windows** is the official companion desktop client for [Glide](https://github.com/GDJ2001/Glide) (`dev.glide.app`), providing high-speed, private, zero-cloud file transfers between Microsoft Windows (Windows 10/11) and Android devices over local Wi-Fi and Ethernet networks.

Glide.Windows is built on **.NET 10** using modern C# 13 and WPF with strict conformance to **Glide Protocol V1** ([`GLIDE_PROTOCOL_V1.md`](../docs/protocol/GLIDE_PROTOCOL_V1.md)) and the **LAN Profile 1 Wire Specification** ([`ANDROID_WINDOWS_LAN_SPECIFICATION.md`](../docs/future/ANDROID_WINDOWS_LAN_SPECIFICATION.md), [`ADR-0018`](../docs/adr/ADR-0018-lan-qr-authentication-and-routing.md)).

---

## 1. Solution Architecture

The solution (`windows/Glide.slnx`) contains five modular projects:

```
windows/
├── Glide.slnx                         # Solution file (.NET 10)
├── Glide.Core/                        # Pure protocol, security & storage engine
│   ├── Models/                        # Protocol V1 JSON envelopes, payloads & exceptions
│   ├── Pairing/                       # QR pairing invitation codec & bootstrap handshake
│   ├── Protocol/                      # Strict ProtocolCodec, envelope validation & sequencers
│   ├── Security/                      # SasCalculator (canonical ExtractDecimal6 derivation)
│   ├── Storage/                       # WindowsPathSanitizer & QuarantineStagingStore
│   └── Transport/Lan/                 # LAN Profile 1 records, LanRecordCodec & LanUuidCodec
├── Glide.Transport/                   # Network transport & discovery adapter
│   ├── Discovery/                     # Zero-dependency RFC 6762/6763 mDNS responder & browser
│   ├── Framing/                       # LengthPrefixedFrameChannel & LanRecordChannel
│   └── Sockets/                       # TlsSocketListener & TlsSocketClient (TLS 1.3 + SPKI pin)
├── Glide.Windows.UI/                  # Modern WPF desktop application
│   ├── ViewModels/                    # MVVM MainViewModel & DiscoveredPeerViewModel
│   ├── Views/                         # MainWindow.xaml (Modern Fluent Dark theme)
│   └── App.xaml                       # Application entry point & resource definitions
├── Glide.App/                         # High-performance CLI console application
│   └── Program.cs                     # Commands: send, receive, pair, list, version
└── Glide.Tests/                       # Cross-platform conformance test suite
    ├── Fixtures/                      # Embedded golden protocol JSON and LAN binary fixtures
    ├── GoldenProtocolFixtureTests.cs  # Conformance against 20+ Protocol V1 golden JSON envelopes
    ├── LanProfile1ConformanceTests.cs # 100% byte-for-byte matching on all 7 LAN binary fixtures
    └── LanProfile1TransferIntegrationTests.cs # E2E TLS 1.3 transfers with SPKI pinning & quarantine
```

---

## 2. Cross-Platform Parity & Core Invariants

1. **Zero Cloud / 100% Offline by Default:**
   All discovery and transfers occur strictly across the local subnet. No cloud signaling servers, user accounts, WebRTC STUN/TURN relays, or internet connections are used or required.
2. **Canonical Protocol Parity:**
   Reuses the exact Glide Protocol V1 JSON envelope schema (`HELLO`, `START_TRANSFER`, `TRANSFER_AUTH`, `OFFER_PAGE`, `ITEM_START`, `ITEM_END`, `FINISH_TRANSFER`). Verified against 20+ canonical golden fixtures.
3. **LAN Profile 1 Wire Framing:**
   - 5-byte header: `kind: byte` + `payloadLength: UInt32` big-endian (64 KiB ceiling).
   - Typed records: `BOOTSTRAP` (0x01), `BOOTSTRAP_RESULT` (0x02), `CONTROL` (0x10), `DATA` (0x20), `STREAM_END` (0x21).
   - Standard RFC 4122 big-endian UUID byte serialization (`LanUuidCodec`) avoiding Windows GUID mixed-endian pitfalls.
4. **Strict Out-of-Band SPKI Pinning & ALPN:**
   - Mutual TLS 1.3 socket connections mandate ALPN `glide-lan/1` or `glide-v1`.
   - Client validates the server's DER SubjectPublicKeyInfo (SPKI) SHA-256 fingerprint against the QR invitation link (`glide://pair/v1#...`) *before* any application bytes leave the client.
5. **Windows Filesystem Security:**
   - [`WindowsPathSanitizer`](Glide.Core/Storage/WindowsPathSanitizer.cs) rejects directory traversal (`..`), drive letters (`C:`), UNC paths (`\\`), control characters, and reserved DOS device names (`CON`, `PRN`, `AUX`, `NUL`, `COM1-9`, `LPT1-9`).
6. **Quarantine Staging & Atomic Commit:**
   - Incoming files are quarantined under `%LocalAppData%\Glide\staging\<sessionId>\`.
   - Continuous streaming directly computes the SHA-256 digest on the fly.
   - Files are atomically moved to `%USERPROFILE%\Downloads\` only upon successful digest match against `ITEM_END`. Corrupted files are immediately purged.

---

## 3. Quick Start & Building

### Prerequisites
- Windows 10/11 (x64 / ARM64)
- [.NET 10 SDK](https://dotnet.microsoft.com/download/dotnet/10.0) (version 10.0.100 or higher)
- PowerShell 7+ (`pwsh`)

### Build the Solution
```powershell
dotnet build windows/Glide.slnx -c Release
```

### Run All Unit & Integration Tests
```powershell
dotnet test windows/Glide.Tests/Glide.Tests.csproj -c Release
```
*Current test count:* **107 / 107 tests passing (100% success rate)**.

### Run Companion Client Auditor
```powershell
pwsh -File tools/verify-windows-companion.ps1
```

---

## 4. Running the Desktop Application (`Glide.Windows.UI`)

Launch the modern WPF desktop application:
```powershell
dotnet run --project windows/Glide.Windows.UI/Glide.Windows.UI.csproj -c Release
```

### Features:
- **Send Tab:**
  - Automatically discovers nearby Android phones and Windows PCs running Glide via mDNS (`_glide._tcp.local.`).
  - Displays device badges (📱 / 💻) with friendly names and IP endpoints.
  - "Pair via Link / QR" dialog to paste any `glide://pair/v1#...` link for out-of-band paired transfers.
  - Multi-file selection with total size and item count indicators.
- **Receive Tab:**
  - One-click server listener toggle.
  - Ephemeral QR pairing invitation generation with one-click "Copy Pairing Link".
  - Real-time progress bar, speed tracker, and active item status.
  - Automatic atomic quarantine commit to your `Downloads` folder.

---

## 5. Running the CLI Tool (`Glide.App`)

The command-line interface provides high-performance terminal operations suitable for scripts and power users:

### View Available Commands
```powershell
dotnet run --project windows/Glide.App/Glide.App.csproj -- --help
```

### Receive Files (Listen Mode with QR Link)
```powershell
dotnet run --project windows/Glide.App/Glide.App.csproj -- receive --port 42424
```
Outputs the pairing invitation:
```
Listening on 192.168.1.50:42424
Invitation link: glide://pair/v1#eyJ2IjoxLCJ0cmFuc3BvcnQiOiJsYW4tdGxzIiwicHJvZmlsZSI6MS...
Scan this QR link on your Android device to send files.
```

### Send Files via QR Pairing Link
```powershell
dotnet run --project windows/Glide.App/Glide.App.csproj -- send photo1.jpg document.pdf --pair "glide://pair/v1#eyJ2..."
```

### Send Files Directly via IP
```powershell
dotnet run --project windows/Glide.App/Glide.App.csproj -- send photo1.jpg document.pdf --host 192.168.1.88 --port 42424
```

### Discover Nearby Peers
```powershell
dotnet run --project windows/Glide.App/Glide.App.csproj -- list
```

---

## 6. Verification and Conformance Matrix

| Check | Target | Status |
|:---|:---|:---:|
| **Build** | `Glide.slnx` compiles all 5 projects with 0 errors | **PASS** ✅ |
| **Protocol Conformance** | 20+ Golden Protocol V1 JSON fixtures (`fixtures/protocol/`) | **PASS (100%)** ✅ |
| **Framing Conformance** | 7/7 LAN Profile 1 Golden Binary fixtures (`fixtures/lan/`) | **PASS (100%)** ✅ |
| **QR Codec** | `InvitationCodec` URI bounds (<=2768) and JSON bounds (<=2048) | **PASS (100%)** ✅ |
| **Security & SPKI** | DER SubjectPublicKeyInfo SHA-256 fingerprint verification | **PASS (100%)** ✅ |
| **Filesystem Safety** | `WindowsPathSanitizer` directory traversal & DOS device filters | **PASS (100%)** ✅ |
| **Quarantine Storage** | Atomic commit & instant purge on digest mismatch | **PASS (100%)** ✅ |
| **Loopback TLS 1.3 E2E** | Multi-file, zero-byte, corrupted payload, and invalid token tests | **PASS (100%)** ✅ |
| **Zero Leakage** | `tools/check-architecture.ps1` Android isolation audit | **PASS (0 issues)** ✅ |
