# Shared User Experience & Native Interface Specification

## 1. Design Philosophy & Shared Interaction Model

Glide delivers a unified, high-speed user experience across all native platforms:
- **Desktop visual reference**: Windows 11 WPF application.
- **Mobile flow reference**: Android Jetpack Compose application.
- **Brand Tokens**: Glide Blue (`#0066FF`), Cyan accent (`#00D2FF`), semantic status colors, Segoe UI / San Francisco / Roboto typography.
- **Core Principle**: Clear operational truthfulness. Zero simulated progress, explicit security gates, and automatic retention of user selections after recoverable errors.

---

## 2. Core Screen Inventory & Component Behavior

### A. Send Screen (`/send`)
1. **File Tray (Combined Selection Component)**:
   - *Empty State*: Clean drop zone with drag-and-drop affordance, "Add files", and "Add folder" (where supported by OS) buttons.
   - *Populated State*: Compact virtualized file list displaying filename, size, thumbnail icon, and removal handle. Includes header with total item count and cumulative byte size.
   - *Actions*: "Add files", "Add folder", "Remove", "Clear All".
   - *File Safety*: Explanations for skipped files (e.g. permission denied, path traversal blocked). Selection persists across transfer retries.
2. **Sending Method Selection (Mutually Exclusive)**:
   - **Quick QR (Sender-Hosted)**: Generates a focused native invitation sheet containing a live QR code, countdown expiry timer, and refresh button. Once the receiver connects and approves, transfer streams immediately without a redundant second "Send" click.
   - **Nearby Devices**: Real-time mDNS / Nearby discovery list. Tapping a device opens an inline connection flow.
   - **Saved Devices**: Lists previously enrolled and trusted devices with last-seen status. Initiates mutual authentication (`identity-v1`) with pinned key verification.

### B. Receive Screen (`/receive`)
- **Status Toggle**: Clear "Ready to Receive" toggle switch.
- **Device Details**: Local device name and current destination download directory with "Change" button.
- **Invitation Access**: "Show My QR Code" and "Copy Invitation Link" affordances.
- **Incoming Offer Consent Card**: When an offer arrives, displays:
  - Sender device name and verified/unverified identity badge.
  - Total item count and cumulative byte size.
  - Target destination directory.
  - Explicit action buttons: **Accept** and **Decline**.

