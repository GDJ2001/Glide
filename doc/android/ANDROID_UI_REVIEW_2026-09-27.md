# Android interface review — 27 September 2026

## Scope and device

Reviewed the Android Compose presentation layer, feature state/ViewModels, navigation, theme/design-system helpers, and QR camera integration. The [source inventory](ui-review-2026-09-27/source-inventory.csv) lists 55 files in these areas; `MainActivity`, camera helpers, and related tests were inspected separately. This is a UI review and repair, not certification of the entire transfer engine or production release.

Manual screenshots were captured from the running **Pixel_10 emulator**, serial `emulator-5554`, API 37, 1080×2424, density 420. Checks covered light/dark appearance, portrait/landscape, and system font scales 1.0, 1.5 and 2.0. The final instrumentation runs use normal and 200% text size. The attached physical device identifies as OPPO CPH2371, not Pixel; it was not modified during this review.

The existing debug-state launch mechanism supplied repeatable presentation states. Those screenshots prove rendering of populated, error and transfer states; they do **not** prove actual peer discovery, data transfer, integrity verification or resume. Camera preview, scanner reopening, and the Android settings recovery action were exercised through the actual UI.

## Repairs

| Finding | Repair | Evidence |
|---|---|---|
| Dark preference could retain the light logo and black status icons when the system itself used light mode. | Expose the resolved Glide theme to branding; one route-aware navigation-shell owner applies system-bar contrast. | [Before](ui-review-2026-09-27/before/theme_dark-dark.png), [after](ui-review-2026-09-27/after/home-dark-large.png) |
| Transfer peer header consumed the entire row and hid the batch count. | Constrain the peer column with weight and let transfer metrics wrap. | [Before](ui-review-2026-09-27/before/transfer_active_multi-large.png), [after](ui-review-2026-09-27/after/transfer-active-large.png) |
| Pairing endpoint badge competed with the device name; verification digits assumed fixed narrow widths. | Give the peer column a bounded share; distribute digit cells across available width and allow vertical growth. | [200% pairing](ui-review-2026-09-27/after/verification-font200.png) |
| Incoming-offer timer crowded its title; PPTX badge clipped its last letter. | Separate the timer from the heading; use a padded badge with minimum rather than fixed dimensions. | [Before](ui-review-2026-09-27/before/offer_incoming_multi-large.png), [after](ui-review-2026-09-27/after/offer-multi-large.png) |
| History detail size and Close action collapsed into vertical letters at 200% text size. | Wrap summary metadata and action groups; keep minimum accessible sizes without three equal-width action columns. | [Before](ui-review-2026-09-27/before/history-detail-font200.png), [after](ui-review-2026-09-27/after/history-detail-font200-fixed.png) |
| File-picker actions truncated “Add photos” and “Add folder”. | Use wrapping, intrinsically sized actions and a wrapping selection summary. | [Before](ui-review-2026-09-27/before/selection-font200.png), [after](ui-review-2026-09-27/after/selection-font200-fixed.png) |
| Oversized selected batches had insufficient explanation for the disabled send action. | Render the total-size limit reason beside the selection summary; maintain the existing validation limit. | Source: `SelectionScreen.kt` |
| Discovery still said “Searching” after timing out or becoming blocked. | Derive the heading from discovery state. | [Before](ui-review-2026-09-27/before/discovery_timeout-large.png), [after](ui-review-2026-09-27/after/discovery-timeout-font200.png) |
| QR and browser-sheet headers could crowd their dismiss controls; diagnostic values could overlap labels. | Constrain the content columns and preserve space for actions/values. | Source: `QrInvitationSheet.kt`, `WebShareSheet.kt`, `SettingsScreen.kt` |
| Scanner inherited ordinary-screen padding and light system bars around its black camera surface. | Give the scanner its own safe drawing insets and skip ordinary navigation chrome; use light bar icons on the camera route, including recreation. | [Before](ui-review-2026-09-27/before/scanner-permission-light.png), [after](ui-review-2026-09-27/after/scanner-permission-light.png) |
| Landscape scanner guidance overlapped the Paste invitation button. | Measure the top/bottom controls, reserve their space, resize the guide to the remaining area, and omit redundant guidance text in short windows. | [Before](ui-review-2026-09-27/before/scanner-landscape-font200.png), [after](ui-review-2026-09-27/after/scanner-landscape-font200-fixed.png) |
| Camera could remain unbound after background/foreground; asynchronous callbacks could update a departed scanner. | Let CameraX manage lifecycle pause/resume, unbind at disposal, guard late callbacks, deliver decoder results on the main executor, and rebuild the preview on explicit retry. | [Reopened live preview](ui-review-2026-09-27/after/scanner-camera-resumed.png) |
| Failed camera startup was silent, camera-error actions did not match their labels, and denied LAN permission could leave “Connecting” indefinitely. | Report camera failure; correctly wire Paste/Try again; terminate blocked connecting state with an actionable error. Preserve a valid consent prompt against late camera errors. | `QrScannerScreen.kt`, `QrPairingViewModel.kt`, navigation wiring; focused ViewModel tests |
| Receive “Open settings” returned to Home. | Share the appropriate Bluetooth/Wi-Fi/VPN/app-settings recovery behavior with discovery. | [Blocked receive](ui-review-2026-09-27/after/receive-blocked.png), [actual settings activity](ui-review-2026-09-27/checks/receive-settings-recovery.txt) |
| Home always claimed “No recent transfers”, regardless of stored history. | Replace the unsupported empty-state claim with a working link to the actual history screen. | [Final Home](ui-review-2026-09-27/after/home-light-font200.png) |

