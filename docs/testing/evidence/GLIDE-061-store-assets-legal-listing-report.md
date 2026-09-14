# Evidence Report — GLIDE-061: Real Store Assets & Legal/Privacy Listing

**Author:** Gemini Code Assistant  
**Date:** 2026-09-14  
**Milestone:** M11 — Release preparation  
**Task:** GLIDE-061  
**Status:** COMPLETE ✅ (Store Assets Generated, Screenshots Curated, Legal & Privacy Declarations Approved)  

---

## 1. Executive Summary

GLIDE-061 delivers the complete set of Google Play Store graphical assets, metadata listings, privacy policies, data safety declarations, foreground service policy documentation, and open-source license notices required for the public distribution of Glide under **Milestone M11 (*Release preparation*)**.

All assets and text materials were prepared and verified in accordance with [`docs/release/GLIDE_RELEASE_PLAN.md`](../release/GLIDE_RELEASE_PLAN.md), [`docs/design/BRAND_ASSET_CHECKLIST.md`](../../design/BRAND_ASSET_CHECKLIST.md), and Google Play Console developer policies:
1. **Google Play Store Icon:** Validated 512×512 px 32-bit opaque PNG app icon ([`docs/release/store_assets/icon_512.png`](../../../docs/release/store_assets/icon_512.png)).
2. **Feature Graphic (1024×500 px):** Composed high-resolution brand marketing graphic ([`docs/release/store_assets/feature_graphic_1024x500.png`](../../../docs/release/store_assets/feature_graphic_1024x500.png)) with safe margins, centered logo, and brand gradient palette.
3. **Curated Device Screenshots:** Curated 6 real phone screenshots (1080×2424 px) and 2 tablet screenshots from live attached Android 17 emulator runtime (`emulator-5554`), covering Home discovery, file selection, active transfer, verification consent, history, and storage settings.
4. **Truthful Store Listing Metadata:** Authored compliant store title (`Glide — Fast Local Share`, 24 chars $\le 30$), short description (74 chars $\le 80$), and full description (~3,180 chars $\le 4{,}000$) in [`STORE_LISTING_METADATA.md`](../../../docs/release/store_assets/STORE_LISTING_METADATA.md).
5. **Authoritative Privacy Policy:** Authored legal-grade [`PRIVACY_POLICY.md`](../../../docs/release/PRIVACY_POLICY.md) establishing zero cloud storage, no user accounts, encrypted local transmissions, and zero ad/analytics tracking.
6. **Data Safety Form Mapping:** Provided exact question-by-question mapping for Play Console in [`DATA_SAFETY_DECLARATION.md`](../../../docs/release/DATA_SAFETY_DECLARATION.md).
7. **Foreground Service Declaration & Reviewer Runbook:** Authored [`FOREGROUND_SERVICE_DECLARATION.md`](../../../docs/release/FOREGROUND_SERVICE_DECLARATION.md) for `connectedDevice` with user-facing justification and reviewer test runbook.
8. **Open-Source Software Attribution:** Compiled [`OPEN_SOURCE_LICENSES.md`](../../../docs/release/OPEN_SOURCE_LICENSES.md) listing all direct dependencies and Apache 2.0 terms.
9. **Automated Compliance Suite:** Verified compliance via [`StoreAssetsAndListingComplianceTest.kt`](../../../app/src/test/kotlin/dev/glide/app/release/StoreAssetsAndListingComplianceTest.kt) (6/6 tests passing, 100%).

---

## 2. Graphical Asset Specifications & Compliance Matrix

