# Glide.Windows — Companion Desktop Client for Glide

**Glide.Windows** is the official companion desktop client for [Glide](https://github.com/GDJ2001/Glide) (`dev.glide.app`), providing high-speed, private, zero-cloud file transfers between Microsoft Windows 11 and Android devices over local Wi-Fi and Ethernet networks.

Glide.Windows is built on **.NET 10** using modern C# 13 and WPF with strict conformance to **Glide Protocol V1** ([`GLIDE_PROTOCOL_V1.md`](../docs/protocol/GLIDE_PROTOCOL_V1.md)) and the **LAN Profile 1 Wire Specification** ([`ANDROID_WINDOWS_LAN_SPECIFICATION.md`](../docs/future/ANDROID_WINDOWS_LAN_SPECIFICATION.md), [`ADR-0018`](../docs/adr/ADR-0018-lan-qr-authentication-and-routing.md)).

---

## 1. Solution Architecture

The solution (`windows/Glide.slnx`) contains the protocol/transport projects plus one shared desktop engine used by both CLI and WPF:

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
├── Glide.Desktop/                     # Shared desktop session/orchestration engine
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
4. **Strict Out-of-Band SPKI Pinning, Bootstrap & ALPN:**
   - Initial LAN pairing uses server-authenticated TLS 1.3 with **only** ALPN `glide-lan/1`; Android clients are not required to present a TLS client certificate.
   - The sender validates the receiver's exact DER SubjectPublicKeyInfo (SPKI) SHA-256 fingerprint from the QR invitation before application bytes are sent.
   - The first application record is an atomically consumed one-use QR bootstrap. Raw host/port discovery is not authentication and direct unpaired SAS transfers are disabled.
   - DNS-SD publishes only the bounded `v/tv/q/p/dn/dt` availability record and the listener's actual SRV port; it does not publish pairing tokens, certificate fingerprints, filenames, or unimplemented trust/resume claims.
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
Current-head verification must come from the repository CI/evidence ledger. Historical pass counts are not release evidence for later commits.

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
dotnet run --project windows/Glide.App/Glide.App.csproj -- receive
```
The listener binds an ephemeral port by default and prints a fresh one-use pairing invitation/QR containing the actual endpoint and SPKI pin. Use `--port <port>` only when an explicit listener port is required.

### Send Files via QR Pairing Link
```powershell
dotnet run --project windows/Glide.App/Glide.App.csproj -- send --pair "glide://pair/v1#eyJ2..." photo1.jpg document.pdf
```

Raw IP/port sending is intentionally disabled for the initial LAN profile because a discovered endpoint does not authenticate the receiving peer.

### Discover Nearby Peers
```powershell
dotnet run --project windows/Glide.App/Glide.App.csproj -- list
```

---

## 6. Verification and Conformance Matrix

| Check | Target | Status |
|:---|:---|:---:|
| **Build** | `Glide.slnx` compiles the current desktop solution | **CURRENT-HEAD CI REQUIRED** |
| **Protocol Conformance** | 20+ Golden Protocol V1 JSON fixtures (`fixtures/protocol/`) | **REVERIFY CURRENT HEAD** |
| **Framing Conformance** | 7/7 LAN Profile 1 Golden Binary fixtures (`fixtures/lan/`) | **REVERIFY CURRENT HEAD** |
| **QR Codec** | `InvitationCodec` URI bounds (<=2768) and JSON bounds (<=2048) | **REVERIFY CURRENT HEAD** |
| **Security & SPKI** | DER SubjectPublicKeyInfo SHA-256 fingerprint verification | **REVERIFY CURRENT HEAD** |
| **Filesystem Safety** | `WindowsPathSanitizer` directory traversal & DOS device filters | **REVERIFY CURRENT HEAD** |
| **Quarantine Storage** | Atomic commit & instant purge on digest mismatch | **REVERIFY CURRENT HEAD** |
| **Loopback TLS 1.3 E2E** | Multi-file, zero-byte, corrupted payload, and invalid token tests | **REVERIFY CURRENT HEAD** |
| **Zero Leakage** | `tools/check-architecture.ps1` Android isolation audit | **REVERIFY CURRENT HEAD** |
