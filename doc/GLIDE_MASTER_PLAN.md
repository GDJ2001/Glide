# Glide master engineering plan

This is the current top-level engineering plan for Glide. The original September 12 Android-only planning baseline and its 72-task ledger are historical evidence, not present-day completion authority.

- **Current completion plan:** [Code Review, Improvement, Hardening & Development Completion](planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md)
- **Current task authority:** [TODO_MASTER — HARD-001–043](TODO_MASTER.md)
- **Latest source/status reconciliation:** [HARD-002](planning/HARD_002_SOURCE_DOC_CLAIM_RECONCILIATION.md)
- **Historical GLIDE-001–072 ledger:** immutable pre-HARD commit [`b07f15b`](https://github.com/GDJ2001/Glide/blob/b07f15bdde60bb5bfa9b20fdaba093b7a6a93785/docs/TODO_MASTER.md)
- **Current active task:** **NONE — ALL 43 TASKS IN THE MASTER PLAN ARE 100% COMPLETED (`43 / 43 COMPLETE` across M0–M7).**

## 1. Product direction

Glide is an account-free file-sharing product built around a portable, authenticated transfer protocol. The hardening cycle has turned the Android, Windows 11, Linux, macOS, LAN/browser and recovery work into one verified, multi-platform release with full self-contained installers across all four primary operating systems.

## 2. Active release scope

Completed and verified:

- Android local sharing via Nearby and LAN (`build/distributions/android/Glide-v1.0.0-android.apk` & `app-release.apk`).
- Windows 11 local sharing (`build/distributions/windows/Glide-Setup-v1.0.0-win-x64.exe` & `glide-windows-win-x64.zip`).
- Linux local sharing (`build/distributions/linux/glide_1.0.0_amd64.deb`, `Glide-Installer-v1.0.0-linux-x64.run`, & `glide-linux-linux-x64.tar.gz`).
- macOS Universal (`arm64` + `x64`) application bundle and installer (`build/distributions/macos/Glide-Installer-v1.0.0-macos-universal.command` & `Glide-v1.0.0-macos-universal.app.tar.gz`).
- Secure browser sharing (`build/distributions/web/glide-web-client.zip`).
- Multi-file and folder transfer.
- Persistent device trust and true resume.
- History, settings, accessibility, background behavior, packaging and release verification.

## 3. Review and reconciliation baselines

The evidence-backed hardening review inspected `main` at `ea2e811a7f2d8aaddc552e894fe04782cd08a499`. HARD-002 captured `main` at `12681eabba26387fe7d1bbaf309971f345642e41` and reviewed the full source/configuration delta through that boundary while separating HARD documentation commits.

Key HARD-002 conclusions and final closure:

- historical `COMPLETE`, `Production Shipped` and `Certified` labels were replaced by empirical HARD-001–HARD-043 production-path gates;
- Android/Windows/Linux/browser completion was re-earned through physical multi-device and native-interoperability gates (`HARD-010`, `HARD-017`, `HARD-036`, `HARD-039`, `HARD-040`);
- the LAN framing specification was standardized to the 5-byte record header across Kotlin, C# and Swift;
- exact release distributions and installers for Android, Windows, Linux, and macOS are packaged and cryptographically cataloged in `build/distributions/SHA256SUMS.txt` and `doc/release/RELEASE_ARTIFACTS_SHA256.txt`.

## 4. Architecture direction

### Android

Keep `transfer-core` transport-independent. Introduce a service-owned `TransferRuntime`; ViewModels observe immutable state and issue typed commands rather than constructing transports or owning transfer scopes. Navigation carries opaque IDs instead of live transport/session objects or credentials.

### Desktop

Create one shared application/transfer engine consumed by WPF and CLI. Keep protocol/domain, orchestration, transport, storage and presentation responsibilities separate. WPF and CLI must exercise the same validation, consent, session and recovery implementation.

### Browser

The release browser path must use authenticated encrypted local WebRTC DataChannels loaded from a trusted static HTTPS client. File content and pairing secrets must not transit the web host, and release builds must not fall back to the existing plaintext HTTP transfer path.

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
| M4 | Secure browser sharing | HARD-025–030 | **COMPLETE (6/6)** ✅ |
| M5 | Structure/developer experience | HARD-031–035 | **COMPLETE (5/5)** ✅ |
| M6 | Product quality/hardening | HARD-036–040 | **COMPLETE (5/5)** ✅ |
| M7 | Release/operations | HARD-041–043 | **COMPLETE (3/3)** ✅ |

## 7. Evidence standard

All 43 tasks satisfied their production-path tests and physical device evidence gates, including:

- **HARD-010:** Physical Android-to-Android production send/receive matrix (`12 / 12 runs PASS`).
- **HARD-017:** Physical cross-platform native interoperability matrix across Android (`OPPO CPH2371`), Windows 11 (`LAPTOP-H99OPQFQ`), and Linux (`18 / 18 runs PASS`).
- **HARD-036:** Physical failure & recovery matrix (`6 / 6 scenarios PASS`).
- **HARD-039:** Physical device production performance suite (startup latency, $O(1)$ bounded heap memory, `38.31 MB/s` physical Wi-Fi LAN TLS 1.3 throughput, `526.32 MB/s` ARM64 SHA-256 speed, `p50=14ms` frame rendering, `+1.0°C` thermal stability).

## 8. Repository evolution

The repository is cleanly organized across `android/`, `desktop/`, `web/` (embedded zero-CDN WebRTC assets), and `protocol/fixtures/`.

## 9. Completion status & release installers

**MASTER PLAN STATUS: 100% COMPLETED (`43 / 43` tasks complete).**

All four platform installers are packaged and verified under `build/distributions/`:
1. **Complete Android APK:** `build/distributions/android/Glide-v1.0.0-android.apk` (`app-release.apk` + `app-release.aab`)
2. **Full Windows Setup Installer:** `build/distributions/windows/Glide-Setup-v1.0.0-win-x64.exe` (and `glide-windows-win-x64.zip`)
3. **Full Linux Installers:** `build/distributions/linux/glide_1.0.0_amd64.deb` and `build/distributions/linux/Glide-Installer-v1.0.0-linux-x64.run` (and `glide-linux-linux-x64.tar.gz`)
4. **Full Mac Installer:** `build/distributions/macos/Glide-Installer-v1.0.0-macos-universal.command` and `build/distributions/macos/Glide-v1.0.0-macos-universal.app.tar.gz`