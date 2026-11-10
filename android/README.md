# Glide Android (`android/`)

Native Android client and shared JVM protocol core for **Glide**, an offline, peer-to-peer, zero-cloud file transfer system.

## Active Project Scope

Glide's active development and release scope covers four native platform families:

| Platform Scope | Implementation | Transport & Discovery |
| --- | --- | --- |
| **Android** (`android/`) | Kotlin, Jetpack Compose, Material 3 (`dev.glide.app`) | Google Play Services Nearby Connections + LAN TLS 1.3 over TCP (`_glide._tcp.local.`) |
| **Windows** (`desktop/windows/`) | C# / .NET 10 LTS, WPF (`Glide.Windows.UI`), CLI (`Glide.App`) | LAN TLS 1.3 over TCP + zero-dependency mDNS responder/browser |
| **Linux** (`desktop/linux/`) | C# / .NET 10 LTS, Avalonia UI (`Glide.Linux.UI`), CLI/systemd daemon (`Glide.App`) | LAN TLS 1.3 over TCP + mDNS discovery |
| **Apple** (`apple/`) | Swift 6 (`GlideCoreKit`), macOS AppKit (`GlideMac`), iOS/iPadOS SwiftUI (`GlideiOS`) | `Network.framework` TLS 1.3 + Bonjour (`_glide._tcp`) |

> **Scope Boundary:** Browser-based local sharing, embedded web gateways, WebRTC browser fallbacks, and non-native transports have been retired and removed from project scope. See [`doc/planning/native-platforms/RECOVERY_STATUS.md`](../doc/planning/native-platforms/RECOVERY_STATUS.md) and [`doc/planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md`](../doc/planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md).

## Android Module Architecture

- `:android:app` (`android/app`) — Single-Activity Jetpack Compose UI, ViewModels, Navigation 3, Hilt DI composition, Foreground Service (`TransferService`), and `TransferRuntime`.
- `:android:core` (`android/core`) — Pure Kotlin JVM library (`:transfer-core`). Zero Android/GMS/Compose/Room dependencies. Owns domain models, Protocol V1 and LAN Profile 1 codecs, state reducer, and abstract ports.
- `:android:transport-nearby` (`android/transport-nearby`) — Google Play Services Nearby Connections adapter.
- `:android:transport-lan` (`android/transport-lan`) — Authenticated TLS 1.3 over TCP socket adapter with NSD/mDNS discovery and SPKI pinning.
- `:android:data` (`android/data`) — Room database (`GlideDatabase` v5), Jetpack DataStore preferences, Android Keystore identity store, and `QuarantineStagingStore`.
- `:android:designsystem` (`android/designsystem`) — Material 3 theme tokens, adaptive layout helpers, icons, and reusable Compose components.
- `:android:baselineprofile` (`android/baselineprofile`) — Baseline Profile generator for startup and frame-timing optimization.

## Security & Protocol Guardrails

- **Length-Prefixed Framing:** Unsigned 32-bit big-endian length prefix; control frames capped at $\le 16\text{ KiB}$, bulk stream chunks bounded to $\le 64\text{ KiB}$.
- **Explicit Consent:** Every incoming file offer requires explicit user approval (`Accept` / `Decline`). Trusted device identity never implies automatic file acceptance.
- **Quarantine Staging:** Incoming payloads stream into private `.part` staging files via `QuarantineStagingStore`, compute incremental SHA-256 digests, verify against `ITEM_END` digests, and atomically commit to user-selected storage only upon verified match.
- **Path Sanitization:** All incoming filenames are sanitized via `FilenameSanitizer` before staging or publication.

## Developer Commands

Run from the repository root (`Glide/`):

```powershell
# Environment check
pwsh -File tools/doctor.ps1

# Architecture and import boundary verification
pwsh -File tools/check-architecture.ps1 -FailFast

# Build Android debug artifacts
pwsh -File tools/build.ps1 -Configuration Debug -Target Android

# Run Android unit tests
pwsh -File tools/test.ps1 -Target Android

# Run Android static analysis and lint
pwsh -File tools/lint.ps1 -Target Android
```

Or run Gradle directly from `android/`:

```powershell
cd android
.\gradlew.bat testDebugUnitTest lintDebug checkArchitecture
```

## License

Licensed under the [Apache License, Version 2.0](../LICENSE).
