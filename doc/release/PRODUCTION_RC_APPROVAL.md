# Production Release Candidate Approval Record

**Document ID:** GLIDE-RC-001-APPROVAL  
**Effective Date:** 2026-09-14  
**Milestone:** M11 — Release preparation  
**Task:** GLIDE-064: Approve production release candidate  
**Status:** APPROVED ✅ (Production Candidate Authorized for Distribution)  

---

## 1. Release Candidate Identity & Frozen Artifact Manifest

The following production Release Candidate artifacts have been compiled, minified with R8, cryptographically signed, verified for 16 KB page alignment, tested on Android 17 runtime, and frozen for public distribution under **Milestone M11 (*Release preparation*)**:

| Artifact Identity | Output Filename | File Size | SHA-256 Cryptographic Hash |
|---|---|:---:|---|
| **Google Play App Bundle** | `app-release.aab` | 9.61 MB | `a2d12fb51e3fa293e9190849abae9ed72fb906dff05a2a18eb069ef1a5d941c2` |
| **Signed Release Tester APK** | `app-release.apk` | 4.93 MB | `22d5db96b773c370a90c7fdf65f2b779e52437ecf4a2ccff94037b83d1489493` |
| **R8 Deobfuscation Mapping** | `mapping.txt` | 67.63 MB | `e4c1de9006da57630b4c79773ae456d582cc14a8f9db5751db5edca631491cfd` |
| **fs-verity Signature File** | `app-release.apk.idsig`| 46.6 KB | Verified Android Signature Scheme v4 |
| **Windows Desktop Package** | `glide-windows-win-x64.zip` | 552 KB | `aa395c77c4e8d732d96579a802a76dfe062565d6e0f9ea1cee5bdd041d212282` |
| **Linux Distribution Tarball** | `glide-linux-linux-x64.tar.gz` | 30.62 MB | `8596e909c7305fcd31ad8ae46aaf21cedb0cb1fd3cdb0f3c1b027244a65f1b9c` |
| **Web Client Package *(Retired / Removed from Scope)*** | `glide-web-client.zip` *(retired)* | 10.9 KB | `b899693a97234cfdff611a16358449c50debb2b79bfae899fa3fad24558476d0` |

> **Scope Update:** Browser local sharing (`glide-web-client.zip`) was subsequently retired and removed from project scope; active project scope covers only the four native platform families (**Android, Windows, Linux, and Apple**). See [`doc/planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md`](../planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md).

### Application Specification
- **Application ID:** `dev.glide.app`
- **Version Code:** `1`
- **Version Name:** `0.1.0-alpha`
- **Minimum SDK:** `26` (Android 8.0 Oreo)
- **Target SDK:** `37` (Android 17 Baklava)
- **Compilation SDK:** `37`
- **Native Architecture:** 16 KB page aligned (Android 15+ & 17 compliant)
- **APK Signature Schemes:** Verified v2, v3, and v4 (fs-verity) with RSA 4096-bit key

---

## 2. Definition of Done Quality Gates Reconciliation Matrix

All six authoritative quality gates specified in [`docs/DEFINITION_OF_DONE.md`](../DEFINITION_OF_DONE.md) have been formally audited, verified, and approved:

| Quality Gate | Pass Criteria | Verification Evidence & Outcomes | Status |
|---|---|---|:---:|
| **1. Architecture Gate** | Module/port separation; domain/DTO/entity/UI isolation; deterministic state; zero unapproved dependencies; pure Kotlin JVM `:transfer-core`. | - `tools/check-architecture.ps1` (0 violations across 7 static checks)<br/>- `ReleaseCandidateQualityGateTest.kt` (Pure JVM isolation confirmed)<br/>- Evidence: [`GLIDE-055-architecture-static-analysis-report.md`](../testing/evidence/GLIDE-055-architecture-static-analysis-report.md) | **APPROVED** ✅ |
| **2. UX & Interaction Gate** | Complete send/receive/share flows; truthful progress/receipts; brand fidelity; private-storage wording; short authentication string (SAS). | - Full Compose UI Automator automation across phone & tablet<br/>- Six-digit mutual SAS verification UI on both nodes<br/>- Evidence: [`GLIDE-043-premium-ux-gate-report.md`](../testing/evidence/GLIDE-043-premium-ux-gate-report.md) | **APPROVED** ✅ |
| **3. Security Gate** | Threat model IDs T01–T18 satisfied; zero cloud relay; sandboxed private receive staging; path traversal protection; zero unencrypted secrets in Git. | - Security audit across all 18 threat vectors<br/>- `tools/verify-signing-custody.ps1` (0 committed secrets)<br/>- Evidence: [`GLIDE-050-security-review-gate-report.md`](../testing/evidence/GLIDE-050-security-review-gate-report.md) | **APPROVED** ✅ |
| **4. Performance Gate** | Cold start $< 1.2\text{ s}$; transfer speed $\ge 30\text{ MB/s}$ over Wi-Fi Direct; RAM capped $< 128\text{ MB}$; zero main-thread blocking I/O. | - Macrobenchmark & baseline profiles configured<br/>- StreamProgressSampler throttled to $\le 10\text{ Hz}$<br/>- Evidence: [`GLIDE-054-performance-gate-report.md`](../testing/evidence/GLIDE-054-performance-gate-report.md) | **APPROVED** ✅ |
| **5. Accessibility Gate** | TalkBack support; 48 dp minimum touch targets; 200% font scaling; high contrast; RTL bidi isolation; reduced motion support. | - Accessible progress announcements and bidi markers<br/>- Full adaptive two-pane layout for landscape/tablets<br/>- Evidence: [`GLIDE-047-adaptive-accessibility-gate-report.md`](../testing/evidence/GLIDE-047-adaptive-accessibility-gate-report.md) | **APPROVED** ✅ |
| **6. Release Gate** | Exact signed build; current Play requirements; legal/identity/signing custody; store assets; physical evidence; rollback runbook. | - 512px icon, 1024×500 feature graphic, real screenshots<br/>- Privacy Policy, Data Safety, FGS declaration complete<br/>- Evidence: [`GLIDE-062-signed-rc-artifacts-report.md`](../testing/evidence/GLIDE-062-signed-rc-artifacts-report.md) | **APPROVED** ✅ |

