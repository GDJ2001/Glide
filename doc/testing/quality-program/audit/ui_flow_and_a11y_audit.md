# Desktop UI Flow, Parity, Accessibility, Refactoring & Benchmark Audit

---

## 1. Screen-by-Screen & Dialog Audit Against R6 (Windows vs. Linux)

### 1.1 Shell & Navigation Rail
- **Requirement (R6)**: Navigation remains **Send, Receive, Activity, Pair devices**, with **Settings at the bottom**; match Windows/Linux action order and placement; remove redundant navigation and obsolete instructional cards.
- **Current State**:
  - **Windows** (`MainWindow.xaml:39-62`):
    - Order: `Send`, `Receive`, `Activity`, `Pair devices` at top; `LocalSharingNote` card (`L54-59`) + `Settings` at bottom.
    - Persistent top bar (`MainWindow.xaml:74-86`) shows `CurrentTab`, `StatusMessage`, `Received files` button (`OpenDownloadsCommand`), and `Clear history` (Activity-only).
    - Responsive compact rail collapses labels below `1008` DIP (`64` DIP width) (`MainWindow.xaml.cs:39-62`).
  - **Linux** (`MainWindow.axaml:63-150`):
    - Order: `Send`, `Receive`, `Activity`, `Pair devices` (`CommandParameter="Pair"`, `Content="Pair"` overwritten by `<StackPanel>`) at top; `Settings` (`Grid.Row="2"`) **above** the `Sidebar Receiving Status Card` (`Grid.Row="3"`, `L122-147`) — so `Settings` is NOT the bottom-most item!
    - No persistent top bar: `Received files` and `Clear history` appear only inside `ActivityPage.axaml:17-25`, and `StatusMessage` is squeezed into a 3-line truncated sidebar card (`MainWindow.axaml:143-145`).
    - Fixed `212` DIP sidebar; `MinWidth="760"` vs Windows `MinWidth="500"`.
- **Gaps & Fixes**:
  1. Move Linux `Sidebar Receiving Status Card` above `Settings` (or into a non-nav footer above `Settings`) so `Settings` sits at the bottom on both platforms.
  2. Remove Windows `LocalSharingNote` (`MainWindow.xaml:54-59`) — obsolete instructional card per R6.
  3. Standardize global header / `Received files` placement and `StatusMessage` visibility across Windows and Linux.

---

