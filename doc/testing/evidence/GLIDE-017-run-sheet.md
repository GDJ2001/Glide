# GLIDE-017: Vertical Slice Execution Run Sheet

**Task:** GLIDE-017: Run first physical vertical slice  
**Milestone:** M3 — First real device transfer  
**Date:** 2026-09-13  
**Environment:** Android Studio Emulator (`Pixel_10` AVD) & Physical Handset Readiness  

---

## 1. Test Environment Details

| Property | Value |
|---|---|
| **Device Identifier** | `emulator-5554` |
| **Model** | `sdk_gphone16k_x86_64` (AVD: `Pixel_10`) |
| **Android OS** | Android 17 (SDK API 37) |
| **Google Play Services (GMS)** | `26.22.36 (260800-937567277)` |
| **Application ID** | `dev.glide.app.debug` |
| **Version** | `0.1.0-alpha` (Version Code 1) |
| **Build Artifact** | [`app/build/outputs/apk/debug/app-debug.apk`](file:///d:/GITHub/Glide/app/build/outputs/apk/debug/app-debug.apk) (15.2 MB) |
| **Test Fixture Pushed** | `/sdcard/Download/glide-test-10mb.bin` |

---

## 2. Test Fixture Verification

- **File Name:** `glide-test-10mb.bin`
- **Expected Size:** `10,485,760 bytes` (10.0 MB)
- **Expected SHA-256:** `38396E6DCF327C8BB3BDFE4C78CEE671AC155CCAD87016BBEBF7D32ECB659F04`
- **Calculated on Device:** `38396e6dcf327c8bb3bdfe4c78cee671ac155ccad87016bbebf7d32ecb659f04`
- **Verification Status:** **PASS** (Cryptographic hash matched exactly)

---

## 3. Slice Execution Log & Visual Evidence

### Step 1: Deployment & Installation
- **Command:** `pwsh -File .\tools\install-slice-devices.ps1`
- **Result:** Successfully streamed APK install to `emulator-5554` and pushed `glide-test-10mb.bin` at 8.3 MB/s.

### Step 2: Home Screen Launch
- **Command:** `adb shell am start -n dev.glide.app.debug/dev.glide.app.MainActivity`
- **Result:** Rendered header ("Glide - Fast, private, local device sharing"), "Send files" primary CTA, "Receive files" secondary CTA, and "Recent activity" empty card.
- **Evidence:** Captured at [`docs/testing/evidence/screenshots/glide_emulator_home.png`](file:///d:/GITHub/Glide/docs/testing/evidence/screenshots/glide_emulator_home.png)

### Step 3: Receive Flow Initialisation
- **Action:** Tap "Receive files" button (`bounds="[53,607][1027,754]"`).
- **Result:** Navigated to Receive route, assigned session identifier `receive-sess`, initiated advertising status ("Initializing transfer..."). Back navigation operates cleanly.
- **Evidence:** Captured at [`docs/testing/evidence/screenshots/glide_emulator_receive.png`](file:///d:/GITHub/Glide/docs/testing/evidence/screenshots/glide_emulator_receive.png)

### Step 4: Send Flow & Storage Access Framework Integration
- **Action:** Return to Home and tap "Send files" (`bounds="[53,418][1027,565]"`).
- **Result:** Navigated to file selection screen ("Select a file to send").
- **Evidence:** Captured at [`docs/testing/evidence/screenshots/glide_emulator_send_select.png`](file:///d:/GITHub/Glide/docs/testing/evidence/screenshots/glide_emulator_send_select.png)

### Step 5: System File Picker & Fixture Selection
- **Action:** Tap "Choose file" (`bounds="[116,803][964,950]"`).
- **Result:** Launched system `DocumentsUI` picker, displayed `/sdcard/Download/glide-test-10mb.bin` with accurate file size and metadata.
- **Evidence:** Captured at [`docs/testing/evidence/screenshots/glide_emulator_file_picker.png`](file:///d:/GITHub/Glide/docs/testing/evidence/screenshots/glide_emulator_file_picker.png)

### Step 6: On-Device Digest & Metadata Resolution
- **Action:** Tap `glide-test-10mb.bin` in picker.
- **Result:** Returning URI parsed by `TransferViewModel`, extracting:
  - Name: `glide-test-10mb.bin`
  - Size: `10.0 MB (10485760 bytes)`
  - Type: `application/octet-stream`
  - SHA-256: `38396e6dcf327c8bb3bdfe4c78cee671ac155ccad87016bbebf7d32ecb659f04`
- **Evidence:** Captured at [`docs/testing/evidence/screenshots/glide_emulator_send_file_ready.png`](file:///d:/GITHub/Glide/docs/testing/evidence/screenshots/glide_emulator_send_file_ready.png)

### Step 7: Proceed to Transfer Session
- **Action:** Tap "Proceed to send" (`bounds="[53,1220][1027,1367]"`).
- **Result:** Smoothly transitioned to active transfer route with session `send-1853640` and progress indicator.
- **Evidence:** Captured at [`docs/testing/evidence/screenshots/glide_emulator_send_session.png`](file:///d:/GITHub/Glide/docs/testing/evidence/screenshots/glide_emulator_send_session.png)

### Step 8: Logcat & Stability Review
- **Inspection:** `adb logcat --pid=6396 -d`
- **Result:** 0 crashes, 0 uncaught exceptions, 0 Room schema violations.

---

## 4. Hardware Verification Gate Status

> [!NOTE]
> In strict compliance with `docs/development/GEMINI_ENGINEERING_RULES.md` (Rule #2 — Hardware evidence must never be fabricated), emulator smoke runs demonstrate full UI, SAF picker, and hashing integrity, but do **not** constitute physical RF/Wi-Fi Direct radio proof.
> 
> Physical radio-to-radio sign-off remains documented in [`docs/testing/evidence/GLIDE-017-physical-slice-protocol.md`](file:///d:/GITHub/Glide/docs/testing/evidence/GLIDE-017-physical-slice-protocol.md) and is ready for execution as soon as two physical handsets are connected via `.\tools\install-slice-devices.ps1`.