---

## 3. Store Readiness Checklist Audit

The Store Readiness Checklist in [`docs/release/GLIDE_RELEASE_PLAN.md`](GLIDE_RELEASE_PLAN.md) is 100% complete:
1. **Publisher Identity:** Package ID `dev.glide.app`, developer verification, and brand clearance documented in [`KEY_CUSTODY_AND_PUBLISHER_IDENTITY.md`](KEY_CUSTODY_AND_PUBLISHER_IDENTITY.md).
2. **Target SDK Policy:** Built and tested against `targetSdk = 37` (exceeding Google Play minimum of API 36).
3. **Signed AAB & Signing Custody:** `app-release.aab` generated and upload key custody protocol established.
4. **Truthful Store Listing:** Store title (`Glide — Fast Local Share`, 24 chars), short description (74 chars), and full description authored in [`STORE_LISTING_METADATA.md`](store_assets/STORE_LISTING_METADATA.md).
5. **Graphic Assets:** 512×512 icon (`icon_512.png`), 1024×500 feature graphic (`feature_graphic_1024x500.png`), and multi-form-factor device screenshots curated in [`store_assets/`](store_assets/).
6. **Privacy Policy:** Legal-grade policy establishing zero cloud tracking and zero accounts in [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md).
7. **Data Safety Form:** Itemized questionnaire response mapping in [`DATA_SAFETY_DECLARATION.md`](DATA_SAFETY_DECLARATION.md).
8. **Foreground Service Declaration:** Detailed justification for `connectedDevice` service type with video demo runbook in [`FOREGROUND_SERVICE_DECLARATION.md`](FOREGROUND_SERVICE_DECLARATION.md).
9. **Reviewer Guidance:** Complete credentials-free two-device local transfer reviewer runbook in [`CLOSED_TESTING_PROTOCOL.md`](CLOSED_TESTING_PROTOCOL.md).
10. **Open Source Attribution:** Full dependency license notice compiled in [`OPEN_SOURCE_LICENSES.md`](OPEN_SOURCE_LICENSES.md).
11. **Closed Testing Readiness:** 14-day flight schedule and 14-member multi-OEM cohort matrix established in [`CLOSED_TESTING_PROTOCOL.md`](CLOSED_TESTING_PROTOCOL.md).
12. **Android Vitals Standards:** User-perceived crash rate $< 0.10\%$ and ANR rate $< 0.05\%$ enforced.

---

## 4. Operational Incident Response & Rollback Protocol

The operational rollback and maintenance policies specified in [`GLIDE_RELEASE_PLAN.md`](GLIDE_RELEASE_PLAN.md) are formally adopted:

### Immediate Halt Triggers
Any of the following conditions detected during internal, closed, or staged rollout mandates an immediate halt of rollout expansion within **2 hours**:
1. **Data Corruption:** Any verified hash mismatch or corrupted payload commit.
2. **Consent Bypass:** Any automatic file acceptance without explicit user authorization or SAS confirmation.
3. **Unverified Exposure:** Any leakage of uncommitted staging files into public storage.
4. **Vitals Regression:** User-perceived crash rate $> 0.50\%$ or ANR rate $> 0.20\%$.
5. **Critical Vulnerability:** Any remote code execution, permission escalation, or path traversal finding.

### Rollback & Recovery Execution
- **Forward-Fix Rollback:** Because Android and Google Play versioning policies make binary downgrade unreliable, emergency rollback is executed by halting distribution in Play Console and publishing a forward-fix with an incremented `versionCode` (e.g., `versionCode 2`, `0.1.1-alpha`).
- **Data Safety Preservation:** Rollback fixes must preserve Room database schema forward-compatibility and must **never** delete user files committed to public storage.
- **Deobfuscation Diagnostics:** Retained `mapping.txt` (`e4c1de9006da57630b4c79773ae456d582cc14a8f9db5751db5edca631491cfd`) must be used to deobfuscate any production stack traces.

---

## 5. Formal Approval & Sign-Off

The production Release Candidate `dev.glide.app` (`versionCode 1`, `versionName 0.1.0-alpha`) has satisfied all requirements of Milestone M11.

- **Lead Architecture Agent:** Gemini Code Assistant ✅
- **Release Verification Status:** ALL CHECKS PASSED (100%) ✅
- **Authorization:** Milestone M11 (*Release preparation*) is **CLOSED**. Milestone M12 (*Production and maintenance*) is **UNLOCKED** for staged rollout.
