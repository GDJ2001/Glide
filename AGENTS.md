# AGENTS.md — Glide Repository AI Development Guide

This document provides durable operational context and guardrails for AI coding assistants working in the Glide repository.

---

## 1. Project Overview & Multi-Platform Architecture

Glide is an offline, peer-to-peer, zero-cloud file transfer system for secure local sharing. Android and Windows are established clients; Linux and Apple native application completion is in progress. Consult `doc/planning/native-platforms/RECOVERY_STATUS.md` before making delivery claims.

### Platform Modules & Boundaries

- **Android (`android/`)**: Single-Activity Jetpack Compose application built with Kotlin 2.4.20, AGP 9.4.1, compileSdk 37, and target bytecode Java 17/21+.
  - `:app` (`android/app`): UI, ViewModels, navigation (Navigation 3), Hilt DI composition, Foreground Service (`TransferService`), and `TransferRuntime`.
  - `:transfer-core` (`android/core`): **Pure Kotlin JVM library**. Strictly zero Android/GMS/Compose/Room dependencies. Owns domain models, protocol codec, state reducer, and abstract ports.
  - `:transport-nearby` (`android/transport-nearby`): Google Play Services Nearby Connections adapter. Forbidden from importing UI or database layers.
  - `:transport-lan` (`android/transport-lan`): TLS 1.3 over TCP socket adapter with zero-dependency mDNS responder/browser.
  - `:data` (`android/data`): Room database (`GlideDatabase` v5), Jetpack DataStore preferences, Keystore identity, and `QuarantineStagingStore`.
  - `:designsystem` (`android/designsystem`): Compose/Material3 theme tokens, icons, and reusable controls.
- **Desktop (`desktop/`)**: Unified .NET 10 (`net10.0`) solution (`desktop/Glide.slnx`).
  - `src/Glide.Core`: Protocol envelopes, cryptographic primitives, length-prefixed framing, and file sanitization.
  - `src/Glide.Transport`: TLS 1.3 sockets and mDNS discovery.
  - `src/Glide.Desktop`: State machine, `DesktopStateStore`, and daemon background engine.
  - `src/Glide.App`: Cross-platform CLI executable and headless systemd daemon.
  - `windows/Glide.Windows.UI`: WPF Desktop Application (`net10.0-windows`).
  - `tests/Glide.Tests` & `tests/Glide.Windows.UI.Tests`: xUnit test suites.
- **Apple (`apple/`)**: Swift Package `GlideCoreKit` and iOS/macOS containers. Their authenticated runtime and target-host builds are open recovery gates.

---

## 2. Core Protocol & Security Guardrails

- **Framing**: Unsigned 32-bit big-endian length prefix. Control payloads $\le 16\text{ KiB}$. Bulk payload stream chunks bounded to $\le 64\text{ KiB}$.
- **Batch Ceilings**: Maximum 1,000 items, maximum 64 GiB per file, maximum 256 GiB per batch.
- **Path Sanitization**: All inbound filenames must be strictly scrubbed via `WindowsPathSanitizer` / `FilenameSanitizer`. Reject path separators (`/`, `\`), traversal (`..`), drive roots (`C:`), UNC paths (`\\server\share`), and DOS device names (`CON`, `PRN`, `AUX`, `NUL`, `COM1-9`, `LPT1-9`).
- **Quarantine Staging**: Inbound files must NEVER be written directly to the user's destination folder. Stream raw bytes into private staging (`.part` files) inside `QuarantineStagingStore`, calculate streaming SHA-256, verify against `ITEM_END` digest, and perform an atomic commit rename only upon verified match.
- **Authentication**: Native LAN pairing requires ephemeral one-time tokens and SHA-256 SPKI pinned TLS certificates. SAS 6-digit comparison must be explicitly confirmed by user action. Never auto-accept transfers.
- **Browser Sharing**: Retired. Do not reintroduce browser file transfer, web gateways, embedded web clients, or web distribution packages. Preserve native `glide://pair/v1` invitations.

---

## 3. Verified Developer Commands

All primary workflows are standardized under `tools/` with PowerShell 7+ runners:

```powershell
# 1. Environment Doctor (Verifies JDK 21+, Android SDK 37, .NET 10, Git)
pwsh -File tools/doctor.ps1

# 2. Architecture & Import Boundary Check (Enforces zero-leakage rules)
pwsh -File tools/check-architecture.ps1 -FailFast

# 3. Windows Companion & Conformance Audit
pwsh -File tools/verify-windows-companion.ps1

# 4. Build Targets (Debug or Release; All, Android, or Windows)
pwsh -File tools/build.ps1 -Configuration Debug -Target All

# 5. Execute Test Suites
pwsh -File tools/test.ps1 -Target All

# 6. Static Analysis & Lint
pwsh -File tools/lint.ps1 -Target All

# 7. Comprehensive Local Verification Gate
pwsh -File tools/verify.ps1
```

### Knowledge Graph Navigation

The repository graph is pre-indexed with Graphify. Use the CLI tool before executing broad file scans:

```bash
graphify query "<SymbolOrConcept>"
```

---

## 4. Protected Areas & Coding Conventions

- **Do Not Manually Modify Generated Artifacts**:
  - `graphify-out/` (Knowledge graph data)
  - `android/data/schemas/` (Room exported schema JSON files; managed via Room migrations)
  - `build/`, `android/app/build/`, `desktop/**/bin/`, `desktop/**/obj/`
- **Never Bypass Layering**:
  - Do NOT import Android or GMS types in `:transfer-core`.
  - Do NOT import networking, sockets, or Nearby in UI ViewModels.
  - Do NOT perform main-thread blocking file I/O or Room database queries.
- **Persistence Changes**: Any changes to `GlideDatabase` require incrementing the Room version and implementing explicit migrations (`Migration(from, to)`). Never enable `fallbackToDestructiveMigration()`.
- **Validation Before Commit**: Run `tools/check-architecture.ps1` and targeted unit tests after any architectural or protocol changes.
