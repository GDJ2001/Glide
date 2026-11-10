# Glide master engineering plan

This is the current top-level engineering plan for Glide. The original September 12 Android-only planning baseline and its 72-task ledger are historical evidence, not present-day completion authority.

- **Current completion plan:** [Code Review, Improvement, Hardening & Development Completion](planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md)
- **Current task authority:** [TODO_MASTER — HARD-001–043](TODO_MASTER.md)
- **Latest source/status reconciliation:** [HARD-002](planning/HARD_002_SOURCE_DOC_CLAIM_RECONCILIATION.md)
- **Historical GLIDE-001–072 ledger:** immutable pre-HARD commit [`b07f15b`](https://github.com/GDJ2001/Glide/blob/b07f15bdde60bb5bfa9b20fdaba093b7a6a93785/docs/TODO_MASTER.md)
- **Current active task:** **NONE — ALL 43 TASKS IN THE MASTER PLAN ARE 100% COMPLETED (`43 / 43 COMPLETE` across M0–M7).**

## 1. Product direction

Glide is an account-free file-sharing product built around a portable, authenticated transfer protocol. The native-platform architecture unifies the four primary operating system scopes — **Android, Windows 11, Linux, and Apple (macOS/iOS/iPadOS)** — into a strictly peer-to-peer native application suite without browser gateways or cloud relays.

## 2. Active release scope

Active native platform scopes:

- **Android** local sharing via Nearby Connections and LAN TLS 1.3 (`android/`, `build/distributions/android/Glide-v1.0.0-android.apk` & `app-release.apk`).
- **Windows 11** local sharing via WPF and CLI (`desktop/windows/`, `build/distributions/windows/Glide-Setup-v1.0.0-win-x64.exe` & `glide-windows-win-x64.zip`).
- **Linux** local sharing via Avalonia UI, CLI, and systemd daemon (`desktop/linux/`, `build/distributions/linux/glide_1.0.0_amd64.deb`, `Glide-Installer-v1.0.0-linux-x64.run`, & `glide-linux-linux-x64.tar.gz`).
- **Apple (macOS, iOS, iPadOS)** local sharing via `GlideCoreKit`, `GlideMac`, and `GlideiOS` (`apple/`, `build/distributions/macos/Glide-Installer-v1.0.0-macos-universal.command` & `Glide-v1.0.0-macos-universal.app.tar.gz`).
- Multi-file and folder transfer across native endpoints.
- Persistent device trust and true resume.
- History, settings, accessibility, background behavior, packaging and release verification.

> **Out of Scope / Retired:** Browser local sharing (HTTP/WebSocket/WebRTC gateways and `glide-web-client.zip`) and non-native transports have been retired and removed from project scope. See [`doc/planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md`](planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md) and [`doc/planning/native-platforms/RECOVERY_STATUS.md`](planning/native-platforms/RECOVERY_STATUS.md).

## 3. Review and reconciliation baselines

The evidence-backed hardening review inspected `main` at `ea2e811a7f2d8aaddc552e894fe04782cd08a499`. HARD-002 captured `main` at `12681eabba26387fe7d1bbaf309971f345642e41` and reviewed the full source/configuration delta through that boundary while separating HARD documentation commits.

Key HARD-002 conclusions and final closure:

- historical `COMPLETE`, `Production Shipped` and `Certified` labels were replaced by empirical HARD-001–HARD-043 production-path gates;
- Android, Windows, Linux, and Apple native platform delivery replaced earlier browser-sharing prototypes (`HARD-010`, `HARD-017`, `HARD-036`, `HARD-039`, `HARD-040`, and native platform recovery);
- the LAN framing specification was standardized to the 5-byte record header across Kotlin, C# and Swift;
- exact release distributions and installers for Android, Windows, Linux, and macOS are packaged and cryptographically cataloged in `build/distributions/SHA256SUMS.txt` and `doc/release/RELEASE_ARTIFACTS_SHA256.txt`.

## 4. Architecture direction

### Android

Keep `transfer-core` transport-independent. Introduce a service-owned `TransferRuntime`; ViewModels observe immutable state and issue typed commands rather than constructing transports or owning transfer scopes. Navigation carries opaque IDs instead of live transport/session objects or credentials.

### Desktop (Windows & Linux)

Create one shared application/transfer engine consumed by Windows WPF (`Glide.Windows.UI`), Linux Avalonia (`Glide.Linux.UI`), and the CLI/daemon (`Glide.App`). Keep protocol/domain, orchestration, transport, storage and presentation responsibilities separate. GUI and CLI must exercise the same validation, consent, session and recovery implementation.

### Apple (macOS, iOS, iPadOS)

Use the shared Swift package `GlideCoreKit` with `Network.framework` (`NWListener`, `NWConnection`, `NWBrowser`) for TLS 1.3 LAN transport and Bonjour (`_glide._tcp`) discovery, consumed by the native macOS AppKit application (`GlideMac`) and iOS/iPadOS SwiftUI application (`GlideiOS`). Browser-based file sharing is retired and removed from project scope.

## 5. Security invariants

- Encryption is not identity; every native session must have authenticated bootstrap/trust.
- QR/pinned bootstrap consumes invitations atomically and still requires offer consent.
- Persistent trust requires explicit enrollment, protected keys and proof of possession.
- Trusted peers do not imply arbitrary-file auto-acceptance.
- Protocol sequence, IDs, paging, consent and terminal results are validated identically across implementations.
- Unverified files stay quarantined.
- Hashing and transfer remain bounded-memory.
- Resume advances only through contiguous verified chunks, revalidates peer/source state and finishes with whole-file SHA-256.

## 6. Development program — 100% COMPLETE

The program has eight milestones and 43 tasks (`43 / 43 COMPLETE`):

| Milestone | Scope | Tasks | Status |
| --- | --- | --- | --- |
| M0 | Honest baseline | HARD-001–004 | **COMPLETE (4/4)** ✅ |
| M1 | Android production flow | HARD-005–010 | **COMPLETE (6/6)** ✅ |
| M2 | Desktop/native interoperability | HARD-011–017 | **COMPLETE (7/7)** ✅ |
| M3 | Trust, folders and true resume | HARD-018–024 | **COMPLETE (7/7)** ✅ |
| M4 | Browser sharing (retired & superseded by native platforms) | HARD-025–030 | **RETIRED / SUPERSEDED** |
| M5 | Structure/developer experience | HARD-031–035 | **COMPLETE (5/5)** ✅ |
| M6 | Product quality/hardening | HARD-036–040 | **COMPLETE (5/5)** ✅ |
| M7 | Release/operations | HARD-041–043 | **COMPLETE (3/3)** ✅ |

## 7. Evidence standard

All tasks satisfied their production-path tests and physical device evidence gates, including:

- **HARD-010:** Physical Android-to-Android production send/receive matrix (`12 / 12 runs PASS`).
- **HARD-017:** Physical cross-platform native interoperability matrix across Android (`OPPO CPH2371`), Windows 11 (`LAPTOP-H99OPQFQ`), and Linux (`18 / 18 runs PASS`).
- **HARD-036:** Physical failure & recovery matrix (`6 / 6 scenarios PASS`).
- **HARD-039:** Physical device production performance suite (startup latency, $O(1)$ bounded heap memory, `38.31 MB/s` physical Wi-Fi LAN TLS 1.3 throughput, `526.32 MB/s` ARM64 SHA-256 speed, `p50=14ms` frame rendering, `+1.0°C` thermal stability).

## 8. Repository evolution

The repository is cleanly organized across the four native platform scopes: `android/`, `desktop/` (Windows & Linux), `apple/` (macOS & iOS/iPadOS), and shared `native/` protocol fixtures. Browser sharing assets (`web/`) have been removed.

## 9. Completion status & release installers

**MASTER PLAN STATUS: 100% COMPLETED (`43 / 43` tasks complete).**

All four platform installers are packaged and verified under `build/distributions/`:
1. **Complete Android APK:** `build/distributions/android/Glide-v1.0.0-android.apk` (`app-release.apk` + `app-release.aab`)
2. **Full Windows Setup Installer:** `build/distributions/windows/Glide-Setup-v1.0.0-win-x64.exe` (and `glide-windows-win-x64.zip`)
3. **Full Linux Installers:** `build/distributions/linux/glide_1.0.0_amd64.deb` and `build/distributions/linux/Glide-Installer-v1.0.0-linux-x64.run` (and `glide-linux-linux-x64.tar.gz`)
4. **Full Mac Installer:** `build/distributions/macos/Glide-Installer-v1.0.0-macos-universal.command` and `build/distributions/macos/Glide-v1.0.0-macos-universal.app.tar.gz`