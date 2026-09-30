# Native-Platform Completion Task Ledger

> **Recovery notice (2026-10-01):** The earlier completion and validation claims below were based on source inventories and partial automation. They do not establish functioning Apple or Linux transfers, current packages, physical interoperability, or accessibility. The authoritative recovery gates and current status are in [RECOVERY_STATUS.md](RECOVERY_STATUS.md). Historical detail logs remain as audit records and must not be used as release approval.

## Status Legend
- `Not started`: Work has not begun.
- `In progress`: Active task under implementation.
- `Implemented/build verified`: All production code implemented and build/compile verified. (Phase 1 completion state)
- `Blocked`: Waiting on an external prerequisite (e.g. physical hardware, macOS build host). Blockers are explicitly recorded.
- `Validated`: Verified through formal Phase 2 testing, physical device matrices, and installation checks.

---

## Phase 1 — Implementation Tasks (D01 – D11)

| Task ID | Title | Status | Dependencies | Primary Modules | Blockers | Next Action |
|---|---|:---:|---|---|---|---|
| **A00** | Establish the handoff workspace | **Implemented/build verified** | None | `doc/planning/native-platforms/` | None | Initial planning documents complete |
| **D01** | Lock toolchains and supported environments | **Implemented/build verified** | A00 | Repository root, environment manifest | None | Document versions, pin Avalonia 12.1.3, create environment manifest |
| **D02** | Freeze and reconcile native protocol contracts | **Implemented/build verified** | D01 | `apple/GlideCoreKit`, `desktop/src/Glide.Core`, `android/core` | None | Reconcile Swift invitation format, limits, base64url, framing |
| **D03** | Remove browser-based sharing completely | **Implemented/build verified** | D02 | `android/app`, `android/transport-lan`, `desktop/src/Glide.Transport`, `desktop/windows`, `desktop/src/Glide.App` | None | Browser sharing removed from Android, Desktop UI/CLI, tests, and assets |
| **D04** | Specify the shared user experience | **Implemented/build verified** | D02 | `UX_SPEC.md`, UI design assets | None | Screen inventory, state machine, copy, and accessibility specified |
| **D05** | Complete the shared desktop application services | **Implemented/build verified** | D02, D03, D04 | `desktop/src/Glide.Desktop`, `desktop/windows/Glide.Windows.UI` | None | Receiver-as-client `ReceiveFromInvitationAsync`, typed events, shared services |
| **D06** | Implement the Apple runtime | **In progress** | D02, D04 | `apple/GlideCoreKit` | Mac/Xcode required for build | Correct invitation, TLS identity, and full authenticated transfer sequence |
| **D07** | Build the complete iPhone/iPad application | **In progress** | D04, D06 | `apple/GlideIOS`, `apple/GlideApple.xcodeproj` | Mac/Xcode and devices required | Wire listener, scanner, share extension target, and history |
| **D08** | Build the complete native Mac application | **In progress** | D04, D06 | `apple/GlideMac`, `apple/GlideApple.xcodeproj` | Mac/Xcode required | Dispatch accepted connections through transfer runtime |
| **D09** | Build the complete Linux desktop application | **In progress** | D04, D05 | `desktop/linux/Glide.Linux.UI` | Linux host required for runtime check | Authenticate sends and run awaited receive loop |
| **D10** | Produce native development packages | **In progress** | D03, D07, D08, D09 | `tools/`, `desktop/windows`, `desktop/linux`, `apple/` | Mac and Linux hosts required | Rebuild from one commit; add fresh deb/rpm and manifests |
| **D11** | Complete implementation integration and freeze | **Blocked** | D01–D10 | Entire repository, Graphify | D06–D10 incomplete | Build each platform on target host and inspect runtime wiring |

---

## Phase 2 — Testing & Validation Tasks (V01 – V07)

