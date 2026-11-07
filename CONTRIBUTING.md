# Contributing to Glide

Thank you for your interest in contributing to Glide! Glide is an offline, peer-to-peer, zero-cloud file transfer system designed for private and verifiable high-speed local sharing.

---

## Code of Conduct

This project and everyone participating in it is governed by the [Glide Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

---

## Development Setup

Before writing code, run the environment doctor script to verify your local toolchains:

```powershell
pwsh -File tools/doctor.ps1
```

### Platform Requirements

- **Android**:
  - JDK 21+ (Java 21 or 25)
  - Android SDK with `compileSdk 37` and build-tools installed (`ANDROID_HOME` or `android/local.properties`)
  - Gradle wrapper (`android/gradlew` on Linux/macOS, `android/gradlew.bat` on Windows)
- **Desktop (Windows & Linux)**:
  - .NET 10 SDK (`net10.0`, `net10.0-windows`)
  - PowerShell 7+ (`pwsh`)
- **Native (SPAKE2)**:
  - CMake 3.22+, C/C++ toolchain (Clang/GCC/MSVC)
  - Pre-built native runtimes reside in `native/glide_spake2/runtimes/`

---

## Architecture & Code Conventions

Glide enforces strict architectural isolation across platform modules. Violations will fail CI and local architecture verification gates:

1. **`:transfer-core` Pure JVM Isolation**:
   - `android/core` is a **pure Kotlin JVM library**.
   - It must have strictly **zero** dependencies on Android SDK, Google Play Services, Compose, or Room.
2. **Transport Adapter Isolation**:
   - `android/transport-nearby` and `android/transport-lan` must not import UI ViewModels or Room database layers.
3. **Core Protocol & Security Invariants**:
   - **Framing**: Unsigned 32-bit big-endian length prefix. Control payloads $\le 16\text{ KiB}$. Bulk stream chunks $\le 64\text{ KiB}$.
   - **Batch Limits**: Max 1,000 items, max 64 GiB per file, max 256 GiB per batch.
   - **Path Sanitization**: All inbound filenames must be strictly scrubbed via `WindowsPathSanitizer` / `FilenameSanitizer`. Reject path separators (`/`, `\`), traversal (`..`), UNC paths (`\\server\share`), drive roots (`C:`), and DOS device names (`CON`, `PRN`, `AUX`, `NUL`, `COM1-9`, `LPT1-9`).
   - **Quarantine Staging**: Never write inbound streams directly to destination folders. Stream raw bytes into `.part` files inside `QuarantineStagingStore`, calculate streaming SHA-256, verify against `ITEM_END` digest, and atomically commit rename on match.
   - **Authentication**: Native LAN pairing requires ephemeral tokens and SHA-256 SPKI pinned TLS certificates. SAS 6-digit comparison must be explicitly confirmed by user action. Never auto-accept transfers.
   - **Browser Sharing**: Retired. Do not reintroduce browser file transfer, web gateways, embedded web clients, or web distribution packages. Preserve native `glide://pair/v1` invitations.

Always verify architecture before pushing:

```powershell
pwsh -File tools/check-architecture.ps1 -FailFast
```

---

## Pull Request Guidelines

1. **Branch Workflow**:
   - Branch from `main` (e.g., `feature/your-feature` or `bugfix/your-fix`).
2. **Commit Style**:
   - Write clean, atomic commits following Conventional Commits (`feat: ...`, `fix: ...`, `docs: ...`, `refactor: ...`).
3. **Verification Gates**:
   - Ensure all local verification gates pass before opening a PR:
     ```powershell
     pwsh -File tools/check-architecture.ps1 -FailFast
     pwsh -File tools/test.ps1 -Target All
     pwsh -File tools/lint.ps1 -Target All
     ```
   - Run the comprehensive local verification gate:
     ```powershell
     pwsh -File tools/verify.ps1
     ```
4. **Clean Commits**:
   - Do not commit build artifacts (`bin/`, `obj/`, `build/`), temporary files, or Python bytecode (`*.pyc`).
   - Do not commit machine-specific absolute paths or hardcoded local IP addresses.
5. **PR Template**:
   - Open a Pull Request on GitHub and fill out all sections of the [Pull Request Template](.github/pull_request_template.md).
