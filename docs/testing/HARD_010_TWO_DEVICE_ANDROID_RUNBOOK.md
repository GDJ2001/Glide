# HARD-010 — Two-Physical-Device Android Production Journey Runbook

**Status:** PREPARED / NOT EXECUTED  
**Evidence authority:** This document defines the physical gate. It is not evidence that the gate has passed.

HARD-010 requires two real Android devices. Emulator, loopback, JVM, screenshot-only, or historical GLIDE evidence cannot satisfy this task.

## 1. Prerequisites

Do not execute the acceptance matrix until all of the following are true:

- HARD-008 and HARD-009 have a green current-head build/test/lint verification and can be promoted from `REVIEW_REQUIRED`.
- The candidate APK was built from the exact repository commit being tested.
- Two physical Android devices are attached through ADB.
- Both devices have Google Play Services for Nearby testing.
- Both devices support the LAN path under test (Android 10 / API 29+ for the current LAN implementation).
- Prefer two different OEMs. Record the actual limitation if only one OEM is available.
- Use generated non-sensitive fixtures only.
- Give the receiver enough free storage for repeated 1 GiB tests.
- For LAN runs, both devices must be on the same eligible local Wi-Fi/LAN path and VPN must be disabled.
- For Nearby runs, required Bluetooth/Wi-Fi/Nearby permissions and radios must be available.

The harness rejects emulator/ranchu/goldfish devices.

## 2. Candidate preparation

Build the exact candidate using the repository's normal Android verification path. Once the APK exists, prepare the two devices:

```powershell
pwsh -NoProfile -File .\tools\hard-010-physical-journey.ps1 `
  -Mode Prepare `
  -ApkPath .\app\build\outputs\apk\debug\app-debug.apk `
  -EvidenceDir .\build\hard-010\<candidate-sha>
```

Optional explicit device selection:

```powershell
  -DeviceA <adb-serial-a> -DeviceB <adb-serial-b>
```

Prepare performs these checks/actions:

1. requires at least two connected **physical** devices;
2. generates deterministic 10 MiB and 1 GiB fixtures if missing;
3. installs the exact APK on both devices;
4. pushes both fixtures to `/sdcard/Download/`;
5. independently hashes the fixtures on the host and both devices;
6. records:
   - repository HEAD;
   - APK SHA-256;
   - manufacturer/model;
   - Android release/API;
   - security patch;
   - build fingerprint;
   - GMS version;
   - approximate RAM;
   - data-filesystem information;
7. writes `environment.json` and `required-matrix.csv`.

If repository HEAD is `UNKNOWN`, HARD-010 cannot be signed off.

## 3. Required matrix

All twelve rows are required.

| Run ID | Transport | Direction | Payload | Scenario |
| --- | --- | --- | --- | --- |
| `nearby-10mb-a-to-b` | Nearby | A → B | 10 MiB | success |
| `nearby-10mb-b-to-a` | Nearby | B → A | 10 MiB | success |
| `nearby-1gb-a-to-b` | Nearby | A → B | 1 GiB | success |
| `nearby-1gb-b-to-a` | Nearby | B → A | 1 GiB | success |
| `nearby-reject-a-to-b` | Nearby | A → B | 10 MiB | receiver reject |
| `nearby-cancel-a-to-b` | Nearby | A → B | 1 GiB | mid-transfer cancel |
| `lan-10mb-a-to-b` | LAN QR | A → B | 10 MiB | success |
| `lan-10mb-b-to-a` | LAN QR | B → A | 10 MiB | success |
| `lan-1gb-a-to-b` | LAN QR | A → B | 1 GiB | success |
| `lan-1gb-b-to-a` | LAN QR | B → A | 1 GiB | success |
| `lan-reject-a-to-b` | LAN QR | A → B | 10 MiB | receiver reject |
| `lan-cancel-a-to-b` | LAN QR | A → B | 1 GiB | mid-transfer cancel |

Run IDs must match this table exactly so `-Mode Audit` can evaluate the evidence.

## 4. Starting evidence capture

Before touching the Glide UI for each matrix row:

```powershell
pwsh -NoProfile -File .\tools\hard-010-physical-journey.ps1 `
  -Mode BeginRun `
  -EvidenceDir .\build\hard-010\<candidate-sha> `
  -RunId <run-id> `
  -Transport nearby|lan `
  -Direction A-to-B|B-to-A `
  -Payload 10mb|1gb `
  -Scenario success|reject|cancel
```

BeginRun:

- clears logcat on both devices;
- records app-private received/staging state when `run-as` is available;
- captures before screenshots;
- records the exact sender/receiver serials and candidate identity.

Do not start the UI journey before BeginRun finishes.

## 5. Nearby success journey

For a Nearby success row:

1. Receiver opens Glide and taps **Receive files**.
2. Sender taps **Send files**.
3. Sender chooses the exact fixture named by the matrix row from Downloads.
4. Sender enters production discovery.
5. Sender selects the intended physical receiver.
6. Both devices must show the same Nearby authentication digits.
7. Human tester compares the digits and explicitly confirms on both devices.
8. Receiver must see the complete offer and explicitly accept it.
9. Confirm no bulk progress appears before receiver acceptance.
10. Let the transfer finish without changing route.
11. Both sides must show a truthful terminal success state only after SHA-256 verification/receipt semantics complete.
12. Receiver taps **Open file** and verifies Android opens the committed Glide file.
13. Open History on both devices and verify the completed session/item is present.
14. Do not manually copy or rename the private received file before EndRun.