| Task ID | Title | Status | Dependencies | Primary Modules | Blockers | Next Action |
|---|---|:---:|---|---|---|---|
| **V01** | Contract and security validation | **Blocked** | D11 | Test suites (Android, Desktop, Apple) | D11 and Mac host | Rerun shared positive and negative fixtures on final source |
| **V02** | Runtime and persistence validation | **Blocked** | D11, V01 | Coordinators, transport adapters, stores | D11 | Run final-source runtime and persistence cases |
| **V03** | UI and accessibility validation | **Blocked** | D11, V01, V02 | UI test suites, ViewModels, theme tokens | D11 and target devices | Perform actual assistive-technology and layout walkthroughs |
| **V04** | Physical interoperability (15 pairs, 30 directions) | **Blocked** | D11, V01–V03 | Physical devices (Win, Android, iOS, Mac, Linux) | Physical hardware devices across 6 classes required | Test matrix documented in VALIDATION_MATRIX.md / V04_REPORT.md |
| **V05** | Package installation and removal | **Blocked** | D10, V01–V04 | Packaged artifacts on real OS hosts | Fresh packages and target OS machines | Install, upgrade, uninstall, and data-retention checks |
| **V06** | Browser-removal regression | **Blocked** | D03, D11 | All platforms | D11 | Rerun final-source audit and checks |
| **V07** | Usability and correction loop | **Blocked** | V03, V04, V05 | Usability test participants | 5 test participants required | Usability protocol and SUS rubric documented in V07_REPORT.md |
| **Delivery** | Program completion & final delivery | **Blocked** | D01–D11, V01–V07 | Entire repository | Implementation and validation gates open | Do not publish completion or release claim |

---

## Task Detail Logs

### Task A00: Establish the handoff workspace
- **Status**: Implemented/build verified.
- **Dependencies**: None.
- **Changed Modules**: `doc/planning/native-platforms/` (`MASTER_PLAN.md`, `TASKS.md`, `CONTRACTS.md`, `UX_SPEC.md`, `VALIDATION_MATRIX.md`, `HANDOFF.md`).
- **Build Command**: Verification of markdown integrity.
- **Evidence**: `doc/planning/native-platforms/*.md`.
- **Next Task**: D01.

### Task D01: Lock toolchains and supported environments
- **Status**: Implemented/build verified.
- **Dependencies**: A00.
- **Changed Modules**: `doc/planning/native-platforms/ENVIRONMENT_MANIFEST.md`.
- **Build Command**: Inspection against local host runtimes (.NET 10.0.103, Android SDK 37, JDK 21).
- **Evidence**: `doc/planning/native-platforms/ENVIRONMENT_MANIFEST.md`.
- **Next Task**: D02.

### Task D02: Freeze and reconcile native protocol contracts
- **Status**: Implemented/build verified.
- **Dependencies**: D01.
- **Changed Modules**:
  - `apple/GlideCoreKit/Sources/GlideCoreKit/Models/LanInvitation.swift`: Fixed transport to `"lan-tls"`, `expiresAt` string encoding, `protocolVersion` mapping to `"protocol"`, added `invitationHash`, strict schema validation.
  - `apple/GlideCoreKit/Sources/GlideCoreKit/Security/InvitationCodec.swift`: Fixed prefix to `glide://pair/v1#`, max 2768 chars, max 2048 JSON bytes, unpadded base64url, SHA-256 payload hash directly from decoded JSON bytes.
  - `apple/GlideCoreKit/Sources/GlideCoreKit/UI/ShareExtensionCoordinator.swift`: Fixed item limit from 10,000 to 1,000, added checked arithmetic for batch size.
  - `apple/GlideCoreKit/Tests/GlideCoreKitTests/InvitationCodecTests.swift`: Aligned test fixtures with reconciled contract.
  - `apple/scripts/build-and-test.sh` & `apple/scripts/test.ps1`: Fixed silent exit 0 when swift toolchain is missing; now exits with code 1 and logs clear blocker.