### 1.2 Send Screen & Associated Dialogs
- **Requirement (R6)**: Clearly separate file selection, destination selection, pairing, and transfer QR sharing; failure retains selected files and offers actionable recovery; remove redundant/obsolete instructions.
- **Current State**:
  - **File Selection**:
    - Both Windows (`MainWindow.xaml:92-154`) and Linux (`SendPage.axaml:19-96`) support drag-and-drop, `Add files`, `Add folder`, and `Clear list`/`Clear all`, plus per-row Remove.
    - **Bug (Windows)**: `MainViewModel.AddDroppedFiles` (`L925-949`) calls `DesktopTransferSelection.Expand` synchronously on the UI thread (freezes UI for large folders). Linux `StagePathsAsync` (`L883-966`) runs off-thread but duplicates/diverges from `DesktopTransferSelection.Expand` (e.g., doesn't flag duplicate destination paths at stage time and allows adding the same file twice to `StagedItems`).
    - **Bug (Windows)**: `SelectedFiles` is **never cleared** after a successful send (`ExecuteSendFilesAsync`, `ExecuteTrustedSendAsync`, `HandleInboundTransferAsync`), whereas Linux clears `StagedItems` on success (`L1110, 1175, 1539`) and retains them on failure/cancellation.
    - **Bug (Windows)**: `ExecuteSendFilesAsync` (`L1541`) and `StartHostedSendPairingAsync` (`L1676`) never call `DesktopBatchPreparer.Prepare` before connecting/hosting.
  - **Destination / Method Selection (`Quick share with QR` vs `Paired device`)**:
    - **Parity Mismatch**:
      - Windows (`MainWindow.xaml:160-168`): No method selected by default (`SendMethodChoice.None`); cards are disabled until `HasSelectedFiles`. Clicking `QuickQr` **immediately** opens the Quick Share QR modal (`StartQuickShareSafelyAsync`). Clicking `Trusted` **immediately** opens the `Choose a saved device` modal (`IsTrustedDevicePickerVisible`), and selecting a peer closes the modal and reveals a `Send to <DeviceName>` primary button (`SendChosenDeviceButton`) on the Send page.
      - Linux (`SendPage.axaml:104-176`): `QuickShareQr` is selected by default even with 0 files. Clicking `QuickShareQr` reveals a `Start quick share with QR` button; clicking `SavedDevice` shows an inline `SavedDeviceRows` list where clicking a device row **immediately launches the transfer** (`ConnectSavedDeviceCommand`) with no review/Send button.
    - **Redundant/Dead Code**:
      - Windows has unreachable `IsSavedDeviceConnectVisible` modal (`MainWindow.xaml:436-455`) and legacy `SendMethodChoice.Nearby` / `SelectPeerCommand` / `ShowSavedDeviceQrCommand` / `SubmitPairModalCommand` code (`MainViewModel.cs:409-521, 1015-1180`).
      - Linux has unused `SendMethod.NearbyDevice` enum & `InvitationInput` / `ReceiveFromInvitationCommand` (`LinuxMainViewModel.cs:44, 490, 582, 1199-1256`).
      - Linux `SendPage.axaml:25` ("Files are transferred directly over your local network. No cloud servers are used.") duplicates `L13` and violates R6 concise-instructions rule.
- **Fixes**:
  1. Match Send action flow across Windows & Linux: separate file queue -> method/destination selection -> explicit primary action (`Show QR code` for Quick Share QR, or `Send to <Device>` after selecting a Paired device).
  2. Run `DesktopTransferSelection.Expand` off-thread on both platforms at staging time and `DesktopBatchPreparer.Prepare` before send/host.
  3. Clear queued files on verified send completion; retain them on failure/cancellation.
  4. Remove dead `SavedDeviceConnect` overlay (Windows) and dead `NearbyDevice`/`InvitationInput` code (Windows & Linux).

---

### 1.3 Receive Screen & Enlarged QR Dialog
- **Requirement (R6)**: Separate pairing vs receive transfer QR sharing; keep receive availability independent; show concise instructions only.
- **Current State**:
  - **Windows (`MainWindow.xaml:271-300`)**:
    - Card 1: `ReceiveStatusTitle` (`Ready to receive` / `Receiving is off`) + `Start receiving`/`Stop receiving`.
    - Card 2 (`Connect a phone`): QR image + `Show larger code`, `Copy receive link`, `Refresh code` + `IsQrZoomModalVisible` (`L488-492`).
    - **CRITICAL UX BUG (Windows)**: Card 2 (`MainWindow.xaml:283-298`) has **NO `IsListening` visibility binding**! When receiving is turned off, Card 2 remains visible showing a blank white 180x180 square and active buttons that do nothing.
    - **CRITICAL NETWORK BUG (Windows)**: Windows `RefreshReceiverInvitation` (`MainViewModel.cs:2221-2369`) does NOT use `DesktopInvitationManager.AssessReadiness()`. In NAT-only VMs (`10.0.2.15`) or offline states, it fails to warn the user and silently falls back to `127.0.0.1` (`L2330-2335`), displaying an unconnectable loopback QR code!
    - **Redundant Text (Windows)**: 3 overlapping instructional strings (`L273`, `L278`, `L286`).
  - **Linux (`ReceivePage.axaml:1-66`)**:
    - Card 1: `ReceivingStatusText` + `Turn on/off receiving` (note button label difference vs Windows `"Start/Stop receiving"`).
    - Card 2: `IsListening` hides QR/warning section when off; when on, displays VM NAT / offline warning banner if `!IsNetworkReady`, or QR + `Show larger code`, `Copy receive link`, `Refresh code` in the same order as Windows.
    - **Broken Test Mismatch**: `LinuxRenderedUiTests.cs:716-717` asserts `<Expander Header="Connection details">` inside `ReceivePage`, which was removed from `ReceivePage.axaml` (only exists in `ModalHost.axaml:118` and `PairPage.axaml:86`).
- **Fixes**:
  1. On Windows, bind Receive Card 2 QR section to `IsListening` + `IsNetworkReady` (using `DesktopInvitationManager`), and add the VM NAT / NoUsableIpv4 warning banner matching Linux.
  2. Standardize button copy (`Start receiving`/`Stop receiving` or `Turn on/off receiving`) and trim redundant helper sentences on Windows.
  3. Decide whether `Connection details` Expander belongs on `ReceivePage` (for parity with `ModalHost` Quick Share QR) and align `LinuxRenderedUiTests.cs` accordingly.

---

### 1.4 Pair Devices Screen
- **Requirement (R6)**: Clearly separate pairing from transfer QR sharing; display pairing QR and link-entry actions according to their actual purpose (`glide://pair/v2` pair-only vs `glide://pair/v1` transfer).
- **Current State**:
  - Both **Windows (`MainWindow.xaml:174-269`)** and **Linux (`PairPage.axaml:1-178`)** bind to `DesktopPairingCoordinator` (`Glide.Desktop/Pairing/DesktopPairingCoordinator.cs`) and expose the exact same 4 actions in identical order:
    1. `Find devices` (mDNS scan for pair-ready peers -> PIN entry)
    2. `Show my QR and code` (`TargetReady`: displays pair-only v2 QR for phone + 6-digit PIN for another PC + expiry countdown + `Copy link`, `New code`, `Cancel`, `Connection details` expander)
    3. `Enter pairing link` (`UseLink`: `glide://pair/v2#...` link entry; `DesktopPairingCoordinator` explicitly rejects v1 transfer links with `"This link sends files — open Receive to use it"`)
    4. `Pair by address` (`ManualEntry`: IPv4:port + 6-digit PIN)
  - Below the pairing card, both show `Paired devices` (`TrustedDevices` / `TrustedPeers`) with `Remove`.
  - **Parity & UI Bugs**:
    1. **Windows `PairDevicesPage`** lacks the `HasDiscoveryDiagnostic` warning banner (`PairPage.axaml:31-42`) and lacks `Pair by address` / `Open Settings` recovery buttons on `IsPairEmpty` (`PairPage.axaml:54-61`).
    2. **Linux `PairPage.axaml:106` vs `109`**: `PairAddress` TextBox has `MaxWidth="320"` without `Width="320"` inside a left-aligned `StackPanel`, so it renders narrower than `PairPin` (`Width="320"`).
    3. **Windows `MainWindow.xaml:219` vs `225`**: `InitiatorPinEntry` TextBox is `Width="180"` vs `ManualEntry` `Width="320"`.
    4. **Windows `CopyPairOnlyLinkCommand` (`MainViewModel.cs:314`)**: Does not update `StatusMessage` after copying and can throw `COMException` on locked clipboard; Linux `PairPage.axaml.cs:17-23` copies via code-behind without `StatusMessage` feedback either.
    5. **Duplicate device names**: Both Windows (`MainWindow.xaml:260`) and Linux (`PairPage.axaml:164`) show ONLY `DisplayName` in the `Paired devices` list — two devices with the same name (e.g., two `"ubuntu"` laptops) are visually identical.
    6. **Tab switch wipes pairing state**: `OnPageLeft()` (`MainViewModel.cs:659`, `LinuxMainViewModel.cs:232`) cancels any non-Idle phase immediately when leaving `Pair devices`, even `Success` or `Failed` results.
- **Fixes**:
  1. Add `HasDiscoveryDiagnostic` notice and `IsPairEmpty` recovery actions (`Pair by address`, `Open Settings`) to Windows `PairDevicesPage`.
  2. Set `Width="320"` on Linux `PairAddress` to align with `PairPin`.
  3. Show short fingerprint (`FingerprintShort`) alongside `DisplayName` in `Paired devices` (at least when duplicate names exist or as subtle secondary metadata).
  4. Only cancel active `TargetReady` window / active scan on `OnPageLeft()`, preserving completed `Success`/`Failed` summary or prompting if an approval is pending.

---

### 1.5 Activity Screen & Active Transfer Banner
- **Requirement (R6)**: Open Activity/progress once at the appropriate transfer transition, then respect manual navigation; keep current progress accessible and terminal results persistent; show distinct connecting, approval, transferring, verifying, and saving states; failures retain selected files and offer actionable recovery.
- **Current State**:
  - Both platforms share `DesktopTransferPresentation` (`Transfer`):
    - Distinct `PhaseText` states (`DesktopTransferPresentation.cs:28-37`): `"Connecting…"` (`Preparing`), `"Waiting for approval…"` (`AwaitingConsent`), `"Transferring…"` (`Transferring`), `"Verifying files…"` (`Verifying`), `"Saving files…"` (`Saving`), `"Waiting for receiver confirmation…"` (`WaitingForConfirmation`).
    - `IsIndeterminate` is `true` for all non-`Transferring` active phases (`L38`).
    - Auto-navigation (`ApplySnapshot` `L98-100`): fires once per `RunId` on `Transferring` (or `Preparing` when `navigateOnPreparation: true` for trusted sends), then respects manual navigation.
    - Terminal results (`HasResult`) stay persistent in the `Current transfer` card until the next transfer starts, at which point failed results move to `PreviousResults` ("Earlier this session", bounded to 100 entries).
  - **Bugs & Parity Gaps**:
    1. **Windows forced tab switches (`MainViewModel.cs:1142, 1409, 1452`)**: `ExecuteReceiveFromInvitationSafelyAsync` (`L1142`), `HandleTrustedIncomingAsync` (`L1409`), and `HandleInboundTransferAsync` (`L1452`) forcibly set `CurrentTab = "Receive"` or `"Send"` at connection start, **breaking R6 manual navigation** and causing jarring jumps (`Send` -> `Receive` -> consent modal -> `Activity`).
    2. **Pre-`Transferring` failures/cancellations don't open Activity**: If Quick Share QR or an incoming Receive fails or is cancelled during `Preparing` or `AwaitingConsent`, `ApplySnapshot` returns `false`, so `Transfer.HasResult` sits invisible on `Activity` while the user stays on `Send`/`Receive`.
    3. **Windows Activity recovery parity (`MainWindow.xaml:333-336`)**: Only renders `Open folder` and `Try again`; missing `Start receiving`, `Pair by address`, and `Open Settings` buttons that `DesktopTransferPresentation` provides and Linux renders (`ActivityPage.axaml:71-73`).
    4. **Windows `RetryTransferCommand` bug (`MainViewModel.cs:358-365`)**: If `SendMethod == Nearby`, retry wrongly triggers `StartQuickShareSafelyAsync()`; if `Trusted`, calls `ExecuteTrustedSendAsync()` without exception wrapper.
    5. **Linux Verified History (`ActivityPage.axaml:135-141`)**: Omits `ItemName` completely (even though search filters by `ItemName`!) and has no `Open folder` button per row (Windows has both).
    6. **Filter active state invisible on BOTH platforms**: Neither Windows (`MainWindow.xaml:357-359`) nor Linux (`ActivityPage.axaml:104-112`) visually highlights the active `All` / `Sent` / `Received` filter button.
    7. **Windows Active Transfer Banner (`MainWindow.xaml:409-411`)**: Shows only `Title` and `BytesText`; lacks progress bar, rate, Cancel button, and click-to-Activity navigation. Linux `ActiveTransferBanner.axaml` has progress bar, rate, file, bytes, Cancel, but lacks click-to-Activity navigation.
    8. **Double UI dispatch on progress**: Both `MainViewModel` and `LinuxMainViewModel` update orphaned VM properties (`OnProgress`, `OnItemStatus`) in addition to `Transfer.ApplyEvent`.
- **Fixes**:
  1. Remove forced `CurrentTab` overrides in `MainViewModel.cs:1142, 1409, 1452`; let `DesktopTransferPresentation` own the single transition to Activity (including terminal failures/cancellations if user hasn't navigated away or via clear inline/banner recovery).
  2. Add missing recovery buttons (`Start receiving`, `Pair by address`, `Open Settings`) to Windows `Activity`.
  3. Add `ItemName` (trimmed) and `Open folder` to Linux `ActivityPage` history rows; bind selected styling to `All`/`Sent`/`Received` filter buttons on both platforms.
  4. Upgrade Windows `ActiveTransferBanner` to parity with Linux (progress, rate, Cancel, click-to-Activity).

---

### 1.6 Settings Screen
- **Requirement (R6)**: Keep destination configuration in Settings; align control heights, contrast, and parity.
- **Current State**:
  - **Windows (`MainWindow.xaml:388-406`)**: Appearance (`ComboBox`: System/Light/Dark), Receiving (`ReceiveDirectory` + `Change folder` + `Advanced connection details` expander with IP/port/fingerprint), Trusted devices (list with `Remove` + `Details` expander). Missing: editable `DeviceName`, editable `ListenerPort`, `Export diagnostic logs`, `About` / version (`1.0.0`), and manual `Check for updates` (even though `GlideUpdateCoordinator` exists).
  - **Linux (`SettingsPage.axaml:1-154`)**: Appearance (3 buttons `Follow System`, `Light`, `Dark` — **not persisted** across restarts, **no `.selected` visual state**), Receiving & Storage (`DeviceName` **not persisted** and doesn't update mDNS, `DownloadDirectory` + `Browse...`), Trusted Devices (ListBox + separate `Revoke selected trusted device` button; `Details` prints raw `LastSeenAtUnixMs` epoch ms!), Advanced (`ListeningPort` without validation or active listener restart, `Export diagnostic logs`), About Glide (`1.0.0`).
- **Fixes**:
  1. Persist Linux `CurrentThemePreference` and `DeviceName` (or make `DeviceName` persistence/mDNS refresh explicit on both Windows & Linux) and bind `.selected` on Linux theme buttons.
  2. Format Linux Settings `TrustedPeers` `LastVerified` timestamp human-readably (via `LinuxSavedDeviceRow` / shared VM) and unify per-row `Remove` parity with `PairPage` and Windows.
  3. On Windows, wire `WM_SETTINGCHANGE` in `MainWindow.xaml.cs` to re-run `ApplyAppearance()` when `AppearanceMode == "System"`, and update semantic status brushes in High Contrast mode.

---

### 1.7 Consent, QR, and Picker Modal Dialogs
- **Windows (`MainWindow.xaml:414-492`)**: 7 overlays (`TrustedPicker`, `SavedDeviceConnect` [dead], `OfferConsentOverlay`, `TrustEnrollment`, `RecipientConfirmation`, `PairModal`, `QrZoomModal`).
- **Linux (`ModalHost.axaml:14-162`)**: 5 overlays (`SasModal`, `OfferModal`, `RecipientModal`, `QuickShareModal`, `ReceiveQrExpanded`).
- **Findings**:
  - Windows `OfferConsentOverlay` (`L460`) does NOT display the actual `ReceiveDirectory` path (says `"your selected receive folder"` generically), whereas Linux (`ModalHost.axaml:63`) displays `{Binding DownloadDirectory}`! Showing the destination path in Windows' Offer dialog increases clarity and parity.
  - Windows `TrustEnrollment` (`L467`) uses `"Not Now"` / `"Trust Device"`, while Linux (`ModalHost.axaml:37-40`) uses `"Decline"` / `"Accept & Save"`. Standardize terminology across desktop.
  - Windows `RecipientConfirmation` (`L474`) uses `"Reject"` / `"Send files"`, while Linux (`L93-96`) uses `"Cancel"` / `"Confirm Recipient"`.
  - **Focus & Escape**: Linux `ModalHost` supports `Escape` (`ModalHost.axaml.cs`), Windows modals do NOT support `Escape`; only 2 of 7 Windows overlays capture/restore focus (`MainWindow.xaml.cs:183-209`); NEITHER Windows nor Linux traps Tab inside the open modal (Tab escapes into obscured background controls behind the scrim).

---

## 2. Visual & Accessibility Audit Summary (R6)

| Audit Dimension | Windows Findings | Linux Findings |
|---|---|---|
| **Field alignment & sizing** | `Remove` button in `SelectedFilesList` `MinHeight="32"` (`MainWindow.xaml:146`) vs `40` elsewhere; `ComboBox` `MinHeight="36"` (`App.xaml:251`); `PairPin` in `InitiatorPinEntry` `Width="180"` vs `ManualEntry` `Width="320"`. | `PairAddress` (`PairPage.axaml:106`) has `MaxWidth="320"` without `Width="320"`, misaligning with `PairPin` (`Width="320"`, `L109`); `Button.method-tab` has no `MinHeight`; Send `"✕"` button has no `MinWidth`/`MinHeight`; `ActiveTransferBanner` Cancel `MinHeight="28"`. |
| **Dark/Light & High-Contrast** | **Dark PrimaryButton fails WCAG AA**: `#FFFFFF` text on `#6D98FF` = **2.84:1** (`App.xaml:43` + `MainViewModel.cs:2041`). **HighContrast** (`MainViewModel.cs:2016-2030`) skips `SuccessBrush`/`WarningBrush`/`ErrorBrush` tokens and doesn't react live to `WM_SETTINGCHANGE` in `System` mode. | **4 WCAG AA contrast failures**: (1) Light `TextMutedBrush` `#75839A` on `#FFFFFF` = **3.82:1** / on `#F1F5FB` = **3.51:1** (`Colors.axaml:123`); (2) Dark `Button.primary` `#FFFFFF` on `#6D98FF` = **2.84:1**; (3) Dark `Button.success` `#FFFFFF` on `#63D7A8` = **1.83:1**; (4) Dark `Button.danger:pointerover` `#FFFFFF` on `#FF9EAA` = **2.10:1**. |
| **Empty & error states** | Empty Send state clear; Activity empty state only shows plain `"No transfer history yet"` (no empty state for `"No matching transfers"` when search returns 0 items!); Receive page lacks offline/NAT error state. | Activity has both empty & search-empty states (`LinuxActivityHistoryViewModel.cs:20-23`); Receive & Send have NAT/offline warning banners; Pair has `DiscoveryDiagnosticSummary`. |
| **Focus & keyboard navigation** | `ComboBox` focus trigger (`App.xaml:267`) never fires because `Toggle` has `Focusable="False"`. Modals don't trap Tab behind scrim, 5 of 7 modals don't capture/restore focus or close on `Escape`. | `Button.primary`, `secondary`, `danger`, `success`, `method-tab` lack `:focus-visible` ring (`Styles.axaml`). `ModalHost` handles `Escape` but does not trap Tab or capture/restore focus on open/close. |
| **Screen-reader / AutomationProperties** | Row-level `Remove`, `Open folder`, `Select pair-ready device` use static generic names without item/device name; `ZoomQrImage` (`MainWindow.xaml:490`) has no `AutomationProperties.Name`; `SelectedFilesList`/`ActivityHistoryList` have double Tab-stop (`ListBoxItem` + inner `Button`). | Row-level `Remove staged item` (`SendPage.axaml:88`) and `Remove paired device` (`PairPage.axaml:169`) lack item/device names; nav buttons set redundant `Content` attribute; `ActivityPage` `List` rows don't wrap/announce `ItemName`. |
| **DPI scaling & narrow widths** | `FitInitialWindowToWorkArea()` converts device pixels -> DIPs (`MainWindow.xaml.cs:96-112`); responsive breakpoints at `1008`, `640`, `600` height work down to `500x480`. | Fixed `212` DIP sidebar & `MinWidth="760"`; `ModalHost` uses hardcoded `Width="440"` instead of `MaxWidth` + `Stretch`; `ReceivePage` QR grid (`L44`) doesn't stack vertically on narrow layouts. |
| **Long / duplicate device names** | Modal peer names (`L439, 460, 467, 474`) and `Activity` `Transfer.PeerName` (`L329`) lack `TextTrimming`/`TextWrapping`; `Pair devices` & `Settings` `TrustedDevices` omit `FingerprintShort` inline (duplicate names look identical). | Modal peer names (`ModalHost.axaml:33, 55, 86`) and `ActivityPage` `PeerName` (`L62, 137`) lack `TextTrimming`; `PairPage` & `SendPage` saved-device lists omit `FingerprintShort` (duplicate names indistinguishable). |

---

## 3. Refactoring Map: Reducing Main ViewModels & Reusing Shared Services (R4)

Both `MainViewModel.cs` (2,419 lines) and `LinuxMainViewModel.cs` (1,835 lines) currently combine shell state, UI adaptation, transfer orchestration, listener/network management, pairing, staging, settings, and history.

### Target Decomposition (Reusing Existing `Glide.Desktop` Services)

1. **Eliminate Reimplemented / Orphaned Services on Windows**:
   - **Reuse `DesktopListenerSupervisor`** (`desktop/src/Glide.Desktop/Services/DesktopListenerSupervisor.cs`) instead of `MainViewModel.RunListenerLoopAsync` & `RunInvitationRotationLoopAsync` (fixes serial accept-loop blocking and adds 4-slot admission bounds + TLS/preamble timeouts).
   - **Reuse `DesktopInvitationManager`** (`desktop/src/Glide.Desktop/Services/DesktopInvitationManager.cs`) instead of `MainViewModel.RefreshReceiverInvitation` (adds `SystemNetworkSnapshotProvider` filtering, VM NAT detection, and eliminates `127.0.0.1` loopback QR fallback).
   - **Reuse `DesktopBatchPreparer` & `DesktopTransferSelection`** consistently on both platforms (off UI thread).
   - **Remove 4 unused Windows service files** after verifying zero callers: `DesktopFileSystemService.cs`, `DesktopProtocolService.cs`, `InvitationService.cs`, `LanListenerService.cs`.

2. **Extract 5 Feature ViewModels / Coordinators** (shared logic in `Glide.Desktop` where platform-agnostic, with thin WPF/Avalonia adapters for file pickers, clipboard, and folder-open):
   - **`SendFeatureViewModel`**
     - *Owns*: `SelectedFiles`/`StagedItems`, async staging via `DesktopTransferSelection.Expand`, `DesktopBatchPreparer.Prepare` pre-send validation, `SendMethod` (`QuickShareQr` vs `SavedDevice`), `_hostedSendRequest`, `TrustedEndpointResolver` candidate resolution + `DesktopTransferRuntime.SendTrustedAsync`, post-success clear & failure retention.
     - *Reuses*: `DesktopTransferSelection`, `DesktopBatchPreparer`, `TrustedEndpointResolver`, `DesktopTransferRuntime`.
   - **`ReceiveFeatureViewModel`**
     - *Owns*: Listener toggle (`IsListening`), `DesktopListenerSupervisor` lifecycle, `DesktopInvitationManager` readiness/NAT guidance + invitation rotation, QR URI/image projection, inbound Profile 1 & Profile 2 dispatch (`HandleIncomingConnectionAsync` / `HandleTrustedIncomingAsync`).
     - *Reuses*: `DesktopListenerSupervisor`, `DesktopInvitationManager`, `DesktopProfile2Router`, `DesktopListenerOwnership` (Linux).
   - **`PairingFeatureViewModel`**
     - *Owns*: Thin projection over `DesktopPairingCoordinator` (`Pairing` snapshot, phase flags, QR image, PIN/address/link inputs, `DiscoveredPeers` projection, `TrustedDevices` projection + revocation).
     - *Reuses*: `DesktopPairingCoordinator`, `MdnsDiscoveryService`, `DesktopPersistentTrustManager`.
   - **`ActivityFeatureViewModel`**
     - *Owns*: `DesktopTransferPresentation` (`Transfer`), `ActivityHistoryViewModel` (search + All/Sent/Received filter + active filter flags), `CancelTransferCommand`, `RetryTransferCommand`, recovery commands, and `OpenFolderCommand`.
     - *Removes*: All orphaned progress/title/rate fields (`TransferTitle`, `TotalProgress`/`TransferProgressFraction`, `TransferRateText`, `TransferredBytesText`, `CurrentFileName`/`CurrentTransferFile`, `_coalescedProgress`, `_transferStopwatch`, duplicate `_rateCalculator`) and double `OnProgress` UI dispatches.
     - *Reuses*: `DesktopTransferPresentation`, `DesktopTransferRateCalculator` (via `DesktopTransferRuntime`), `DesktopStateStore`.
   - **`SettingsFeatureViewModel`**
     - *Owns*: `ReceiveDirectory`/`DownloadDirectory` + `QuarantineStagingStore.VerifyDestinationWritableAsync()`, `ListenerPort` validation & controlled listener restart, `DeviceName` persistence/mDNS refresh, `AppearanceMode` persistence + live OS theme listener, `TrustedDevices` list, `BoundedTransferDiagnosticSink` export, and `GlideUpdateCoordinator` (Windows).
     - *Reuses*: `DesktopStateStore`, `QuarantineStagingStore`, `BoundedTransferDiagnosticSink`, `WindowsUiPreferencesStore`.

3. **Reduce `MainViewModel` / `LinuxMainViewModel` to Shell Composition (~200 lines)**:
   - Composes the 5 feature VMs + `ConsentCoordinator` (`DesktopConsentViewModel` / `LinuxConsentDecision`).
   - Owns `CurrentTab` / `ActivePage`, single auto-navigation to `Activity` from `DesktopTransferPresentation.ApplySnapshot`, `IsTransferBannerVisible`, modal host coordination, CLI/URI activation routing, and deterministic `DisposeAsync()`/`Dispose()` cleanup.

---

## 4. Detailed Analysis of `TransferDisplayBenchmark` & What Needs Fixing (R3)

### Current Implementation Defects
1. **`TransferDisplayBenchmark.kt` (`android/benchmark/src/main/kotlin/dev/glide/benchmark/TransferDisplayBenchmark.kt:35-54`)**:
   - Annotated and documented (`GLIDE-052-macrobenchmark-baseline-profile-report.md:20, 50-52, 97-100`) as measuring active transfer UI rendering, `TransferProgressCard` recomposition, radar visualizer animation, and `<= 10 Hz` progress throttling.
   - **Actual behavior**: Launches `MainActivity` onto `HomeScreen`, waits for `By.pkg("dev.glide.app").depth(0)`, executes `Thread.sleep(1000)`, and calls `device.waitForIdle()`. It **never leaves `HomeScreen`**, **never renders `TransferScreen` or `TransferProgressCard`**, and **never processes a single progress update**!
2. **`DebugLaunchHarness.kt` (`android/app/src/benchmark/kotlin/dev/glide/app/DebugLaunchHarness.kt`)**:
   - The `benchmark` build variant compiles a strictly no-op `DebugLaunchHarness` (`L11-18`), whereas `src/debug/kotlin/.../DebugLaunchHarness.kt` contains `transfer_active_single`, `transfer_active_multi`, and `transfer_verifying` states. Even if `TransferDisplayBenchmark` tried to pass `debug_state` extras, the `benchmark` APK ignores them.
3. **`NavigationBenchmark.kt` & `BaselineProfileGenerator.kt`**:
   - Both use nullable safe-calls (`historyButton?.click()`, `settingsButton?.click()`), silently passing if UI buttons are not found.
   - Neither traverses `DiscoveryScreen` or `TransferScreen`.
4. **`tools/run-transport-benchmark.ps1` (`L17`)**:
   - Invokes `:android:transport-nearby:testDebugUnitTest` (obsolete Gradle path prefix `:android:` — should be verified against `android/settings.gradle.kts`, likely `:transport-nearby:testDebugUnitTest`).

### Required Fixes for R3 Compliance
1. **Replace the idle-home measurement in `TransferDisplayBenchmark.kt` with a real active progress journey**, explicitly split and labeled into two distinct tiers:
   - **Tier A — Deterministic Simulated Progress Journey** (clearly named & tagged `simulated`, e.g., `transferDisplay_simulatedProgressJourney`):
     - In `benchmark` buildType (via an explicit, release-excluded benchmark instrumentation receiver/extra), launch directly into `GlideRoute.Transfer` rendering `TransferProgressCard` (`TransferScreen`).
     - Drive a deterministic >= 5-second progress sequence (`Preparing` -> `AwaitingConsent` -> `Transferring` with throttled `<= 5 Hz` byte/speed updates from `0%` to `100%` -> `Verifying` -> `Saving` -> `Completed`) while `FrameTimingMetric()` captures real Compose recompositions, linear progress animations (`animateFloatAsState`), speed/remaining-time text updates, and accessibility throttling.
     - Assert that `TransferProgressCard` / progress text / terminal completion card actually appeared in the UI hierarchy (fail loudly if idle Home was shown!).
   - **Tier B — Installed Real-Transfer Measurement**:
     - Run an actual installed APK transfer (e.g., Pixel 10 emulator <-> host or loopback/local peer driver) while recording `FrameTimingMetric()`, backend monotonic phase timings (`preparation`, `discovery`, `connection`, `authentication`, `user consent`, `payload`, `verification`, `saving`, `final acknowledgement`), exact bytes, 1s/5s rates, CPU, managed/native memory, GC, thermal state, and battery conditions into the shared R3 diagnostic report format.
2. **Fix `NavigationBenchmark.kt` and `BaselineProfileGenerator.kt`**:
   - Replace `?.click()` with `device.wait(Until.findObject(...), UI_TIMEOUT_MS)` + non-null assertions so missing screens fail immediately.
   - Include `Discovery` and `Transfer` display screens in `BaselineProfileGenerator` so `baseline-prof.txt` actually exercises the hot paths claimed in `GLIDE-052`.
3. **Verify & fix `tools/run-transport-benchmark.ps1` Gradle project paths** against `android/settings.gradle.kts` (`:transport-nearby:...` instead of `:android:transport-nearby:...`).
