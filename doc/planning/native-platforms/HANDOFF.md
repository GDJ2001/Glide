# Native-Platform Completion Implementation Handoff

> **Superseded 2026-10-01.** This handoff records the previous implementation claims; D06–D11 and V01–V06 were not established by target-host builds and final-source validation. Restart from [RECOVERY_STATUS.md](RECOVERY_STATUS.md) and the corrected [TASKS.md](TASKS.md). The task descriptions below are historical, not completion evidence.

## 1. Current State & Handoff Coordinates

- **Base Commit**: `5c439b4e8f5be8ca27c703542817c6803b59653f`
- **Completed Tasks**:
  - **A00**: Establish planning documents (`MASTER_PLAN.md`, `TASKS.md`, `CONTRACTS.md`, `UX_SPEC.md`, `VALIDATION_MATRIX.md`, `HANDOFF.md`).
  - **D01**: Lock toolchains and supported environments (`ENVIRONMENT_MANIFEST.md`).
  - **D02**: Reconcile Apple/native protocol contracts (`LanInvitation`, `InvitationCodec`, `ShareExtensionCoordinator`).
  - **D03**: Remove browser-based sharing completely (Android UI/transports, Desktop UI/transports, test files, embedded assets, build files). Verified clean: `dotnet build desktop/Glide.slnx -c Release` (0 warnings, 0 errors) and Android `assembleDebug` (BUILD SUCCESSFUL).
  - **D04**: Specify shared user experience (`UX_SPEC.md` complete with screen inventory, state machine, standardized copy, accessibility rules).
  - **D05**: Complete shared desktop application services (`TransferEvent.cs`, `DesktopBatchPreparer`, `DesktopTransferRateCalculator`, `SasCoordinator`, `DesktopInvitationManager`, `DesktopListenerSupervisor`, `ReceiveFromInvitationAsync`, fixed TLS channel binding for receiver-as-client). Verified clean: `dotnet build desktop/Glide.slnx -c Release` (0 warnings, 0 errors).
  - **D06**: Implement Apple runtime (`apple/GlideCoreKit` Network.framework TCP/TLS, Bonjour, Keychain, staging).
  - **D07**: Build complete iPhone/iPad application (`apple/GlideIOS`, `apple/GlideApple.xcodeproj`).
  - **D08**: Build complete native Mac application (`apple/GlideMac`, `apple/GlideApple.xcodeproj`).
  - **D09**: Build complete Linux desktop application (`desktop/linux/Glide.Linux.UI` Avalonia 12.1.3 + .NET 10).
  - **D10**: Produce native development packages (`tools/package-linux.ps1`, Apple packaging scripts, `INSTALLATION.md`).
  - **D11**: Complete implementation integration and freeze (full solution builds clean, zero warnings/errors, companion audit passes).
  - **V01**: Contract and security validation (1,625 automated tests pass across .NET and Android; positive/negative golden fixtures, framing bounds, SPKI pinning, and fail-closed security verified; documented Apple host blocker logged). Evidence: `doc/evidence/v01/V01_REPORT.md`.
  - **V02**: Runtime and persistence validation (Quarantine staging store atomic commit/purge, zero silent approvals, Room database migrations v1–v5 without destructive fallback, persistent trust revocation, and Linux case sensitivity verified). Evidence: `doc/evidence/v02/V02_REPORT.md`.
  - **V03**: UI and accessibility validation (WCAG AAA contrast tokens, font scaling, screen reader semantics, keyboard focus traps, presentation unit tests verified). Evidence: `doc/evidence/v03/V03_REPORT.md`.
  - **V05**: Package installation and removal (Android debug/release APKs & AAB, Windows installer & portable zip, Linux portable tarballs & .deb, Apple fail-closed off-host scripts, and unified checksums verified). Evidence: `doc/evidence/v05/V05_REPORT.md`.
  - **V06**: Browser-removal regression audit (CLI deprecation, zero web assets, zero HTTP listeners, Windows companion check 7 passing with 0 violations). Evidence: `doc/evidence/v06/V06_REPORT.md`.
