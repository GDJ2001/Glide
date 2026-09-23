# Evidence Report — HARD-042: Exact-Candidate Release Dossier Reconciliation

**Author:** Gemini Code Assistant  
**Date:** 2026-09-23  
**Milestone:** M7 — Release and operations  
**Task:** HARD-042: Complete exact-candidate release dossier  
**Status:** COMPLETE ✅ (All Legal, Security, Privacy, License, Key Custody, and Quality Gate Evidence Synchronized with Exact Candidate Artifacts)  

---

## 1. Executive Summary

HARD-042 reconciles the complete release dossier against the exact multi-platform Release Candidate artifacts compiled and verified under HARD-041. All release plans, approval records, rollout protocols, legal notices, and automated release auditors have been synchronized to eliminate any discrepancies between documentation, operational scripts, and binary artifacts.

### Key Outcomes

1. **Cryptographic Identity Reconciliation:**
   - Synchronized exact SHA-256 hashes across all release authority documents and automated auditors:
     - Android Play App Bundle (`app-release.aab`): `a2d12fb51e3fa293e9190849abae9ed72fb906dff05a2a18eb069ef1a5d941c2` (9.61 MB)
     - Signed Release Tester APK (`app-release.apk`): `22d5db96b773c370a90c7fdf65f2b779e52437ecf4a2ccff94037b83d1489493` (4.93 MB)
     - R8 Deobfuscation Mapping (`mapping.txt`): `e4c1de9006da57630b4c79773ae456d582cc14a8f9db5751db5edca631491cfd` (67.63 MB)
     - Windows Desktop Standalone Archive (`glide-windows-win-x64.zip`): `aa395c77c4e8d732d96579a802a76dfe062565d6e0f9ea1cee5bdd041d212282` (552 KB)
     - Linux Distribution Tarball (`glide-linux-linux-x64.tar.gz`): `8596e909c7305fcd31ad8ae46aaf21cedb0cb1fd3cdb0f3c1b027244a65f1b9c` (30.62 MB)
     - Embedded Web Client Distribution (`glide-web-client.zip`): `b899693a97234cfdff611a16358449c50debb2b79bfae899fa3fad24558476d0` (10.9 KB)

2. **Automated Release Auditors 100% Green:**
   - [`tools/verify-production-release-gate.ps1`](../../../tools/verify-production-release-gate.ps1): **0 violations (5/5 checks passed)**.
   - [`tools/verify-production-rollout.ps1`](../../../tools/verify-production-rollout.ps1): **0 violations (5/5 checks passed)**.
   - [`tools/verify-signing-custody.ps1`](../../../tools/verify-signing-custody.ps1): **0 violations (5/5 checks passed)**.
   - [`tools/verify.ps1`](../../../tools/verify.ps1): **4/4 CI gates passed (Architecture 7/7, Windows Companion 8/8, Build Debug All, Lint All)**.

3. **Multi-Platform Legal & License Notices Updated:**
   - [`doc/release/OPEN_SOURCE_LICENSES.md`](../../release/OPEN_SOURCE_LICENSES.md) updated with full attribution for:
     - Android dependencies (Apache 2.0 / Google APIs Terms).
     - .NET standard Base Class Library runtime under MIT License (Microsoft Corporation) with formal disclosure that **zero external NuGet dependencies** are bundled in production binaries.
     - Web client disclosure certifying 100% vanilla ES2022/HTML5/CSS3 with zero npm or CDN dependencies.

4. **Privacy & Data Safety Alignment:**
   - Re-verified [`doc/release/PRIVACY_POLICY.md`](../../release/PRIVACY_POLICY.md) and [`doc/release/DATA_SAFETY_DECLARATION.md`](../../release/DATA_SAFETY_DECLARATION.md) against shipped SDKs and runtime behaviors: zero cloud servers, zero analytics, zero ad networks, and sandboxed app-private receive directories.

5. **Operational Rollback Runbooks Verified:**
   - Immediate halt triggers (Data Corruption, Consent Bypass, Unverified Exposure, Vitals Regression, Critical Vulnerability) and forward-fix rollback runbooks confirmed active in [`doc/release/PRODUCTION_RC_APPROVAL.md`](../../release/PRODUCTION_RC_APPROVAL.md) and [`doc/release/PRODUCTION_ROLLOUT_PROTOCOL.md`](../../release/PRODUCTION_ROLLOUT_PROTOCOL.md).

---

## 2. Release Candidate Dossier Reconciliation Matrix

