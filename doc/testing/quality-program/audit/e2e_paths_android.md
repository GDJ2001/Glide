# Glide Android (`android/`) — End-to-End Traces, Architecture Refactoring Map & R3/R6/R7 Audit

---

## 1. End-to-End Path Traces

### 1.1 Startup
1. OS starts `GlideApplication` (`@HiltAndroidApp`, `AndroidManifest.xml:74`).
2. `GlideApplication` initializes WorkManager periodic cleanup (`OrphanMaintenanceScheduler` -> `OrphanMaintenanceWorker` -> `OrphanMaintenanceCleaner`) and invokes `ProcessDeathReconciler` to mark previously active journal/session entries safely without crashing or wiping committed files.
3. OS launches `MainActivity` (`@AndroidEntryPoint`, `launchMode="singleTask"`, `configChanges=...`), reads `LaunchOverrides` (`DebugLaunchHarness`, no-op in `release`), inspects `Intent` via `ShareIngressHandler` (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`, `glide://pair/v1`), applies `GlideTheme`, and renders `GlideAdaptiveScaffold` + `GlideAppNavDisplay` (Navigation 3).
   - **Audit Finding**: Ensure `ProcessDeathReconciler` runs asynchronously off main thread; verify `ProcessDeathReconciler.kt:91` status alignment (`"Verified"` vs `"Completed"`).

### 1.2 Discovery
1. `DiscoveryScreen` collects `DiscoveryViewModel.uiState`.
2. `DiscoveryViewModel` delegates to `ProductionDiscoveryController` + `UnifiedDiscoveryRepository` + `TransportRouter`.
3. `ProductionDiscoveryController` starts dual discovery adapters when permitted (`PermissionPolicy` checks `NEARBY_WIFI_DEVICES`, `BLUETOOTH_*`, `ACCESS_FINE_LOCATION` on <=32, `ACCESS_LOCAL_NETWORK` on API 37+):
   - LAN: `NsdDiscoveryAdapter` (`NsdClientWrapper`, `_glide._tcp.local.`, `LanTxtCodec`) — **currently disabled on API 26–28** by `TransferModule.kt:109` (`SDK_INT >= Q`) and `RouteSelectorTest.kt:114-131`.
   - Nearby: `NearbyDiscoveryAdapter` (`NearbyClientWrapper` / GMS `ConnectionsClient`, `NearbyConfig`).
4. `UnifiedDiscoveryRepository` merges `PeerObservation` events into `PeerCandidate` rows, computes freshness/expiry, and matches trusted peer fingerprints (`RoomTrustedPeerStore`).
   - **Audit Finding**: Duplicate device names are not consistently disambiguated across `HomeScreen` / `DiscoveryScreen`, and `ProductionDiscoveryController` mixes discovery start/stop with receive availability lifecycle.

### 1.3 Pairing (QR Code & Manual Link / Pair-Only)
1. **Initiator / Receiver displays QR (`QrInvitationSheet` / `QrPairingViewModel`)**:
   - `ProductionLanController` calls `InvitationAuthority` + `LanLeaseIdentity` + `LanListener` to start an ephemeral TLS 1.3 listener and encode a one-time invitation (`InvitationCodec`, `glide://pair/v1`) via `QrEncoder` (`zxing-core`).
2. **Scanner / Client scans or pastes (`QrScannerScreen` / `PairOnlyViewModel` / `ShareIngressParser`)**:
   - CameraX `QrFrameAnalyzer` (running at `minIntervalMs = 100L` = 10 Hz in `QrScannerScreen.kt:371`) passes decoded text via `QrDecoder` -> `InvitationCodec`.
   - `ConnectionConfirmationDialog` (`QrScannerScreen.kt:613`) exposes raw `endpoint.address:endpoint.port` to end users.
   - `PairOnlyViewModel` (`PairOnlyViewModel.kt:41-44` blocks `SDK_INT < Q`; L53 uses `Build.MODEL` instead of `SettingsStore.deviceName`) invokes `Profile2PairingClient.runPairOnlyWithQrAsync`, which **tries only `invitation.endpoints.firstOrNull()`** (`Profile2PairingClient.kt:58`) over `PinnedTlsConnector` + `PinnedTrustManager`.
   - Both sides confirm the 6-digit SAS code (`PairingVerificationCard`) before `PersistentTrustManager` enrolls the peer into `RoomTrustedPeerStore` (`trusted_peers` table).
   - **Audit Findings**:
     - `GlideAppNavDisplay.kt:949` dismisses `PairOnlyUiState.Complete` without resetting `scannerState` out of `PairOnlyConnecting`.
     - `Profile2PairingClient` fails if `endpoints.firstOrNull()` is unreachable on a multi-homed peer.

### 1.4 Trusted Reconnect
1. Sender picks an already-trusted peer (`HomeScreen` `PairedDeviceCard` or `DiscoveryScreen`), intending to skip QR re-pairing (per R5/R6).
2. `TransferRuntime.startLanReceiver` (`L463-472`) resolves `expectedPeerFingerprintHex` ONLY when `Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q`, setting `null` on API 26–28!
3. Outbound/inbound TLS 1.3 handshake validates peer certificate via `PinnedTrustManager` (`checkValidity()` + SPKI SHA-256) and `TrustedSessionHandshake`.
   - **Audit Finding**: `ProductionLanController.kt:99,173-206` caches `LanLeaseIdentity` indefinitely across binds even though `LanLeaseIdentity.create` defaults `ttlMillis = 120_000L` (2 minutes). After 2 minutes idle, `PinnedTrustManager`'s `leaf.checkValidity()` rejects valid peers with certificate expiry failures!

### 1.5 QR / Link Sharing for Transfer
1. Receiver opens `ReceiveReadinessScreen` (`GlideRoute.Receive`), which passes `"Glide Device"` hardcoded from `GlideAppNavDisplay.kt:392-395,406-409,432-435,446-450,455-463` instead of `SettingsStore.deviceName`, and **double-dispatches** `StartLanReceiver` to both `TransferService.startLanReceiver` and `transferRuntime.dispatch(...)`.
2. Receiver displays QR/link via `QrInvitationSheet` (LAN transfer bootstrap); scanner scans via `QrScannerScreen(QrScanPurpose.RECEIVE_FROM_SENDER)` (`GlideAppNavDisplay.kt:838-847`), which navigates to `GlideRoute.Transfer("receive-lan-${invitation.invitationId}")` while `TransferRuntime` internally creates its session ID separately.
3. No browser/web fallback exists anywhere (verified compliant with retired browser sharing policy).

### 1.6 Explicit Consent
1. Sender dispatches `OFFER_BEGIN` / `OFFER_PAGE` / `OFFER_END` (`OfferPaginator` -> `OfferAssembler`, `<= 16 KiB` control frames, max 1,000 items, 64 GiB/file, 256 GiB/batch).
2. **Receiver side**: `TransferReducer` transitions to `SessionPhase.AwaitingConsent`; `TransferStatePresenter` bypasses progress throttling and immediately pushes `TransferUiState.AwaitingConsent`; `OfferConsentCard` renders file list, sizes, and `Accept` / `Decline` buttons (`TransferViewModel.acceptOffer` / `rejectOffer`). Never auto-accepts.
3. **Sender side bug**: `TransferUiState.kt:224-236` (`toUiState()`) maps `SessionPhase.Offering`/`AwaitingConsent` + `DeviceRole.Sender` into `TransferUiState.Transferring(0f)` instead of `AwaitingConsent`, making `SenderOfferWaitingCard` (`OfferConsentCard.kt:424`) dead/unreachable via `toUiState()`.

### 1.7 Transfer Execution & Throttling
1. `TransferSessionFactory` builds `TransferCoordinator` (`TransferSessionFactory.kt:146,260` force `enableTrueResumeCapability = false`) with `SourceAccess`, `ReceiveStore`, `HistoryStore`, and transport (`LanTransferTransport` or `NearbyTransferTransport`).
2. Outbound reads `AndroidSourceAccess` / `BoundedSourceStager`, streams chunks (`<= 64 KiB`) via `StreamHasher` + `LanRecordWriter` (or `NearbyPayloadBridge`) over TLS 1.3 / Nearby payload stream.
3. **Throttling & Allocation Bugs**:
   - `TransferCoordinator.kt:2680-2691` forwards raw unthrottled per-chunk `BulkProgress` events directly to `dispatch(StreamProgress)` while also running 10 Hz `StreamProgressSampler`.
   - `StreamHasher.copyAndHashWithChunks` allocates a fresh `ByteArray(chunkSize)` per chunk.
   - `TransferStatePresenter` applies time coalescing for UI `StateFlow`, while keeping consent, boundaries, errors, and terminal outcomes immediate.

### 1.8 Verification & Quarantine Staging (`AndroidReceiveStore` / `AndroidPrivateFileManager` / `ResumableStagingStore`)
1. Receiver streams payload into private staging `.part` inside `AndroidReceiveStore` (app-private `filesDir`, never user Downloads/SAF) using `FilenameSanitizer` / `SafeRelativePath`.
2. **I/O Bottleneck**: `AndroidPrivateFileManager` & `AndroidReceiveStore` write with unbuffered `FileOutputStream` and later re-hash `.part` with a raw 64 KiB `FileInputStream`.
3. **Evictable Resumable Staging Bug**: `DatabaseModule.kt:106-110` configures `ResumableStagingStore` under `context.cacheDir/staging/resumable-v1`, allowing Android storage cleaner to evict resumable `.part` state mid-transfer or across restarts.
4. Coordinator validates received byte length + SHA-256 against `ITEM_END`; only on exact match does `AndroidReceiveStore.commitVerified` execute atomic rename and record `Verified` in `receive_journal` + `received_files`.
   - **Reconciliation Bug**: `AndroidReceiveStore.kt:278-287,312-321` omit `relativePath = entry.relativePath` during crash recovery.

### 1.9 Verified Saving & SAF / Public Save Destination
1. While `TransferRuntime.observeCoordinator` keeps `_isSessionActive = true` (`L839`), `autoSaveVerifiedFiles` invokes `publishVerifiedReceive` (`VerifiedReceivePublisher.kt:10-38`).
2. `FileExporter` writes either:
   - To user-selected SAF tree URI (`DocumentFile`), OR
   - On API 29+, to MediaStore `Download/Glide` (on API 26–28 without SAF tree, save requires SAF folder selection).
3. **Bugs**:
   - `FileExporter.kt:401` checks free space on internal app storage instead of the SAF destination volume!
   - `TransferResultCard.kt:411` hardcodes `"Saved ${exportStatus.destinationName} to your selected folder"` even when saved to `Download/Glide`, and `L358,442,480` hide manual `Save` buttons whenever `exportStatus is ExportUiStatus.Success`.
   - `TransferViewModel.kt:340` (`exportAllVerifiedItems`) fails with `"No files found on device to export"` if all verified items are already in `runtime.autoSavedItemIds`.
   - `TransferUiState` / UI cards lack an explicit `Saving` phase/indicator distinct from SHA-256 `Verifying`.

### 1.10 History
1. `TransferCoordinator` triggers `RecordHistory` effect (`RoomHistoryStore` -> `SessionDao`, `ItemDao`, `ReceivedFileDao`).
2. **Bugs**:
   - `TransferCoordinator` accesses `sessionHistoryRecorded` outside `mutex`.
   - `RoomHistoryStore.kt:44,71-75,97-102` and `HistoryViewModel.kt:103,126` drop/hardcode `failureCode = null`, hiding diagnostic failure reasons in `HistoryDetailDialog`.
   - `HistoryDetailDialog.kt:277` hardcodes `"in selected folder"` and `L519` renders `errorContainer.copy(alpha = 0.4f)` behind `onErrorContainer`.

### 1.11 Settings
1. `SettingsScreen` -> `SettingsViewModel` <-> `DataStoreSettingsStore` (`glide_settings` preferences) + `RoomTrustedPeerStore` (`TrustedPeerDao`).
2. **Bugs**:
   - `SettingsScreen.kt:395-417` (`ReceiveSaveLocationSection`) renders outside a `Card` and displays `"Download/Glide"` on `SDK_INT < 29 && treeUri == null` right beside a red error warning forcing folder selection.
   - `GlideAppNavDisplay.kt:557` sets `onKeepReceivingInBackgroundChanged = null` whenever `customSettingsStore == null` (disabling the background toggle in production if wiring relies on that callback!).

### 1.12 Foreground Service & Background Operation
1. `TransferService` (`foregroundServiceType="connectedDevice"`, `exported="false"`) keeps transfers and optional background receive alive and posts `TransferNotificationManager` notifications with `sessionId`-validated `Accept`/`Reject`/`Cancel` `PendingIntent`s (`TransferServicePolicy.shouldAcceptSessionAction`).
2. **Bugs**:
   - `TransferService` is not `@AndroidEntryPoint`: `L210,284` call `DatabaseModule.provideSettingsStore(applicationContext)` (bypassing Hilt `@Singleton`, creating a duplicate DataStore instance risk).
   - `L322,334,345` mutate state via `setTransferStateForTesting(...)` in production service action paths!
   - `L683` `stopIdleLanReceiver` uses `startService` gated only on static mutable `isRunning`.

### 1.13 Shutdown & Cancellation
1. User taps Back (`TransferScreen.kt:85-97` `BackHandler`), notification `Cancel`, or TopAppBar Back (`TransferScreen.kt:132` — **currently bypasses cancel/reject guard!**).
2. `TransferCoordinator.cancelAndAwaitCleanup(reason)` triggers `CancelRequested`, awaits `awaitTerminalCleanup()`, closes transports (`LanTransferTransport` / `NearbyTransferTransport`), cleans unverified `.part` files, and invokes `onTerminalCallback` -> `TransferService.stopForeground` / `stopSelf()`.
   - **Bug**: Untracked/un-awaited child coroutines (`startSendingStream` L2216, `commitDestination` L2314, `StartNegotiation`, `acceptOffer`, `rejectOffer`, `cancel`) inside `TransferCoordinator` can outlive `awaitTerminalCleanup()`.

### 1.14 Room v5 & Upgrades (`GlideDatabase`)
1. `GlideDatabase` (`version = 5`, `exportSchema = true`) has 6 tables (`receive_journal`, `received_files`, `sessions`, `items`, `trusted_peers`, `resumable_sessions`) and explicit `MIGRATION_1_2`..`MIGRATION_4_5` without `fallbackToDestructiveMigration()`.
2. `MIGRATION_4_5` drops v4 `resumable_sessions` fail-closed so pre-HARD-022 unauthenticated resume rows cannot authorize resume offsets; Room schema JSONs `1.json`–`5.json` are tracked cleanly.
3. **Audit Gaps**: `DatabaseUpgradeRegressionTest` checks v3 rather than v5, `RoomMigrationTest` mocks `execSQL` rather than executing real Room `MigrationTestHelper` against `schemas/1.json..5.json`, and `ProductionHealthComplianceTest:113` asserts stale `"Version 1"` docs.

---

## 2. `:transfer-core` (`android/core/`) Purity Verification

Verified by inspecting `android/core/build.gradle.kts` and all 49 `.kt` files under `android/core/src/main/`:
- **Build plugins**: `kotlin.jvm` + `kotlin.serialization` only.
- **Dependencies**: `kotlinx-coroutines-core`, `kotlinx-serialization-json`, `junit` (test only).
- **Android/GMS/Compose/Room imports**: **ZERO** across all 49 main files and 42 test files.
- **Architectural note**: `RouteSelector` takes an abstract `apiLevel: Int` parameter (pure JVM Kotlin, zero `android.os.Build` import), preserving purity while expressing Android policy (`apiLevel < 29` currently blocks LAN — fix the integer threshold cleanly when lifting the API 26–28 restriction).
- **Test Enforcement Gap**: `ArchitectureRulesTest.kt:38-55,130,297-305` and `ForegroundMvpPrivacyAuditTest.kt:34-42` omit `:transport-lan` (`dev.glide.transport.lan` / `android/transport-lan/build.gradle.kts`) when checking module boundaries; fix those test scans during Phase 2/3.

---

## 3. R4 Refactoring Map (Android Coordinator & Runtime Split)

Requirement R4 mandates: *"Split Android's coordinator and runtime around session transitions, availability ownership, transfer execution, verified saving, and presentation; reuse existing session manager/factory components rather than introducing competing owners."*

### 3.1 Current Competing / Overlapping Ownership
- `TransferRuntime.kt` (**1,622 LOC**) currently handles transport lifecycle, discovery/LAN receiver triggers, `TransferCoordinator` launching & state collection, `autoSaveVerifiedFiles`, terminal cleanup, `TrustRuntimeState`, and test overrides (`setTransferStateForTesting`).
- `TransferSessionManager.kt` (**249 LOC**) already implements thread-safe `@Synchronized` single-session ownership, `ActiveSessionTracker` sync, `TransferStatePresenter` projection, `awaitTerminalCleanup()` barrier, and `onTerminalCallback` — **but is unused by `TransferRuntime`** (only covered by `ChaosGateAcceptanceTest` and `TransferSessionManagerTest`).

### 3.2 Target Clean Component Boundaries (Reusing Existing Types)

| Responsibility | Target / Reused Component | Exact Scope & Ownership Change |
|---|---|---|
| **Session Transitions & Single Owner** | **Reuse `TransferSessionManager`** + `:core` `TransferReducer` / `TransferCoordinator` | Wire `TransferRuntime` (injected/scoped, not split-brain) to delegate `startSession`, `acceptConsent`, `rejectConsent`, `cancelTransfer`, and `finalizeTerminalSession` strictly through `TransferSessionManager`. Retire `TransferRuntime`'s duplicate job tracking & `TransferService` `setTransferStateForTesting` calls. |
| **Session Construction** | **Reuse `TransferSessionFactory`** | Keep pure construction of `TransferCoordinator` wired to ports (`SourceAccess`, `ReceiveStore`, `HistoryStore`, `TransferTransport`, `ResumeSourceVerifier`, `ResumableStagingStore`). Fix `enableTrueResumeCapability` flag control once persistent `filesDir` resume staging is verified. |
| **Availability & Listener Ownership** | Extract focused `ReceiveAvailabilityController` from `ProductionDiscoveryController` + `ProductionLanController` | Separate idle mDNS/Nearby/LAN receive readiness & `LanLeaseIdentity` certificate rotation from active transfer execution; single-dispatch `StartLanReceiver` from one owner instead of double-dispatch in `GlideAppNavDisplay.kt:455-463`. |
| **Transfer Execution** | Scope `TransferCoordinator` child coroutines + extract transport/chunk stream helpers inside `:core` | Protect `sessionHistoryRecorded` under `mutex`; tie all spawned jobs (`startSendingStream`, `commitDestination`, etc.) into a supervised session `Job` awaited by `awaitTerminalCleanup()`; remove unthrottled per-64 KiB `BulkProgress` -> `StreamProgress` dispatch. |
| **Verified Saving** | **Reuse `VerifiedReceivePublisher`** (`publishVerifiedReceive`) | Remove inline `autoSaveVerifiedFiles` state-synchronization tangle from `TransferRuntime` (`L839`): emit explicit `Saving` UI state while `VerifiedReceivePublisher` runs, return structured destination metadata (`PublicDownloadGlide` vs `UserSafFolder(displayName)`), and resolve `autoSavedItemIds` vs manual export conflict cleanly. |
| **Presentation & Throttling** | **Reuse `TransferStatePresenter`** + fixed `SessionState.toUiState()` | Fix `Offering`/`AwaitingConsent` + `Sender` mapping in `TransferUiState.kt:224-236`; enforce unified `<= 5 Hz` (`>= 200ms`) progress coalescing across UI & `TransferNotificationManager` while delivering consent, verification, saving, errors, and terminal outcomes immediately. |

### 3.3 Safe Code Removal & Verification Plan Before Deleting Legacy Glue
- Before removing duplicate session/state helpers in `TransferRuntime` or dead `RecentActivityCard` in `HomeScreen.kt:393-462`, check `graphify query`, Kotlin/Compose call sites, Hilt DI modules (`DatabaseModule`, `TransferModule`), `baseline-prof.txt`, `proguard-rules.pro`, and `TransferRuntimeTest`/`Hard003`/`ChaosGateAcceptanceTest` so behavior is preserved and `ChaosGateAcceptanceTest` protects real production wiring.

---

## 4. R6 Flow & Accessibility Audit

| R6 Criterion | Current Observed State (Verified Files & Lines) | Gap / Defect |
|---|---|---|
| **Completed sending returns to previous file-selection workflow** | `SentTransferNavigation.kt` (`returnToFileSelectionAfterSend`) called from `GlideAppNavDisplay.kt:617-628` on `Completed && Sender`. | Verify it tracks handled `sessionId` once so manual back/history navigation isn't hijacked; ensure failed sends keep `SelectedTransferStore` intact. |
| **Paired sends do not require repeat QR scan** | `DiscoveryViewModel` / `HomeScreen` `PairedDeviceCard` initiate paired transfer directly; however `TransferRuntime.kt:463-472` nulls `expectedPeerFingerprintHex` on `SDK_INT < 29` and `ProductionLanController` caches expired 2-min `LanLeaseIdentity` certs. | Lift API 29 gate for API 26–28 trusted LAN and rotate `LanLeaseIdentity` cert before expiry so trusted reconnect works reliably without re-scanning. |
| **Open Activity/progress once at transfer transition, then respect manual navigation; terminal results persistent** | `GlideAppNavDisplay.kt:838-847` pushes `GlideRoute.Transfer("receive-lan-${invitationId}")` on QR scan separately from session ID tracking; `PairOnlyUiState.Complete` dismiss leaves `scannerState` as `PairOnlyConnecting` (`L949`). | Track `autoOpenedSessionIds` so `Transfer` opens once per session start and never snaps back if user navigates away; unify session ID across QR receive & runtime. |
| **Distinct connecting, approval, transferring, verifying, and saving states** | `TransferUiState.kt:224-236` maps `Offering`/`AwaitingConsent` + `Sender` to `Transferring(0f)` (hiding `SenderOfferWaitingCard`); no explicit `Saving` state exists in `TransferUiState` during `autoSaveVerifiedFiles` (`TransferRuntime.kt:839`). | Restore distinct Sender `AwaitingConsent`/waiting state, add explicit `Saving` phase between SHA-256 `Verifying` and `Completed`, and ensure `TransferScreen` Back icon (`L132`) invokes `BackHandler` guard (`L85-97`). |
| **Keep destination configuration in Settings; expose effective Android save destination & successful-save status** | `SettingsScreen.kt:395-417` shows `"Download/Glide"` on `SDK_INT < 29 && treeUri == null` next to a red error warning (and lacks a `Card` wrapper); `OfferConsentCard.kt:122-360` omits effective save destination; `TransferResultCard.kt:411` hardcodes `"to your selected folder"` even for `Download/Glide`, and `L358,442,480` hide Save buttons on `Success`; `HistoryDetailDialog.kt:277` hardcodes `"in selected folder"`. | Compute one canonical `EffectiveSaveDestination` (`Download/Glide`, SAF folder name, or `"Select folder required"`) surfaced identically in Settings, `OfferConsentCard`, `TransferResultCard`, and `HistoryDetailDialog`; keep explicit copy-export option working cleanly. |
| **Dark/light contrast & readable error/empty states** | `HistoryDetailDialog.kt:519` renders `errorContainer.copy(alpha = 0.4f)` behind `onErrorContainer`; `ActionableError.kt:205,230` leaks `"VirtualBox Bridged Adapter"`; `QrScannerScreen.kt:613` displays raw `endpoint.address:port`. | Use un-alpha'd semantic surface tokens matching `ColorContrast.kt`, replace dev/VM jargon with clear user guidance, and tuck raw IP/port behind expandable technical details. |
| **TalkBack / contentDescriptions, focus, scaling, long/duplicate device names** | `ReceiveReadinessScreen.kt:115-131` `Switch` lacks `contentDescription`; `HomeScreen.kt:371` `PairedDeviceCard` omits `.isolateBidi()` and duplicate-name disambiguation; `AccessibilitySemanticsTest.kt:43` uses stale `"Open settings"` selector; `AdaptiveAccessibilityGateAcceptanceTest`/`AccessibilityAndRtlAcceptanceTest`/`PremiumUxGateAcceptanceTest` assert tautological local booleans rather than rendered Compose semantics. | Add `Switch` semantics/merged row clickable semantics, apply `.isolateBidi()` + short peer ID/transport tag on duplicate names, verify font scaling (`2.0x`) & keyboard focus (`GlideKeyboardShortcuts`) in real Compose UI tests. |
| **Retain concise instructions; remove redundant nav & obsolete cards** | `HomeScreen.kt:70-77,393-462` defines unused `RecentActivityCard` dead code; `HomeScreen.kt:276-286` empty `"No paired devices yet."` clickable card duplicates `"Pair device"` `TextButton` (`L262-272`). | Remove dead `RecentActivityCard` and duplicate empty-state card CTA; streamline copy across Home/Discovery/Receive. |

---

## 5. R3 & R7 Gradle Task Paths, Test Hygiene & Baseline Profile Setup Audit

### 5.1 Actual vs Documented Gradle Subprojects & Task Paths
- **Actual `android/settings.gradle.kts` (`rootProject.name = "Glide"`)**:
  `:app`, `:core`, `:data`, `:designsystem`, `:transport-lan`, `:transport-nearby`, `:benchmark`.
- **Obsolete / Broken References**:
  - `android/README.md:20-26` documents nonexistent prefixes `:android:app`, `:android:core`, ..., and nonexistent module `:android:baselineprofile` (`android/baselineprofile`).
  - `tools/*.ps1` callers must invoke `:core:test`, `:data:testDebugUnitTest`, `:designsystem:testDebugUnitTest`, `:transport-lan:testDebugUnitTest`, `:transport-nearby:testDebugUnitTest`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:connectedDebugAndroidTest`, and `:benchmark:connectedBenchmarkAndroidTest` (NOT `:android:baselineprofile:...`).

### 5.2 Test Hygiene & Gating Findings (R3 Audit)
1. **Default-excluded tests**:
   - `android/app/build.gradle.kts:133-141` excludes `**/Hard003*` unless `-Phard003Regression=true` is passed.
   - `android/data/build.gradle.kts` excludes `StorageStreamingBenchmarkTest` unless `-PrunStorageBenchmarks=true` is passed.
2. **Silently skipped compliance assertions**:
   - `ClosedTestingComplianceTest:85`, `ProductionReleaseGateTest:74`, `ProductionRolloutComplianceTest:72` call `assumeTrue` when APK/AAB artifacts are absent — must run after building release artifacts and report real artifact presence/hashes.
3. **Mocked / tautological / broken tests**:
   - `RoomMigrationTest` mocks `SupportSQLiteDatabase.execSQL` instead of running real SQLite schema migration verification against `schemas/1.json..5.json`; `DatabaseUpgradeRegressionTest` validates v3 rather than v5.
   - `ChaosGateAcceptanceTest` & `TransferSessionManagerTest` exercise unused `TransferSessionManager` rather than active `TransferRuntime` wiring.
   - `AdaptiveAccessibilityGateAcceptanceTest`, `AccessibilityAndRtlAcceptanceTest`, `LanAccessibilityAdaptiveAndScannerQualityTest`, `PremiumUxGateAcceptanceTest` assert tautological local Kotlin booleans (`val x = true; assertTrue(x)`).
   - `TransferNotificationManagerTest:298-305` sets up `SecurityException` without invoking `dispatchUpdate`.
   - `FoldPostureAndInputAcceptanceTest:157-187` checks modifier non-null without testing key dispatch.
   - `ArchitectureRulesTest` & `ForegroundMvpPrivacyAuditTest` omit `:transport-lan` from module/import scans.
   - `AccessibilitySemanticsTest.kt:43` and `VisualRegressionTest.kt:60-63` in `androidTest` use outdated labels (`"Open settings"`, `"Scan QR code"`).
   - `ProductionHealthComplianceTest:113` asserts stale `"Version 1"` documentation on Room v5.

### 5.3 Baseline Profile & Macrobenchmark Audit (R3 & R7)
1. **Plugin & Build Config Gap**: `android/gradle/libs.versions.toml` has `profileinstaller = "1.4.1"` and `benchmark = "1.5.0"`, and `:app` depends on `androidx.profileinstaller`, **but neither `benchmark/build.gradle.kts`, `app/build.gradle.kts`, nor `libs.versions.toml` configures the `androidx.baselineprofile` Gradle plugin**. Moreover, `benchmark/build.gradle.kts` configures `benchmark` buildType with `isDebuggable = true`, whereas `:app` sets `benchmark` `isDebuggable = false`.
2. **Stale `android/app/src/main/baselineProfiles/baseline-prof.txt`**:
   - References nonexistent classes/methods: `MainActivityKt;->GlideApp(HomeViewModel;...)`, `CoroutinesModule`, `StorageModule`, `TransportModule`, `TransferResultDialogKt`, `dev/glide/designsystem/theme/ColorTokens` (+ `ShapeTokens`, `TypographyTokens`), `TransferReducer(TransferSessionState)`, `GlideSettingsRepository`.
   - `BaselineProfilePackagingTest` only substring-checks 16 class tokens, so stale rules silently stay invalid and yield sub-optimal ART AOT compilation.
3. **Invalid `TransferDisplayBenchmark.kt` (called out in R3)**:
   - Lines 47-53 simply wait for `MainActivity` home screen and `Thread.sleep(1000)` on idle UI rather than exercising an active progress journey.
4. **`BaselineProfileGenerator.kt` & `NavigationBenchmark.kt` coverage gap**:
   - Only navigate Home -> History -> Settings -> Back, missing Selection, Discovery, Receive Readiness, and Transfer progress/result routes.
5. **Required R3/R7 Action**:
   - Fix `TransferDisplayBenchmark` to drive a deterministic simulated active transfer display journey (via `benchmark`-only `DebugLaunchHarness` / `LaunchOverrides`, clearly labeled as deterministic simulated UI progress) alongside real installed transfer traces.
   - Expand `BaselineProfileGenerator` & `NavigationBenchmark` to cover all core CUJs, fix/regenerate `baseline-prof.txt` on the Pixel 10 emulator, and measure cold/warm startup + frame timing (`P50/P90/P95/P99`) before and after optimization against `benchmark` (`Release`-minified) APK baselines.