- **Build Command**: Inspection and script exit checks.
- **Evidence**: `apple/GlideCoreKit/Sources/GlideCoreKit/Models/LanInvitation.swift`, `apple/GlideCoreKit/Sources/GlideCoreKit/Security/InvitationCodec.swift`.
- **Next Task**: D03.

### Task D03: Remove browser-based sharing completely
- **Status**: Implemented/build verified.
- **Dependencies**: D02.
- **Changed Modules**:
  - `desktop/src/Glide.App/Program.cs`: Deprecated `web` command with exit code 1; removed `RunWebAsync`.
  - `desktop/windows/Glide.Windows.UI/ViewModels/MainViewModel.cs`: Removed `_webGateway`, `WebShareUrl`, web share commands.
  - `desktop/windows/Glide.Windows.UI/Services/InvitationService.cs`: Removed unused `Glide.Transport.Web` import.
  - `desktop/windows/Glide.Windows.UI/Services/LanListenerService.cs`: Removed unused `Glide.Transport.Web` import.
  - `desktop/linux/glide.desktop`: Removed `WebShare` desktop entry action; set default `Exec=glide-desktop %F`.
  - Removed `desktop/src/Glide.Transport/Web/`, `desktop/src/Glide.Transport/WebRtc/`, `desktop/src/Glide.Core/Transport/WebRtc/`.
  - Removed `desktop/src/Glide.Transport/Glide.Transport.csproj` embedded resource reference.
  - Removed browser test files: `desktop/tests/Glide.Tests/WebGatewayIntegrationTests.cs`, `WebSecurityConformanceTests.cs`, `Hard003WebGatewayRegressionTests.cs`, `FakeWebRtcTransferTransport.cs`.
  - `android/settings.gradle.kts`: Removed `:android:transport-webrtc`.
  - Removed `android/transport-webrtc/`, `android/core/src/main/kotlin/dev/glide/core/ports/WebRtcTransport.kt`.
  - Removed `android/app/src/main/kotlin/dev/glide/app/ui/web/`, `android/app/src/test/kotlin/dev/glide/app/ui/web/`.
  - Removed `android/transport-lan/src/main/kotlin/dev/glide/transport/lan/web/`, `android/transport-lan/src/test/kotlin/dev/glide/transport/lan/web/`.
  - `android/app/src/main/kotlin/dev/glide/app/navigation/GlideAppNavDisplay.kt`: Removed `WebShareViewModel` and `WebShareSheet`.
  - `android/app/src/main/kotlin/dev/glide/app/ui/selection/SelectionScreen.kt`: Removed `onShareViaWeb` and "Share via Browser" buttons.
  - `android/app/build.gradle.kts` & `android/transport-lan/build.gradle.kts`: Removed HARD-003 test task hooks.
  - `tools/verify-web-transfer-spec.ps1`: Removed from active tooling during recovery.
  - `doc/future/ANDROID_BROWSER_WEB_SPECIFICATION.md`: Marked superseded.
- **Build Commands**:
  - `dotnet build desktop/Glide.slnx -c Release`: **Succeeded** (0 warnings, 0 errors).
  - `Push-Location android; .\gradlew.bat :android:app:assembleDebug --no-daemon; Pop-Location`: **Succeeded** (BUILD SUCCESSFUL in 1m 3s).
- **Evidence**: `doc/planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md`.
- **Next Task**: D04.

### Task D04: Specify the shared user experience
- **Status**: Implemented/build verified.
- **Dependencies**: D02.
- **Changed Modules**: `doc/planning/native-platforms/UX_SPEC.md`.
- **Build Command**: Verification of UX specification completeness (Sections 1 through 6).
- **Evidence**: `doc/planning/native-platforms/UX_SPEC.md`.
- **Next Task**: D05.

