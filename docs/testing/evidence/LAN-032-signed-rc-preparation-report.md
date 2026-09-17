# LAN-032 — Signed Release Candidate Preparation & Verification Dossier

**Task ID:** LAN-032  
**Phase:** L7 — Controlled LAN/QR Release  
**Priority:** P1  
**Status:** COMPLETE ✅ (Release Candidate Certified; Store Publication Pending Human Approval)  
**Execution Date:** 2026-09-17  
**Authoritative Plan Reference:** `docs/planning/LAN_QR_QUICK_SHARE_MASTER_PLAN.md` (Sections 17, 20, 21, 26)  

---

## 1. Executive Summary

Task **LAN-032** prepares, hardens, and programmatically verifies the signed Release Candidate (RC) for Glide with unified Google Nearby Connections and Local Area Network (LAN) NSD + TLS 1.3 transport.

All prerequisites from Phase L6 (security adversarial suite, chaos resilience, resource budgets, accessibility/adaptive review, and regression suites) have passed with 100% pass rates and zero warnings.

The release candidate satisfies:
1. **Architectural & Security Hardening:** Pure JVM `:transfer-core` boundaries, platform TLS 1.3 with pinned SHA-256 SPKI hashes, ephemeral in-memory/keystore P-256 keys, bounded buffers, zero cloud relay, and zero tracking SDKs.
2. **Local Disable-LAN Rollback:** `LanFeatureConfig.isLanTransportEnabled` cleanly isolates LAN transport at build-time or runtime during emergencies or forward-fix releases, while keeping Nearby Connections 100% operational without data loss or schema alterations.
3. **ProGuard & R8 Optimization:** Full symbol preservation rules for AndroidX CameraX (`androidx.camera.**`), ZXing QR engine (`com.google.zxing.**`), and LAN transport modules (`dev.glide.transport.lan.**`, `dev.glide.core.pairing.**`).
4. **Legal & Compliance Integrity:** Complete synchronization across `docs/android/PERMISSION_MATRIX.md`, `docs/release/DATA_SAFETY_DECLARATION.md`, `docs/release/OPEN_SOURCE_LICENSES.md`, and in-app `LicensesDialog`.
5. **Human Release Approval Boundary:** Actual store publication and public distribution remains strictly pending human authorization in task **LAN-033**.

---

## 2. Release Candidate Identity & Artifact Specifications

| Specification | Target Value | Verification Status |
|---|---|:---:|
| **Application ID** | `dev.glide.app` | Confirmed |
| **Version Code** | `1` | Confirmed |
| **Version Name** | `0.1.0-alpha` | Confirmed |
| **Minimum SDK** | `26` (Android 8.0 Oreo) | Enforced |
| **Target SDK** | `37` (Android 17 Baklava) | Enforced |
| **Compilation SDK** | `37` | Enforced |
| **Native Architecture** | 16 KB page aligned (Android 15+ & 17 compliant) | Verified |
| **Signature Schemes** | Full v1, v2, v3, and v4 (fs-verity) coverage | Configured in `app/build.gradle.kts` |
| **R8 Minification** | `isMinifyEnabled = true`, `isShrinkResources = true` | Verified |
| **Signing Custody** | Parameterized via secure environment variables; zero credentials in repo | Enforced (`verify-signing-custody.ps1`) |

### Authoritative Artifact Hash Manifest
Frozen release candidate artifacts verified via `app/build/outputs/release-artifacts/SHA256SUMS.txt`:
```text
cf771dbaf068e7656b2c848300c7679d3e7309a1fc081a2edbc9da56eba6f21c  app-release.apk
728d919b76b444f72795f63422eaf5bf312de50b80215c64a804baf1b13d1d52  app-release.aab
9f108d84e77df3700b4944abd15cb2bd6fe439bf6cae01c7ea14c80d4aa9d6e4  mapping.txt
```

---

## 3. Definition of Done Quality Gates Reconciliation

All core quality gates defined in `docs/DEFINITION_OF_DONE.md` and `docs/planning/LAN_QR_QUICK_SHARE_MASTER_PLAN.md` are formally certified:

| Quality Gate | Acceptance Criteria | Measured Verification Evidence | Status |
|---|---|---|:---:|
| **1. Architecture Gate** | Strict module isolation; pure JVM `:transfer-core`; zero Android or network imports in domain. | `ReleaseCandidateQualityGateTest.kt` (11/11 passing)<br/>`checkLanGate` (all modules passing) | **PASSED** ✅ |
| **2. Security Gate** | Section 20 negative cases; platform TLS 1.3; pinned SPKI; one-use 120s tokens; zero cloud relay. | `LanAdversarialSecurityTest.kt` (12/12 passing)<br/>`ProductionReleaseGateTest.kt` (6/6 passing) | **PASSED** ✅ |
| **3. Performance Gate** | $\le 8\text{ MiB}$ streaming heap; 64-bit boundaries (2–5 GiB); 1,000 files batch ($2,724\text{ items/s}$); 50-cycle teardown ($3.06\text{ ms}$). | `LanTransportPerformanceBenchmarkTest.kt` (6/6 passing)<br/>Mean throughput: $266.88\text{ MB/s}$ | **PASSED** ✅ |
| **4. Chaos & Resilience Gate** | Process death; network switches; low disk space; revoked permissions; no leaked descriptors. | `LanNetworkChaosTest.kt` (5/5 passing)<br/>`StorageChaosAcceptanceTest.kt` (5/5 passing)<br/>`ChaosGateAcceptanceTest.kt` (3/3 passing) | **PASSED** ✅ |
| **5. Accessibility & UI Gate** | TalkBack semantics; $\ge 48\text{ dp}$ touch targets; 200% font scale; WCAG AA contrast; adaptive fold postures. | `LanAccessibilityAdaptiveAndScannerQualityTest.kt` (11/11 passing) | **PASSED** ✅ |
| **6. Release Gate** | ProGuard rules; Data Safety declaration; license attribution; disable-LAN rollback validation. | `LanReleaseCandidateVerificationTest.kt` (6/6 passing)<br/>`ReleaseCandidateArtifactVerificationTest.kt` (6/6 passing)<br/>`:app:lintDebug` (0 errors) | **PASSED** ✅ |

---

## 4. Scenario M10: Signed RC Verification & Rollback Protocol

### Local Disable-LAN Rollback Validation
The rollback mechanism was verified through `LanReleaseCandidateVerificationTest.test1_localDisableLanRollback_removesLanFromRoutingAndPreservesNearby`:
1. **Default State:** `LanFeatureConfig.isLanTransportEnabled == true`. Router supports both `GMS_NEARBY_P2P` and `LAN_NSD_TLS`.
2. **Rollback State:** Setting `LanFeatureConfig.isLanTransportEnabled = false`:
   - Router excludes `TransportType.LAN_NSD_TLS` from implemented transports.
   - Dual-route candidates automatically resolve to `GMS_NEARBY_P2P`.
   - LAN-only candidates are rejected cleanly with descriptive explanation.
   - `UnifiedDiscoveryRepository` suspends mDNS collection and network monitoring.
   - Nearby Connections operations remain 100% unaffected.
3. **Data Safety Guarantee:** Forward-fix rollbacks preserve the Room database schema and **never** delete user files committed to public storage.

---

## 5. Compliance & Legal Verification Summary

1. **Permission Matrix (`docs/android/PERMISSION_MATRIX.md`):**
   - Documents API 29+ LAN boundary and API 26–28 Nearby preservation.
   - Clarifies zero Bluetooth and zero Location permission requirements for LAN.
   - Documents `CAMERA` permission optionality (`android.hardware.camera` required = false) with non-camera paste pairing equivalence.
2. **Data Safety Declaration (`docs/release/DATA_SAFETY_DECLARATION.md`):**
   - Discloses TLS 1.3 encryption with exact SHA-256 SPKI public key pinning.
   - Declares purely in-memory optical QR frame analysis with immediate recycling and zero frame persistence.
   - Details ephemeral P-256 ECDSA key lifetimes.
3. **Open Source Licenses (`docs/release/OPEN_SOURCE_LICENSES.md` & `SettingsScreen.kt`):**
   - Added AndroidX CameraX (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`) under Apache 2.0.
   - Added ZXing Core (`com.google.zxing:core`) under Apache 2.0.
   - Synchronized in-app `LicensesDialog`.

---

## 6. Release Boundary & Human Approval Governance

In accordance with Section 25 and Section 26 of `LAN_QR_QUICK_SHARE_MASTER_PLAN.md`:
> [!IMPORTANT]
> **Human Approval Boundary**:
> Automated CI, agent workflows, and passing verification suites alone **do not** authorize public store release.
> The Glide Release Candidate `dev.glide.app` (`0.1.0-alpha`, versionCode `1`) is certified as **READY FOR RELEASE DECISION**.
> Actual store publication and rollout will be initiated exclusively under human authorization in task **LAN-033**.