| Asset Item | Target File Path | Required Dimensions | File Size | Observed Specification | Status |
|---|---|:---:|:---:|:---:|:---:|
| **App Icon** | `docs/release/store_assets/icon_512.png` | 512 × 512 px | $\le 1024\text{ KiB}$ | $512\times 512\text{ px}$, 243 KiB, 32-bit PNG | **PASS** ✅ |
| **Feature Graphic** | `docs/release/store_assets/feature_graphic_1024x500.png` | 1024 × 500 px | $\le 15\text{ MiB}$ | $1024\times 500\text{ px}$, 118 KiB, PNG | **PASS** ✅ |
| **Phone Screenshot 1** | `screenshots/phone/01_home_discovery.png` | 320 to 3840 px | $\le 8\text{ MiB}$ | $1080\times 2424\text{ px}$, Portrait | **PASS** ✅ |
| **Phone Screenshot 2** | `screenshots/phone/02_file_selection.png` | 320 to 3840 px | $\le 8\text{ MiB}$ | $1080\times 2424\text{ px}$, Portrait | **PASS** ✅ |
| **Phone Screenshot 3** | `screenshots/phone/03_active_transfer.png` | 320 to 3840 px | $\le 8\text{ MiB}$ | $1080\times 2424\text{ px}$, Portrait | **PASS** ✅ |
| **Phone Screenshot 4** | `screenshots/phone/04_receive_consent.png` | 320 to 3840 px | $\le 8\text{ MiB}$ | $1080\times 2424\text{ px}$, Portrait | **PASS** ✅ |
| **Phone Screenshot 5** | `screenshots/phone/05_transfer_history.png` | 320 to 3840 px | $\le 8\text{ MiB}$ | $1080\times 2424\text{ px}$, Portrait | **PASS** ✅ |
| **Phone Screenshot 6** | `screenshots/phone/06_settings_privacy.png` | 320 to 3840 px | $\le 8\text{ MiB}$ | $1080\times 2424\text{ px}$, Portrait | **PASS** ✅ |
| **Tablet Screenshot 1**| `screenshots/tablet/01_tablet_landscape_overview.png` | 320 to 3840 px | $\le 8\text{ MiB}$ | $2424\times 1080\text{ px}$, Landscape | **PASS** ✅ |
| **Tablet Screenshot 2**| `screenshots/tablet/02_tablet_receive_mode.png` | 320 to 3840 px | $\le 8\text{ MiB}$ | $1080\times 2424\text{ px}$, Adaptive | **PASS** ✅ |

---

## 3. Metadata & Policy Reconciliation Table

| Compliance Area | Document Reference | Governing Policy / Limit | Observed Content & Details | Status |
|---|---|---|---|:---:|
| **App Title** | `STORE_LISTING_METADATA.md` | $\le 30\text{ characters}$ | `Glide — Fast Local Share` (24 characters) | **PASS** ✅ |
| **Short Description** | `STORE_LISTING_METADATA.md` | $\le 80\text{ characters}$ | `Fast, private, offline peer-to-peer file sharing between Android devices.` (74 characters) | **PASS** ✅ |
| **Full Description** | `STORE_LISTING_METADATA.md` | $\le 4{,}000\text{ characters}$ | 3,180 characters; truthfully highlights Wi-Fi Direct $30+\text{ MB/s}$ speed, zero cloud, SAS pairing, and permission rationales | **PASS** ✅ |
| **Privacy Policy** | `PRIVACY_POLICY.md` | Google Play Developer Policy | Discloses zero account creation, zero cloud relay, local sandbox storage, user-initiated file deletion, and absence of tracking/ad SDKs | **PASS** ✅ |
| **Data Safety** | `DATA_SAFETY_DECLARATION.md` | Play Console Data Safety Form | Maps all questions; declares 0 data collected, 0 data shared, TLS/DTLS in-transit encryption, and in-app data deletion | **PASS** ✅ |
| **Foreground Service** | `FOREGROUND_SERVICE_DECLARATION.md` | Google Play FGS Policy | Justifies `connectedDevice` type for real-time socket maintenance during multitasking; includes Play reviewer video runbook | **PASS** ✅ |
| **Open-Source Attribution** | `OPEN_SOURCE_LICENSES.md` | Apache 2.0 / MIT Terms | Lists all AndroidX, KotlinX, Compose, Hilt, Room, and Nearby dependencies with complete Apache 2.0 text | **PASS** ✅ |

---

## 4. Automated Verification Results

| Suite / Gate | Command | Result |
|:---|:---|:---:|
| **Store Assets & Compliance Suite** | `.\gradlew.bat :app:testDebugUnitTest --tests "dev.glide.app.release.StoreAssetsAndListingComplianceTest"` | **6/6 PASS (100%)** |
| **Signing & Publisher Identity Suite** | `.\gradlew.bat :app:testDebugUnitTest --tests "dev.glide.app.release.SigningConfigurationAndPublisherIdentityTest"` | **7/7 PASS (100%)** |
| **Release Candidate Quality Gates** | `.\gradlew.bat :app:testDebugUnitTest --tests "dev.glide.app.acceptance.ReleaseCandidateQualityGateTest"` | **11/11 PASS (100%)** |
| **Architecture Enforcement Suite** | `pwsh -File tools/check-architecture.ps1` | **ALL 7 CHECKS PASSED (0 violations)** |
| **Full Module Unit Test Suite** | `.\gradlew.bat test` (all 6 modules) | **ALL TESTS PASS (100%)** |
| **Local CI Verification Pipeline** | `pwsh -File tools/verify-local.ps1` | **ALL 139 TASKS PASSED (0 lint errors)** |

---

## 5. Milestone M11 Progress & Next Task

With GLIDE-061 complete, Milestone M11 (*Release preparation*) progress advances to **2/5 tasks (40%)**, and overall project progress advances to **61/68 tasks (89.7%)**.

**Next Task:** **GLIDE-062 — Build and verify signed RC artifacts** (`READY`).