Existing branding and transport/protocol decisions were preserved. No application dependencies were added. The only added production helper centralizes an already-existing settings action. A receive-blocked debug fixture was added to exercise that recovery screen.

## Manual coverage

Screens inspected: Home, settings and diagnostics, populated history and available/missing-file detail states, selected files, discovery peers/timeout, receive readiness/blocked state, pairing verification, incoming offers, active multi-file transfer, completion and interruption states, QR permission education, live camera and paste dialog. Landscape navigation rails and scroll behavior were checked for settings and active transfer.

- [Light Home at 200%](ui-review-2026-09-27/after/home-light-font200.png)
- [Dark receive at 200%](ui-review-2026-09-27/after/receive-dark-font200.png)
- [Landscape settings at 200%](ui-review-2026-09-27/after/settings-landscape-font200.png)
- [Landscape transfer at 200%](ui-review-2026-09-27/after/transfer-landscape-font200.png)
- [Paste dialog](ui-review-2026-09-27/after/scanner-paste.png)

Large text legitimately increases scrolling. The requirement is readable content and reachable actions, not forcing an entire screen into one viewport. Screenshots were taken throughout the repairs; some demonstrate individual fixes before later unrelated adjustments. Final binary/test evidence is recorded in the checks folder.

## Automated verification

Commands run from `android/`, with `ANDROID_HOME` and `ANDROID_SDK_ROOT` pointing at the installed Android SDK:

```powershell
.\gradlew.bat :android:app:assembleDebug :android:app:assembleDebugAndroidTest `
  :android:app:testDebugUnitTest --tests 'dev.glide.app.ui.*' `
  :android:app:lintDebug
.\gradlew.bat :android:designsystem:testDebugUnitTest
```

The focused app UI unit suite contains **257 tests**, all passing, including **18 QR-pairing ViewModel tests**. The design-system unit suite contains **26 tests**, all passing. Debug application/test APK builds and Android lint passed. Existing lint warnings remain; this is not a zero-warning claim.