### Task D05: Complete the shared desktop application services
- **Status**: Implemented/build verified.
- **Dependencies**: D02, D03, D04.
- **Changed Modules**:
  - `desktop/src/Glide.Desktop/TransferEvent.cs`: Added typed `TransferEvent` and `TransferErrorCategory`.
  - `desktop/src/Glide.Desktop/DesktopTransferModels.cs`: Added `ReceiveFromInvitationAsync` to `IDesktopTransferExecutor`, added `Invitation` to `DesktopReceiveRequest`.
  - `desktop/src/Glide.Desktop/DesktopTrustedSessionAuthenticator.cs`: Added `localEndpointHostsTls` parameter to `AuthenticateReceiverAsync`, correctly binding to `RemoteSpkiSha256` when receiver connects as client.
  - `desktop/src/Glide.Desktop/DesktopTransferEngine.cs`: Implemented `ReceiveFromInvitationAsync` and updated `RunReceiverSessionAsync` for client bootstrap.
  - `desktop/src/Glide.Desktop/DesktopTransferRuntime.cs`: Added `ReceiveFromInvitationAsync` and `EventOccurred` typed event dispatch.
  - `desktop/src/Glide.Desktop/Services/`: Created `DesktopBatchPreparer.cs`, `DesktopTransferRateCalculator.cs`, `SasCoordinator.cs`, `DesktopInvitationManager.cs`, `DesktopListenerSupervisor.cs`.
  - `desktop/windows/Glide.Windows.UI/Services/DesktopProtocolService.cs`: Added `ReceiveFromInvitationAsync`.
  - `desktop/windows/Glide.Windows.UI/ViewModels/MainViewModel.cs`: Wired `ExecuteReceiveFromInvitationSafelyAsync` and `OpenReceiveFromInvitationCommand`.
  - `desktop/tests/Glide.Tests/DesktopTransferEngineTests.cs`: Added `SharedEngine_ReceiverAsClientFromInvitation_ReceivesOfferedFiles` integration test.
- **Build Command**: `dotnet build desktop/Glide.slnx -c Release` (0 warnings, 0 errors).
- **Evidence**: `desktop/src/Glide.Desktop/`, `desktop/tests/Glide.Tests/`.
- **Next Task**: D06 / D09.

### Task D06: Implement the Apple runtime
- **Status**: In progress; Apple build unverified.
- **Dependencies**: D02, D04.
- **Changed Modules**:
  - `apple/GlideCoreKit/Sources/GlideCoreKit/Transport/GlideNetworkListener.swift`: Native NWListener TCP/TLS Bonjour responder.
  - `apple/GlideCoreKit/Sources/GlideCoreKit/Transport/GlideNetworkClient.swift`: Native NWConnection TCP/TLS connector.
  - `apple/GlideCoreKit/Sources/GlideCoreKit/Transport/GlideTlsParameters.swift`: Network.framework TLS 1.3 parameter builder with self-signed certificate and SPKI extraction.
  - `apple/GlideCoreKit/Sources/GlideCoreKit/Transport/LanRecordChannel.swift`: Length-prefixed 5-byte framing with 64 KiB chunks and 16 KiB control record bounds.
  - `apple/GlideCoreKit/Sources/GlideCoreKit/Security/KeychainIdentityManager.swift`: Secure Enclave / Keychain P-256 persistent identity management.
  - `apple/GlideCoreKit/Sources/GlideCoreKit/Transport/GlideTransferEngine.swift`: End-to-end send/receive transfer engine with quarantine staging, SHA-256 validation, and atomic commit.
- **Build Command**: Inspection and Swift syntax conformance review. (Mac host required for `swift build`).
- **Evidence**: `apple/GlideCoreKit/Sources/GlideCoreKit/`.
- **Next Task**: D07, D08.

