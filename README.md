# Glide

[![CI](https://github.com/GDJ2001/Glide/actions/workflows/ci.yml/badge.svg)](https://github.com/GDJ2001/Glide/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Google Play](https://img.shields.io/badge/Google_Play-dev.glide.app-green.svg)](https://play.google.com/store/apps/details?id=dev.glide.app)

Glide is an offline, peer-to-peer, zero-cloud file transfer system engineered for secure, verifiable, high-speed local sharing across mobile and desktop devices.

For illustrated Windows, Linux, and Android instructions, open the [English or Sinhala–English user guides](doc/user-guide/START-HERE.html). Offline HTML and PDF editions are included.

---

## Key Features

- **Zero Cloud & Offline First**: Direct device-to-device streaming over high-speed local Wi-Fi and Nearby Connections. No servers, no accounts, no telemetry.
- **Cryptographic Security**: Mutual authentication via ephemeral SPAKE2 keys, pinned SHA-256 TLS 1.3 certificates, and Short Authentication String (SAS) 6-digit visual comparison.
- **Zero-Trust Quarantine Staging**: Inbound payloads are streamed to private quarantine storage (`.part`), verified against streaming SHA-256 digests, sanitized against path traversal attacks, and committed only upon cryptographic match.
- **Multi-Platform Native Performance**:
  - **Android**: Pure Kotlin JVM core, Jetpack Compose UI, Room persistence, and Foreground Service streaming. Available on Google Play (`dev.glide.app`).
  - **Windows**: Native .NET 10 desktop application with modern Windows desktop UX and system tray daemon.
  - **Linux**: Cross-platform .NET 10 engine with Avalonia UI and headless daemon mode (`systemd`).
  - **Apple**: Swift Package `GlideCoreKit` and native macOS/iOS containers.

---

## Repository Structure

```
Glide/
├── android/            # Android client (:app, :transfer-core, :transport-lan, :transport-nearby, :data)
├── desktop/            # Cross-platform .NET 10 desktop engine & GUI (Windows WPF, Linux Avalonia, CLI)
├── apple/              # Swift package GlideCoreKit & Apple containers
├── native/             # Native BoringSSL SPAKE2 cryptographic shared libraries
├── doc/                # Architecture, threat models, protocol specs, and release runbooks
├── docs/               # GitHub Pages web landing root and Privacy Policy
└── tools/              # Cross-platform PowerShell build, test, lint, and verification automation
```

---

## Quick Start & Verification

Glide standardizes local developer workflows via PowerShell 7+ runners in `tools/`:

```powershell
# 1. Verify developer environment toolchains
pwsh -File tools/doctor.ps1

# 2. Check architecture boundaries and zero-leakage isolation
pwsh -File tools/check-architecture.ps1 -FailFast

# 3. Build all platform targets (Debug)
pwsh -File tools/build.ps1 -Configuration Debug -Target All

# 4. Execute test suites
pwsh -File tools/test.ps1 -Target All

# 5. Run static analysis and lint
pwsh -File tools/lint.ps1 -Target All

# 6. Execute full verification gate
pwsh -File tools/verify.ps1
```

---

## Contributing

We welcome contributions! Please review [CONTRIBUTING.md](CONTRIBUTING.md) and our [Code of Conduct](CODE_OF_CONDUCT.md) before submitting pull requests.

## Security

Security is foundational to Glide. To report vulnerabilities, refer to [SECURITY.md](SECURITY.md) or open a confidential security advisory on GitHub.

## License

This project is licensed under the Apache License 2.0 — see the [LICENSE](LICENSE) file for details. Third-party dependency notices are documented in [doc/release/OPEN_SOURCE_LICENSES.md](doc/release/OPEN_SOURCE_LICENSES.md).