| Document | Governing Purpose | Candidate Alignment & Verification Status | Status |
|---|---|---|:---:|
| [`PRODUCTION_RC_APPROVAL.md`](../../release/PRODUCTION_RC_APPROVAL.md) | Formal production candidate approval | Hashes updated to match exact candidate; 6 quality gates certified; halt triggers and forward-fix rollback ratified | **RECONCILED** ✅ |
| [`PRODUCTION_ROLLOUT_PROTOCOL.md`](../../release/PRODUCTION_ROLLOUT_PROTOCOL.md) | Phased staged rollout (5% $\to$ 20% $\to$ 50% $\to$ 100%) | 4-phase schedule, 24h soak period, Vitals thresholds (<0.10% crash, <0.05% ANR), operator runbook | **RECONCILED** ✅ |
| [`GLIDE_RELEASE_PLAN.md`](../../release/GLIDE_RELEASE_PLAN.md) | Store readiness checklist | 12/12 items verified complete | **RECONCILED** ✅ |
| [`KEY_CUSTODY_AND_PUBLISHER_IDENTITY.md`](../../release/KEY_CUSTODY_AND_PUBLISHER_IDENTITY.md) | Signing custody & namespace policy | RSA 4096 upload key custody, Play App Signing separation, zero committed secrets verified | **RECONCILED** ✅ |
| [`OPEN_SOURCE_LICENSES.md`](../../release/OPEN_SOURCE_LICENSES.md) | Third-party software attribution | Disclosures added for Android, Desktop (.NET zero-NuGet), and Web (zero-CDN) | **RECONCILED** ✅ |
| [`PRIVACY_POLICY.md`](../../release/PRIVACY_POLICY.md) | Legal privacy disclosures | Zero cloud, zero accounts, zero analytics, sandboxed local transfers | **RECONCILED** ✅ |
| [`DATA_SAFETY_DECLARATION.md`](../../release/DATA_SAFETY_DECLARATION.md) | Play Console Data Safety questionnaire | Answers mapped directly to shipped transports; no unverified claims | **RECONCILED** ✅ |
| [`FOREGROUND_SERVICE_DECLARATION.md`](../../release/FOREGROUND_SERVICE_DECLARATION.md) | FGS type justification | Justified for `connectedDevice` peer transfer with user cancellation | **RECONCILED** ✅ |
| [`CLOSED_TESTING_PROTOCOL.md`](../../release/CLOSED_TESTING_PROTOCOL.md) | 14-day closed testing protocol | 14-member multi-OEM cohort matrix and reviewer runbooks verified | **RECONCILED** ✅ |
| [`LAN_QR_ROLLOUT_AND_MAINTENANCE_HANDOFF.md`](../../release/LAN_QR_ROLLOUT_AND_MAINTENANCE_HANDOFF.md) | Maintenance handoff for LAN/QR | Candidate hashes updated; operational support procedures active | **RECONCILED** ✅ |

---

## 3. Automated Release Auditor Outcomes

```text
=================================================================
   Glide GLIDE-064: Production Release Candidate Approval Auditor
=================================================================
[Check 1/5] Auditing frozen Release Candidate cryptographic identity... PASS
[Check 2/5] Auditing Definition of Done quality gate evidence files...  PASS
[Check 3/5] Auditing Store readiness checklist in GLIDE_RELEASE_PLAN.md... PASS (12/12 checked)
[Check 4/5] Auditing formal approval record in PRODUCTION_RC_APPROVAL.md... PASS
[Check 5/5] Auditing incident response triggers & rollback protocol... PASS
   PRODUCTION RELEASE GATE APPROVED (0 violations)

=================================================================
   Glide GLIDE-065: Controlled Production Rollout Auditor        
=================================================================
[Check 1/5] Auditing frozen Release Candidate cryptographic identity... PASS
[Check 2/5] Auditing PRODUCTION_ROLLOUT_PROTOCOL.md phased schedule...  PASS
[Check 3/5] Auditing Android Vitals quality thresholds & halt triggers... PASS
[Check 4/5] Auditing initial release bounded distribution contingency... PASS
[Check 5/5] Auditing operator runbook & two-device smoke verification... PASS
   CONTROLLED PRODUCTION ROLLOUT AUDIT PASSED (0 violations)

============================================================
   Glide Signing Custody & Publisher Identity Auditor       
============================================================
[Check 1/5] Auditing repository for committed keystores and secret files... PASS (0 committed secrets)
[Check 2/5] Verifying .gitignore protection rules... PASS
[Check 3/5] Verifying app/build.gradle.kts signing configuration safety... PASS
[Check 4/5] Inspecting cryptographic toolchain (keytool & apksigner)... PASS
[Check 5/5] Checking Release Keystore Parameters... PASS
   ALL SIGNING CUSTODY & REPOSITORY CHECKS PASSED (0 issues)
```

---

## 4. Verification Verdict

All requirements for **HARD-042** are met in full:
- All release candidate evidence, legal policies, licenses, support matrices, and incident runbooks match the exact build artifacts produced in HARD-041.
- All automated release auditors pass with zero violations.
- No release-gate manual check remains outstanding.
- **Task Status:** **COMPLETE ✅**
