# GLIDE-037: Polish Home and Discovery Surfaces Verification Report

**Author:** Antigravity Agent  
**Date:** 2026-09-13  
**Milestone:** M6 — Premium UI  
**Task:** `GLIDE-037` — Polish Home and discovery surfaces  
**Priority:** P2  
**Status:** COMPLETE & VERIFIED  

---

## 1. Executive Summary & Task Scope

Task `GLIDE-037` delivers production-grade, polished user interfaces for the Home, Discovery, and Receive Readiness surfaces in accordance with `GLIDE_DESIGN_SYSTEM.md`, `GLIDE_USER_FLOWS.md`, and `UI_QUALITY_CHECKLIST.md`. It elevates the initial experience of Glide from basic functional screens to a cohesive, calm, privacy-reassuring, and accessible Android sharing interface.

### Acceptance Criteria Checklist
- [x] **Home Screen Brand Lockup & Clear Space:** Integrated horizontal brand lockup (`ic_glide_logo_color` / `ic_glide_logo_inverse`) with $\ge 1/4$ symbol height clear space, subtitle *"Fast, private, local device sharing"*, and subtle cyan ambient wash ($\le 8\%$ opacity) behind non-text header decoration.
- [x] **Privacy Reassurance:** Displayed prominent privacy reassurance card: *"No cloud, no accounts, direct local Wi-Fi & Bluetooth. Files transfer straight between your devices."*
- [x] **Primary & Secondary Action Hierarchy:** Primary "Send files" button ($\ge 56\text{dp}$ height, rounded) and secondary "Receive files" button ($\ge 56\text{dp}$ height, rounded) with accessible touch targets $\ge 48\text{dp}$.
- [x] **Active Transfer Summary Card:** Dynamic banner rendered on Home when transfers are in-flight or paused, displaying transfer metadata, peer name, progress indicator, and one-tap "Resume" / session transition.
- [x] **Discovery Surface Design & Restrained Motion:** Searching header *"Searching for nearby devices..."* with guidance *"Open Receive on the other device to share directly."* Uses a restrained 2.4s low-amplitude tonal breathing indicator (strictly NO radar circles, radial ripples, or distance estimation graphics).
- [x] **Discovered Peer Card Hierarchy:** Displays device type glyphs (Phone, Tablet, Computer, Unknown), device display name, and availability status. Selected peer renders primary container fill + 2dp elevation + 2dp outline border + accessibility selected semantics.
- [x] **List Stability & Single-Tap Connection Lock:** Stable keys (`peer.id.value`) prevent list jumping. When a peer goes out of range, the row remains in-place and transitions to "Unavailable" rather than abruptly disappearing. Redundant taps during connection setup are rejected.
- [x] **Discovery Timeout & Blocked States:** 120s discovery timeout state with "Retry" and "Cancel" actions. Blocked states (Bluetooth off, Wi-Fi off, Missing permissions, Transport error) render descriptive diagnostic cards and direct intent buttons.
- [x] **Receive Readiness Surface:** Headline *"Ready to receive"*, local device visibility badge, local device name, and 120s discoverability countdown timer with 2.4s tonal breathing badge. Prominent $\ge 56\text{dp}$ "Stop receiving" button cleanly stops advertising and returns to Home.
- [x] **Asset Integrity:** Canonical files under `asset/` and `design/brand/` preserved 100% untouched. Asset verification script passes with exit code 0 (`Export-GlideAssets.ps1 -ValidateOnly`).
- [x] **Automated Testing:** 6/6 unit & acceptance tests passing in `DiscoveryScreenAcceptanceTest.kt`, navigation route tests passing in `NavigationTest.kt`, 0 lint errors, and `verify-local.ps1` passing completely.
- [x] **Physical Emulator Live Verification:** All 7 UI states verified on `Pixel_10` AVD (Android 17 / API 37) with captured screenshot evidence.

---

## 2. Design System & User Flow Alignment

### 2.1 Color, Typography & Motion Tokens (`GLIDE_DESIGN_SYSTEM.md`)
- **Ambient Wash:** Rendered via static `RadialGradient` with `GlidePrimary` / `GlideCyan` at 6%–8% alpha, positioned behind non-text header zones. Does not interfere with text contrast (all foreground body and headline text exceeds WCAG AA 4.5:1 / AAA 7:1).
- **Restrained Motion:** Implemented with `animateFloat` using an `infiniteRepeatable` 2400ms tonal breathing pulse (alpha oscillating gently between 0.65f and 1.0f).
- **Typography:** Uses Material 3 typography tokens (`titleLarge`, `titleMedium`, `bodyMedium`, `labelLarge`) with brand colors (`onSurface`, `onSurfaceVariant`).
- **Brand Lockup:** Brand horizontal logo (`132dp x 44dp`, 3:1 aspect ratio) with clear margins exceeding $11\text{dp}$ ($> 1/4$ symbol height of $44\text{dp}$).

