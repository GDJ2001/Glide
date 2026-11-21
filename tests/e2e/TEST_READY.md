# Glide 1.0.0 — Opaque-Box E2E Test Readiness Report (`TEST_READY.md`)

- **Worktree / Branch:** `d:\GITHub\Glide-codex` (`codex/v1.0.0-release` @ `4a102faddd09ba851a319be1a8b522af9f9d581c`)
- **Suite Root:** `d:\GITHub\Glide-codex\tests\e2e\`
- **Evidence Root:** `d:\GITHub\Glide-codex\build\test-evidence\e2e\`
- **Total Declared E2E Tests:** **`145`** opaque-box tests (`Tier 1 = 60`, `Tier 2 = 60`, `Tier 3 = 16`, `Tier 4 = 9`) + **`6` Preflight Checks**
- **Opaque-Box & Security Audit Status:** **`0` `<ProjectReference>` dependencies**, **`0` internal `using Glide.*` product namespace imports**, **`0` leaked invitation secrets** across `summary.json`, `e2e.trx`, and `console.log`, **`0` mutations** to host `%LOCALAPPDATA%\Glide` or Windows CNG key `"Glide.Identity.v1"`, and primary checkout `d:\GITHub\Glide` (`26` paused Apple entries) untouched.

---

## 1. Architectural Guarantees & Opaque-Box Purity

1. **Zero Internal-API Coupling**:
   - `tests/e2e/Glide.E2E.Tests/Glide.E2E.Tests.csproj` (`net10.0`) references only `Microsoft.NET.Test.Sdk 17.14.1`, `xunit 2.9.3`, and `xunit.runner.visualstudio 3.1.4` (`0` `<ProjectReference>` items; `0` managed product assembly loads).
   - All tests drive compiled external binaries (`Glide.App`, `Glide.BackendTestDriver`, `Glide.Windows.UI` / `Glide.exe`, `Glide.Linux.UI` / `glide-desktop`, and Android `dev.glide.app` / `dev.glide.app.debug` via `adb`) or independent raw TLS 1.3 (`ALPN: glide-lan/1`) + `LanRecord` + `GLD2` wire framing (`WireClientProbe.cs`, loading only native C `glide_spake2.dll` / `libglide_spake2.so` exports for SPAKE2 curves).
2. **Independent Cryptographic & Staging Verification**:
   - Every positive transfer test independently computes and compares byte lengths and full `SHA-256` digests of every source file against every destination file (`CorpusVerifier.AssertDestinationMatchesManifest`), and asserts zero leftover `.part` or `.quarantine` files in staging or destination directories (`E2ESandbox.AssertStagingClean`).
3. **Host Isolation (`%LOCALAPPDATA%\Glide` & Linux XDG Sandboxing)**:
   - Each test executes inside an isolated temporary root (`%TEMP%\glide-e2e\<runId>\<testId>\` on Windows or `/tmp/glide-e2e/<runId>/<testId>/` on Linux VM) with disjoint `src/`, `staging/`, `dest/`, `config/`, `data/`, `state/`, `cache/`, `runtime/` (`0700`), and `logs/` directories.
   - Windows `Loopback` tests never invoke `Glide.App identity rotate` or `test-trust`/`test-ownership` against the host `%LOCALAPPDATA%\Glide` store or Windows CNG key `"Glide.Identity.v1"`.
4. **5-Pattern Secret Redaction Parity**:
   - Identical 5-pattern secret redaction (`glide://pair/(v\d+)#[A-Za-z0-9_-]+`, JSON `"token":"..."`, escaped `\"token\":\"...\"`, XML `&quot;token&quot;:&quot;...&quot;`, and double-escaped XML `&amp;quot;token&amp;quot;:&amp;quot;...&amp;quot;`) is enforced across `E2ESandbox.RedactSecrets`, `run-e2e.ps1` (`Redact-Secrets`), and `Invoke-VmRemote.ps1` (`Redact-Secrets`), scrubbing `summary.json`, `e2e.trx`, and `console.log` before persistence.

---

## 2. The 6 Preflight Checks

Before executing any test suite, `tests/e2e/run-e2e.ps1` and the 4 platform drivers under `tests/e2e/drivers/` enforce **6 preflight gates**:

| # | Preflight Check | Owner Script / Component | What It Verifies & Fail-Closed Behavior |
|---|---|---|---|
| **PF-1** | **`.NET 10 SDK` Toolchain Resolution** | `run-e2e.ps1` (`Ensure-DotnetOnPath`) | Verifies `dotnet --list-sdks` includes `.NET 10.x` (`^10\.`), automatically prepending `$env:USERPROFILE\.dotnet` to `DOTNET_ROOT` and `PATH` when Machine `PATH` points to `.NET 8`. Throws immediately if `.NET 10 SDK` is absent. |
| **PF-2** | **Serialized Suite & Target Binary Build Gate** | `run-e2e.ps1` (`Invoke-WithBuildLock` + `Invoke-DotnetWithRetry`) | Acquires named mutex `Local\GlideE2EBuildLock` (120s timeout), builds `Glide.E2E.Tests`, `Glide.BackendTestDriver`, and `Glide.App` with `--disable-build-servers -m:1` (retrying `MSB3026`/`MSB3027`), and publishes self-contained `linux-x64` binaries (`build/e2e-linux-bin/{app,driver,ui}`) when `LiveVm`/`All` has a reachable VM. |
| **PF-3** | **Linux Ubuntu 26 VM Reachability & Batch SSH Probe** | `drivers/Invoke-VmRemote.ps1 -Action Probe` | Probes TCP port `22` on `$VmHost` (`192.168.0.245`) and tests batch-mode OpenSSH (`-o BatchMode=yes -o ConnectTimeout=5`). In `LiveVm` mode, writes `summary.json` with `status = "BLOCKED_TARGET_UNAVAILABLE"` and exits `1` if unreachable; in `All` mode, records `SKIPPED_TARGET_UNAVAILABLE`. |
| **PF-4** | **Android Emulator / Device & Package Readiness Probe** | `drivers/Invoke-AndroidUi.ps1 -Action Probe` | Verifies `adb -s <serial>` (`emulator-5554`) device connection, `sys.boot_completed=1`, and installed package (`dev.glide.app` or `dev.glide.app.debug`), labeling `EMULATOR` vs physical target. In `LiveAndroid` mode, fails closed (`BLOCKED_TARGET_UNAVAILABLE`, exit `1`) if offline or uninstalled. |
| **PF-5** | **Windows WPF Single-Instance Mutex & AppData Backup Preflight** | `drivers/Invoke-WindowsUi.ps1 -Action PreflightCheck` & `StartSession` | Requires explicit `GLIDE_E2E_WINDOWS_UI_READY=1` opt-in, verifies `Glide.exe` binary existence, checks `Local\Glide.Windows.UI` single-instance mutex so an active user session is never killed, backs up `%LOCALAPPDATA%\Glide` before launching `Glide.exe`, and restores it on `StopSession`. |
| **PF-6** | **Distribution Package & Sandboxed Installer Preflight** | `drivers/Test-PackageLifecycle.ps1 -Action Probe` & `TargetEnvironment.cs` | Validates existence and non-zero size of target distribution packages (`.exe`, `.msi`, `.zip`, `.deb`, `.run`, `.apk`) and verifies sandboxed prefix/target readiness before running `UF12` install/upgrade/uninstall retention tests. |

---

## 3. Runner Modes & Filter Semantics (`tests/e2e/run-e2e.ps1`)

| Mode (`-Mode`) | Target Filter Applied | Live Target Behavior | Zero-Match / Failure Behavior |
|---|---|---|---|
| **`DryRun`** *(default)* | Optional `-Tier`, `-TestId`, `-Filter` | Discovers all matching xUnit test methods via `dotnet test --no-build --list-tests` and cross-checks all `145` `[Trait("Id", "E2E-T...")]` annotations in `Tier1..Tier4.cs` without starting transfers. | Unconditionally checks for `"No test matches the given testcase filter"` and fails closed with `status = "NO_TESTS_MATCHED"`, `exitCode = 1` when `discoveredTests.Count == 0`. |
| **`LocalLoopback`** | `Target=Loopback` (`79` tests) | Runs all 79 local loopback process/wire/CLI/staging/protocol tests in isolated `%TEMP%\glide-e2e\` sandboxes with ephemeral ports (`--port 0`). Marks `WindowsUi`, `LinuxVm`, `AndroidEmulator`, and `CrossPlatform` as `NOT_RUN`. | Fails closed (`NO_TESTS_MATCHED`, exit `1`) if filter matches 0 tests, or `FAILED` (exit `1`) on any failed/skipped test. |
| **`LiveVm`** | `Target=LinuxVm` (`20` tests) | Runs `PF-3` SSH/TCP:22 probe against `$VmHost` (`192.168.0.245`), publishes `linux-x64` binaries, and executes all 20 Linux CLI/daemon/systemd/Avalonia/installer tests on the VM (`label = "VM"`). | **Fails closed immediately** (`status = "BLOCKED_TARGET_UNAVAILABLE"`, exit `1`) if TCP:22 or SSH auth is unavailable. |
| **`LiveAndroid`** | `Target=AndroidEmulator` (`19` tests) | Runs `PF-4` `adb` probe against `$AndroidSerial` (`emulator-5554`) and executes all 19 Android UI Automator, Share ingress, `DebugLaunchHarness`, adaptive posture, foreground service, SAF, and APK upgrade tests (`label = "EMULATOR"`). | **Fails closed immediately** (`status = "BLOCKED_TARGET_UNAVAILABLE"`, exit `1`) if `adb` device or package is unavailable. |
| **`All`** | `Target=Loopback` + reachable `WindowsUi`, `LinuxVm`, `AndroidEmulator`, and matching `CrossPlatform` (`CrossTarget=Vm\|Android\|All`) | Runs `LocalLoopback` plus every reachable live target; dynamically enables `CrossPlatform` tests whose required peer(s) (`CrossTarget=Vm`, `CrossTarget=Android`, or `CrossTarget=All`) are online. | Reports unreachable live targets explicitly as `SKIPPED_TARGET_UNAVAILABLE` in `summary.json` without fabricating passes. |

---

## 4. Feature Coverage Matrix (`UF1`–`UF12` ↔ `PROJECT.md` `F1`–`F10`)

| Feature ID | User-Facing Feature Area | `PROJECT.md` / Requirement Mapping | Tier 1 (Happy Path) | Tier 2 (Boundary / Fault) | Tier 3 (Pairwise) | Tier 4 (Real-World) | Total Tests |
|---|---|---|---:|---:|---:|---:|---:|
| **`UF1`** | Send Workflow & File Staging | R6, AC; `F4`, `F5`, `F6`, `F7`, `F8` | `5` | `5` | `1` | `2` | **`13`** |
| **`UF2`** | Receive Workflow & Listener Lifecycle | R5, R6; `F2`, `F3`, `F4`, `F5`, `F6`, `F7` | `5` | `5` | `2` | `1` | **`13`** |
| **`UF3`** | Device Pairing (`Pair devices` / Profile 2 QR & PIN) | R5, R6, AC; `F3`, `F4`, `F5`, `F6` | `5` | `5` | `1` | `0` *(in T4-002/005/009)* | **`11`** |
| **`UF4`** | Quick Share QR & Link (`glide://pair/v1#`) | R5, R6, AC; `F3`, `F4`, `F5`, `F6` | `5` | `5` | `0` *(in T3-013/T4-001/006)* | `0` | **`10`** |
| **`UF5`** | Trusted Reconnect & Saved Devices (`TRUSTED_SEND`) | R5, R6, AC; `F3`, `F4`, `F5`, `F6`, `F7` | `5` | `5` | `3` | `1` | **`14`** |
| **`UF6`** | Explicit Consent & Trust/Identity Management | R5, AC; `F3`, `F4`, `F5`, `F6` | `5` | `5` | `2` | `0` *(in T4-001..007)* | **`12`** |
| **`UF7`** | Activity, Progress States & Verified History | R6, R7; `F4`, `F5`, `F6`, `F7`, `F8` | `5` | `5` | `1` | `0` *(in T4-001/004/007/008)* | **`11`** |
| **`UF8`** | Settings, Theme & Save Destination | R5, R6; `F4`, `F5`, `F6`, `F7` | `5` | `5` | `2` | `0` *(in T4-002/003/005/007)* | **`12`** |
| **`UF9`** | Headless CLI, Systemd Daemon & Isolation/Diagnostics | R3, R5; `F1`, `F3`, `F5` | `5` | `5` | `1` | `1` | **`12`** |
| **`UF10`** | Android Share Ingress, Background & Adaptive UI | R5, R6; `F6`, `F7` | `5` | `5` | `1` | `3` | **`14`** |
| **`UF11`** | Cancellation, Disconnect, Recovery & Staging Cleanup | R5, R7, AC; `F2`, `F3`, `F5`, `F6`, `F8` | `5` | `5` | `1` | `0` *(in T4-001/003/007/008)* | **`11`** |
| **`UF12`** | Packaging, Install, Upgrade, Uninstall & Data Retention | R1, R5, R8, AC; `F1`, `F5`, `F8`, `F10` | `5` | `5` | `1` | `1` | **`12`** |
| **Total** | **12 User-Facing Features (`100%` `F1`–`F10` Coverage)** | **All R1–R8 & Acceptance Criteria** | **`60`** | **`60`** | **`16`** | **`9`** | **`145`** |

