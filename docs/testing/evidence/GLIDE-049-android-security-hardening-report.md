# GLIDE-049: Android Security Hardening Evidence Report

**Author:** Antigravity Agent  
**Date:** 2026-09-13  
**Milestone:** M8 — Security Hardening  
**Task:** GLIDE-049 — Harden Android components, storage and privacy  
**Status:** COMPLETE ✅  

---

## 1. Executive Summary

Task GLIDE-049 executes the second phase of Milestone M8 (*Security Hardening*), focusing on Android platform component security, storage isolation, multi-version backup leak prevention, Share Sheet ingress boundaries, notification privacy, safe FileProvider scoping, and cryptographic entropy integrity.

In accordance with:
- [docs/security/GLIDE_THREAT_MODEL.md](file:///d:/GITHub/Glide/docs/security/GLIDE_THREAT_MODEL.md) (Threats T09–T15, T18)
- [docs/security/SECURITY_CHECKLIST.md](file:///d:/GITHub/Glide/docs/security/SECURITY_CHECKLIST.md)
- [docs/android/STORAGE_AND_BACKGROUND.md](file:///d:/GITHub/Glide/docs/android/STORAGE_AND_BACKGROUND.md)
- [docs/development/GEMINI_ENGINEERING_RULES.md](file:///d:/GITHub/Glide/docs/development/GEMINI_ENGINEERING_RULES.md)

all Android components are strictly isolated, cloud backups and D2D migrations are explicitly disabled across all API levels, FileProvider paths are narrowed to dedicated subdirectories, Share Sheet ingress enforces scheme and count boundaries, and foreground notifications guarantee lockscreen privacy.

---

## 2. Threat Model Mitigations Implemented & Verified

| Threat Code | Threat Category | Implemented Defense & Location | Verified Behavior |
|---|---|---|---|
| **T09** | Unauthorized App Access | Only MainActivity is exported for launcher/ingress. TransferService is non-exported (xported="false"). | Background transfer service cannot be invoked or hijacked by unauthorized 3rd-party apps. |
| **T10** | Local Data Leak / Insecure Logging / Notification PII | Notification visibility set to NotificationCompat.VISIBILITY_PRIVATE with a generic public version (uildPublicNotification). Filename and peer redaction when 
otificationDetails is disabled. Zero PII, auth digits, or filenames in production logs. | Lockscreen shows generic "Glide file transfer" with zero metadata; release logs contain zero sensitive data. |
| **T11** | Backup Leakage / Cloud Data Extraction | Multi-version backup exclusion: ndroid:allowBackup="false", data_extraction_rules.xml (API 31+), and ackup_rules.xml (API <31). Excludes oot, database, sharedpref, ile, and xternal domains. | Zero transfer history (glide_history.db), quarantined files (eceived/), or settings leak via Google Drive or D2D transfer. |
| **T12** | Intent Spoofing / Malicious Ingress | ShareIngressParser validates incoming intents, strictly allowing only content:// schemes; rejects ile://, http://, https://, tp://, and scripts. Rejects text/link shares. Enforces ProtocolLimits.MAX_ITEMS_PER_BATCH. | Local file inclusion (LFI), SSRF, and parcelable exploits are blocked at the ingress boundary. |
| **T13** | Randomness / Token Weakness | Canonical UUIDv4 generated using cryptographically strong SecureRandom (DefaultIdGenerator via UUID.randomUUID()). Value classes enforce lowercase canonical representation. Zero unseeded java.util.Random in security contexts. | 1,000 consecutively generated session IDs verified unique with zero collisions, matching UUID_V4_REGEX. |
| **T14** | File Provider / External App Over-exposure | Narrowed ile_paths.xml from overly broad <cache-path path="." /> to <cache-path name="shares" path="shares/" /> and <files-path name="received" path="received/" />. FileOpener grants strictly FLAG_GRANT_READ_URI_PERMISSION. | Third-party viewer apps cannot access arbitrary cache files or private databases. Read-only access only. |
| **T15** | Malicious File Content / Traversal Ingress | Ingress deduplication preserves distinct references; non-existent files return FileNotFound; quarantining strictly checks context.filesDir/received. | Path traversals rejected; zero access to uncommitted or unverified temporary files. |
| **T18** | Diagnostics & Telemetry Leakage | Complete absence of analytics, advertising, tracking, or telemetry SDKs. Diagnostic logs restricted to high-level lifecycle counts. | 100% offline-first local operation verified. |

---

## 3. Component Hardening Details

### 3.1 AndroidManifest Component Isolation (pp/src/main/AndroidManifest.xml)
- **Single Exported Activity:** Only MainActivity declares ndroid:exported="true" for MAIN/LAUNCHER and SEND/SEND_MULTIPLE intent filters.
- **Isolated Internal Service:** TransferService declares ndroid:exported="false" with ndroid:foregroundServiceType="connectedDevice".
- **Protected FileProvider:** ndroidx.core.content.FileProvider declares ndroid:exported="false" and ndroid:grantUriPermissions="true".
- **Explicit Backup Disablement:** Declares ndroid:allowBackup="false", ndroid:dataExtractionRules="@xml/data_extraction_rules", and ndroid:fullBackupContent="@xml/backup_rules".

### 3.2 Backup and Data Extraction Rules
- **Modern Android (API 31+ / Android 12+):** pp/src/main/res/xml/data_extraction_rules.xml
  - Defines <cloud-backup> excluding oot, database, sharedpref, ile, and xternal.
  - Defines <device-transfer> excluding oot, database, sharedpref, ile, and xternal.
  - Declares 0 <include> rules.
- **Legacy Android (API <31):** pp/src/main/res/xml/backup_rules.xml
  - Defines <full-backup-content> excluding oot, database, sharedpref, ile, and xternal.
  - Declares 0 <include> rules.

### 3.3 FileProvider Scoping (pp/src/main/res/xml/file_paths.xml)
- **Restricted Cache Path:** <cache-path name="shares" path="shares/" /> restricts shared cache access exclusively to the shares/ subdirectory (replaces vulnerable path=".").
- **Restricted Received Path:** <files-path name="received" path="received/" /> scopes verified file access to iles/received/.
- **Root and External Storage Prohibited:** 0 <external-path>, 0 <external-files-path>, and 0 <root-path> entries.

### 3.4 Share Ingress Boundary Validation (ShareIngressParser.kt)
- Strictly requires content:// scheme; rejects ile:// (preventing local private file extraction), web URLs (http://, https://), and executable schemes (javascript:, tp://).
- Rejects plain text/URL shares in V1 with explicit user feedback.
- Enforces batch ceiling of at most ProtocolLimits.MAX_ITEMS_PER_BATCH (,000$ items).
- Deduplicates identical URIs without altering presentation order.

### 3.5 Notification Privacy (TransferNotificationManager.kt)
- All notifications configure NotificationCompat.VISIBILITY_PRIVATE.
- Provides uildPublicNotification which outputs a sanitized public notification:
  - Title: "Glide file transfer"
  - Text: "Transfer in progress"
  - Zero filenames, zero peer identifiers, zero transferred byte counts, zero ETA metrics.
- Honors GlideSettings.notificationDetails: when disabled, notification titles and texts hide filenames during AwaitingConsent, Transferring, Verifying, and Completed states.

---

## 4. Test Verification Suite (AndroidSecurityHardeningTest.kt)

A dedicated security hardening unit test suite was added to :app:
pp/src/test/kotlin/dev/glide/app/security/AndroidSecurityHardeningTest.kt

### Test Case Execution Summary:
1. manifest_onlyMainActivityIsExported — PASSED ✅ (Verifies XML DOM of manifest components)
2. manifest_backupConfigurationIsExplicitAndDisabled — PASSED ✅ (Verifies manifest attributes)
3. dataExtractionRules_excludesAllPrivateDomainsFromCloudAndDeviceTransfer — PASSED ✅ (Verifies cloud and D2D exclusions)
4. ackupRules_excludesAllPrivateDomainsFromLegacyBackup — PASSED ✅ (Verifies legacy backup exclusions)
5. ileProvider_scopesPathsNarrowlyWithoutRootExposure — PASSED ✅ (Verifies XML DOM of file paths)
6. shareIngress_rejectsInsecureSchemes — PASSED ✅ (Verifies rejection of ile://, http://, https://, tp://)
7. shareIngress_rejectsTextAndLinkShares — PASSED ✅ (Verifies rejection of text-only shares)
8. shareIngress_enforcesCeilingAndDeduplication — PASSED ✅ (Verifies batch bounding and deduplication)
9. 
otificationPrivacy_visibilityIsPrivateAndPublicVersionIsSanitized — PASSED ✅ (Verifies lockscreen privacy)
10. ileOpener_returnsFileNotFoundForNonExistentOrUnverifiedFiles — PASSED ✅ (Verifies missing file handling)
11. ileOpener_resolveReceivedFile_strictlyChecksReceivedDirectory — PASSED ✅ (Verifies received directory boundaries)
12. cryptographicRandomness_generatesUniqueCanonicalUuidV4 — PASSED ✅ (1,000 UUIDv4 tokens generated with 0 collisions)
13. identifierTypes_rejectNonCanonicalAndMalformedInputs — PASSED ✅ (Verifies canonical UUIDv4 validation across value classes)

**Result:** 13/13 passing (100% green).

---

## 5. Verification Gate Summary

- .\gradlew.bat :app:testDebugUnitTest --tests "dev.glide.app.security.AndroidSecurityHardeningTest": **SUCCESS** (13/13 tests, 0 failures)
- .\gradlew.bat :app:testDebugUnitTest: **SUCCESS** (All app unit tests green)
- .\gradlew.bat test: **SUCCESS** (All unit tests across all 5 modules green)
- pwsh -File tools/verify-local.ps1: **SUCCESS** (139 tasks, 0 lint errors, all tests passed)
