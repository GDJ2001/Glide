# Glide 1.0.0 User Guide — Coverage, Evidence, and Label Audit

Updated 2026-11-14. All 52 supplied captures (50 unique hashes: Windows 17, Linux 16, Android 19 files / 17 unique + 2 exact duplicates) remain byte-identical in `assets/<platform>/`, while annotated PNG copies with numbered badges and highlight boxes live in `assets/annotated/<platform>/`. Every original, annotated image, and callout coordinates/text block is indexed in `screenshot-manifest.json`.

## 1. Screenshot Coverage (50 / 50 Unique Captures Illustrated)

- **Windows (17 / 17 unique)**: `send-empty`, `send-qr-dialog`, `send-paired-picker`, `receive-ready`, `receive-large-qr`, `receive-offer`, `pair-find`, `pair-show-qr`, `pair-enter-code`, `pair-approval`, `pair-complete`, `pair-enter-link`, `pair-by-address`, `activity-receiving`, `activity-history`, `activity-cancelled`, `settings`.
- **Linux (16 / 16 unique)**: `send-empty`, `send-staged`, `send-qr-dialog`, `receive-ready`, `receive-offer`, `pair-find`, `pair-show-qr`, `pair-enter-code`, `pair-complete`, `pair-enter-link`, `pair-by-address`, `pair-not-found`, `activity-sending`, `activity-history`, `settings-upper`, `settings-lower`.
- **Android (17 / 17 unique)**: `home`, `send-select-empty`, `send-select-staged`, `recipient-list` (Light theme), `send-qr-confirmation`, `send-progress`, `nearby-verify` (Dark theme), `nearby-verify-light` (Light theme), `receive-offer`, `receive-complete`, `history`, `settings-appearance`, `settings-trust`, `settings-storage`, `recipient-bluetooth-off`, `recipient-wifi-off`, `recipient-no-devices`.

## 2. Capture and Validation Gaps (Reported Honestly)

- **QR recipient confirmation modals**: Windows `Confirm receiving device` (`Reject` / `Send files`, `MainWindow.xaml:474`) and Linux `Confirm Recipient` (`Cancel` / `Confirm Recipient`, `ModalHost.axaml:85-98`) are described in exact walkthrough steps and verified against UI source code, but no live screenshot of those two post-scan dialogs was present in the 52-file capture bundle.
- **Android Receive readiness & camera QR scanner**: Android `Receive readiness` (`ReceiveReadinessScreen.kt:122,161,251,276`) and the live camera viewfinder overlay (`QrScannerScreen.kt:272,626-634,726`) were not supplied in the capture bundle; Section 1, 4, and Appendix describe their exact controls from Android UI code.
- **Apple (iOS / iPadOS / macOS)**: Per `doc/planning/native-platforms/RECOVERY_STATUS.md`, Apple core/simulator backends passed CI unit tests, while physical signed-device distribution remains an open gate; no iOS or macOS screenshots are included in the 1.0.0 user guide.
- **Physical 9-route transfer execution**: Route cards and walkthroughs match source-verified control names and supplied screenshots; executing all nine physical device-to-device routes remains a separate test gate.
- **Live QR/pairing values**: Annotated screenshots containing time-limited QR codes or numeric PINs/codes (`receive-ready`, `receive-large-qr`, `send-qr-dialog`, `pair-show-qr`, `pair-enter-code`, `nearby-verify`, `nearby-verify-light`) include an explicit `"EXAMPLE ONLY - THIS CODE HAS EXPIRED"` banner stamp added onto the annotated copy.

## 3. Source-Verified UI Label & Limit Audit