### 2.2 User Flows (`GLIDE_USER_FLOWS.md`)
- **Flow 1: Sending Flow (Discovery):** User selects files -> enters Discovery -> sees searching guidance -> selects peer -> single-click lock triggers session -> transfers seamlessly.
- **Flow 2: Receiving Flow (Receive Readiness):** User taps "Receive files" -> device enters discoverable state -> 120s countdown begins -> device name clearly displayed -> user can tap "Stop receiving" anytime or receives incoming transfer prompt automatically.

---

## 3. Implementation Details

### 3.1 Files Created & Modified
1. `tools/derive-android-resources.ps1`:
   - Added mechanical derivation for horizontal brand lockups:
     - `ic_glide_logo_color.png` (mdpi through xxxhdpi)
     - `ic_glide_logo_inverse.png` (mdpi through xxxhdpi)
     - `ic_glide_symbol.png` (mdpi through xxxhdpi)
2. `app/src/main/kotlin/dev/glide/app/navigation/GlideRoute.kt`:
   - Added `GlideRoute.Discovery(val batchId: String? = null, val sourceHandles: List<String> = emptyList())`
   - Added `GlideRoute.ReceiveReadiness`
3. `app/src/main/kotlin/dev/glide/app/ui/discovery/DiscoveryModels.kt`:
   - Defined `DeviceType` (`PHONE`, `TABLET`, `COMPUTER`, `UNKNOWN`)
   - Defined `DiscoveredPeer`, `BlockedReason`, `DiscoveryUiState`, `ReceiveReadinessUiState`
4. `app/src/main/kotlin/dev/glide/app/ui/discovery/DiscoveryViewModel.kt`:
   - Manages discovery lifecycle, 120s timeout timer, stable peer tracking, and single-click connection locking.
5. `app/src/main/kotlin/dev/glide/app/ui/discovery/ReceiveReadinessViewModel.kt`:
   - Manages receiver advertising, 120s countdown timer, and incoming connection transitions.
6. `app/src/main/kotlin/dev/glide/app/ui/discovery/DiscoveryScreen.kt`:
   - Implements searching header, 2.4s tonal breathing badge, `DiscoveredPeerCard`, blocked diagnostic states, and timeout empty states.
7. `app/src/main/kotlin/dev/glide/app/ui/discovery/ReceiveReadinessScreen.kt`:
   - Implements receiver readiness state, countdown display, visibility indicator, and 56dp "Stop receiving" action.
8. `app/src/main/kotlin/dev/glide/app/ui/HomeScreen.kt`:
   - Updated with horizontal brand lockup, ambient wash, privacy card, 56dp primary/secondary buttons, and active transfer banner.
9. `app/src/main/kotlin/dev/glide/app/navigation/GlideAppNavDisplay.kt`:
   - Routed `GlideRoute.Discovery` and `GlideRoute.ReceiveReadiness` to respective Compose screens.
10. `app/src/main/kotlin/dev/glide/app/MainActivity.kt`:
    - Added test/demo state routing for live physical verification on Android 17.

---

## 4. Automated Verification Results

### 4.1 Acceptance Test Suite (`DiscoveryScreenAcceptanceTest.kt`)
Executed via `.\gradlew.bat :app:testDebugUnitTest --tests "dev.glide.app.ui.discovery.DiscoveryScreenAcceptanceTest"`:

```
dev.glide.app.ui.discovery.DiscoveryScreenAcceptanceTest > discoveryViewModel_initialSearchingState PASSED
dev.glide.app.ui.discovery.DiscoveryScreenAcceptanceTest > discoveryViewModel_rapidPeerLoss_marksUnavailableWithoutRemovingIndex PASSED
dev.glide.app.ui.discovery.DiscoveryScreenAcceptanceTest > discoveryViewModel_singleClickConnectionLock_ignoresSubsequentClicks PASSED
dev.glide.app.ui.discovery.DiscoveryScreenAcceptanceTest > discoveryViewModel_blockedReasons_reflectCorrectUiState PASSED
dev.glide.app.ui.discovery.DiscoveryScreenAcceptanceTest > receiveReadinessViewModel_initialCountdownAndStop PASSED
dev.glide.app.ui.discovery.DiscoveryScreenAcceptanceTest > deviceType_heuristicsClassifyCorrectly PASSED
```
- **Total Tests:** 6/6 passing (100%).

