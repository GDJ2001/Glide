# GLIDE-046 Accessibility and RTL Remediation — Delivery Report

**Date:** 2026-09-13  
**Milestone:** M7 — Adaptive Layout & Accessibility  
**Status:** COMPLETE ✅

---

## 1. Scope

Complete accessibility, bidirectional (RTL) remediation, and screen-reader optimizations across Glide Android per `GLIDE_DESIGN_SYSTEM.md`, `GLIDE_USER_FLOWS.md`, and `UI_QUALITY_CHECKLIST.md`:
- **Bidirectional (RTL) Isolation:** Protect mixed-script filenames and user device names with Unicode First Strong Isolates (`\u2068` / `\u2069`). Protect numerical pairing and verification codes with Left-to-Right Isolates (`\u2066` / `\u2069`) to guarantee left-to-right digit sequence irrespective of system layout direction.
- **RTL Code Inversion Safety:** Enforce `LayoutDirection.Ltr` on verification digit rows via `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr)` so RTL locales never invert digit visual positions.
- **Screen Reader (TalkBack) Semantics:**
  - **Discovered Peers:** Announce device name, device type, availability status, selected state, and explicit click label (`"Connect to <Peer>"`).
  - **Pairing Verification Code:** Format pairing digits individually with comma separation (`4, 8, 2, 9, 1, 6`) and explicit comparison instruction (`"Verification code: 4, 8, 2, 9, 1, 6. Compare with code on <Peer>."`). Suppress individual digit-box accessibility focus nodes (`clearAndSetSemantics { }`) to prevent screen reader users having to swipe through 6 individual numbers without context.
  - **Progress Announcements:** Throttle rapid byte updates ($50\text{Hz}$ / $5\text{Hz}$ chattering) using `AccessibilityProgressThrottler`, firing announcements strictly at $10\%$ milestone boundaries ($0\%, 10\%, 20\%, \dots, 100\%$) or on phase transitions.
  - **Consent Separation:** Maintain strict semantic separation between pairing code verification (authenticating devices) and offer consent (accepting files into local storage).
  - **Two-Pane History Selection:** Announce `selected = true/false` and explicit selection/detail actions on history session cards.
- **Touch Targets ($\ge 48\text{dp}$):** Enforce minimum $48\text{dp}$ touch target dimensions across all interactive buttons, filter chips, icon buttons, and cards.
- **Dynamic Text Scaling ($200\%$):** Ensure scrollable containers (`verticalScroll`, `LazyColumn`) and adaptive layouts preserve reachability and eliminate text clipping or truncated buttons under $200\%$ system font scale.
- **WCAG AA Contrast:** Verify text and interactive UI elements satisfy contrast ratios $\ge 4.5:1$ (normal body text) and $\ge 3.0:1$ (large text, icons, accents).

---

## 2. Files Created / Modified

| File | Change |
|------|--------|
| `designsystem/.../BidiIsolation.kt` | **CREATED** — Unicode directional isolate utilities: `FSI` (`\u2068`), `LRI` (`\u2066`), `RLI` (`\u2067`), `PDI` (`\u2069`), `String.isolateBidi()`, `String.isolateBidiCode()`, and `BidiIsolation.stripIsolates()`. |
| `designsystem/.../AccessibilityProgressThrottler.kt` | **CREATED** — Progress announcement throttler restricting TalkBack events to 10% milestone buckets or phase transitions. |
| `designsystem/.../BidiIsolationTest.kt` | **CREATED** — Unit test suite verifying FSI/PDI wrapping, LRI code isolation, isolate stripping, and 10% progress throttling. |
| `app/.../ui/accessibility/AccessibilityAndRtlAcceptanceTest.kt` | **CREATED** — 10 comprehensive acceptance tests verifying bidi isolation, code isolation, TalkBack digit formatting, progress throttling, peer card semantics, history card selection semantics, 48dp touch targets, strict consent separation, WCAG AA contrast, and 200% font scale container safety. |
| `app/.../ui/pairing/PairingVerificationCard.kt` | **MODIFIED** — Applied `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr)` to digit row; applied `clearAndSetSemantics { }` to individual boxes; provided unified spoken description with individual digits and comparison instruction; isolated peer name. |
| `app/.../ui/transfer/TransferProgressCard.kt` | **MODIFIED** — Integrated `AccessibilityProgressThrottler` with polite live region semantics; isolated item and peer names. |
| `app/.../ui/transfer/OfferConsentCard.kt` | **MODIFIED** — Isolated peer and item names in semantic descriptions; verified 48dp button targets. |
| `app/.../ui/transfer/TransferResultCard.kt` | **MODIFIED** — Isolated file and peer names; ensured vertical action button stacking for large text safety. |
| `app/.../ui/history/HistoryScreen.kt` | **MODIFIED** — Added `heightIn(min = 48.dp)` to filter chips; added `isSelected: Boolean`, `selected` semantics, and `isolateBidi()` to `HistorySessionCard`. |
| `app/.../ui/history/HistoryDetailDialog.kt` | **MODIFIED** — Added `isolateBidi()` to peer and file names; enforced `heightIn(min = 48.dp)` on Open and Export buttons. |
| `app/.../ui/discovery/ReceiveReadinessScreen.kt` | **MODIFIED** — Applied `isolateBidi()` to local device name and incoming peer name across status badges and semantics. |
| `app/.../ui/selection/SelectionScreen.kt` | **MODIFIED** — Applied `isolateBidi()` to file names in Ready card and file list; enforced 48dp height on Remove buttons. |

