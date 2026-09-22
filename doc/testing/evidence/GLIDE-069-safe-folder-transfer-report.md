# Evidence Report — GLIDE-069: Safe Folder Transfer Extension Specification

**Author:** Gemini Code Assistant  
**Date:** 2026-09-14  
**Milestone:** M13 — Future work (outside V1)  
**Task:** GLIDE-069  
**Status:** COMPLETE ✅ (ADR-0014 Accepted, Safe Folder Transfer Specification Published, Pure JVM Path Validation Fixtures Verified, Threat Model Covered)  

---

## 1. Executive Summary

GLIDE-069 initiates Milestone M13 (*Future work outside V1*) by establishing the authoritative architecture, technical specification, path traversal defense invariants, Storage Access Framework (SAF) performance optimization plans, and pure JVM path validation fixtures for **safe recursive folder transfers** in **Glide** (`dev.glide.app`).

All acceptance criteria defined in [`docs/TODO_MASTER.md`](../../TODO_MASTER.md) and [`docs/future/CROSS_PLATFORM_PATH.md`](../../future/CROSS_PLATFORM_PATH.md) have been systematically fulfilled:
1. **Architectural Decision Record (ADR-0014):** Formulated and indexed [`docs/adr/ADR-0014-safe-folder-transfer-extension.md`](../../adr/ADR-0014-safe-folder-transfer-extension.md), selecting discrete item streaming with canonical normalized POSIX relative paths over risky archive containerization (ZIP/TAR streaming).
2. **Comprehensive Specification:** Authored [`docs/future/SAFE_FOLDER_TRANSFER_SPECIFICATION.md`](../../future/SAFE_FOLDER_TRANSFER_SPECIFICATION.md), defining capability negotiation (`CAP_FOLDER_TRANSFER = 0x01`), extended item manifest with `relativePath`, bounded complexity limits, collision handling, and threat model analysis.
3. **Strict Bounded Complexity Limits:**
   - Max recursion depth: $\le 16\text{ levels}$.
   - Max total file count: $\le 10,000\text{ files}$.
   - Max total subdirectories: $\le 1,000\text{ directories}$.
   - Max relative path string length: $\le 1024\text{ bytes}$.
   - Max single segment length: $\le 255\text{ bytes}$.
4. **SAF Direct ContentResolver Traversal Performance Plan:**
   - Designed low-level query plan on `DocumentsContract.buildChildDocumentsUriUsingTree` with projection optimization, bypassing high-latency `DocumentFile.listFiles()` IPC overhead.
   - Enforced cyclic loop detection via visited `documentId` tracking (`HashSet<String>`).
   - Defined streaming backpressure pipeline with bounded heap memory footprint ($< 16\text{ MB}$).
5. **Destination Collision & User Data Sanctity Invariant:**
   - Non-destructive monotonic collision renaming (e.g. `Folder (1)/...`) guaranteed upon destination conflict.
   - Two-stage commit architecture isolating unverified items in `cacheDir/staging/<session_id>/` with zero unverified files written to public destination storage.
6. **Pure JVM Path Validation & Security Model:**
   - Implemented [`SafeRelativePath.kt`](../../../transfer-core/src/main/kotlin/dev/glide/core/folder/SafeRelativePath.kt) in `:transfer-core` with zero Android framework dependencies.
   - Created comprehensive unit tests [`SafeRelativePathTest.kt`](../../../transfer-core/src/test/kotlin/dev/glide/core/folder/SafeRelativePathTest.kt) asserting rejection of directory traversal (`..`, `.`), root delimiters (`/`, `\`), Windows drive letters (`C:`), null bytes, control characters, and reserved device names (`CON`, `PRN`, `AUX`, `NUL`, etc.).
7. **Threat Model Coverage:** Evaluated and mitigated threats T01–T08, T10–T15, and T17.
8. **Automated Verification:** Created [`tools/verify-folder-extension-spec.ps1`](../../../tools/verify-folder-extension-spec.ps1) (5/5 checks passed) and [`SafeFolderTransferComplianceTest.kt`](../../../app/src/test/kotlin/dev/glide/app/future/SafeFolderTransferComplianceTest.kt) (6/6 tests passed).

---

## 2. Bounded Complexity & Security Invariant Matrix

| Constraint / Rule | Specification Limit | Implementation Verification | Status |
|:---|:---:|:---|:---:|
| **Max Recursion Depth** | $\le 16\text{ levels}$ | `SafeRelativePath.MAX_DEPTH = 16` | Verified ✅ |
| **Max Relative Path Length** | $\le 1024\text{ characters}$ | `SafeRelativePath.MAX_PATH_LENGTH = 1024` | Verified ✅ |
| **Max Segment Length** | $\le 255\text{ characters}$ | `SafeRelativePath.MAX_SEGMENT_LENGTH = 255` | Verified ✅ |
| **Parent Traversal (`..`)** | Strictly Banned | RegEx and segment loop check in `SafeRelativePath.kt` | Verified ✅ |
| **Current Dir (`.`)** | Strictly Banned | Segment loop check in `SafeRelativePath.kt` | Verified ✅ |
| **Root Slashes (`/`, `\`)** | Strictly Banned | `startsWith('/') \|\| startsWith('\\')` check | Verified ✅ |
| **Drive Letters (`C:`, `D:`)** | Strictly Banned | Drive prefix validation check | Verified ✅ |
| **Null Bytes & Control Chars** | Strictly Banned | `ch.code < 0x20 \|\| ch.code == 0x7F` check | Verified ✅ |
| **Reserved DOS/Windows Names** | Strictly Banned | `CON`, `PRN`, `AUX`, `NUL`, `COM1-9`, `LPT1-9` check | Verified ✅ |

---

## 3. Automated Verification Results

| Suite / Gate | Command | Result |
|:---|:---|:---:|
| **Pure JVM Path Validation Suite** | `.\gradlew.bat :transfer-core:test --tests "dev.glide.core.folder.SafeRelativePathTest"` | **9/9 PASS (100%)** |
| **Folder Extension Compliance Suite** | `.\gradlew.bat :app:testDebugUnitTest --tests "dev.glide.app.future.SafeFolderTransferComplianceTest"` | **6/6 PASS (100%)** |
| **Folder Extension Spec Auditor** | `pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\verify-folder-extension-spec.ps1` | **5/5 PASS (0 violations)** |
| **Architecture Enforcement** | `pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\check-architecture.ps1` | **7/7 PASS (0 issues)** |
| **Local CI Verification Pipeline** | `pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\verify-local.ps1` | **139/139 tasks PASS (0 lint errors)** |

---

## 4. Milestone Progress & Next Steps

- **Milestone M13 (*Future work outside V1*):** 1/4 tasks complete (25%).
- **Task GLIDE-069:** COMPLETE ✅.
- **Next Task:** **GLIDE-070 — Specify persistent identity and true resume** (READY 🚀).