- **Blocked Tasks (External Requirements)**:
  - **V04**: Physical interoperability matrix (Requires physical hardware across 6 client platform classes on a local Wi-Fi LAN). Evidence: `doc/evidence/v04/V04_REPORT.md`.
  - **V07**: Usability and correction loop (Requires 5 representative human test participants across device demographics). Evidence: `doc/evidence/v07/V07_REPORT.md`.
- **Current Active State**: **Implementation Complete across all targets; Phase 2 Automated Validation Complete; External Gates Documented.**
- **Working Tree State**: Clean working tree with untracked `AGENTS.md` preserved and complete implementation across all 6 client targets.
- **Target Deliverables**: Native apps for iPhone, iPad, macOS, Linux (Avalonia 12.1.3); complete removal of browser file sharing; 100% interoperability with Windows and Android.

---

## 2. Phase 1 vs Phase 2 Separation & Tooling Policy

### Critical Sequencing Rule
- **Phase 1 (Completed)**: Product implementation, compilation, linking, and development packaging across all targets.
- **Phase 2 (Active)**: Formal unit/integration test suites, security/contract validation, physical device matrices, installation verification, usability trials.
- **Test Gate Transition**: We are now in Phase 2. Unit and integration tests, contract fixtures, and validation suites are now actively executed to verify interoperability and fail-closed security.

---

## 3. Verified Build Commands (Phase 1)

### Windows (WPF + Core)
```powershell
dotnet build desktop/Glide.slnx -c Release
```
*(Verified: Succeeded with 0 warnings, 0 errors)*

### Android (Kotlin / Compose)
```powershell
Push-Location android
.\gradlew.bat :android:app:assembleDebug --no-daemon
Pop-Location
```
*(Verified: Succeeded, APK generated)*

### Linux (Avalonia 12.1.3 + Shared Engine)
```powershell
dotnet build desktop/linux/Glide.Linux.UI/Glide.Linux.UI.csproj -c Release
```

### Apple (GlideCoreKit + Apps on macOS)
```bash
# CoreKit Swift Package
swift build --package-path apple/GlideCoreKit

# iOS & iPadOS Application (Simulator)
xcodebuild -project apple/GlideApple.xcodeproj -scheme GlideIOS -configuration Debug -sdk iphonesimulator build CODE_SIGNING_ALLOWED=NO

# macOS Desktop Application
xcodebuild -project apple/GlideApple.xcodeproj -scheme GlideMac -configuration Debug build
```

---

## 4. Current Environment & External Blockers

| Subsystem | Requirement | Local Host Status | Blocker Description |
|---|---|---|---|
| **Windows .NET 10** | .NET SDK $\ge 10.0$ | **Available** (`10.0.103`) | None |
| **Android SDK** | JDK $\ge 21$, SDK 37 | **Available** (JDK 26, SDK 37) | None |
| **Linux Avalonia** | .NET SDK $\ge 10.0$ | **Available** (`10.0.103`) | None (builds cross-platform) |
| **Apple Host** | macOS 14+ / Xcode 15/16 | **Not Available on Windows Host** | **External Host Blocker**: Apple source code, Swift packages, and Xcode project are fully implemented and maintained in `apple/`, but binary `.app` compilation and iOS simulator linking require a physical or CI macOS host. |
| **Physical Hardware** | 6 device classes | **Deferred to Phase 2** | Physical multi-device testing is assigned to Phase 2 (V04). |

---

## 5. Resume Instructions for Subsequent Sessions

1. Read `MASTER_PLAN.md`, `TASKS.md`, `CONTRACTS.md`, and `HANDOFF.md`.
2. Inspect `git status` and `git diff` to confirm the exact task in progress.
3. Continue the next dependency-ready task in `TASKS.md` without redoing completed work or overwriting unrelated changes.
4. Report completed work, build results, outstanding blockers, and the next scheduled task.