### C. Pairing & Device Management (`/pair`)
- Compact tabbed or modal controller:
  - Mode 1: **Display Invitation** (shows this device's QR and copyable URI).
  - Mode 2: **Scan / Enter Invitation** (camera viewfinder on mobile, manual paste input on desktop).
  - Mode 3: **Discovered Peers** (nearby devices awaiting pairing).

### D. Activity Screen (`/activity`)
- **Persistent Active Transfer Bar**: Shows current batch filename, transfer speed (e.g. `45.2 MB/s`), byte progress bar, percentage, and Cancel button. Remains visible across tab navigation.
- **Verified History**: Virtualized list of completed, cancelled, and failed transfers. Filterable by All / Sent / Received.
- **Actions**: "Open File", "Show in Folder / Finder", "Clear History".

### E. Settings Screen (`/settings`)
- **Appearance**: System (Default), Light, Dark.
- **Receiving Destination**: Default download folder picker.
- **Trusted Devices**: List of enrolled public keys and fingerprints with explicit "Revoke" button.
- **Advanced Network**: Listener port, IP addresses, TLS SPKI fingerprint.
- **About**: Version, licenses, and offline zero-cloud declaration.

---

## 3. Comprehensive State Machine

All native UI controllers must subscribe to and reflect these exact states:

| UI State | Meaning & User Affordance |
|---|---|
| `Empty` | No files selected. Action: select files or drop them. |
| `Selecting` | File picker or system folder dialog open. |
| `Discovering` | Actively browsing for mDNS or Nearby beacons. |
| `NoDevices` | Discovery active, 0 peers found. Shows tips (Wi-Fi, Bluetooth). |
| `InvitationRequired` | Need to scan QR or paste invitation URI to connect. |
| `Connecting` | TLS 1.3 socket handshake and SPKI pin verification in progress. |
| `AwaitingApproval` | SAS 6-digit comparison or incoming batch consent awaiting human confirmation. |
| `Transferring` | Active bulk byte streaming. Shows real-time speed and byte progress. |
| `Verifying` | Stream EOF reached; receiver calculating final SHA-256 digest match. |
| `Completed` | 100% verified, atomic commit complete, receipt issued. |
| `Rejected` | Remote peer explicitly declined the transfer offer. |
| `Cancelled` | Either user actively cancelled the transfer. |
| `Failed` | Network drop, timeout, or integrity failure. Displays actionable error code. |
| `Busy` | Coordinator currently occupied with another transfer batch (one active batch rule). |

---

## 4. Platform-Specific Layout Adaptations

### Windows (WPF .NET 10)
- **Window Dimensions**: Default $1040 \times 720\text{ DIP}$. Supports Windows Snap layouts down to $820 \times 560\text{ DIP}$.
- **Compact Reflow**: Reflows down to $500 \times 480$ logical units using a compact navigation rail when snapped.
- **Icons**: Fluent system vector geometries ([`FluentIcons.xaml`](file:///d:/GITHub/Glide/desktop/windows/Glide.Windows.UI/Resources/FluentIcons.xaml)).

### Android (Jetpack Compose)
- **Mobile Scaffold**: Material3 adaptive navigation bar on compact screens, navigation rail on medium/expanded foldables/tablets.
- **Haptics & Motion**: Native tactile feedback on confirmation; respect Android system `Reduced Motion` setting.

### iPhone & iPad (SwiftUI)
- **iPhone**: TabView with `Send`, `Receive`, `Activity`, `Settings`. Camera overlay for QR scanning via `AVCaptureSession`.
- **iPad**: Three-column or two-column `NavigationSplitView`. Responsive to available window width in Stage Manager and Split View.
- **Share Extension**: Action Extension with App Group container staging for sharing from Photos and Files.

### macOS (SwiftUI & AppKit)
- **Windowing**: Standard document-style application window with toolbar navigation. Regular Dock icon.
- **Menu Bar**: Native macOS Edit, View, and Window menus. Keyboard shortcuts (`⌘O` for Add Files, `⌘,` for Settings).
- **Finder Integration**: Drag-and-drop into window; context menu "Reveal in Finder" via `NSWorkspace.shared.activateFileViewerSelecting()`.
- **Optional Status Item**: Lightweight menu-bar menu showing quick receive status.

### Linux (Avalonia 12.1.3 on .NET 10)
- **Backend & Windowing**: X11 / XWayland client window with native window manager decorations.
- **Storage Dialogs**: Uses `TopLevel.StorageProvider` for native GTK/KDE file picker dialogs.
- **Theme**: Respects desktop dark/light mode via `PlatformSettings.ColorValues`.
- **Single Instance**: Communicates via local named pipe or domain socket to focus existing window on duplicate launch.

---

## 5. Standardized User Interface Copy & Notifications

To guarantee consistency across all 5 native clients, identical copy and semantics must be used:

### General & Action Copy
| Identifier | Standard Copy | Context / Purpose |
|---|---|---|
| `action_send` | "Send" | Primary action to start or commit a send flow |
| `action_receive` | "Receive" | Navigation or toggle for receiving files |
| `action_cancel` | "Cancel" | Cancels an ongoing transfer or modal flow |
| `action_decline` | "Decline" | Explicitly rejects an incoming transfer offer |
| `action_accept` | "Accept" | Explicitly accepts an incoming transfer offer |
| `action_add_files` | "Add Files" | Opens native file picker |
| `action_add_folder`| "Add Folder" | Opens native directory picker (where supported) |
| `action_clear_all` | "Clear All" | Clears the selected file tray |
| `action_show_in_folder` | "Show in Folder" (Windows/Linux) / "Reveal in Finder" (macOS) | Opens file manager at destination |

### Transfer Status Messages
| State | Standard Display Text |
|---|---|
| Ready to receive | "Ready to receive files on this network" |
| Not ready to receive | "Receiving is paused" |
| Connecting | "Connecting to {deviceName}..." |
| Awaiting verification | "Compare security codes with {deviceName}" |
| SAS Code prompt | "Ensure both screens display: {sasCode}" |
| Awaiting approval | "Waiting for {deviceName} to accept transfer..." |
| Transferring | "Transferring {currentFile} ({currentFileIndex} of {totalFiles})" |
| Verifying | "Verifying file integrity..." |
| Completed | "Transfer completed successfully ({itemCount} files, {totalBytes})" |
| Cancelled | "Transfer was cancelled" |
| Declined | "Transfer offer was declined by {deviceName}" |
| Failed generic | "Transfer failed: {actionableMessage}" |

### Security & Error Warnings
| Condition | Display Text |
|---|---|
| Invalid filename / Traversal | "Filename '{fileName}' contained invalid path characters and was renamed for safety." |
| Batch limit exceeded | "Batch exceeds limit of 1,000 files." |
| Batch byte size exceeded | "Batch exceeds maximum size of 256 GiB." |
| Single file size exceeded | "File '{fileName}' exceeds maximum single file limit of 64 GiB." |
| Insufficient storage | "Not enough disk space to receive incoming transfer ({neededBytes} required)." |
| Pinned identity mismatch | "Security warning: Pinned identity for {deviceName} has changed. Please verify before continuing." |
| Network timeout | "Connection timed out. Ensure both devices are on the same Wi-Fi or local network." |

---

## 6. Accessibility & Inclusivity Specifications

All Glide native applications must meet **WCAG 2.1 Level AA** standards:

### 1. Screen Reader Semantics
- **Android**: `Modifier.semantics { contentDescription = "..."; role = Role.Button }` across all custom interactive composables.
- **Windows (WPF)**: `AutomationProperties.Name`, `AutomationProperties.HelpText`, and `AutomationProperties.LiveSetting="Assertive"` on transfer progress indicators and status changes.
- **macOS & iOS (SwiftUI)**: `.accessibilityLabel()`, `.accessibilityValue()`, `.accessibilityHint()`, and `.accessibilityAddTraits(.isButton)`.
- **Linux (Avalonia)**: `AutomationProperties.Name` and appropriate automation peers on custom views for Orca screen reader compatibility over AT-SPI.

### 2. Touch Targets & Focus Navigation
- Touch targets must be at least **$48 \times 48\text{ dp}$** on Android and **$44 \times 44\text{ pt}$** on iOS.
- Full keyboard tab navigation on Windows, macOS, and Linux:
  - Visible focus indicators with high-contrast outlines (minimum 3:1 contrast against background).
  - Standard key bindings: `Enter` / `Space` to activate, `Escape` to dismiss sheets/modals, `Tab` / `Shift+Tab` to navigate focus.
  - Shortcut support: `Ctrl+O` / `Cmd+O` for file picking, `Ctrl+R` / `Cmd+R` for refresh discovery.

### 3. Visual & Motion Accessibility
- **Contrast**: Minimum 4.5:1 contrast ratio for normal text and 3:1 for large text and UI components.
- **Dynamic Type / Font Scaling**: Respect OS text scale factors up to 200% without layout truncation or overlapping text.
- **Reduced Motion**: Respect `prefers-reduced-motion` on desktop and system-level `Remove animations` on Android/iOS; replace continuous spinners with static indicators or simple opacity transitions when enabled.