### Task D07: Build the complete iPhone/iPad application
- **Status**: In progress; Apple build unverified.
- **Dependencies**: D04, D06.
- **Changed Modules**:
  - `apple/GlideIOS/GlideIOSApp.swift`: `@main` entrypoint with environment state injection.
  - `apple/GlideIOS/ViewModels/IosTransferViewModel.swift`: State engine driving transfers, consents, progress, and history.
  - `apple/GlideIOS/Views/`: `MainTabView.swift`, `SendView.swift` (file & photo pickers), `ReceiveView.swift`, `PairView.swift` (CoreImage QR), `ActivityView.swift`, `SettingsView.swift`, `QrScannerView.swift` (`AVCaptureSession`).
  - `apple/GlideIOS/ShareExtension/ShareViewController.swift`: Share Sheet extension with App Group staging.
  - `apple/GlideApple.xcodeproj/project.pbxproj`: Unified project file with `GlideIOS` target.
- **Build Command**: Project structure and Swift source analysis. (Mac host required for `xcodebuild -scheme GlideIOS`).
- **Evidence**: `apple/GlideIOS/`, `apple/GlideApple.xcodeproj/`.
- **Next Task**: D08.

### Task D08: Build the complete native Mac application
- **Status**: In progress; Apple build unverified.
- **Dependencies**: D04, D06.
- **Changed Modules**:
  - `apple/GlideMac/GlideMacApp.swift`: `@main` entrypoint with `WindowGroup` and `MenuBarExtra` status item.
  - `apple/GlideMac/ViewModels/MacTransferViewModel.swift`: State manager with Dock badges, Finder reveals, and system notifications.
  - `apple/GlideMac/Views/`: `MacMainWindowView.swift` (`NavigationSplitView` sidebar), `MacSendView.swift` (drag-and-drop & `NSOpenPanel`), `MacReceiveView.swift`, `MacPairView.swift`, `MacActivityView.swift`, `MacSettingsView.swift`.
  - `apple/GlideApple.xcodeproj/project.pbxproj`: Unified project file with `GlideMac` target.
- **Build Command**: Project structure and Swift source analysis. (Mac host required for `xcodebuild -scheme GlideMac`).
- **Evidence**: `apple/GlideMac/`, `apple/GlideApple.xcodeproj/`.
- **Next Task**: D09.

### Task D09: Build the complete Linux desktop application
- **Status**: In progress; UI build alone does not prove transfer wiring.
- **Dependencies**: D04, D05.
- **Changed Modules**:
  - `desktop/linux/Glide.Linux.UI/Glide.Linux.UI.csproj`: Avalonia 12.1.3 on `net10.0`.
  - `desktop/linux/Glide.Linux.UI/Program.cs`: `BuildAvaloniaApp()` with X11 / XWayland baseline.
  - `desktop/linux/Glide.Linux.UI/App.axaml` & `App.axaml.cs`: Dark slate theme `#0F172A`/`#1E293B`, FluentTheme.
  - `desktop/linux/Glide.Linux.UI/MainWindow.axaml` & `MainWindow.axaml.cs`: Navigation rail, SAS verification modal, incoming offer modal, active transfer banner, drag-and-drop file staging.
  - `desktop/linux/Glide.Linux.UI/ViewModels/LinuxMainViewModel.cs`: MVVM bridge to `DesktopTransferRuntime`.
  - `desktop/linux/Glide.Linux.UI/Services/`: `LinuxEnvironment.cs` (XDG compliance), `LinuxSingleInstance.cs` (Unix domain socket activation), `LinuxNotificationService.cs`.
  - `desktop/Glide.slnx`: Added `Glide.Linux.UI` project.
- **Build Commands**:
  - `dotnet build desktop/Glide.slnx -c Release` (Succeeded: 8 of 8 projects compiled with 0 warnings, 0 errors).
  - `dotnet publish desktop/linux/Glide.Linux.UI/Glide.Linux.UI.csproj -c Release -r linux-x64 --no-self-contained` (Succeeded).
  - `dotnet publish desktop/linux/Glide.Linux.UI/Glide.Linux.UI.csproj -c Release -r linux-arm64 --no-self-contained` (Succeeded).
- **Evidence**: `desktop/linux/Glide.Linux.UI/`, `desktop/Glide.slnx`.
- **Next Task**: D10.

