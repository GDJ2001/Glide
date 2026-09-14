# Evidence Report — GLIDE-060: Publisher Identity & Signing Custody Finalization

**Author:** Gemini Code Assistant  
**Date:** 2026-09-14  
**Milestone:** M11 — Release preparation  
**Task:** GLIDE-060  
**Status:** COMPLETE ✅ (Publisher Identity Governance & Signing Custody Fully Established)  

---

## 1. Executive Summary

GLIDE-060 establishes the authoritative publisher identity governance, Google Play App Signing key separation model, upload key custody protocols, monotonic release versioning, and environment-driven Gradle signing configuration for the Glide application.

This work fulfills the release preparation requirements set forth in [`docs/release/GLIDE_RELEASE_PLAN.md`](../release/GLIDE_RELEASE_PLAN.md), [`docs/development/CI_CD_PLAN.md`](../../development/CI_CD_PLAN.md), and [`docs/development/GEMINI_ENGINEERING_RULES.md`](../../development/GEMINI_ENGINEERING_RULES.md):
1. **Zero Repository Secrets:** Enforced strict git hygiene preventing any keystore (`*.jks`, `*.keystore`, `*.p12`, `*.pfx`, `*.pk8`) or secret properties file from entering source control.
2. **Dynamic Signing Architecture:** Added environment- and property-driven signing configuration in `app/build.gradle.kts` supporting APK Signature Schemes v1, v2, v3, and v4 without disrupting local unsigned/debug builds.
3. **Publisher Identity & Namespace Governance:** Designed dynamic package ID overrides (`GLIDE_APPLICATION_ID`), formalizing namespace compliance, DNS/domain ownership validation, and resolution of image loader naming collisions.
4. **Authoritative Key Custody Standard:** Authored [`docs/release/KEY_CUSTODY_AND_PUBLISHER_IDENTITY.md`](../../release/KEY_CUSTODY_AND_PUBLISHER_IDENTITY.md) defining air-gapped cold storage, split-knowledge custodian protocols, and an upload key reset disaster recovery runbook.
5. **Monotonic Versioning Schema:** Established deterministic integer `versionCode` progression ($\text{versionCode} = \text{Major} \times 10000 + \text{Minor} \times 100 + \text{Patch}$) and SemVer `versionName` binding.
6. **Automated Verification:** Created dedicated verification tooling ([`tools/verify-signing-custody.ps1`](../../../tools/verify-signing-custody.ps1)) and unit test suite ([`SigningConfigurationAndPublisherIdentityTest.kt`](../../../app/src/test/kotlin/dev/glide/app/release/SigningConfigurationAndPublisherIdentityTest.kt)).

---

## 2. Key Architecture & Invariant Reconciliation

| Architectural Invariant | Governing Rule / Standard | Implementation & Enforcement | Status |
|---|---|---|:---:|
| **Zero Secret Storage in Git** | Rule #1, `CI_CD_PLAN.md` | `.gitignore` comprehensively excludes `*.jks`, `*.keystore`, `*.p12`, `*.pfx`, `*.pem`, `*.key`, `*.pk8`, `signing.properties`, `release.properties`, `secrets.properties`. Verified in `test1` and `test2`. | **PASS** ✅ |
| **Play App Signing Separation** | `GLIDE_RELEASE_PLAN.md` | Two-tier architecture: Google manages App Signing Key in Cloud KMS; Publisher retains Upload Key for signing AABs. Documented in `KEY_CUSTODY_AND_PUBLISHER_IDENTITY.md`. | **PASS** ✅ |
| **Dynamic Signing Configuration** | Android Gradle Plugin 8.7 | `app/build.gradle.kts` dynamically reads `GLIDE_RELEASE_KEYSTORE_PATH`, `GLIDE_RELEASE_KEYSTORE_PASSWORD`, `GLIDE_RELEASE_KEY_ALIAS`, and `GLIDE_RELEASE_KEY_PASSWORD`. Safe fallback when credentials absent. | **PASS** ✅ |
| **Full APK Signature Schemes** | Android Security Best Practices | `enableV1Signing = true`, `enableV2Signing = true`, `enableV3Signing = true`, `enableV4Signing = true` (fs-verity) explicitly declared on release signing configuration. Verified in `test7`. | **PASS** ✅ |
| **Publisher Application ID** | Google Play Developer Policy | Configurable via `GLIDE_APPLICATION_ID` / `glide.applicationId` (default: `dev.glide.app`). Validates format, 2+ dot-separated segments, non-reserved identifiers, $\le 255\text{ characters}$. Verified in `test4`. | **PASS** ✅ |
| **Monotonic Integer Version Code** | Google Play Console API | Calculated via $(\text{Major} \times 10000) + (\text{Minor} \times 100) + \text{Patch}$. Monotonic increase enforced across release stages (`10000` $\to$ `10001` $\to$ `10100` $\to$ `20000`). Verified in `test5`. | **PASS** ✅ |
| **Air-Gapped Cold Storage** | Security Architecture | Physical hardware token (YubiKey/IronKey) custody with split-knowledge protocol between two authorized custodians. Documented in custody standard. | **PASS** ✅ |
| **Disaster Recovery Runbook** | Operational Reliability | Step-by-step upload key reset procedure via Play Console and PEPK (Play Encrypt Private Key) tool. | **PASS** ✅ |

