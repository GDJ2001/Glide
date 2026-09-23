# Controlled Production Rollout & Vitals Monitoring Protocol

**Document ID:** GLIDE-OPS-002-ROLLOUT  
**Effective Date:** 2026-09-14  
**Milestone:** M12 — Production and maintenance  
**Task:** GLIDE-065: Perform controlled production rollout  
**Authoritative Reference:** [`docs/release/GLIDE_RELEASE_PLAN.md`](GLIDE_RELEASE_PLAN.md) · [`docs/release/PRODUCTION_RC_APPROVAL.md`](PRODUCTION_RC_APPROVAL.md) · [`docs/DEFINITION_OF_DONE.md`](../DEFINITION_OF_DONE.md)  
**Status:** ACTIVE ✅  

---

## 1. Executive Summary & Rollout Philosophy

This protocol defines the operational procedures, telemetry gating metrics, observation schedules, and console execution steps for conducting a controlled production rollout of **Glide** (`dev.glide.app`, `versionCode 1`, `versionName 0.1.0-alpha`) on Google Play under **Milestone M12 (*Production and maintenance*)**.

### Core Philosophy
1. **Safety Over Speed:** Production distribution progresses monotonically through bounded cohorts to detect any unanticipated real-world regressions before broad exposure.
2. **Defensive Telemetry:** Rollout progression is strictly gated by quantitative Android Vitals thresholds (crash rate $< 0.10\%$, ANR rate $< 0.05\%$) and qualitative review triage.
3. **Immediate Halt Authority:** Any lead engineer or release operator is empowered to immediately halt rollout expansion if critical safety or integrity anomalies emerge.
4. **Forward-Fix Rollback:** In accordance with Google Play package versioning constraints, rollbacks are executed by halting distribution in Play Console and issuing an expedited hotfix with an incremented `versionCode`.

---

## 2. Frozen Release Candidate Manifest

All production distribution actions must utilize the exact cryptographic binaries compiled, verified, and approved under **GLIDE-064**:

| Artifact Identity | Output Path | File Size | SHA-256 Cryptographic Hash |
|---|---|:---:|---|
| **Google Play App Bundle** | `app/build/outputs/bundle/release/app-release.aab` | 9.61 MB | `a2d12fb51e3fa293e9190849abae9ed72fb906dff05a2a18eb069ef1a5d941c2` |
| **Signed Release Tester APK** | `app/build/outputs/apk/release/app-release.apk` | 4.93 MB | `22d5db96b773c370a90c7fdf65f2b779e52437ecf4a2ccff94037b83d1489493` |
| **R8 Deobfuscation Mapping** | `app/build/outputs/mapping/release/mapping.txt` | 67.63 MB | `e4c1de9006da57630b4c79773ae456d582cc14a8f9db5751db5edca631491cfd` |
| **fs-verity Signature File** | `app/build/outputs/apk/release/app-release.apk.idsig` | 46.6 KB | Verified Android Signature Scheme v4 |

### Target Application Specifications
- **Application ID:** `dev.glide.app`
- **Version Code:** `1`
- **Version Name:** `0.1.0-alpha`
- **Minimum SDK:** `26` (Android 8.0 Oreo)
- **Target SDK:** `37` (Android 17 Baklava)
- **Native Page Size:** 16 KB page-aligned (compatible with Android 15+ and 17)
- **APK Signature Schemes:** v2 + v3 + v4 (fs-verity) verified with RSA 4096-bit key

---

## 3. Phased Staged Rollout Schedule & Gating Gates

Where Google Play Console staged rollout is available for the account and release track, distribution proceeds through four discrete phases:

```
[ Phase 1: 5% ]  --> (24h Soak + Vitals Gate) -->
[ Phase 2: 20% ] --> (24h Soak + Vitals Gate) -->
[ Phase 3: 50% ] --> (24h Soak + Vitals Gate) -->
[ Phase 4: 100% ] (Full General Availability)
```

### Staged Progression Matrix