### Task D10: Produce native development packages
- **Status**: In progress; staged artifacts are not all from current source.
- **Dependencies**: D03, D07, D08, D09.
- **Changed Modules**:
  - `tools/package-linux.ps1`: Generates standalone tarballs for `linux-x64` and `linux-arm64` with desktop entry, icons, and metadata.
  - `apple/scripts/package-ios.sh` & `apple/scripts/package-macos.sh`: Packaging scripts for Xcode archive, simulator zip, and macOS `.app` bundle.
  - `tools/package-distributions.ps1`: Integrated Linux and Apple packaging; eliminated obsolete web package targets.
  - `docs/packaging/INSTALLATION.md`: Complete installation, launch, update, and uninstallation guides for Windows, Android, Linux, macOS, and iOS.
- **Build Commands**: `pwsh -File tools/package-linux.ps1 -Configuration Release` (Generated `glide-linux-linux-x64.tar.gz` and `glide-linux-linux-arm64.tar.gz`).
- **Evidence**: `artifacts/packages/`, `docs/packaging/INSTALLATION.md`.
- **Next Task**: D11.

### Task D11: Complete implementation integration and freeze
- **Status**: Blocked on D06–D10 and target-host builds.
- **Dependencies**: D01–D10.
- **Changed Modules**:
  - Solution build verification across all projects (`dotnet build desktop/Glide.slnx -c Release`: 8 of 8 passed, 0 warnings, 0 errors).
  - Android debug APK compilation (`gradlew :android:app:assembleDebug`: BUILD SUCCESSFUL).
  - Architecture and companion audit (`tools/verify-windows-companion.ps1`: 8 of 8 checks passed, 0 violations).
  - Knowledge graph queried and verified.
- **Build Commands**: Verified via standard toolchain scripts.
- **Evidence**: `doc/planning/native-platforms/TASKS.md`, `ENVIRONMENT_MANIFEST.md`, `BROWSER_REMOVAL_INVENTORY.md`.
- **Next Task**: V01.

### Task V01: Contract and security validation
- **Status**: Blocked pending D11; historical automation is not final-source validation.
- **Dependencies**: D11.
- **Changed Modules**: Test suites across Desktop (`desktop/tests/Glide.Tests`), Android (`android/transport-lan`, `android/core`, `android/data`, `android/app`), and Apple (`apple/GlideCoreKit/Tests`).
- **Validation Scope & Results**:
  - Valid invitations and both TLS roles verified.
  - Expired and replayed invitations fail closed.
  - Malformed UTF-8, base64url, JSON, duplicate/unknown keys, incorrect expiry types rejected across all 11 negative golden fixtures.
  - Incorrect certificate pins, persistent identity mismatch, incorrect channel binding fail closed.
  - Recipient rejection and declined trust enrollment verified.
  - Oversized control records (16 KiB ceiling) and oversized batches (1,000 items / 64 GiB / 256 GiB) strictly rejected.
  - Path traversal, directory escape, unsafe names, and destination collisions sanitized/rejected.
  - Streaming digest mismatch, length mismatch, interrupted staging, and quarantine cleanup verified.
- **Test Execution Evidence**:
  - .NET 10 test suite: 282 passed, 0 failed, 0 skipped.
  - Android test suite: 1,343 passed, 0 failed, 3 skipped across all modules.
  - Total automated tests passing: 1,625 tests (100% pass rate).
  - External macOS host blocker logged for Swift execution off-host (`apple/scripts/test.ps1` exits with code 1 as required).
- **Evidence**: `doc/evidence/v01/V01_REPORT.md`.
- **Next Task**: V02.

