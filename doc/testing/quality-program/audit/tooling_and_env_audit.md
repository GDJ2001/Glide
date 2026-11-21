# Glide Tooling, Build, Packaging, Graphify, Test Quality & Environment Baseline Audit

**Audit Date:** 2026-11-19 (UTC)  
**Repository:** `d:\GITHub\Glide` (READ-ONLY inspection)  
**HEAD Commit:** `4a102faddd09ba851a319be1a8b522af9f9d581c` (`main`, synchronized with `remotes/origin/main`)

---

## 1. Git, Apple Working-Tree & GitHub Actions State

### 1.1 Git Repository Baseline
- `git rev-parse HEAD`: `4a102faddd09ba851a319be1a8b522af9f9d581c`
- `git branch -a`: `* main`, `remotes/origin/main`
- Recent 5 commits (`git log -n 5 --oneline`):
  1. `4a102fa Keep Android paired sends on trusted reconnect route`
  2. `5a13177 Route Android saved-device sends through trusted LAN reconnect`
  3. `24bd93f Merge branch 'main' of https://github.com/GDJ2001/Glide`
  4. `ab2508b Add WiX MSI packaging and verification`
  5. `abde039 Parse Android compressed mDNS records for trusted reconnect`
- Total tracked files (`git ls-files`): **1,837**.