New instrumentation checks assert actual text layouts for the batch counter, history size/Close actions and complete selection-action labels, plus theme-aware system bars and scanner recreation. Existing navigation/accessibility tests were updated to match the current visible Home content; lifecycle tests cover rotation, back and background/foreground. Tests are run specifically on `emulator-5554`, avoiding the connected physical handset.

Final normal-font instrumentation: **15/15 passed**. See [instrumentation output](ui-review-2026-09-27/checks/instrumentation-normal.log), [build/lint output](ui-review-2026-09-27/checks/build-lint.log), [focused unit-test output](ui-review-2026-09-27/checks/focused-unit-tests.log), and [exact APK hashes](ui-review-2026-09-27/checks/artifacts.txt). No source changes follow this binary qualification; the remaining edits are documentation/evidence.

Final **200% font instrumentation: 5/5 passed**, including scanner activity recreation. See [large-text instrumentation output](ui-review-2026-09-27/checks/instrumentation-font200.log). Font scale was restored to 1.0 and automatic rotation restored after manual verification.

The full app unit suite was also run during this review: **649 tests, 16 failures, 3 skipped**. See the [unfiltered log](ui-review-2026-09-27/checks/full-app-unit-tests.log). The failures belong to existing static architecture/privacy/security/release-gate tests, including repository-root discovery that resolves an Android subdirectory and then constructs nonexistent nested `android/app` paths. The historical production approval test fails separately. These were not suppressed or marked complete, and their failures prevent a claim that all repository checks or release security gates pass.

| Failing class | Failures |
|---|---:|
| `ForegroundMvpPrivacyAuditTest` | 1 |
| `ArchitectureRulesTest` | 5 |
| `ProductionReleaseGateTest` | 1 |
| `AndroidSecurityHardeningTest` | 5 |
| `SecurityGateAcceptanceTest` | 4 |

## Reproduce screenshots and final UI tests

```powershell
$adb = 'C:\Users\GDJ\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-debug.apk
& $adb -s emulator-5554 install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $adb -s emulator-5554 shell settings put system font_scale 2.0
& $adb -s emulator-5554 shell am start -W -n dev.glide.app.debug/dev.glide.app.MainActivity `
  --es debug_state history_detail_available --es debug_theme light
# Wait for actual application content before capturing; avoid a splash-only screenshot.
& $adb -s emulator-5554 shell screencap -p /sdcard/glide-ui.png
& $adb -s emulator-5554 pull /sdcard/glide-ui.png ./glide-ui.png
& $adb -s emulator-5554 shell am instrument -w `
  -e class dev.glide.app.ui.VisualRegressionTest `
  dev.glide.app.debug.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s emulator-5554 shell settings put system font_scale 1.0
```

Fixtures are gated by the application's debuggable flag; do not use them as production-transfer evidence.

## Limits and follow-up

1. Resolve the 16 full-suite failures with correct checkout-root detection and an evidence-based historical release-record review. Do not turn missing evidence into passing tests.
2. Physically verify phone↔PC QR scanning, consent and transfer on the same router. An emulator camera preview does not establish optical decode reliability on the user's phone.
3. Run TalkBack, RTL, extreme display-size, foldable and lower-API/OEM qualification before release. Semantics tests and one Pixel emulator do not replace those checks.
4. Home now links truthfully to History; an actual recent-session preview would be a separate feature requiring observed history state.
5. The diagnostics/browser-sheet header fixes were reviewed in source; full browser-transfer functionality is outside this UI pass. UI screenshot fixtures do not certify transfer, storage, permission or background-execution behavior.

Camera lifecycle rationale: [official CameraX architecture](https://developer.android.com/media/camera/camerax/architecture), checked 27 September 2026. CameraX lifecycle binding manages start/stop without manually unbinding every pause.

Related: [permission matrix](PERMISSION_MATRIX.md), [storage/background design](STORAGE_AND_BACKGROUND.md), [Windows QR repair](../windows/QR_PAIRING_FIX_2026-09-27.md).
