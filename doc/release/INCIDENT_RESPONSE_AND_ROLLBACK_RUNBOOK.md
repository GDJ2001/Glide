# Production Incident Response & Rollback Runbook

**Document ID:** GLIDE-OPS-003-INCIDENT  
**Effective Date:** 2026-09-14  
**Milestone:** M12 — Production and maintenance  
**Task:** GLIDE-066: Establish incident and rollback operations  
**Authoritative Reference:** [`docs/release/GLIDE_RELEASE_PLAN.md`](GLIDE_RELEASE_PLAN.md) · [`docs/release/PRODUCTION_ROLLOUT_PROTOCOL.md`](PRODUCTION_ROLLOUT_PROTOCOL.md) · [`docs/DEFINITION_OF_DONE.md`](../DEFINITION_OF_DONE.md)  
**Status:** APPROVED & ACTIVE ✅  

---

## 1. Executive Summary & Operating Philosophy

This runbook establishes the formal operational procedures for incident triage, emergency distribution halts, privacy-preserving diagnostics, data-corruption escalation, forward-fix rollbacks, and recovery verification for **Glide** (`dev.glide.app`) during production distribution under **Milestone M12 (*Production and maintenance*)**.

### Operating Principles
1. **User Data Sanctity:** User files committed to public storage (`Environment.DIRECTORY_DOWNLOADS/Glide` or user-selected SAF tree URIs) are sacred and must **never** be deleted or corrupted by updates, hotfixes, or rollback procedures.
2. **Zero Content Inspection:** Glide is a private, direct local transfer application. Support and engineering personnel are strictly forbidden from requesting or inspecting user payload files, media contents, or unredacted filenames.
3. **Speed of Containment:** Critical safety anomalies (data corruption, consent bypass, unverified staging leakage) mandate an immediate distribution halt within **$\le 2$ hours**.
4. **Forward-Fix Rollback Exclusivity:** Because Android OS package managers and Google Play do not support client-side binary downgrades, all rollbacks are implemented as Play Console distribution halts followed by expedited forward-fix releases with monotonic `versionCode` increments.

---

## 2. Severity Classification Matrix & Response SLAs

All production anomalies, user reports, and Android Vitals regressions are classified into four severity tiers with enforceable response windows:

| Severity Level | Definition & Trigger Criteria | Immediate Action | Halt SLA | Hotfix SLA |
|:---:|---|---|:---:|:---:|
| **P0 (Blocker / Emergency)** | • Verified data corruption (hash mismatch committed to public storage)<br/>• Consent bypass (transfers accepted without user authorization)<br/>• Unverified staging file exposure to public storage<br/>• Critical vulnerability (remote execution, path traversal)<br/>• User-perceived crash rate $> 0.50\%$ or ANR rate $> 0.20\%$ | **Halt Play Console rollout immediately**; declare incident; page on-call team. | $\mathbf{\le 2\text{ hours}}$ | $\mathbf{\le 24\text{ hours}}$ |
| **P1 (Critical)** | • Transfer failure rate $> 15\%$ on specific OEM or Android version<br/>• User-perceived crash rate $> 0.10\%$ or ANR rate $> 0.05\%$<br/>• Battery drain alert / excessive background wake locks ($> 0.10\%$)<br/>• Discovery regression on Android 14+ or 16 KB native runtimes | Freeze staged rollout percentage; initiate root-cause investigation. | $\le 4\text{ hours}$ | $\le 48\text{ hours}$ |
| **P2 (Major)** | • Non-blocking UI glitch or theme rendering artifact<br/>• Transfer throughput drop below $30\text{ MB/s}$ over Wi-Fi Direct<br/>• Accessibility TalkBack labeling omission<br/>• History item display formatting defect | Log priority defect; schedule resolution in next planned milestone release. | N/A | Next Sprint ($\le 7\text{ days}$) |
| **P3 (Minor)** | • Cosmetic wording or typo in non-critical surface<br/>• Minor animation frame drop on low-end hardware<br/>• Non-impacting documentation inconsistency | Backlog defect for continuous improvement. | N/A | Normal Backlog |

---

## 3. Incident Command System (ICS) Roles & Escalation

### ICS On-Call Roster
- **Incident Commander (IC):** Has sole authority to order a Play Console distribution halt, authorize public communications, and approve emergency hotfix releases.
- **Technical Lead (TL):** Leads technical root-cause analysis (RCA), reproduces defect against exact release commit, implements code hotfix, and oversees deobfuscation.
- **Communications Lead (CL):** Coordinates user-facing advisories, monitors GitHub Issues, reviews user report emails, and drafts Play Console release notes.

### Emergency Communication Channels
- **Security Inquiries:** `security@glide.dev` (monitored 24/7 during rollout).
- **User Support Inquiries:** `support@glide.dev` (first-response within 4 hours for P0/P1).
- **Public Vulnerability Disclosure:** GitHub Security Advisories (coordinated disclosure).