| Phase | Rollout % | Observation Window | Minimum Sample Size | Promotion Gate Criteria |
|:---:|:---:|:---:|:---:|---|
| **Phase 1** | **5%** | $\ge 24\text{ hours}$ | $\ge 100$ active installs or closed cohort soak | - User-perceived crash rate $< 0.10\%$<br/>- User-perceived ANR rate $< 0.05\%$<br/>- Zero data corruption reports<br/>- Zero consent bypass reports |
| **Phase 2** | **20%** | $\ge 24\text{ hours}$ | $\ge 500$ active installs | - Crash rate $< 0.10\%$, ANR rate $< 0.05\%$<br/>- Zero background Wi-Fi scan warnings<br/>- No critical performance degradation |
| **Phase 3** | **50%** | $\ge 24\text{ hours}$ | $\ge 2,000$ active installs | - Crash rate $< 0.10\%$, ANR rate $< 0.05\%$<br/>- Review sentiment $\ge 4.0\star$ / zero transfer bugs<br/>- Zero staging leakage or storage errors |
| **Phase 4** | **100%** | Ongoing | Full user population | - Stable production operations maintained<br/>- Transition to 7-day post-launch health review |

> [!IMPORTANT]
> **Observation Windows:** Time durations are minimum operational targets, not automated chronometers. If install velocity is low, the observation window must be extended until an adequate sample size is achieved. Low volume means insufficient evidence, not zero failure rate.

---

## 4. Initial Release Bounded Distribution Contingency

Google Play Console imposes distinct operational rules for initial application launches versus subsequent updates:
1. **First-Time Production Release:** In certain developer account tiers, Google Play requires an initial production release to launch at 100% availability, reserving staged rollout percentages exclusively for subsequent version updates (`versionCode > 1`).
2. **Account Verification Policy:** Accounts created after November 13, 2023 require completed 14-day closed testing with 12+ opted-in testers before applying for production distribution access.

### Contingency Strategy for Initial Release
If Google Play Console does not permit staged rollout on `versionCode 1`:
1. **Pre-Production Bounded Stage:** The signed Release Candidate must first complete the 14-day flight on the **Closed Testing Track** across the 14-member multi-OEM cohort established in [`CLOSED_TESTING_PROTOCOL.md`](CLOSED_TESTING_PROTOCOL.md).
2. **Monitored Initial Launch:** Upon production approval, the initial release is published with **24/7 active watch** during the initial 48 hours.
3. **Play Console Halt Readiness:** The release operator maintains constant access to the Play Console **Halt Rollout** action to freeze distribution immediately should any anomalous telemetry emerge.
4. **Subsequent Staged Releases:** All subsequent releases (`versionCode 2+`) strictly execute the $5\% \rightarrow 20\% \rightarrow 50\% \rightarrow 100\%$ staged progression.

---

## 5. Android Vitals & Telemetry Health Review

During all rollout phases, the release engineering team conducts mandatory daily audits of Google Play Console Android Vitals and crash reporting telemetry:

### Quality Threshold Targets

| Vitals Metric | Play Bad Behavior Limit | Glide Production Target | Action on Target Breach |
|---|:---:|:---:|---|
| **User-Perceived Crash Rate** | $1.09\%$ | $\mathbf{< 0.10\%}$ | Immediate investigation; halt if $> 0.50\%$ |
| **User-Perceived ANR Rate** | $0.47\%$ | $\mathbf{< 0.05\%}$ | Immediate investigation; halt if $> 0.20\%$ |
| **Excessive Wake Locks** | $> 0.10\%$ | $\mathbf{0.00\%}$ | Triage foreground service lifecycle |
| **Excessive Background Wi-Fi Scans** | $> 0.10\%$ | $\mathbf{0.00\%}$ | Verify scanning halts when transfer completes |
| **Excessive Background BLE Scans** | $> 0.10\%$ | $\mathbf{0.00\%}$ | Verify BLE discovery halts upon peer connect |

### Daily Health Audit Checklist
Every 24 hours during rollout, the on-call operator must record:
- [ ] Android Vitals User-Perceived Crash Rate (last 24h)
- [ ] Android Vitals User-Perceived ANR Rate (last 24h)
- [ ] Top crash clusters inspected via `mapping.txt` deobfuscation
- [ ] User review sentiment and 1-star / 2-star feedback triage
- [ ] Battery & foreground service behavior alerts
- [ ] Physical device smoke validation on local hardware

---

## 6. Immediate Halt Triggers & Rollback Protocol

### Halt Triggers
Rollout expansion must be halted within **2 hours** of detecting any of the following:
1. **Data Corruption:** Any verified SHA-256 hash mismatch or corrupt file committed to public storage.
2. **Consent Bypass:** Any automatic transfer acceptance without explicit user authorization and 6-digit mutual SAS verification.
3. **Unverified Exposure:** Any leakage of staging files into public storage prior to hash verification.
4. **Vitals Regression:** User-perceived crash rate $> 0.50\%$ or ANR rate $> 0.20\%$.
5. **Critical Vulnerability:** Path traversal attempt, permission escalation, or memory leak leading to system instability.

