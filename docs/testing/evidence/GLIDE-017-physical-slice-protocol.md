# GLIDE-017: First Physical Vertical Slice Test Protocol

## 1. Objective & Requirements
- **Task:** `GLIDE-017` — Run first physical vertical slice.
- **Milestone:** M3 — First real device transfer.
- **Specification:** [`docs/TODO_MASTER.md`](../../TODO_MASTER.md), [`docs/testing/GLIDE_TEST_STRATEGY.md`](../GLIDE_TEST_STRATEGY.md), and [`docs/ASSUMPTIONS.md`](../../ASSUMPTIONS.md) (Assumption `A06`).
- **Prerequisites:**
  - Milestone tasks `GLIDE-001` through `GLIDE-016` completed and verified.
  - Two physical Android devices with Google Play Services (GMS) running Android 8.0+ (API 26+).
  - Internet access (Wi-Fi internet & mobile data) **DISABLED** on both devices during testing.
  - Wi-Fi radio and Bluetooth radio **ENABLED** on both devices.

---

## 2. Automated Tooling & Fixture Preparation

1. **Generate Test Fixtures:**
   Run the PowerShell fixture generator:
   ```powershell
   pwsh -File .\tools\generate-slice-fixtures.ps1 -Generate1GB
   ```
   - **10 MB fixture:** `build/fixtures/glide-test-10mb.bin`  
     Expected SHA-256: `38396E6DCF327C8BB3BDFE4C78CEE671AC155CCAD87016BBEBF7D32ECB659F04`
   - **1 GB fixture:** `build/fixtures/glide-test-1gb.bin`

2. **Deploy to Physical Devices:**
   Connect both devices via USB or Wireless ADB, then run:
   ```powershell
   pwsh -File .\tools\install-slice-devices.ps1
   ```
   This will:
   - Identify both connected devices, their models, Android versions, and GMS versions.
   - Install `app/build/outputs/apk/debug/app-debug.apk` onto each phone.
   - Push the test fixture files to `/sdcard/Download/` on both phones.

---

## 3. Physical Test Matrix Execution

### Test Group 1: 10 MB Transfers (5 runs per direction)
- **Runs 1–5 (Device A $\to$ Device B):**
  1. Launch Glide on Device A and Device B.
  2. Device B taps **Receive files** (Advertising Nearby).
  3. Device A taps **Send files**, selects `glide-test-10mb.bin`, and discovers Device B.
  4. Both devices display the SAS (Short Authentication String) 4-digit pairing match.
  5. User verifies match on both devices.
  6. Device B receives consent offer for `glide-test-10mb.bin` (10.0 MB) and taps **Accept file**.
  7. Both devices display live transfer progress and SHA-256 verification.
  8. Upon completion, Device B taps **Open file** to view content via `FileProvider`.
  9. Record transfer duration and inspect history record on both devices.

- **Runs 6–10 (Device B $\to$ Device A):**
  - Reverse roles and repeat steps 1–9.

### Test Group 2: Large File Transfer (1 GB payload)
- **Run 11 (Device A $\to$ Device B):** Transfer `glide-test-1gb.bin`. Verify continuous streaming, memory bounds, and SHA-256 verification pass.
- **Run 12 (Device B $\to$ Device A):** Reverse roles with 1 GB payload.

### Test Group 3: Failure & Cancellation Recovery
- **Run 13 (Consent Rejection):** Device A offers file; Device B taps **Decline**. Verify both devices transition to `Cancelled` and no staging file remains on Device B.
- **Run 14 (Sender Cancellation):** Device A starts sending stream; Device A taps prominent 56dp **Cancel transfer**. Verify Device B discards partial file and both transition to `Cancelled`.
- **Run 15 (Network Radio Sever):** Mid-transfer, disable Bluetooth/Wi-Fi on Device A. Verify Device B times out gracefully, discards unverified partial data, and reports `Failed` without crashing.

---

## 4. Hardware Evidence Record Template

| Field | Device A (Sender / Receiver) | Device B (Receiver / Sender) |
|---|---|---|
| Manufacturer & Model | *(e.g., Google Pixel 8)* | *(e.g., Samsung Galaxy S23)* |
| Android OS & API Level | *(e.g., Android 14, API 34)* | *(e.g., Android 13, API 33)* |
| GMS Core Version | *(e.g., 24.32.33)* | *(e.g., 24.32.33)* |
| RAM Class | *(e.g., 8 GB)* | *(e.g., 8 GB)* |
| Available Storage | *(e.g., 64 GB free)* | *(e.g., 128 GB free)* |
| Wi-Fi / Bluetooth Radio | Wi-Fi Direct / BLE | Wi-Fi Direct / BLE |
| Internet Status | **Disabled** (Airplane Mode + Wi-Fi/BT enabled) | **Disabled** (Airplane Mode + Wi-Fi/BT enabled) |

---

## 5. Acceptance Criteria Checklist
- [ ] 10 MB payload transfer completes with identical source and received SHA-256 hashes (`38396E6DCF327C8BB3BDFE4C78CEE671AC155CCAD87016BBEBF7D32ECB659F04`).
- [ ] 1 GB payload transfer completes with zero memory leaks or OOM exceptions.
- [ ] Zero hash mismatches across all 10 standard test runs.
- [ ] Receiver "Open file" successfully launches viewer via FileProvider for verified files.
- [ ] History records survive process termination / app relaunch on both devices.
- [ ] Unverified/cancelled/rejected files are quarantined/purged and cannot be opened.