### Target Distribution Across the 145 Tests

| Target Trait (`[Trait("Target", ...)]`) | Tier 1 | Tier 2 | Tier 3 | Tier 4 | Total | Execution Requirement |
|---|---:|---:|---:|---:|---:|---|
| **`Loopback`** | `35` | `39` | `4` | `1` | **`79`** | Runs locally on Windows/Linux host in isolated `%TEMP%\glide-e2e\` sandboxes |
| **`LinuxVm`** | `8` | `8` | `3` | `1` | **`20`** | Requires reachable Ubuntu 26 VM over SSH (`-Mode LiveVm` or `-Mode All`) |
| **`AndroidEmulator`** | `8` | `8` | `2` | `1` | **`19`** | Requires online `Pixel_10a` (`emulator-5554`) via `adb` (`-Mode LiveAndroid` or `-Mode All`) |
| **`WindowsUi`** | `8` | `3` | `4` | `0` | **`15`** | Requires Windows host + `GLIDE_E2E_WINDOWS_UI_READY=1` (`-Mode All`) |
| **`CrossPlatform`** (`CrossTarget=Vm`: `9`, `Android`: `1`, `All`: `2`) | `1` | `2` | `3` | `6` | **`12`** | Requires matching live peer(s) (`VM`, `Android`, or `VM + Android`) in `-Mode All` |
| **Total** | **`60`** | **`60`** | **`16`** | **`9`** | **`145`** | `100%` discovered in `DryRun`; `79/79` executed and passing in `LocalLoopback` |

---

## 5. Complete 145-Test Catalog (Tiers 1–4)

### 5.1 Tier 1 — Category-Partition Feature Coverage (`Tier1_FeatureCoverageTests.cs`, 60 Tests)

| Test ID | Feature | Target | xUnit Test Method |
|---|---|---|---|
| `E2E-T1-001` | `UF1` | `Loopback` | `E2E_T1_001_SingleSmallFile_LoopbackTransfer_MatchesSha256` |
| `E2E-T1-002` | `UF1` | `Loopback` | `E2E_T1_002_HostedQuickShareQr_SenderHostsListener_MatchesSha256` |
| `E2E-T1-003` | `UF1` | `Loopback` | `E2E_T1_003_NestedDirectoryTree_PreservesRelativePaths_MatchesSha256` |
| `E2E-T1-004` | `UF1` | `Loopback` | `E2E_T1_004_UnicodeAndSpacesFilenames_TransferAccurately` |
| `E2E-T1-005` | `UF1` | `WindowsUi` | `E2E_T1_005_WindowsWpfSendTab_QuickQrAndTrustedMethods_Smoke` |
| `E2E-T1-006` | `UF2` | `Loopback` | `E2E_T1_006_EphemeralPortListener_EmitsValidV1Invitation` |
| `E2E-T1-007` | `UF2` | `Loopback` | `E2E_T1_007_OpaqueWireClient_CompletesFullProfile1TransferToListener` |
| `E2E-T1-008` | `UF2` | `Loopback` | `E2E_T1_008_QuarantineStagingCleanup_LeavesZeroPartOrQuarantineFiles` |
| `E2E-T1-009` | `UF2` | `LinuxVm` | `E2E_T1_009_LinuxVmXdgIsolatedReceive_StartsListenerCleanly` |
| `E2E-T1-010` | `UF2` | `WindowsUi` | `E2E_T1_010_WindowsWpfReceiveControls_ToggleZoomCopyRefresh` |
| `E2E-T1-011` | `UF3` | `Loopback` | `E2E_T1_011_V2PairOnlyUri_GoldenFixtureAndRoundTrip_MatchContract` |
| `E2E-T1-012` | `UF3` | `Loopback` | `E2E_T1_012_Profile2TranscriptAndGld2Framing_MatchesGoldenVector` |
| `E2E-T1-013` | `UF3` | `Loopback` | `E2E_T1_013_IsolatedTrustAndIdentity_InitializeAndRotate_Succeeds` |
| `E2E-T1-014` | `UF3` | `WindowsUi` | `E2E_T1_014_WindowsWpfPairDevicesPage_PinAndV2Qr_Smoke` |
| `E2E-T1-015` | `UF3` | `LinuxVm` | `E2E_T1_015_LinuxVmPairDevices_EnrollsTrustStore` |
| `E2E-T1-016` | `UF4` | `Loopback` | `E2E_T1_016_V1InvitationUri_MatchesGoldenFixtureAndRoundTrip` |
| `E2E-T1-017` | `UF4` | `Loopback` | `E2E_T1_017_V1UriViaStdinPipe_ConnectsWithoutLeakingSecretInLogs` |
| `E2E-T1-018` | `UF4` | `Loopback` | `E2E_T1_018_V1InvitationSpkiPin_AuthenticatesTls13Listener` |
| `E2E-T1-019` | `UF4` | `WindowsUi` | `E2E_T1_019_WindowsUriProtocolActivation_OpensPairModal` |
| `E2E-T1-020` | `UF4` | `AndroidEmulator` | `E2E_T1_020_AndroidPasteInvitationDialog_AcceptsV1Uri` |
| `E2E-T1-021` | `UF5` | `Loopback` | `E2E_T1_021_Gld2SessionDispatchSchema_ValidatesTrustedSendAndPairOnlyPurposes` |
| `E2E-T1-022` | `UF5` | `Loopback` | `E2E_T1_022_MdnsDiscoveryPrimitives_ValidateNamePresenceAndMulticastBind` |
| `E2E-T1-023` | `UF5` | `Loopback` | `E2E_T1_023_IsolatedTrustStores_MutualEnrollmentAndTransfer_Succeeds` |
| `E2E-T1-024` | `UF5` | `WindowsUi` | `E2E_T1_024_WindowsWpfPairedDevicePicker_SkipsQrScan` |
| `E2E-T1-025` | `UF5` | `CrossPlatform [Vm]` | `E2E_T1_025_WinToUbuntuVm_TrustedSendWithoutRepeatQr_Succeeds` |
| `E2E-T1-026` | `UF6` | `Loopback` | `E2E_T1_026_ExplicitOfferConsent_AcceptCommitsVerifiedFiles` |
| `E2E-T1-027` | `UF6` | `Loopback` | `E2E_T1_027_ExplicitRecipientConfirmation_HostedSenderAcceptCommitsFiles` |
| `E2E-T1-028` | `UF6` | `Loopback` | `E2E_T1_028_ReceiverNeverAutoAccepts_DestinationEmptyUntilOfferDecision` |
| `E2E-T1-029` | `UF6` | `LinuxVm` | `E2E_T1_029_LinuxVmXdgIsolatedIdentityAndTrustCli_ShowListRevoke` |
| `E2E-T1-030` | `UF6` | `WindowsUi` | `E2E_T1_030_WindowsWpfConsentAndEnrollmentModals_ShowFullFingerprint` |
| `E2E-T1-031` | `UF7` | `Loopback` | `E2E_T1_031_ResultMetrics_ReportExactVerifiedCountAndBytes` |
| `E2E-T1-032` | `UF7` | `Loopback` | `E2E_T1_032_R3DiagnosticsEnvOverride_LogsWithoutInvitationSecrets` |
| `E2E-T1-033` | `UF7` | `Loopback` | `E2E_T1_033_MultiPageOfferBatch_36Items_TransfersAndVerifiesAll` |
| `E2E-T1-034` | `UF7` | `WindowsUi` | `E2E_T1_034_WindowsWpfActivityStatesAndSearchableHistory_Smoke` |
| `E2E-T1-035` | `UF7` | `AndroidEmulator` | `E2E_T1_035_AndroidDebugLaunchHarness_RendersTransferAndHistoryStates` |
| `E2E-T1-036` | `UF8` | `Loopback` | `E2E_T1_036_CustomDestinationDirectory_ReceivesExclusivelyIntoTargetFolder` |
| `E2E-T1-037` | `UF8` | `Loopback` | `E2E_T1_037_DisjointStagingAndDestination_CommitsCleanlyWithSha256Match` |
| `E2E-T1-038` | `UF8` | `WindowsUi` | `E2E_T1_038_WindowsSettingsThemeAndDestination_Smoke` |
| `E2E-T1-039` | `UF8` | `LinuxVm` | `E2E_T1_039_LinuxVmXdgPathsAndSettingsPersistence_VerifyLowercaseGlide` |
| `E2E-T1-040` | `UF8` | `AndroidEmulator` | `E2E_T1_040_AndroidSettingsScreen_ShowsSaveDestinationAndTheme` |
| `E2E-T1-041` | `UF9` | `Loopback` | `E2E_T1_041_GlideAppCliHelp_DocumentsAllSubcommandsAndExitsZero` |
| `E2E-T1-042` | `UF9` | `Loopback` | `E2E_T1_042_GlideAppWebCommand_RetiredGatewayFailsClosed` |
| `E2E-T1-043` | `UF9` | `Loopback` | `E2E_T1_043_ListenerOwnershipCheck_VerifiesSingleOwnerLease` |
| `E2E-T1-044` | `UF9` | `LinuxVm` | `E2E_T1_044_LinuxVmDaemonRuntimeFile_Writes0600UriAndStopsOnSigterm` |
| `E2E-T1-045` | `UF9` | `LinuxVm` | `E2E_T1_045_LinuxVmSystemdUnit_SmokeCheck` |
| `E2E-T1-046` | `UF10` | `AndroidEmulator` | `E2E_T1_046_AndroidActionSend_RoutesToSelection` |
| `E2E-T1-047` | `UF10` | `AndroidEmulator` | `E2E_T1_047_AndroidActionSendMultiple_StagesBatch` |
| `E2E-T1-048` | `UF10` | `AndroidEmulator` | `E2E_T1_048_AndroidAdaptiveLayout_BookPosture_RendersViaUiAutomator` |
| `E2E-T1-049` | `UF10` | `AndroidEmulator` | `E2E_T1_049_AndroidForegroundTransferService_ContinuesInBackground` |
| `E2E-T1-050` | `UF10` | `AndroidEmulator` | `E2E_T1_050_AndroidPairedSendCompletion_ReturnsToSelection` |
| `E2E-T1-051` | `UF11` | `Loopback` | `E2E_T1_051_MidTransferCancel_RetainsSourceLeavesDestAndStagingClean` |
| `E2E-T1-052` | `UF11` | `Loopback` | `E2E_T1_052_WireCancelControlFrame_TearsDownCleanly` |
| `E2E-T1-053` | `UF11` | `Loopback` | `E2E_T1_053_ReceiverAbruptKillMidStream_SenderFailsCleanlyWithoutPartialDest` |
| `E2E-T1-054` | `UF11` | `Loopback` | `E2E_T1_054_RetryAfterCancellation_CompletesWithExactSha256` |
| `E2E-T1-055` | `UF11` | `Loopback` | `E2E_T1_055_RogueReceiverSendsBusyError_SenderExitsCleanly` |
| `E2E-T1-056` | `UF12` | `Loopback` | `E2E_T1_056_PackageLifecycleDriver_DryRunValidatesInstallerScripts` |
| `E2E-T1-057` | `UF12` | `WindowsUi` | `E2E_T1_057_WindowsInstaller_InstallUpgradeUninstallRetention` |
| `E2E-T1-058` | `UF12` | `LinuxVm` | `E2E_T1_058_LinuxRunInstaller_SandboxedPrefixLifecycle` |
| `E2E-T1-059` | `UF12` | `LinuxVm` | `E2E_T1_059_LinuxDebPackage_InstallAndRetainUserData` |
| `E2E-T1-060` | `UF12` | `AndroidEmulator` | `E2E_T1_060_AndroidApk_InstallUpgradeAndRetainData` |

### 5.2 Tier 2 — Boundary Value Analysis & Adversarial Faults (`Tier2_BoundaryCornerTests.cs`, 60 Tests)

| Test ID | Feature | Target | xUnit Test Method |
|---|---|---|---|
| `E2E-T2-001` | `UF1` | `Loopback` | `E2E_T2_001_ZeroByteFile_TransfersWithExactEmptySha256` |
| `E2E-T2-002` | `UF1` | `Loopback` | `E2E_T2_002_EmptyBatchAndMissingSourceFile_RejectedCleanly` |
| `E2E-T2-003` | `UF1` | `Loopback` | `E2E_T2_003_Max1000ItemsSucceeds_And1001ItemsRejected` |
| `E2E-T2-004` | `UF1` | `Loopback` | `E2E_T2_004_ProtocolSizeCeilings_Over256GiBAndOver64GiBAnd33ItemsPerPage_RejectedOnWire` |
| `E2E-T2-005` | `UF1` | `CrossPlatform [Vm]` | `E2E_T2_005_Over4GiB_SustainedIncompressibleTransfer_NoSlowdownAndSha256Match` |
| `E2E-T2-006` | `UF2` | `Loopback` | `E2E_T2_006_PathTraversalAttacks_DotsSlashWinUncDrive_AllRejected` |
| `E2E-T2-007` | `UF2` | `Loopback` | `E2E_T2_007_DosReservedNamesAndTrailingDotSpace_Rejected` |
| `E2E-T2-008` | `UF2` | `Loopback` | `E2E_T2_008_FilenameCollision_SuffixesWithoutOverwritingExistingFiles` |
| `E2E-T2-009` | `UF2` | `Loopback` | `E2E_T2_009_ReparsePointJunctionInDestination_FailsClosedWithoutEscapingSandbox` |
| `E2E-T2-010` | `UF2` | `Loopback` | `E2E_T2_010_Utf8BomAndZeroByteResumeMetadata_RejectedOnWire` |
| `E2E-T2-011` | `UF3` | `Loopback` | `E2E_T2_011_V2UriTtlAndFixtureInvalidCases_AllRejected` |
| `E2E-T2-012` | `UF3` | `Loopback` | `E2E_T2_012_V2UriPassedToV1SendCommand_ReturnsUpdateGlideMessage` |
| `E2E-T2-013` | `UF3` | `Loopback` | `E2E_T2_013_Profile2FixtureRejectedPurposes_ValidateProtocolOrderRules` |
| `E2E-T2-014` | `UF3` | `LinuxVm` | `E2E_T2_014_LinuxVmProfile2PinLockout_ExpiresOn3rdAttempt` |
| `E2E-T2-015` | `UF3` | `WindowsUi` | `E2E_T2_015_WindowsLeavingPairDevicesPage_CancelsReadyWindow` |
| `E2E-T2-016` | `UF4` | `Loopback` | `E2E_T2_016_MalformedV1Uris_PaddingAndOversizeAndUnknownPrefix_RejectedByCli` |
| `E2E-T2-017` | `UF4` | `Loopback` | `E2E_T2_017_ExpiredV1Invitation_RejectedByCliSend` |
| `E2E-T2-018` | `UF4` | `Loopback` | `E2E_T2_018_OneUseInvitationCannotBeReused_AndWrongBootstrapRoleRejected` |
| `E2E-T2-019` | `UF4` | `Loopback` | `E2E_T2_019_WrongBootstrapTokenAndAlteredInvitationHash_Rejected` |
| `E2E-T2-020` | `UF4` | `Loopback` | `E2E_T2_020_LanEndpointInvalidLiterals_LeadingZeroZoneIdHostname_Rejected` |
| `E2E-T2-021` | `UF5` | `Loopback` | `E2E_T2_021_TlsSpkiPinMismatch_FailsClosedOnClientAndServer` |
| `E2E-T2-022` | `UF5` | `Loopback` | `E2E_T2_022_CapabilityDowngrade_MissingStreamOrIdentityV1_Rejected` |
| `E2E-T2-023` | `UF5` | `Loopback` | `E2E_T2_023_IdentitySubstitutionAndExpectedPeerMismatch_FailsClosed` |
| `E2E-T2-024` | `UF5` | `Loopback` | `E2E_T2_024_DuplicateDisplayNamesAndForbiddenMdnsKeys_SchemaValidation` |
| `E2E-T2-025` | `UF5` | `CrossPlatform [Vm]` | `E2E_T2_025_NetworkInterfaceChange_RevokesStaleInvitationsAndRecovers` |
| `E2E-T2-026` | `UF6` | `Loopback` | `E2E_T2_026_ReceiverDeclinesOffer_CommitsZeroBytesAndCleansStaging` |
| `E2E-T2-027` | `UF6` | `Loopback` | `E2E_T2_027_HostedSenderDeclinesRecipient_AbortsCleanly` |
| `E2E-T2-028` | `UF6` | `Loopback` | `E2E_T2_028_RogueReceiverRejectsOfferWithUserRejected_SenderExitsCleanly` |
| `E2E-T2-029` | `UF6` | `Loopback` | `E2E_T2_029_ExpectedPeerMismatchAfterTrustRotation_RejectsConnection` |
| `E2E-T2-030` | `UF6` | `LinuxVm` | `E2E_T2_030_LinuxVmDaemon_RejectsPairOnlyAndUnenrolledPeer` |
| `E2E-T2-031` | `UF7` | `Loopback` | `E2E_T2_031_BadSha256Digest_TriggersIntegrityMismatchAndZeroCommittedBytes` |
| `E2E-T2-032` | `UF7` | `Loopback` | `E2E_T2_032_TruncatedAndExcessDataStreams_BothRejectedCleanly` |
| `E2E-T2-033` | `UF7` | `Loopback` | `E2E_T2_033_WrongItemIdAndInvalidControlSequence_Rejected` |
| `E2E-T2-034` | `UF7` | `Loopback` | `E2E_T2_034_LostFinalAcknowledgement_DropsAfterItemEnd_SenderReportsFailureWithoutHanging` |
| `E2E-T2-035` | `UF7` | `Loopback` | `E2E_T2_035_CorruptDiagnosticJsonlPathAndLogRedaction_IsolatedToSandbox` |
| `E2E-T2-036` | `UF8` | `Loopback` | `E2E_T2_036_InsufficientStorageRejection_SurfacesCleanlyToSender` |
| `E2E-T2-037` | `UF8` | `Loopback` | `E2E_T2_037_UnavailableOrFileBlockedDestination_FailsClosed` |
| `E2E-T2-038` | `UF8` | `WindowsUi` | `E2E_T2_038_WindowsUnwritableSaveFolder_RejectedInSettings` |
| `E2E-T2-039` | `UF8` | `LinuxVm` | `E2E_T2_039_LinuxCorruptDesktopStateJson_ArchivedAndResetToDefaults` |
| `E2E-T2-040` | `UF8` | `AndroidEmulator` | `E2E_T2_040_AndroidErrorAndMissingFileStates_RenderActionableGuidance` |
| `E2E-T2-041` | `UF9` | `Loopback` | `E2E_T2_041_GlideAppCliInvalidArgsAndUnknownVerbs_ExitOneCleanly` |
| `E2E-T2-042` | `UF9` | `Loopback` | `E2E_T2_042_GlideAppDaemonMissingRequiredFlags_FailsBeforeTouchingStores` |
| `E2E-T2-043` | `UF9` | `LinuxVm` | `E2E_T2_043_LinuxPermissiveRuntimeDir0777_RejectedFailClosed` |
| `E2E-T2-044` | `UF9` | `LinuxVm` | `E2E_T2_044_LinuxSecondListenerProcess_BlockedByListenerLock` |
| `E2E-T2-045` | `UF9` | `LinuxVm` | `E2E_T2_045_LinuxIdentityRotateYes_RevokesAllPeers` |
| `E2E-T2-046` | `UF10` | `AndroidEmulator` | `E2E_T2_046_AndroidNonContentUriShare_ShowsInvalidShareDialog` |
| `E2E-T2-047` | `UF10` | `AndroidEmulator` | `E2E_T2_047_AndroidTextOnlyShare_ShowsUnsupportedShareDialog` |
| `E2E-T2-048` | `UF10` | `AndroidEmulator` | `E2E_T2_048_AndroidShareOver1000Items_ShowsBatchLimitExceeded` |
| `E2E-T2-049` | `UF10` | `AndroidEmulator` | `E2E_T2_049_AndroidShareWhileTransferActive_ShowsActiveSessionBusy` |
| `E2E-T2-050` | `UF10` | `AndroidEmulator` | `E2E_T2_050_AndroidCorruptQrInvitation_ShowsScanErrorDialog` |
| `E2E-T2-051` | `UF11` | `Loopback` | `E2E_T2_051_SourceMutatesMidSend_SenderAbortsAndZeroPartialCommitted` |
| `E2E-T2-052` | `UF11` | `Loopback` | `E2E_T2_052_StaleStagingFilesPresent_ValidTransferStillCommitsCleanly` |
| `E2E-T2-053` | `UF11` | `Loopback` | `E2E_T2_053_SilentWirePeer_ReceiverTimesOutWithinBoundedBudget` |
| `E2E-T2-054` | `UF11` | `Loopback` | `E2E_T2_054_OversizedAndCorruptControlFrames_RejectedBeforeAllocation` |
| `E2E-T2-055` | `UF11` | `Loopback` | `E2E_T2_055_UnknownLanRecordKind_RejectedAndClosesConnection` |
| `E2E-T2-056` | `UF12` | `Loopback` | `E2E_T2_056_PackageLifecycleDriver_FailsClosedOnMissingPackagePath` |
| `E2E-T2-057` | `UF12` | `WindowsUi` | `E2E_T2_057_WindowsSilentUninstall_PreservesAppDataUnlessRemoveFlagSet` |
| `E2E-T2-058` | `UF12` | `LinuxVm` | `E2E_T2_058_LinuxInstallerTamperCheck_RejectsCorruptedRunPayload` |
| `E2E-T2-059` | `UF12` | `LinuxVm` | `E2E_T2_059_LinuxDebUpgrade_PreservesDaemonEnvAndTrustStore` |
| `E2E-T2-060` | `UF12` | `AndroidEmulator` | `E2E_T2_060_AndroidApkUpgrade_PreservesRoomDbAndKeystoreIdentity` |

### 5.3 Tier 3 — Pairwise Cross-Feature Combinatorial Scenarios (`Tier3_CrossFeatureTests.cs`, 16 Tests)

| Test ID | Primary Feature | Target | xUnit Test Method |
|---|---|---|---|
| `E2E-T3-001` | `UF5` | `Loopback` | `E2E_T3_001_TrustedStore_PlusNestedFolderBatch_PlusFilenameCollision` |
| `E2E-T3-002` | `UF2` | `WindowsUi` | `E2E_T3_002_ReceiveToggleOff_PairDevicesStillActive_ReceiveToggleBackOn` |
| `E2E-T3-003` | `UF3` | `CrossPlatform [Vm]` | `E2E_T3_003_QrV2Pair_ImmediateTrustedSend_Restart_SecondTrustedSend` |
| `E2E-T3-004` | `UF11` | `Loopback` | `E2E_T3_004_CancelMidTransfer_RetainsSourceCleanStaging_AndRetryToCompletion` |
| `E2E-T3-005` | `UF5` | `Loopback` | `E2E_T3_005_DuplicatePeerNames_RevokeOne_TransferToRemainingPeer` |
| `E2E-T3-006` | `UF8` | `LinuxVm` | `E2E_T3_006_LinuxSettingsDestChange_PlusDaemon_PlusMaxOfferBytes` |
| `E2E-T3-007` | `UF10` | `AndroidEmulator` | `E2E_T3_007_AndroidShareMultiple_PairedSend_ReturnToSelection` |
| `E2E-T3-008` | `UF7` | `WindowsUi` | `E2E_T3_008_WindowsActivityNavigateAwayMidTransfer_BannerAndResultPreserved` |
| `E2E-T3-009` | `UF2` | `Loopback` | `E2E_T3_009_AtomicCommitAndCrossDirectoryVerifiedCopy_MatchesSha256` |
| `E2E-T3-010` | `UF5` | `CrossPlatform [Vm]` | `E2E_T3_010_StaleMdnsDiscovery_EndpointRefresh_VerifiedTransfer` |
| `E2E-T3-011` | `UF6` | `Loopback` | `E2E_T3_011_TrustMismatchRejectsTransfer_ThenReEnrollRecoverySucceeds` |
| `E2E-T3-012` | `UF6` | `LinuxVm` | `E2E_T3_012_LinuxIdentityRotateYes_CascadesRevocationAndBlocksDaemon` |
| `E2E-T3-013` | `UF1` | `WindowsUi` | `E2E_T3_013_WindowsSingleInstanceForwarding_WithBusyGuard` |
| `E2E-T3-014` | `UF9` | `LinuxVm` | `E2E_T3_014_LinuxXdgOverride_PlusSystemdDaemonEnv_PlusInvitationRotation` |
| `E2E-T3-015` | `UF8` | `AndroidEmulator` | `E2E_T3_015_AndroidCustomSafDestination_ExposesEffectiveSaveDestination` |
| `E2E-T3-016` | `UF12` | `CrossPlatform [Vm]` | `E2E_T3_016_PackageUpgrade100To100_PreservesIdentityTrustSettingsHistoryAndFiles` |

### 5.4 Tier 4 — Real-World Multi-Platform Workload & Lifecycle Scenarios (`Tier4_RealWorldScenarioTests.cs`, 9 Tests)

| Test ID | Primary Feature | Target | xUnit Test Method |
|---|---|---|---|
| `E2E-T4-001` | `UF1` | `Loopback` | `E2E_T4_001_MixedDeveloperProjectBundle_Nested0BSmallMultiPageUnicodeAndLargeIncompressible_MatchesSha256` |
| `E2E-T4-002` | `UF5` | `CrossPlatform [Vm]` | `E2E_T4_002_WindowsAndUbuntu26Vm_CrossOsTrustedPairAndSync` |
| `E2E-T4-003` | `UF9` | `CrossPlatform [Vm]` | `E2E_T4_003_Ubuntu26VmToWindows_HeadlessDaemonBackupWorkflow` |
| `E2E-T4-004` | `UF2` | `LinuxVm` | `E2E_T4_004_Ubuntu26Vm_LocalSystemdDaemonAndAvaloniaUiParity` |
| `E2E-T4-005` | `UF10` | `CrossPlatform [Android]` | `E2E_T4_005_WindowsAndPixel10aEmulator_PhotoShareAndTrustedReconnect` |
| `E2E-T4-006` | `UF10` | `CrossPlatform [All]` | `E2E_T4_006_Pixel10aEmulatorToUbuntu26Vm_ShareSheetBatchTransfer` |
| `E2E-T4-007` | `UF10` | `AndroidEmulator` | `E2E_T4_007_Pixel10aEmulator_AppLifecycleMinimizationAndCustomSafSave` |
| `E2E-T4-008` | `UF1` | `CrossPlatform [Vm]` | `E2E_T4_008_LargeIncompressibleOver4GiB_SustainedTransferAndUiNavigation` |
| `E2E-T4-009` | `UF12` | `CrossPlatform [All]` | `E2E_T4_009_FullCleanInstallPairTransferUpgradeRestartUninstallRetentionLifecycle` |

---

## 6. Recorded Verification Evidence (`build/test-evidence/e2e/`)

| Verification Check | Command Executed | Run ID / Evidence Path | Recorded Result |
|---|---|---|---|
| **Full Suite Discovery (`DryRun`)** | `pwsh -NoProfile -File tests/e2e/run-e2e.ps1 -Mode DryRun -NoBuild` | `build/test-evidence/e2e/20261120T165710Z-5b36dd/summary.json` | **`status = "DRY_RUN_OK"`**, `exitCode = 0`, `discoveredTestMethods = 145`, `declaredTraitIds = 145` |
| **Negative Filter Fail-Closed (`DryRun`)** | `pwsh -NoProfile -File tests/e2e/run-e2e.ps1 -Mode DryRun -TestId NONEXISTENT-ID -NoBuild` | `build/test-evidence/e2e/20261120T165715Z-f512b6/summary.json` | **`status = "NO_TESTS_MATCHED"`**, `exitCode = 1`, `discoveredTestMethods = 0`, `declaredTraitIds = 145` |
| **Unreachable Live Target Fail-Closed (`LiveVm`)** | `pwsh -NoProfile -File tests/e2e/run-e2e.ps1 -Mode LiveVm -VmHost 127.0.0.1 -NoBuild` | `build/test-evidence/e2e/20261120T163221Z-33dde1/summary.json` | **`status = "BLOCKED_TARGET_UNAVAILABLE"`**, `exit 1` (`TCP port 22 on 127.0.0.1 is closed or unreachable`) |
| **Full `LocalLoopback` Suite (`79` Tests)** | `pwsh -NoProfile -File tests/e2e/run-e2e.ps1 -Mode LocalLoopback -NoBuild` | `build/test-evidence/e2e/20261120T165734Z-6bfb89/{summary.json,e2e.trx,console.log}` | **`status = "PASSED"`**, `exitCode = 0`, **`total = 79, passed = 79, failed = 0, skipped = 0`** (`2.86 min`), `uriLeaks = 0`, `tokLeaks = 0`, `%LOCALAPPDATA%\Glide` diff = `0` |
| **Isolated `.NET 10` WPF UIA Driver Probe** | `drivers/Invoke-WindowsUi.ps1` (`GetComboSelection`, `RadioButton` `PostMessage(VK_SPACE)`, fail-closed `Win32Click`) | Verified in `test_writer_e2e_r4/handoff.md` | **`PASS`** (`ComboBox` `Dark -> Light`, `RadioButton` `PostMessageSpace(RadioButton)` -> `CommandFired:QuickQr`, `Win32Click` fail-closed `exit 1` when input injection is rejected) |
| **Live Target Suites (`WindowsUi=15`, `LinuxVm=20`, `AndroidEmulator=19`, `CrossPlatform=12`)** | `pwsh -NoProfile -File tests/e2e/run-e2e.ps1 -Mode LiveVm \| LiveAndroid \| All` | Ready & fail-closed (`66` live-target tests) | Verified for AST (`0` parse errors across all `5` PowerShell scripts), `0` stubs/swallowed errors, and line-by-line XAML/Avalonia/Compose contract alignment; scheduled for live Stage 5 (`M5`) execution across Windows, Ubuntu 26 VM, and `Pixel_10a` (`emulator-5554`). |