---

## 3. Automated Verification Matrix

The automated signing custody and publisher identity verification suite was executed via Gradle and PowerShell:

### A. Unit & Policy Test Suite (`SigningConfigurationAndPublisherIdentityTest.kt`)
| Test Case | Invariant / Validation Target | Result | Status |
|---|---|:---:|:---:|
| `test1_gitIgnore_excludesAllKeystoresAndSecretProperties` | Verifies `.gitignore` explicitly lists all keystore and credential patterns | **PASS** | 100% |
| `test2_repositoryScan_zeroCommittedKeystoresOrSecrets` | Audits all source files in repository for unencrypted keystores/properties | **PASS** | 100% |
| `test3_buildScript_zeroHardcodedPasswordsAndWiresDynamicProviders` | Asserts zero hardcoded passwords and verifies dynamic provider bindings | **PASS** | 100% |
| `test4_publisherApplicationId_conformsToAndroidPackageNamingSpec` | Enforces Android package naming specification and reserved word rejection | **PASS** | 100% |
| `test5_monotonicVersioning_invariantsAndFormula` | Verifies monotonic `versionCode` formula and Semantic Versioning regex | **PASS** | 100% |
| `test6_keyCustodyPolicyDocument_existsAndComplete` | Verifies existence and comprehensive contents of `KEY_CUSTODY_AND_PUBLISHER_IDENTITY.md` | **PASS** | 100% |
| `test7_apkSignatureSchemeSupport_v1v2v3v4Declared` | Verifies APK Signature Schemes v1, v2, v3, and v4 are enabled | **PASS** | 100% |
| **Total Test Suite** | **7 / 7 PASSED** | **100%** | **PASS** ✅ |

### B. Toolchain & Repository Audit (`tools/verify-signing-custody.ps1`)
```
============================================================
   Glide Signing Custody & Publisher Identity Auditor       
============================================================

[Check 1/5] Auditing repository for committed keystores and secret files...
  PASS: Zero keystores or unencrypted secrets detected in repository.

[Check 2/5] Verifying .gitignore protection rules...
  PASS: .gitignore comprehensively excludes all keystores and credential files.

[Check 3/5] Verifying app/build.gradle.kts signing configuration safety...
  PASS: app/build.gradle.kts has 0 hardcoded secrets and wires dynamic providers.

[Check 4/5] Inspecting cryptographic toolchain (keytool & apksigner)...
  Keytool found: C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe
  Apksigner found: C:\Users\GDJ\AppData\Local\Android\Sdk\build-tools\30.0.3\apksigner.bat

[Check 5/5] Checking Release Keystore Parameters...
  No external release keystore configured in environment; release signing defaults to unconfigured/unsigned.

============================================================
   ALL SIGNING CUSTODY & REPOSITORY CHECKS PASSED (0 issues)    
============================================================
```

---

## 4. Verification Suite Results

| Suite / Gate | Command | Result |
|:---|:---|:---:|
| **Signing & Publisher Identity Suite** | `.\gradlew.bat :app:testDebugUnitTest --tests "dev.glide.app.release.SigningConfigurationAndPublisherIdentityTest"` | **7/7 PASS (100%)** |
| **Release Candidate Quality Gates** | `.\gradlew.bat :app:testDebugUnitTest --tests "dev.glide.app.acceptance.ReleaseCandidateQualityGateTest"` | **11/11 PASS (100%)** |
| **Signing Custody Verification Tool** | `pwsh -File tools/verify-signing-custody.ps1` | **5/5 CHECKS PASSED** |
| **Architecture Enforcement Suite** | `pwsh -File tools/check-architecture.ps1` | **ALL 7 CHECKS PASSED** |
| **Full Module Unit Test Suite** | `.\gradlew.bat test` (all 6 modules) | **ALL TESTS PASS (100%)** |
| **Local CI Verification Pipeline** | `pwsh -File tools/verify-local.ps1` | **ALL 139 TASKS PASSED** |

---

## 5. Milestone M11 Progress & Next Steps

With GLIDE-060 complete, Milestone M11 (*Release preparation*) progress stands at **1/5 tasks (20%)**, and overall project progress stands at **60/68 tasks (88.2%)**.

**Next Task:** **GLIDE-061 — Prepare real store assets and legal/privacy listing** (`READY`).