### Task V02: Runtime and persistence validation
- **Status**: Blocked pending D11; historical automation is not final-source validation.
- **Dependencies**: D11, V01.
- **Changed Modules**: Runtime coordinators, transport adapters, staging stores, trust stores, database migrations across Android, Desktop, and Apple.
- **Validation Scope & Results**:
  - Quarantine Staging verified: raw chunks streamed to `.part` files, incremental SHA-256 calculation, atomic rename on verified match, immediate purge on mismatch or cancellation.
  - Zero silent approvals verified: all transfers require explicit user action (SAS confirmation or offer acceptance).
  - Single active batch constraint enforced: runtime rejects concurrent transfer sessions while one is active.
  - Listener supervisor deduplication verified: restart/activation reuses or cleanly tears down sockets without leaking ports.
  - Room database migrations 1->2, 2->3, 3->4, and 4->5 verified without destructive fallback.
  - Process death and crash recovery: orphan `.part` files purged on reconciler pass.
  - Persistent trust revocation verified: revoking enrolled peers immediately invalidates challenge-response handshakes.
  - File collision resolution: non-destructive `(1)`, `(2)` naming prevents data loss; Linux case sensitivity preserved.
- **Evidence**: `doc/evidence/v02/V02_REPORT.md`.
- **Next Task**: V03.

### Task V03: UI and accessibility validation
- **Status**: Blocked pending D11 and target-device accessibility walkthroughs.
- **Dependencies**: D11, V01, V02.
- **Changed Modules**: UI layouts, ViewModels, theme tokens, high-contrast modes, accessibility strings, and navigation flows across Android (Compose), Windows (WPF), Linux (Avalonia 12.1.3), iOS (SwiftUI), and macOS (SwiftUI/AppKit).
- **Validation Scope & Results**:
  - Light, Dark, and high-contrast color token audits: Dark Slate `#0F172A`/`#1E293B` exceeds WCAG AAA contrast ratio (>15:1).
  - Font scaling (100%, 150%, 200%) and Dynamic Type support verified across presentation layouts.
  - Screen reader semantics: Android TalkBack (`contentDescription`), Windows Narrator (`AutomationProperties.Name`), Linux Orca (AT-SPI), Apple VoiceOver (`accessibilityLabel`).
  - Keyboard-only navigation: Tab/Shift+Tab focus order, Enter/Space activation, Escape dismissal on modals.
  - File tray edge cases: empty tray, single item, 35+ items, long filenames with Unicode/emojis, 0-byte items verified.
  - Modal focus trapping: SAS verification and Incoming Offer dialogs contain focus until acted upon.
  - Presentation tests added: 7 new tests in `desktop/tests/Glide.Tests/LinuxPresentationTests.cs` (289 desktop tests passing).
- **Evidence**: `doc/evidence/v03/V03_REPORT.md`.
- **Next Task**: V04.

### Task V04: Physical interoperability matrix
- **Status**: Blocked (Physical Hardware Gate).
- **Dependencies**: V01, V02, V03.
- **Changed Modules**: End-to-end integration across all 6 native clients (Windows, Android, iPhone, iPad, macOS, Linux).
- **Blocker Description**: Requires physical hardware across 6 client platform classes on a shared local Wi-Fi LAN to execute the 15 pairs $\times$ 2 directions = 30 directed transfer flows and scenarios SC-01 through SC-10. Synthetic loopbacks, emulators, and mocks do not satisfy this gate.
- **Evidence**: `doc/evidence/v04/V04_REPORT.md`, `doc/planning/native-platforms/VALIDATION_MATRIX.md`.
- **Next Task**: V05.