### 4.2 Navigation Route Serialization (`NavigationTest.kt`)
- `discoveryRoute_serializesAndDeserializesCorrectly`: **PASS**
- `receiveReadinessRoute_serializesAndDeserializesCorrectly`: **PASS**

### 4.3 Canonical Brand Asset Integrity (`Export-GlideAssets.ps1 -ValidateOnly`)
```
[PASS] Master files: 20 found, 0 missing
[PASS] Export directories: all present
[PASS] All exports exist and are newer than masters
[PASS] Zero modifications detected in canonical masters
```

### 4.4 Full Local Verification (`tools/verify-local.ps1`)
- **Modules Tested:** `:core:model`, `:core:crypto`, `:core:protocol`, `:core:transport`, `:app`.
- **Lint Errors:** 0.
- **Unit Tests:** 350+ passing.
- **Result:** `=== Verification Complete: ALL CHECKS PASSED ===` (Exit Code 0).

---

## 5. Physical Emulator Verification (`Pixel_10` AVD / Android 17 / API 37)

All key UI states were executed and captured on a live `Pixel_10` AVD running Android 17 (API 37) on a 16k-page-size Linux kernel:

### 5.1 Polished Home Screen
- **Screenshot:** `docs/testing/evidence/screenshots/glide_emulator_home_polished.png`
- **Verification:** Horizontal brand lockup with clear space, subtitle *"Fast, private, local device sharing"*, subtle cyan ambient wash, privacy reassurance card, 56dp "Send files" and "Receive files" buttons, and recent activity card.

![Polished Home Screen](screenshots/glide_emulator_home_polished.png)

### 5.2 Active Transfer Banner on Home
- **Screenshot:** `docs/testing/evidence/screenshots/glide_emulator_home_active_transfer.png`
- **Verification:** Dynamic "Transfer in progress" card showing recipient device name, file count, speed, linear progress bar, and 48dp "Resume" button.

![Home Active Transfer Banner](screenshots/glide_emulator_home_active_transfer.png)

### 5.3 Discovery Searching State
- **Screenshot:** `docs/testing/evidence/screenshots/glide_emulator_discovery_searching.png`
- **Verification:** "Select recipient" app bar, "Searching for nearby devices..." header, 2.4s low-amplitude tonal breathing pill, zero radar or ripple graphics.

![Discovery Searching](screenshots/glide_emulator_discovery_searching.png)

### 5.4 Discovered Peer List & Selected State
- **Screenshot:** `docs/testing/evidence/screenshots/glide_emulator_discovery_peers.png`
- **Verification:** Distinct device glyphs (Phone, Tablet, Computer), device names, availability badge. Selected peer card displays primary container background, 2dp elevation, and 2dp outline border.

![Discovered Peers](screenshots/glide_emulator_discovery_peers.png)

### 5.5 Discovery Blocked State (Bluetooth Disabled)
- **Screenshot:** `docs/testing/evidence/screenshots/glide_emulator_discovery_blocked.png`
- **Verification:** Clear error diagnostics card, "Bluetooth is turned off" guidance, and prominent "Open Settings" action.

![Discovery Blocked State](screenshots/glide_emulator_discovery_blocked.png)

### 5.6 Discovery Timeout State (120 Seconds)
- **Screenshot:** `docs/testing/evidence/screenshots/glide_emulator_discovery_timeout.png`
- **Verification:** "No devices found" card after 120s timeout, troubleshooting tips, "Cancel" and "Retry" actions.

![Discovery Timeout State](screenshots/glide_emulator_discovery_timeout.png)

### 5.7 Receive Readiness Screen
- **Screenshot:** `docs/testing/evidence/screenshots/glide_emulator_receive_readiness.png`
- **Verification:** "Ready to receive" headline, device visibility status badge, local device name, 120s discoverability countdown timer with 2.4s tonal breathing badge, privacy note, and prominent 56dp "Stop receiving" button.

![Receive Readiness Screen](screenshots/glide_emulator_receive_readiness.png)

---

## 6. Conclusion & Next Steps

Task `GLIDE-037` has satisfied all design system, user flow, accessibility, and quality checklist criteria. The entry and discovery surfaces of the application deliver a polished, calm, and trustworthy local sharing experience on modern Android devices.

- **Completed Task:** `GLIDE-037` — Polish Home and discovery surfaces (P2, Milestone M6)
- **Next Task:** `GLIDE-038` — Polish Incoming Transfer Approval Surface (P2, Milestone M6)