### Forward-Fix Rollback Procedure
1. **Halt Rollout:** In Google Play Console, navigate to `Production` $\rightarrow$ `Releases` $\rightarrow$ click **Halt Rollout**. This immediately freezes new downloads of the affected version.
2. **Diagnose & Deobfuscate:** Retrieve stack traces from Play Console and deobfuscate using the retained `mapping.txt` (`9f108d84e77df3700b4944abd15cb2bd6fe439bf6cae01c7ea14c80d4aa9d6e4`).
3. **Author Forward Hotfix:** Implement root-cause fix on branch `hotfix/v0.1.1`. Increment `versionCode` to `2` and `versionName` to `0.1.1-alpha`.
4. **Preserve Schema Compatibility:** Ensure Room database migrations are strictly additive; **never** delete user files committed to public storage.
5. **Build & Verify Signed Hotfix:** Compile, sign, and verify new AAB/APK artifacts using `tools/build-and-verify-rc.ps1`.
6. **Deploy Hotfix:** Upload `app-release.aab` (`versionCode 2`) to Play Console and initiate an expedited staged rollout.

---

## 7. Play Console Operator Step-by-Step Runbook

### Step 1: Initiating Staged Rollout
1. Open [Google Play Console](https://play.google.com/console).
2. Select application **Glide** (`dev.glide.app`).
3. Navigate to **Release** $\rightarrow$ **Production**.
4. Click **Create new release**.
5. Upload the signed `app-release.aab` (`SHA-256: a2d12fb51e3f...`).
6. Upload the R8 deobfuscation file `mapping.txt` (`SHA-256: e4c1de90...`).
7. Enter release name: `0.1.0-alpha (1)`.
8. Enter localized release notes from [`docs/release/store_assets/STORE_LISTING_METADATA.md`](store_assets/STORE_LISTING_METADATA.md).
9. Select rollout type: **Staged rollout**.
10. Set percentage: **5%**.
11. Click **Review release** $\rightarrow$ **Start rollout to Production**.

### Step 2: Increasing Rollout Percentage
1. Navigate to **Production** $\rightarrow$ **Releases**.
2. Verify that the 24-hour observation window and gating criteria for the current phase are satisfied.
3. Click **Update rollout**.
4. Increase percentage to next target (**20%**, **50%**, or **100%**).
5. Click **Save** $\rightarrow$ **Update rollout**.

### Step 3: Halting Rollout (Emergency)
1. Navigate to **Production** $\rightarrow$ **Releases**.
2. Locate the active staged release.
3. Click **Halt rollout**.
4. Confirm halt in the modal dialog.
5. Notify the engineering team and begin the forward-fix rollback procedure.

---

## 8. Two-Device Smoke Verification Runbook

Before initiating or expanding any rollout phase, a local two-device transfer smoke test must be executed on real hardware or emulator runtimes:

### Test Environment
- **Device A (Sender):** Android runtime (e.g., `emulator-5554`, Android 17 / API 37) running signed `app-release.apk`.
- **Device B (Receiver):** Secondary Android device or emulator running signed `app-release.apk`.

### Smoke Execution Sequence
1. **Clean Installation:**
   ```powershell
   adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk
   ```
2. **Launch & Permissions:**
   - Launch Glide on both devices.
   - Verify prompt-free startup (no premature permission requests).
3. **Radar Discovery:**
   - On Device A: Select a test payload (photo or document).
   - Verify radar discovery surfaces Device B within $\le 3\text{ seconds}$.
4. **Mutual Verification (SAS):**
   - Tap Device B on Device A.
   - Verify identical 6-digit numeric Short Authentication String (SAS) appears on both screens.
   - Confirm SAS match on both devices.
5. **Payload Streaming & Receipt:**
   - Verify high-throughput streaming with responsive progress indicator ($\le 10\text{ Hz}$ throttle).
   - Verify transfer completes with truthful success receipt.
   - Verify SHA-256 hash verified and payload safely committed to public storage.
6. **Logcat Health Check:**
   - Verify zero unhandled exceptions, zero StrictMode violations, and zero memory leaks.