### 1.2 Dirty Working Tree (`git status --porcelain=v1 -uall`) — 26 Entries
Every dirty/untracked path in the working tree is Apple work or an Apple parity status note (**MUST preserve untouched when creating a `codex/` worktree**; e.g., copy or patch-exclude `apple/**` cleanly without touching `d:\GITHub\Glide`'s working tree):
- **20 Modified (`M`) under `apple/`**:
  - `apple/GlideCoreKit/Sources/GlideCoreKit/Lifecycle/IosLifecycleCoordinator.swift`
  - `apple/GlideCoreKit/Sources/GlideCoreKit/Security/PersistentTrustStore.swift`
  - `apple/GlideCoreKit/Sources/GlideCoreKit/Transport/GlideNetworkClient.swift`
  - `apple/GlideCoreKit/Sources/GlideCoreKit/Transport/GlideNetworkListener.swift`
  - `apple/GlideCoreKit/Sources/GlideCoreKit/Transport/GlideTransferEngine.swift`
  - `apple/GlideIOS/GlideIOSApp.swift`
  - `apple/GlideIOS/Info.plist`
  - `apple/GlideIOS/ViewModels/IosTransferViewModel.swift`
  - `apple/GlideIOS/Views/ActivityView.swift`
  - `apple/GlideIOS/Views/MainTabView.swift`
  - `apple/GlideIOS/Views/ReceiveView.swift`
  - `apple/GlideIOS/Views/SendView.swift`
  - `apple/GlideIOS/Views/SettingsView.swift`
  - `apple/GlideMac/ViewModels/MacTransferViewModel.swift`
  - `apple/GlideMac/Views/MacActivityView.swift`
  - `apple/GlideMac/Views/MacMainWindowView.swift`
  - `apple/GlideMac/Views/MacReceiveView.swift`
  - `apple/GlideMac/Views/MacSendView.swift`
  - `apple/GlideMac/Views/MacSettingsView.swift`
  - `apple/scripts/run-backend-tests.sh`
- **6 Untracked (`??`)**:
  - `apple/GlideCoreKit/Sources/GlideCoreKit/Models/TransferFeedback.swift`
  - `apple/GlideCoreKit/Sources/GlideCoreKit/Transport/GlidePeerBrowser.swift`
  - `apple/GlideCoreKit/Sources/GlideCoreKit/Transport/TrustedEndpointCandidate.swift`
  - `apple/GlideCoreKit/Tests/GlideCoreKitTests/TrustedEndpointResolverTests.swift`
  - `apple/scripts/create-source-handoff.py`
  - `doc/testing/APPLE_PARITY_SOURCE_STATUS_2026-11-17.md`

### 1.3 GitHub Actions Workflows (`.github/workflows/`)
GitHub Actions workflows are **NOT disabled in YAML**. Five active workflow files exist:
1. `.github/workflows/ci.yml` — triggers on `main` and `'hardening/**'`. **Broken/Obsolete Gradle task paths**: invokes `:android:app:assembleDebug` (line 57), `:android:app:compileDebugUnitTestKotlin :android:transport-lan:compileDebugUnitTestKotlin` (line 60), and `:android:app:lintDebug` (line 69) while `working-directory: android` (where `settings.gradle.kts` names projects `:app`, `:transport-lan`, etc.).
2. `.github/workflows/linux-development-build.yml` — triggers on `push` to `'codex/**'`.
3. `.github/workflows/apple-development-build.yml` — triggers on `push` to `'codex/**'`.
4. `.github/workflows/apple-installer-package.yml` — triggers on `push` to `'codex/**'`.
5. `.github/workflows/deploy-privacy-policy-pages.yml` — deploys `docs/` to GitHub Pages.

> **Worktree / Push Implication:** Pushing a `'codex/**'` branch to GitHub will automatically trigger `linux-development-build.yml`, `apple-development-build.yml`, and `apple-installer-package.yml` unless workflows are gated/path-filtered or repo-level Actions are disabled on GitHub.

---

## 2. Graphify Knowledge Graph Freshness & Coverage

- **Report metadata (`graphify-out/GRAPH_REPORT.md`)**: `Built from commit: 0cc53501` (`2026-10-01`), summarizing `13122 nodes · 32470 edges`.
- **Incremental drift**: `graphify-out/graph.json` contains `15758 nodes` and `graphify-out/manifest.json` tracks **1,576** keys (`.vscode/launch.json` entry includes `mtime`, `seen`, `ast_hash`, `semantic_hash`).
- **Missing tracked files (`git ls-files` vs `manifest.json`)**:
  - **266** tracked files are missing from `manifest.json`, of which **61 are non-Apple code/UI/build/config/packaging files**:
    - Android code/manifests/resources/TOML: `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/kotlin/dev/glide/app/LaunchOverrides.kt`, `android/app/src/main/kotlin/dev/glide/app/navigation/SentTransferNavigation.kt`, `android/app/src/main/kotlin/dev/glide/app/service/VerifiedReceivePublisher.kt`, `android/app/src/main/kotlin/dev/glide/app/util/ReceivedFileSaver.kt`, `android/app/src/{benchmark,debug,release}/kotlin/dev/glide/app/DebugLaunchHarness.kt`, `android/app/src/test/kotlin/dev/glide/app/service/VerifiedReceivePublisherTest.kt`, `android/{benchmark,data,designsystem,transport-lan,transport-nearby}/src/main/AndroidManifest.xml`, `android/gradle/libs.versions.toml`, `gradle/libs.versions.toml`, 10 XML resource files.
    - Desktop C#/Avalonia/Tests: `desktop/src/Glide.Core/Transport/Lan/TransferBufferPool.cs`, `desktop/src/Glide.Desktop/{DesktopTimeoutPolicy.cs,DesktopTransferIoOptions.cs,Diagnostics/DesktopTransferDiagnostics.cs,Services/TrustedEndpointResolver.cs}`, `desktop/windows/Glide.Windows.UI/Common/ProgressWidthConverter.cs`, **all 13 Avalonia `.axaml` UI files** under `desktop/linux/Glide.Linux.UI/`, `desktop/tests/Glide.Tests/{GbpsTransferPipelineAndStorageTests.cs,LinuxAuditRegressionTests.cs}`.
    - Packaging/Tooling/CI: `desktop/windows/installer/{Glide.nsi,Glide.wxs}`, `desktop/windows/{package-windows-msi.ps1,verify-windows-msi.ps1}`, `tools/{build-play-release.ps1,diagnose-desktop-transfer.ps1,generate-tablet-screenshots.ps1,run-physical-matrix.ps1,verify-windows-msi.ps1}`, `.github/workflows/deploy-privacy-policy-pages.yml`, `.github/ISSUE_TEMPLATE/*`, `doc/user-guide/{build_guides.py,package_guides.py}`.
- **Stale mtime keys**: **167** files in `manifest.json` have disk `mtime > manifest.mtime + 2s` (including core runtime/UI/transport files such as `TransferRuntime.kt`, `TransferService.kt`, `TransferSessionManager.kt`, `ProductionLanController.kt`, `LanTransferTransport.kt`, `DesktopTransferEngine.cs`, `DesktopTransferRuntime.cs`, `QuarantineStagingStore.cs`, `MainWindow.xaml.cs`, `LinuxRenderedUiTests.cs`).
- **Extra/deleted keys in `manifest.json`**: **5** keys no longer exist in tracked files (`doc/release/store_assets/screenshots/tablet/01_tablet_landscape_overview.png`, `02_tablet_receive_mode.png`, `tools/SetClip.java`, `tools/TestReflection.java`, `tools/copy_corpus.sh`).
- **Conclusion**: Graphify is substantially stale for recent commits and misses key performance/diagnostic/Avalonia XAML/MSI files; re-verify graph answers directly against source until rebuilt.

---

## 3. Tooling, Build, Packaging, Dependency & Benchmark Audit

### 3.1 Build & Dependency Configuration
- **Android (`android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle/libs.versions.toml`)**:
  - `rootProject.name = "Glide"`; modules `:app`, `:core`, `:data`, `:designsystem`, `:transport-lan`, `:transport-nearby`, `:benchmark`.
  - Active catalog (`android/gradle/libs.versions.toml`): `agp = "9.5.1"`, `kotlin = "2.4.21"`.
  - **Unused catalog entries** (`android/gradle/libs.versions.toml`): version `coil` (`"3.6.3"`) has no library entry or `.gradle.kts` reference; library `androidx-compose-material-adaptive` is declared in TOML with zero `.gradle.kts` references.
  - **Stale root Gradle duplicate (`gradle/`)**: `gradle/libs.versions.toml` (`agp = "9.4.1"`, `kotlin = "2.4.20"`), `gradle/gradle-daemon-jvm.properties`, and `gradle/wrapper/gradle-wrapper.properties` duplicate `android/gradle/` at outdated versions.
  - **Version defaults (`android/app/build.gradle.kts:19-26`)**: `customVersionCode` defaults to `2`, `customVersionName` defaults to `"1.0.0"`.
  - **Build types & exclusions**: `android/app/build.gradle.kts` adds `benchmark` build type and excludes `*Hard003*`; `android/data/build.gradle.kts` excludes `StorageStreamingBenchmarkTest` from normal test runs.
- **Desktop (`desktop/Glide.slnx`)**:
  - Solution contains 9 projects: `Glide.Core`, `Glide.Transport`, `Glide.Desktop`, `Glide.App` (`net10.0`), `Glide.Linux.UI` (`net10.0`, Avalonia `12.1.3`), `Glide.Windows.UI` (`net10.0-windows`, `NetSparkleUpdater.UI.WPF 3.1.0`), `Glide.Tests` (`net10.0`, xUnit `2.9.3`, `Avalonia.Headless 12.1.3`, `ZXing.Net 0.16.11`), `Glide.Windows.UI.Tests` (`net10.0-windows`), `Glide.BackendTestDriver` (`net10.0`).
  - **No `Directory.Build.props` exists** at repo root or `desktop/`.
  - All `.csproj` files use explicit package versions (`QRCoder 1.8.0` in `Glide.Core`, `NetSparkleUpdater.UI.WPF 3.1.0` in `Glide.Windows.UI`, `Avalonia* 12.1.3` in `Glide.Linux.UI`).

### 3.2 Packaging Pipelines
- **Windows (`desktop/windows/`)**:
  - `package-windows.ps1` + `installer/Glide.nsi` (NSIS per-user installer `.exe` + portable `.zip`).
  - `package-windows-msi.ps1` + `installer/Glide.wxs` + `verify-windows-msi.ps1` (WiX v5 per-machine `.msi`).
- **Linux (`desktop/linux/`)**:
  - `tools/package-linux.ps1` / `tools/package-linux-native.sh`: builds `.deb`, `.rpm`, `.tar.gz` (plus self-extracting `.run` via `desktop/linux/installer/make-installer.sh` + `installer-stub.sh`, systemd unit `glide.service`, desktop entry `glide.desktop`). Note `package-linux-native.sh:81` handles `libicu78` (Ubuntu 26.04+) vs `libicu74` (Ubuntu 24.04).
- **Android (`tools/build-play-release.ps1`, `tools/package-android.ps1`)**:
  - Signed Release AAB/APK + ProGuard `mapping.txt` + `SHA256SUMS.txt`.

### 3.3 Obsolete Gradle Paths, Broken Script Paths & Metadata Drift
| File & Line(s) | Defect | Impact |
|---|---|---|
| `.github/workflows/ci.yml:57,60,69` | Uses `:android:app:*` and `:android:transport-lan:*` inside `working-directory: android` | CI Gradle invocations fail with unknown project path |
| `tools/build-and-verify-rc.ps1:125-126` | Passes `:android:app:bundleRelease`, `:android:app:assembleRelease` from `WorkingDirectory = android` | Fails to resolve Gradle tasks |
| `tools/run-transport-benchmark.ps1:17` | Passes `:android:transport-nearby:testDebugUnitTest` from `android/` (lines 24-27 also fallback to obsolete `shared\transport-nearby`) | Transport benchmark runner fails |
| `tools/verify-local.ps1:37` | Runs `.\gradlew.bat :android:app:lintDebug` inside `android/` | Fails to run Android lint |
| `tools/install-slice-devices.ps1:59` | Warning prints obsolete `:android:app:assembleDebug` command | Misleading instructions |
| `tools/test.ps1:44` | When `-Filter` is set, hardcodes `:app:test${Configuration}UnitTest` | Cannot filter unit tests in `:core`, `:data`, `:transport-lan`, or `:transport-nearby` |
| `tools/check-architecture.ps1:228` | Checks `$projectRoot/build.gradle.kts` instead of `$projectRoot/android/build.gradle.kts` | Architecture script's root build-script check looks at missing root file |
| `tools/derive-android-resources.ps1:24-26,188` | References `$ProjectRoot\app\src\main\res` and `asset\masters` / `asset\platforms` | Actual paths are `android\app\src\main\res` and `assets\` |
| `tools/test-lifecycle-matrix.ps1:6`, `tools/capture-discovery-screenshots.ps1:8-9`, `tools/verify-folder-extension-spec.ps1:6`, `tools/verify-resume-spec.ps1:6` | Reference `docs/` (repo uses `doc/`) or hardcoded brain paths | Broken evidence/doc references |
| `tools/build-play-release.ps1:12 vs 23` | Docstring says `versionCode = 1`, param default is `2` | Docstring drift |
| `tools/package-android.ps1:69`, `tools/assemble-release-manifest.ps1:272,345-348,492,507,549` | Hardcodes `AGP 9.4.1`, `.NET 10.0.100`, `Ubuntu 24.04`, and embeds Apple artifacts | Stale manifest metadata (`android/gradle/libs.versions.toml` is `9.5.1`; WSL/CI toolchains differ) |
| `tools/verify-v1-closure.ps1:6,37` | Checks historical `0.1.0-alpha`, `versionCode 1` | Historical M12 V1 gate only (not 1.0.0) |

### 3.4 Benchmark & Diagnostic Telemetry Audit
- **Android Macrobenchmark `TransferDisplayBenchmark.kt` (`android/benchmark/src/main/kotlin/dev/glide/benchmark/TransferDisplayBenchmark.kt:47-53`)**:
  - Currently only starts the default launch activity, waits for `TARGET_PACKAGE`, calls `Thread.sleep(1000)`, and `device.waitForIdle()`. **It measures the idle Home screen for 1 second, NOT an active transfer progress display.**
- **Existing Desktop Benchmark Schema (`build/benchmarks/stage1-baseline.json` + `stage1-diagnostics.jsonl`)**:
  - Produced by `desktop/tests/Glide.BackendTestDriver/Program.cs` `benchmark` command (`[--size-mb N] [--mode legacy|current] [--path all|1|2|3|4] [--run both|cold|warm] [--stage NAME]`).
  - Stages: `1_RawTlsBaseline`, `2_GlideTlsAndFramingNoStorage`, `3_GlideCompleteBackend`, `4_GlideUiRuntimeCallbacks` (recorded at commit `7b877c600d8c`).
- **Desktop Telemetry Schema (`desktop/src/Glide.Desktop/Diagnostics/DesktopTransferDiagnostics.cs`)**:
  - `DesktopTransferDiagnosticSample` via `BoundedTransferDiagnosticSink`: emits JSONL at ~1 Hz + terminal events, bounded to 5 MiB x 4 rotating files, redacting URIs and tokens. `tools/diagnose-desktop-transfer.ps1` consumes this pipeline.
- **Gradle-Excluded Benchmarks**: `StorageStreamingBenchmarkTest` is excluded in `android/data/build.gradle.kts`; `*Hard003*` is excluded in `android/app/build.gradle.kts`.

### 3.5 Script Write-Safety Check (`tools/build.ps1`, `tools/test.ps1`, `tools/lint.ps1`)
- Confirmed by reading `tools/build.ps1` (lines 1-116), `tools/test.ps1` (lines 1-124), and `tools/lint.ps1` (lines 1-100):
  - All three scripts write build/test/lint outputs solely under gitignored paths (`android/*/build/`, `desktop/**/bin/`, `desktop/**/obj/`, `desktop/**/TestResults/`) and do **not** modify tracked files. (Note: `lint.ps1 -Fix` would run format fixes if invoked with `-Fix`, which was not invoked.)

---

## 4. Test Suite Quality Audit (235 Tracked Test & Benchmark Files)

Programmatic static scan + manual inspection across all **235** tracked test/benchmark files (48 desktop `.cs` + 187 Android `.kt`/`.java`):

1. **Always-Skipped Release Binary Assertions (`assumeTrue` against wrong root)**:
   - `android/app/src/test/kotlin/dev/glide/app/release/ClosedTestingComplianceTest.kt:79-88`
   - `android/app/src/test/kotlin/dev/glide/app/release/ProductionReleaseGateTest.kt:68-77`
   - `android/app/src/test/kotlin/dev/glide/app/release/ProductionRolloutComplianceTest.kt:66-75`
   - **Defect**: `resolveProjectRoot()` walks up to repo root (`d:\GITHub\Glide`, finding `doc/`), then checks `File(root, "app/build/outputs/...")` — **missing the `android/` prefix**. Consequently `artifacts.all { it.exists() }` always evaluates `false` and `assumeTrue(...)` silently skips binary verification! Furthermore, `ProductionReleaseGateTest.kt:59` and `ProductionRolloutComplianceTest.kt:55` assert historical `versionName "0.1.0-alpha"` / `versionCode 1`.
2. **Mocked / Bypassed Production Wiring in Acceptance Tests**:
   - `android/app/src/test/kotlin/dev/glide/app/acceptance/CrossOemAcceptanceAndSoakTest.kt:385-482,590-701`: `test3_bidirectionalTransferParity_roleReversal` inserts directly into in-memory DAOs; `test7`–`test10` manually call `SessionState.copy(...)` instead of driving `TransferCoordinator` and reducers.
   - *Contrast*: `Hard003ProductionWiringRegressionTest.kt` and `Hard003DesktopRegressionTests.cs` DO test production wiring (and `Hard003*` is excluded from default `:app` Gradle tests!).
3. **Timing-Sensitive & Pixel-Brittle WPF UI Mega-Test**:
   - `desktop/tests/Glide.Windows.UI.Tests/WindowsSendUiIntegrationTests.cs:21-472,499-519`: single ~450-line STA test containing 40+ geometry/timing assertions using `Thread.Sleep(20)` in `PumpDispatcherUntil` (line 517) and exact DIP pixel assertions (`ActualWidth == 64` at line 230, `ActualWidth == 212` at line 261, `Window.Width = 500/820/1040`, `ScrollableWidth <= 1` at line 255, `TranslatePoint` geometry checks).
4. **Swallowed Exceptions (`catch { }`) in Desktop Tests**:
   - **46** empty/comment-only catch blocks across the scan; most Android `catch (_: ...Exception) {}` are intentional negative-test assertions (e.g., `RemoteInvitationCodecTest.kt:94`, `ProtocolCodecTest.kt`), and many C# `catch { }` wrap temp-directory cleanup (`Directory.Delete`).
   - **Real risk**: `desktop/tests/Glide.Tests/LinuxAuditRegressionTests.cs:494` uses bare `catch { }` around background server task work (`unrelatedServerTask`), and `desktop/tests/Glide.BackendTestDriver/Program.cs:896,920,1458,1906` has bare `catch { }` blocks.
5. **Wall-Clock `Thread.sleep` Calls in Tests**:
   - Found 8 occurrences: `TransferDisplayBenchmark.kt:51` (`1000ms`), `BoundedSourceStagerTest.kt:87,92,135` (`5000ms`, `5000ms`, `20ms`), `LanNetworkChaosTest.kt:261` (`50ms`), `LanTransportPerformanceBenchmarkTest.kt:545` (`20ms`), `LinuxAuditRegressionTests.cs:987` (`Thread.Sleep(delay)`), `WindowsSendUiIntegrationTests.cs:517` (`20ms`).
6. **`@Ignore` / `[Fact(Skip=...)]`**: **0** occurrences found.

---

## 5. Non-Modifying Environment & Verification Resource Baseline

### 5.1 Non-Modifying Scripts Executed
| Check | Command | Exit | Result |
|---|---|---:|---|
| Environment Doctor | `pwsh -NoProfile -File tools/doctor.ps1` | `1` (FAIL) | **7/8 checks passed** (JDK 26.0.1 >= 21, Android SDK `compileSdk 37`, Gradle wrapper, Node `24.12.0`, `adb` & `aapt2 30.0.3`, Git, PowerShell `7.7.0`); **Check `[4/8]` FAILED: `.NET SDK 8.0.427 (< 10.0 required)`**. Note: `.NET 10.0.8` and `10.0.14` *shared runtimes* (`Microsoft.NETCore.App` / `Microsoft.WindowsDesktop.App` / Host `10.0.14`) are installed on Windows, **but no .NET 10 SDK is installed on Windows**. |
| Architecture Check | `pwsh -NoProfile -File tools/check-architecture.ps1 -FailFast` | `0` (PASS) | **All 7/7 checks passed (0 issues)**. Note: Check `[7/7]` or script line 228 checks `$projectRoot/build.gradle.kts` which does not exist at repo root. |
| Windows Companion | `tools/verify-windows-companion.ps1` | Not Run | Read lines 1-100 first: check `[2/8]` invokes `dotnet build desktop/Glide.slnx -c Release`, which requires `.NET 10 SDK` (missing on Windows host). |

### 5.2 Host Storage, Packaging, Network & Benchmark Tools
- **Disk Free Space (`Get-PSDrive C, D`)**:
  - `C:`: **89.05 GB free** (361.99 GB used)
  - `D:`: **83.05 GB free** (416.94 GB used)
- **Windows Packaging & Perf Tools**:
  - WiX CLI: `C:\Users\GDJ\.dotnet\tools\wix.exe` (present)
  - NSIS: `C:\Program Files (x86)\NSIS\makensis.exe` (`True` on disk, **not in system `PATH`**)
  - Built-in Windows: `WinSAT.exe`, `ssh.exe`, `scp.exe` (present)
  - Missing in `PATH`: `iperf3`, `fio`, `dotnet-counters` (note `adb` and `emulator` are also NOT in system `PATH`, only under `C:\Users\GDJ\AppData\Local\Android\Sdk\`).

### 5.3 Android Emulator / Physical Devices
- `C:\Users\GDJ\AppData\Local\Android\Sdk\platform-tools\adb.exe devices -l`: **0 attached devices** right now.
- `C:\Users\GDJ\AppData\Local\Android\Sdk\emulator\emulator.exe -list-avds`: **`Pixel_10a`** (1 AVD listed).
- `Pixel_10a` AVD config (`%USERPROFILE%\.android\avd\Pixel_10a.avd\config.ini`):
  - `image.sysdir.1=system-images\android-37.1\google_apis_playstore_ps16k\x86_64\` (API 37.1, 16 KB page-size Play Store image)
  - `abi.type=x86_64`, `hw.ramSize=2048`, `disk.dataPartition.size=10G`.

### 5.4 Ubuntu VM & WSL Targets (Linux Verification Access)
- **VirtualBox VM `"ubuntu"`** (`C:\Program Files\Oracle\VirtualBox\VBoxManage.exe`):
  - `VMState="running"`, `nic1="bridged"` (`bridgeadapter1="Intel(R) Ethernet Connection (13) I219-V"`, `macaddress1="080027585B45"`).
  - VirtualBox Shared Folder mapping: `Shared` -> `C:\Users\GDJ\Downloads\Shared` (already contains `glide_1.0.0_4a102fa_amd64.deb`, `62,930,528` bytes, `2026-11-15`).
  - Guest Additions: `GuestAdditionsRunLevel=1`, `GuestAdditionsVersion="6.0.0 r127566"`. `VBoxManage guestproperty enumerate ubuntu` does **not** report a current guest IPv4 property (older Guest Additions 6.0.0).
  - Historical ARP entry `192.168.0.245` (`08-00-27-58-5B-45`, matching `macaddress1`!) is currently `Stale` on `Ethernet`, and TCP probes to `192.168.0.245` ports `22`, `9000`, `42424` returned `TcpTestSucceeded=False` (VM either lacks active SSH/Glide listener on those ports or holds a different DHCP/paused IP). Because Shared Folder `C:\Users\GDJ\Downloads\Shared` is mounted directly on the host, `.deb`/test drop-off works via the Shared Folder even if SSH isn't exposed, though interactive login / DHCP IP refresh inside VirtualBox console may be needed for live VM testing.
- **WSL 2 Distros (`wsl -l -v`)** — all currently `Stopped`:
  - `Ubuntu-18.04` (default)
  - `Planora-Test-Ubuntu-24.04`
  - `Glide-Test-Ubuntu-24.04` (previously inspected with `.NET SDK 10.0.401` at `/usr/share/dotnet/sdk`)
  - `docker-desktop`

### 5.5 Existing Pre-Built Outputs on Disk (Gitignored)
- `android/app/build/outputs/apk/debug/app-debug.apk` (`29,626,293` bytes, `2026-11-15`)
- `android/app/build/outputs/apk/release/app-release-unsigned.apk` (`5,144,203` bytes, `2026-10-16`)
- `android/app/build/outputs/bundle/debug/app-debug.aab` (`25,166,039` bytes, `2026-11-14`)
- `build/distributions/android/`: `app-release.apk`, `app-release.aab`, `Glide-v1.0.0-android.{apk,aab}`, `SHA256SUMS.txt`
- `build/distributions/`: `Glide-1.0.0-primary-PC-handoff-*.zip`, `ubuntu26-repack.sh`
- `desktop/windows/Glide.Windows.UI/bin/Release/net10.0-windows/` & `desktop/linux/Glide.Linux.UI/bin/Release/net10.0/` & `desktop/src/Glide.App/bin/{Debug,Release}/net10.0/` (older outputs from Oct/Nov 2026).