For EndRun, pass:
- `-SasOrPinVerified`
- `-OfferConsentVerified`
- `-OpenVerified`
- `-HistoryVerified`
- `-ObservedResult pass`

The harness independently requires a **new** receiver-private file with the exact source SHA-256.

## 6. LAN QR success journey

For a LAN success row:

1. Confirm both devices are on the same eligible local network and VPN is off.
2. Receiver opens Glide → **Receive files** → **Show QR code**.
3. The QR must be created from the live listener. Do not use screenshots or an old invitation.
4. Sender taps **Send files** and selects the exact fixture.
5. Sender enters discovery and chooses **Scan QR**.
6. Scan the receiver's live QR. Paste may be used only for a separately recorded accessibility/manual-paste variant; the required matrix uses camera QR.
7. Sender reviews the invitation confirmation and explicitly continues.
8. A successful connection must be the pinned-TLS/bootstrap LAN route; do not fall back to Nearby after an authentication failure.
9. Receiver sees the complete offer and explicitly accepts it.
10. Confirm no bulk bytes precede offer consent.
11. Let the transfer complete.
12. Receiver taps **Open file**.
13. Verify History on both devices.
14. Do not modify the receiver's private committed file before EndRun.

For the harness, `-SasOrPinVerified` means the LAN authentication journey was explicitly verified: fresh QR → confirmation → pinned TLS/bootstrap succeeded. It does **not** mean a human manually reads the raw SPKI fingerprint.

Successful LAN runs use the same `-OfferConsentVerified`, `-OpenVerified`, `-HistoryVerified`, and `-ObservedResult pass` flags as Nearby.

## 7. Reject journey

Use the 10 MiB payload.

1. Complete transport authentication/pairing.
2. Receiver must see the complete offer.
3. Receiver explicitly selects **Decline/Reject**.
4. Verify both devices terminate without starting bulk transfer.
5. Verify receiver cannot open an unverified file.
6. Do not retry inside the same run.

EndRun should use:

```powershell
-ObservedResult pass -SasOrPinVerified
```

The harness requires:

- no new receiver private file matching the payload SHA-256;
- receiver staging directory empty afterward;
- no Glide fatal crash/ANR signature.

## 8. Mid-transfer cancel journey

Use the 1 GiB payload to make the active-transfer window observable.

1. Complete authentication.
2. Receiver explicitly accepts the offer.
3. Wait until real transfer progress is visible and non-zero.
4. Sender taps **Cancel transfer** while bytes are still moving.
5. Verify both devices reach a cancelled/interrupted terminal state, never success.
6. Receiver must not be able to open an unverified partial.
7. Wait for terminal cleanup to finish before EndRun.

EndRun should use:

```powershell
-ObservedResult pass -SasOrPinVerified -OfferConsentVerified
```

The harness requires:

- no new committed receiver file matching the full payload SHA-256;
- receiver staging directory empty afterward;
- no Glide fatal crash/ANR signature.

## 9. Ending evidence capture

Immediately after the observed terminal state:

```powershell
pwsh -NoProfile -File .\tools\hard-010-physical-journey.ps1 `
  -Mode EndRun `
  -EvidenceDir .\build\hard-010\<candidate-sha> `
  -RunId <run-id> `
  -ObservedResult pass `
  [verification switches] `
  -Notes "<short factual observation>"
```

EndRun records:

- after screenshots from both devices;
- logcat from both devices;
- receiver private-file SHA-256 state when available;
- staging state;
- whether a **new** file matches the expected payload hash;
- fatal Glide crash/ANR signature detection;
- the human-observed verification flags.

If private inspection is unavailable because the candidate is non-debuggable, the run does not satisfy this harness as written. Use a separately reviewed independent receiver-file extraction/hash method and update the evidence protocol rather than weakening the cryptographic gate.

## 10. Final structural audit

After all twelve runs:

```powershell
pwsh -NoProfile -File .\tools\hard-010-physical-journey.ps1 `
  -Mode Audit `
  -EvidenceDir .\build\hard-010\<candidate-sha>
```

Audit fails unless every required row exists and satisfies its evidence contract.

For every success run it requires:

- human observed pass;
- authentication verified;
- offer consent verified;
- private inspection available;
- a new receiver private file matching the exact source SHA-256;
- empty receiver staging directory after completion;
- Open verified;
- History verified;
- no Glide fatal crash/ANR signature.

For reject/cancel it requires:

- human observed pass;
- applicable authentication/consent flags;
- private inspection available;
- no new full payload file committed;
- empty receiver staging directory;
- no Glide fatal crash/ANR signature.

Audit success is necessary but **not sufficient** to mark HARD-010 complete. A human must still review the screenshots/logs and confirm the evidence belongs to the recorded candidate/devices.

## 11. Evidence promotion

Do not commit generated binaries, raw fixture files, or large unredacted log archives to Git.

After a successful physical campaign:

1. redact logs only where necessary, without removing relevant failures;
2. create a concise evidence report under `docs/testing/evidence/`;
3. record:
   - exact repository SHA;
   - APK SHA-256;
   - both device identities;
   - all 12 run results;
   - fixture SHA-256 values;
   - discovery/pairing observations;
   - transfer/hash/open/history results;
   - reject/cancel cleanup;
   - any failures and reruns;
4. reference external/raw evidence paths where retained;
5. only then update `docs/TODO_MASTER.md`.

A failed run is evidence. Do not silently exclude it or replace it with a later pass without documenting both attempts.