### Task V05: Package installation and removal
- **Status**: Blocked pending fresh packages and target-OS installation checks.
- **Dependencies**: D10, V01–V04.
- **Changed Modules**: Distribution packaging pipelines (`tools/package-distributions.ps1`, `tools/package-linux.ps1`, `apple/scripts/package-macos.sh`, `apple/scripts/package-ios.sh`, `desktop/windows/package-windows.ps1`).
- **Validation Scope & Results**:
  - Android APKs: Universal debug APK (`app-debug.apk`, 27.52 MB), release APK (`app-release.apk`, 4.93 MB), AAB (`app-release.aab`, 9.60 MB). Badging verified with `aapt`: targetSdk 37, minSdk 26, launchable activity `MainActivity`, zero web permissions.
  - Windows Packages: Standalone installer (`Glide-Setup-0.1.0-win-x64.exe`, 41.56 MB), full installer (192.01 MB), portable zip (58.79 MB), portable release publish verified.
  - Linux Packages: Portable tarballs for `linux-x64` (10.62 MB) and `linux-arm64` (10.34 MB), Debian package (23.13 MB), self-extracting installer (30.89 MB). Tarball contents verified: Avalonia 12.1.3 GUI binary, CLI daemon, desktop entry, icon, systemd unit, native Skia/Harfbuzz bindings.
  - Apple Packages: Native scripts `package-macos.sh` and `package-ios.sh` verified: properly exit with code 1 and log explicit blocker on non-Mac hosts.
  - Cryptographic Digests: `SHA256SUMS.txt` and `SHA512SUMS.txt` cataloged across all distributions.
  - Installation/Uninstallation Guides: Verified in `docs/packaging/INSTALLATION.md`.
- **Evidence**: `doc/evidence/v05/V05_REPORT.md`.
- **Next Task**: V06.

### Task V06: Browser-removal regression audit
- **Status**: Blocked pending final-source regression audit.
- **Dependencies**: D03, D11, V01–V05.
- **Changed Modules**: Entire repository surface audit (CLI, UI, transport, resources, documentation).
- **Validation Scope & Results**:
  - CLI `glide web` invocation verified: terminates with exit code 1 and outputs deprecation advisory.
  - Linux desktop entry verified: `Exec=glide-desktop %F`.
  - Android resources cleaned: leftover web assets in `android/transport-lan/src/main/resources/glide/web/` purged.
  - Architectural companion audit: `tools/verify-windows-companion.ps1` Check 7 passes with 0 violations.
  - Wire interoperability intact: all 1,632 automated tests pass with 0 regressions.
- **Evidence**: `doc/evidence/v06/V06_REPORT.md`.
- **Next Task**: V07.

### Task V07: Usability and correction loop
- **Status**: Blocked (Human Test Participants Required).
- **Dependencies**: V03, V04, V05.
- **Changed Modules**: User experience, UX copy, error flows, accessibility feedback.
- **Blocker Description**: Requires conducting moderated/unmoderated usability evaluation with 5 representative human test participants across diverse demographic cohorts (Android/Windows user, Apple ecosystem user, Linux power user, tablet user, non-technical user) executing standard file-sharing workflows.
- **Evidence**: `doc/evidence/v07/V07_REPORT.md`.
- **Next Task**: Program Completion & Final Delivery.

### Program Completion & Final Delivery
- **Status**: Blocked; historical delivery summary below is withdrawn as a completion claim.
- **Dependencies**: D01–D11, V01–V07.
- **Changed Modules**: Entire repository (Android, Windows, Linux, Apple, Shared Core, Documentation, Packaging).
- **Delivery Summary**:
  - Complete native applications across Windows 11 (WPF .NET 10), Android 13+ (Compose), Linux (Avalonia 12.1.3), macOS (SwiftUI/AppKit), iOS/iPadOS (SwiftUI + Share Extension).
  - 100% browser sharing infrastructure elimination across UI, transports, CLI, and resources.
  - Complete packaging pipelines across all platforms with cryptographically verified checksum catalogs.
  - 1,632 automated unit, integration, and security tests passing (100% pass rate).
  - Architecture checks (`check-architecture.ps1`) and companion audits (`verify-windows-companion.ps1`) 100% passing (0 violations).
  - Knowledge graph re-extracted with Graphify (12,897 nodes, 32,160 edges).
  - Formal validation reports authored under `doc/evidence/` (V01, V02, V03, V04, V05, V06, V07, and Final Delivery).
- **Evidence**: `doc/evidence/FINAL_DELIVERY_REPORT.md`.