| UI Label / Constant | Platform / Layer | Source File & Line | Notes / Context |
|---|---|---|---|
| `Start receiving` / `Stop receiving` | Windows | `desktop/windows/Glide.Windows.UI/ViewModels/MainViewModel.cs:749` | Receive listener toggle (`ReceiveActionText`) |
| `Turn on receiving` / `Turn off receiving` | Linux | `desktop/linux/Glide.Linux.UI/ViewModels/LinuxMainViewModel.cs:320` | Linux-specific Receive toggle (`ReceivingButtonText`) |
| `Confirm receiving device` / `Reject` / `Send files` | Windows | `desktop/windows/Glide.Windows.UI/Views/MainWindow.xaml:474` | Sender consent dialog shown after phone scans Quick Share QR |
| `Confirm Recipient` / `Cancel` | Linux | `desktop/linux/Glide.Linux.UI/Views/Controls/ModalHost.axaml:85,93,96` | Linux sender consent dialog after phone scans Quick Share QR |
| `Received files` | Windows / Linux | `MainWindow.xaml:82`; `ActivityPage.axaml:17` | Opens configured download destination folder |
| `Sharing is local` | Windows | `desktop/windows/Glide.Windows.UI/Views/MainWindow.xaml:56` | Sidebar offline/local-only privacy card |
| `Clear list` | Windows | `desktop/windows/Glide.Windows.UI/Views/MainWindow.xaml:133` | Empties staged Send queue on Windows |
| `Clear all` | Linux / Android | `SendPage.axaml:60`; `SelectionScreen.kt:172` | Empties staged Send queue on Linux & Android |
| `Remove` | Windows / Android | `MainWindow.xaml:146,261,402` | Removes queued item or revokes paired peer |
| `Clear history` | Windows / Linux | `MainWindow.xaml:84`; `ActivityPage.axaml:22` | Clears Activity log without deleting received files |
| `Cancel QR share` | Windows | `desktop/windows/Glide.Windows.UI/Views/MainWindow.xaml:483` | Cancels Quick Share QR dialog on Windows |
| `Cancel quick share` | Linux | `desktop/linux/Glide.Linux.UI/Views/Controls/ModalHost.axaml:132` | Cancels Quick Share QR dialog on Linux |
| `Refresh code` / `Refresh` | Windows / Linux | `MainWindow.xaml:294,483,490`; `ReceivePage.axaml:55`; `ModalHost.axaml:129,155` | Rotates one-time QR invitation |
| `Show larger code` / `Copy receive link` | Windows / Linux | `MainWindow.xaml:292-293`; `ReceivePage.axaml:50,53` | Enlarges transfer QR or copies `glide://` receive link |
| `Add folder` / `Add files...` / `Add folder...` | Windows / Linux | `MainWindow.xaml:132`; `SendPage.axaml:29,31,54,57` | Queues files or directories for sending |
| `Quick share with QR` / `Paired device` | Windows / Linux | `MainWindow.xaml:160-164`; `SendPage.axaml:109-112` | Chooses QR quick share or saved paired peer |
| `Connect` | Linux / Android | `SendPage.axaml:162`; `QrScannerScreen.kt:626` | Starts connection to saved peer / scanned invitation |
| `Find devices` / `Show my QR and code` / `Enter pairing link` / `Pair by address` | Windows / Linux | `MainWindow.xaml:180-182`; `PairPage.axaml:17,20,23,25` | Pairing modes |
| `Copy link` / `New code` / `Try again` | Windows / Linux | `MainWindow.xaml:194,208-209`; `PairPage.axaml:55,82-83` | Pair-only link copy, PIN rotation, and retry |
| `Approve` / `Decline` / `Open Settings` | Windows / Linux | `MainWindow.xaml:238`; `PairPage.axaml:59,136` | Pair confirmation & failed-discovery settings shortcut |
| `Open folder` / `Search history` / `All` / `Sent` / `Received` / `Completed` / `Technical details` | Windows / Linux | `MainWindow.xaml:332,334,356-359,375-376`; `ActivityPage.axaml:65,70,102-110` | Activity history filtering, folder launch, and diagnostics |
| `Accept transfer` / `Decline` | Windows | `desktop/windows/Glide.Windows.UI/Views/MainWindow.xaml:460` | Explicit incoming transfer approval on Windows |
| `Accept Transfer` / `Decline` | Linux | `desktop/linux/Glide.Linux.UI/Views/Controls/ModalHost.axaml:68,71` | Explicit incoming transfer approval on Linux |
| `Appearance` / `Change folder` / `Advanced connection details` | Windows | `desktop/windows/Glide.Windows.UI/Views/MainWindow.xaml:392,396-397` | Windows Settings controls |
| `Download Directory` / `Browse...` / `Revoke selected trusted device` / `Listening Port` / `Export diagnostic logs` | Linux | `desktop/linux/Glide.Linux.UI/Views/Pages/SettingsPage.axaml:20,52,57,105,118,126` | Linux Settings controls |
| `Send files` / `Receive files` / `Paired devices` / `Pair device` | Android | `android/app/src/main/kotlin/dev/glide/app/ui/HomeScreen.kt:218,241,258,271` | Android Home screen actions |
| `Last seen ...` / `Paired device` | Android | `android/app/src/main/kotlin/dev/glide/app/ui/HomeScreen.kt:377` | Home paired-device status subtitle |
| `Choose photos & videos` / `Choose files & documents` / `Choose folder` / `Proceed to send` | Android | `android/app/src/main/kotlin/dev/glide/app/ui/selection/SelectionScreen.kt:231,250,269,513,653` | File batch picker actions |
| `Scan QR code or paste invitation` / `Retry` / `Cancel` | Android | `android/app/src/main/kotlin/dev/glide/app/ui/discovery/DiscoveryScreen.kt:197,287,306,534` | Recipient picker and recovery |
| `Paste invitation` | Android | `android/app/src/main/kotlin/dev/glide/app/ui/pairing/QrScannerScreen.kt:272,726` | Camera-free `glide://` URI input fallback |
| `Verify connection` / `Numbers match` / `Don't match` | Android | `android/app/src/main/kotlin/dev/glide/app/ui/pairing/PairingVerificationCard.kt:187,367,395` | Nearby 4-digit SAS verification |
| `Accept file` / `Accept N files` / `Decline` | Android | `android/app/src/main/kotlin/dev/glide/app/ui/transfer/OfferConsentCard.kt:385,413` | Explicit receiver consent prompt |
| `Keep receiving in background` / `Ready to receive` / `Scan PC QR code` / `Stop receiving` | Android | `android/app/src/main/kotlin/dev/glide/app/ui/discovery/ReceiveReadinessScreen.kt:122,161,251,276` | Foreground/background listener & PC QR launch |
| `Verified` / `Share file` / `Open file` / `Done` | Android | `android/app/src/main/kotlin/dev/glide/app/ui/transfer/TransferResultCard.kt:309,515,529,544,562` | Post-transfer completion card |
| `Open Bluetooth settings` / `Open Wi-Fi settings` / `Check again` | Android | `android/app/src/main/kotlin/dev/glide/app/ui/failure/ActionableError.kt:59-68` | Recovery buttons for blocked radios |
| `All` / `Sent` / `Received` | Android | `android/app/src/main/kotlin/dev/glide/app/ui/history/HistoryScreen.kt:154,165,176` | Android History filter chips |
| `Device name` / `Download/Glide` / `Received file save location` / `Choose folder` / `Use Download/Glide` | Android | `android/app/src/main/kotlin/dev/glide/app/ui/settings/SettingsScreen.kt:256,395,399,411,413` | Storage & device identity preferences |
| `Theme mode` / `Dynamic color` / `Reduced motion` / `Notification details` / `Keep screen awake` / `Delete all` / `Nearby diagnostics` / `Privacy policy` / `Open source licenses` / `Manage trusted devices` / `Pair with another device` | Android | `android/app/src/main/kotlin/dev/glide/app/ui/settings/SettingsScreen.kt:503,536,558,652,678,772,817,889,907,1299,1305` | Appearance, wake lock, trust, diagnostics, legal |
| `MAX_ITEMS_PER_BATCH = 1_000` / `MAX_ITEM_BYTES = 64 GiB` / `MAX_BATCH_BYTES = 256 GiB` / `MAX_CONTROL_PAYLOAD_BYTES = 16 KiB` / `MAX_FOLDER_DEPTH = 16` / `MAX_FOLDER_FILES = 10_000` | Protocol core | `android/core/src/main/kotlin/dev/glide/core/domain/DomainModels.kt:6-17`; `desktop/src/Glide.Core/Transport/Lan/LanRecordKind.cs:35-36` | Enforced protocol & batch guardrails |