---

## 4. Privacy-Preserving User Report Handling

Glide's architectural promise is zero cloud relay and zero account tracking. All customer support and user diagnostics must adhere to strict privacy-preserving protocols:

### Strict Prohibition on Content Collection
1. **Never Request Payload Files:** Support staff and automated intake forms must never request that users email, upload, or share the files they were attempting to transfer.
2. **Never Request Unredacted File Paths:** Avoid collecting full original directory paths that may reveal personal usernames or sensitive directory structures.

### Consented Redacted Diagnostics Collection
When a user consents to submitting diagnostic logs for troubleshooting:
- **Redacted Logcat:** Capture logs via Android system reporting with all payload data, transfer contents, and remote device identifiers masked.
- **Sanitized Metadata Only:**
  - Device Model & Manufacturer (e.g., `Pixel 8`, `Samsung SM-S928B`).
  - Android OS version & API level (e.g., `Android 15`, `API 35`).
  - Application version & build (e.g., `0.1.0-alpha`, `versionCode 1`).
  - Error stack trace (sanitized of personal file names).
  - Room database schema version (e.g., `Schema v1`).
  - Transport medium (e.g., `Wi-Fi Direct`, `Nearby Connections LAN`).

### Wrong-Recipient Sharing Triage
If a user reports accidentally transmitting a file to an unintended nearby peer:
1. **Immediate Reassurance:** Reassure the user that Glide requires explicit mutual Short Authentication String (SAS) confirmation and acceptance on both devices before transmission starts.
2. **Local Isolation Guidance:** Advise the user that files exist solely on the recipient device's local storage and were never stored in any cloud intermediate.
3. **Cache Purge Guidance:** Instruct the user on clearing unlinked staging caches via in-app Settings (`Clear transfer staging cache`).

---

## 5. Data-Corruption Escalation & Quarantine Inspection

Glide's transfer engine enforces a strict two-stage commit architecture:
`Wire Stream` $\rightarrow$ `Private Sandboxed Staging Directory` $\rightarrow$ `SHA-256 Digest Verification` $\rightarrow$ `Public Storage Commit`.

### Corruption Detection Workflow
```
[ Incoming Payload Stream ]
          │
          ▼
[ Staging: context.cacheDir/staging/<transferId>/ ]
          │
          ▼
[ SHA-256 Checksum Computation ]
          │
    ┌─────┴────────────────┐
    ▼                      ▼
[ MATCH ]             [ MISMATCH ]
    │                      │
    ▼                      ▼
[ Atomic Commit ]     [ Quarantine Isolation ]
(Public Storage)      (context.cacheDir/staging/quarantine/)
                           │
                           ▼
                      [ User Alerted & Staging Purged ]
                      (Zero bytes written to Public Storage)
```

### Escalation Runbook for Verified Hash Mismatches
1. **Quarantine Isolation:** If `ReceivedSha256 != ManifestSha256`, the engine terminates the session and moves unverified blocks to `staging/quarantine/`.
2. **Zero Public Leakage:** Verify that no partial or corrupted file was committed to `Environment.DIRECTORY_DOWNLOADS/Glide` or exported via SAF.
3. **Forensic Chunk Analysis:** Inspect session journal entries (`TransferItemRecord`) to identify which chunk offsets suffered corruption.
4. **Transport Medium Triage:** Determine whether packet loss occurred over Wi-Fi Direct or BLE control channels.
5. **Safe Quarantine Purge:** Execute atomic deletion of quarantined blocks:
   ```kotlin
   context.cacheDir.resolve("staging/quarantine").deleteRecursively()
   ```

---

## 6. Forward-Fix Rollback Execution Procedure

Google Play Console and Android package managers strictly reject binary downgrades (installing an APK with `versionCode < currentInstalledVersionCode`). Consequently, emergency rollback is executed through an **expedited forward-fix release**:

### Step 1: Halt Rollout in Google Play Console ($\le 2\text{ hours}$)
1. Log in to [Google Play Console](https://play.google.com/console).
2. Select **Glide** (`dev.glide.app`).
3. Navigate to **Release** $\rightarrow$ **Production** (or **Closed testing** if in flight).
4. Locate the active release (`versionCode 1`, `0.1.0-alpha`).
5. Click **Halt rollout**.
6. Confirm halt. This immediately blocks new downloads of the affected version worldwide.

### Step 2: Branch & Reproduce Hotfix
1. Check out the release tag corresponding to the halted release:
   ```bash
   git checkout tags/v0.1.0-alpha -b hotfix/v0.1.1-alpha
   ```
2. Reproduce the failure using automated unit/instrumentation tests.
3. Deobfuscate any production stack traces using retained `mapping.txt`:
   ```bash
   retrace mapping.txt production_stacktrace.txt
   ```

### Step 3: Implement & Verify Hotfix
1. Implement the minimal root-cause code fix.
2. Ensure Room database schema migrations are **strictly additive**; `fallbackToDestructiveMigration()` is **forbidden**.
3. Re-verify the **User Data Sanctity Invariant**: existing downloads and database tables remain untouched.

### Step 4: Monotonic Version Bump
Update `app/build.gradle.kts`:
- Increment `versionCode`: `1` $\rightarrow$ `2`
- Increment `versionName`: `0.1.0-alpha` $\rightarrow$ `0.1.1-alpha`

### Step 5: Build, Sign & Re-verify Artifacts
1. Execute signed release build:
   ```powershell
   pwsh -File tools/build-and-verify-rc.ps1
   ```
2. Verify APK signature schemes (v2, v3, v4) and 16 KB page alignment.
3. Verify test suites and architecture gates:
   ```powershell
   pwsh -File tools/verify-local.ps1
   ```

### Step 6: Deploy Forward-Fix Release
1. Upload new `app-release.aab` (`versionCode 2`) to Google Play Console.
2. Upload new `mapping.txt`.
3. Provide transparent release notes acknowledging the stability hotfix.
4. Initiate staged rollout ($5\% \rightarrow 20\% \rightarrow 50\% \rightarrow 100\%$) under 24/7 active watch.

---

## 7. Tabletop Incident Simulations & Recovery Checklists

To validate team readiness, three realistic operational tabletop failure drills are formalized:

### Drill 1: Bad Transfer / Corrupted Payload Incident
- **Scenario:** A Wi-Fi Direct socket disconnects abruptly mid-stream; receiver receives truncated bytes, and the transfer fails with a hash mismatch.
- **Injected Anomaly:** Artificially flip payload byte 1,048,576 during simulated stream.
- **Expected Engine Response:**
  1. Transfer engine detects SHA-256 digest mismatch upon stream conclusion.
  2. Engine quarantines staging blocks into `cache/staging/quarantine/`.
  3. Engine fires `TransferState.Failed(VerificationFailure)`.
  4. Zero bytes written to user's public Downloads directory.
  5. User UI displays clear failure state with retry prompt.
- **Recovery Verification Checklist:**
  - [x] Quarantined blocks successfully isolated from public storage.
  - [x] Room history marks session as `FAILED` with `REASON_HASH_MISMATCH`.
  - [x] Quarantine purge cleanly removes staging artifacts without touching existing user files.

### Drill 2: Main-Thread Blocking / ANR Cluster Incident
- **Scenario:** High-volume file selection (e.g., 500 images) triggers synchronous file metadata querying on the main thread, resulting in an ANR spike ($> 0.20\%$) on low-end devices.
- **Injected Anomaly:** Artificially inject heavy query on main thread in test harness.
- **Expected Response:**
  1. Android Vitals triggers P0 threshold alert ($> 0.20\%$ ANR).
  2. Operator executes Play Console halt within $\le 2\text{ hours}$.
  3. Lead engineer inspects ANR traces and retraces through `mapping.txt`.
  4. Offload file provider querying to `Dispatchers.IO` with Kotlin coroutines.
  5. Deploy forward-fix (`versionCode 2`).
- **Recovery Verification Checklist:**
  - [x] StrictMode static analysis (`tools/check-architecture.ps1`) asserts 0 main-thread I/O.
  - [x] Play Console distribution halt confirmed within 2 hours.
  - [x] Forward-fix APK successfully tested on Android 17 emulator runtime without ANRs.

### Drill 3: Signing Key & Upload Credential Incident
- **Scenario:** The local developer machine holding the upload keystore suffers hardware loss.
- **Expected Response:**
  1. Verify Google Play App Signing architecture: Play App Signing holds the master app-signing key in Google's secure infrastructure; developer only holds the upload key.
  2. Developer contacts Google Play Console account owner.
  3. Generate new upload key pair:
     ```bash
     keytool -genkeypair -alias glide-upload -keyalg RSA -keysize 4096 -validity 10000 -keystore upload-keystore.jks
     ```
  4. Export upload key certificate in PEM format:
     ```bash
     keytool -export -rfc -alias glide-upload -file upload_certificate.pem -keystore upload-keystore.jks
     ```
  5. Submit upload key reset request in Google Play Console under **App integrity** $\rightarrow$ **Request upload key reset**.
  6. Existing user installs remain 100% compatible; Play re-signs the bundle with the master app key.
- **Recovery Verification Checklist:**
  - [x] Play App Signing separation verified (no master signing key lost).
  - [x] Upload key reset procedure documented and tested.
  - [x] Zero impact on installed user base update compatibility.