---

## 3. Key Invariants & Architectural Remediation

### 3.1 Bidi Isolation Mechanics
Filenames containing Hebrew, Arabic, or mixed Latin/RTL scripts can cause bidirectional text reordering across file extensions or punctuation (e.g. `דו״ח.pdf` rendering as `pdf.דו״ח` or corrupting surrounding sentences).
- **Filenames and Peer Names:** Wrapped with `\u2068` (First Strong Isolate) and `\u2069` (Pop Directional Isolate). The internal directionality of the string is computed independently without leaking into adjacent text.
- **Verification Codes:** Wrapped with `\u2066` (Left-to-Right Isolate) and `\u2069` (Pop Directional Isolate). This guarantees digits `482916` are always evaluated left-to-right even when embedded inside RTL text.

### 3.2 TalkBack Verification Code Formatting & RTL Order Inversion
- **Issue:** Screen readers by default pronounce 6 consecutive digits as a single large number ("four hundred eighty-two thousand nine hundred sixteen"). Furthermore, in RTL layouts (`LayoutDirection.Rtl`), Compose `Row` reverses horizontal order, visually inverting digits (`6 1 9 2 8 4`).
- **Remediation:**
  1. Wrapped the digit row in `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr)`.
  2. Applied `Modifier.clearAndSetSemantics { }` to individual digit boxes, eliminating 6 redundant swipe stops.
  3. Placed unified spoken description on the parent container:
     `"Verification code: 4, 8, 2, 9, 1, 6. Compare with code on <Peer>."`

### 3.3 Screen Reader Progress Throttling
- **Issue:** Transfer progress streams update at up to $50\text{Hz}$. Announcing each update to accessibility services floods the speech queue and renders TalkBack completely unusable.
- **Remediation:** `AccessibilityProgressThrottler` tracks the active phase and $10\%$ milestone buckets ($0\%, 10\%, 20\%, \dots, 100\%$). Intermediate byte increments return `null` and are discarded. Live region uses `LiveRegionMode.Polite` so announcements do not cut off other screen reader speech.

### 3.4 200% Font Scale Safety
All text surfaces use flexible vertical layouts inside `verticalScroll` or `LazyColumn`. Primary action buttons stack vertically where needed or use dynamic weights, preventing button clipping or out-of-bounds overflow.

---

## 4. Automated Test Evidence

### Design System Tests
```bash
.\gradlew.bat :designsystem:testDebugUnitTest
```
- `BidiIsolationTest`: 6/6 tests PASS.
- `PostureTest`: 4/4 tests PASS.
- Total design system tests: 10/10 PASS.

### App Accessibility Acceptance Tests
```bash
.\gradlew.bat :app:testDebugUnitTest --tests "dev.glide.app.ui.accessibility.*"
```
- `bidiFilenameIsolation_protectsMixedScriptAndRtlFilenames`: PASS
- `bidiCodeIsolation_preservesLeftToRightDigitOrderingInRtl`: PASS
- `pairingDigitsTalkBackFormatting_announcesIndividualDigitsWithComparisonInstruction`: PASS
- `accessibilityProgressThrottling_suppressesHighFrequencyJitterAndEmits10PercentMilestones`: PASS
- `discoveredPeerCard_providesCompleteAccessibilitySemantics`: PASS
- `historySessionCard_exposesExplicitTwoPaneSelectionSemantics`: PASS
- `interactiveTargets_enforceMinimum48dpTouchTargetBounds`: PASS
- `strictConsentSafety_separatesPairingVerificationAndOfferConsentSemantics`: PASS
- `colorContrastAndVisualDistinction_meetsWcagAaStandard`: PASS
- `textScalingAndScrollContainerSafety_supports200PercentFontScale`: PASS

### Full Verification Pipeline
```powershell
pwsh -File tools/verify-local.ps1
```
- **Actionable Tasks:** 139 executed/up-to-date.
- **Lint:** 0 errors, 0 warnings across all modules.
- **Result:** `=== Verification Complete: ALL CHECKS PASSED ===` (Exit code 0).

---

## 5. Emulator Visual Evidence (Pixel 10 / Android 17 / API 37)

- **200% Text Scaling Test:**
  - Command: `adb shell settings put system font_scale 2.0`
  - Artifact: `docs/testing/evidence/screenshots/glide_046_large_text_200.png`
  - Observation: Headline, body text, action buttons, reassurance card, and navigation bar properly scaled and wrapped; zero text truncation, zero overlapping elements, touch targets $>48\text{dp}$.
  - Cleanup: `adb shell settings put system font_scale 1.0` restored normal scale.
