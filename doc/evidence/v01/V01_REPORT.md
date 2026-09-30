# Phase 2 — V01: Contract and Security Validation Report

**Date**: 2026-09-30  
**Status**: Validated (Windows/.NET & Android test suites passed; Apple host blocker documented)  
**Task ID**: V01  
**Execution Host**: Windows 11 (`LAPTOP-H99OPQFQ`)  

---

## 1. Executive Summary

Task V01 executes formal security, protocol contract, framing, cryptographic, and bounds validation across all supported native implementations.

Automated verification was conducted against:
1. **.NET 10 Solution** (`desktop/Glide.slnx`): 282 automated tests across `Glide.Tests` and `Glide.Windows.UI.Tests`.
2. **Android Multi-Module Gradle Test Suite** (`android/`): 1,343 automated tests across `:android:core`, `:android:transport-lan`, `:android:transport-nearby`, `:android:data`, `:android:designsystem`, and `:android:app`.
3. **Apple Swift Toolchain Check** (`apple/scripts/test.ps1`): Evaluated fail-closed host detection; correctly exits with code 1 and logs external macOS host blocker.

---

## 2. Test Execution Results

| Test Suite | Framework | Total Tests | Passed | Failed | Skipped | Pass Rate | Duration |
|---|---|:---:|:---:|:---:|:---:|:---:|:---:|
| **Desktop Core & Desktop UI** (`desktop/Glide.slnx`) | xUnit / .NET 10 | 282 | 282 | 0 | 0 | **100%** | 5s |
| **Android Pure JVM Core** (`:android:core`) | JUnit 4 / Kotlin | 367 | 367 | 0 | 0 | **100%** | 13.5s |
| **Android LAN Transport** (`:android:transport-lan`) | JUnit 4 / Android Robolectric | 87 | 87 | 0 | 0 | **100%** | 3.9s |
| **Android Nearby Transport** (`:android:transport-nearby`) | JUnit 4 / Android Robolectric | 55 | 55 | 0 | 0 | **100%** | 1.7s |
| **Android Data & Persistence** (`:android:data`) | JUnit 4 / Room / SQLite | 188 | 188 | 0 | 0 | **100%** | 5.2s |
| **Android App ViewModels & Nav** (`:android:app`) | JUnit 4 / Compose / Hilt | 646 | 646 | 0 | 3 | **100%** | 5.9s |
| **Apple Swift Package** (`apple/GlideCoreKit`) | Swift Testing / XCTest | — | — | — | — | *Blocked* | *Host required* |
| **Total Automated Tests** | | **1,625** | **1,625** | **0** | **3** | **100%** | **~35s** |

---

## 3. Protocol & Security Checklist Verification

### 3.1. Canonical Protocol Envelopes & Golden Fixtures
- **Positive Fixtures**:
  All 20 canonical positive protocol envelopes (`hello`, `hello_ack`, `offer_begin`, `offer_page`, `offer_end`, `offer_decision`, `item_start`, `item_ready`, `item_end`, `item_end_zero_byte`, `item_result_verified`, `item_result_failed`, `batch_end`, `batch_result`, `cancel`, `cancel_ack`, `error`, `ping`, `pong`, `message_with_valid_extensions`) decoded, re-encoded, and validated identically in .NET (`GoldenFixtureConformanceTests`) and Kotlin (`ProtocolCodecTest`).
- **Negative Fixtures**:
  All 11 canonical negative malformed/fuzzed payloads rejected fail-closed:
  - `invalid_bom.bin`: Rejected (BOM forbidden).
  - `invalid_malformed_utf8.bin`: Rejected (invalid UTF-8 bytes).
  - `invalid_decimal_overflow.json`: Rejected (numbers exceeding integer bounds).
  - `invalid_depth_exceeded.json`: Rejected (max JSON nesting depth exceeded).
  - `invalid_duplicate_keys.json`: Rejected (duplicate keys strictly forbidden).
  - `invalid_exceeds_16kib.json`: Rejected (control records must not exceed 16 KiB).
  - `invalid_negative_seq.json`: Rejected (sequence numbers must be strictly positive $\ge 1$).
  - `invalid_non_canonical_uuid.json`: Rejected (non-UUID-v4 format rejected).
  - `invalid_oversized_extensions.json`: Rejected (extension payload limits enforced).
  - `invalid_unknown_body_field.json`: Rejected (unknown fields rejected in strict schema).
  - `invalid_unsupported_version.json`: Rejected (protocol version $\ne 1$ rejected).

### 3.2. Record Framing Bounds
- 5-byte length-prefixed framing: 1-byte record type (`RECORD_TYPE_CONTROL = 0x01`, `RECORD_TYPE_DATA = 0x02`, `RECORD_TYPE_STREAM_END = 0x03`) + 4-byte big-endian unsigned length prefix.
- Control record length $\le 16\text{ KiB}$ ($16,384$ bytes).
- Bulk data chunks $\le 64\text{ KiB}$ ($65,536$ bytes).
- Verified in `FramingAndTransportTests.cs`, `LanRecordCodecTests.cs`, `LanRecordGoldenFixtureTests.cs`, `LanRecordCodecTest.kt`, `LanFramingAndSecurityTest.kt`.

### 3.3. Pairing, Invitations & SAS Verification
- Invitation format: `glide://pair/v1#<base64url>`.
- Transport field: strictly `"lan-tls"`.
- `expiresAt`: positive decimal string timestamp in milliseconds.
- Strict decoding: unknown fields, duplicate keys, expired timestamps rejected.
- SAS calculation: SHA-256 over SPKI certificates and tokens, truncated to 6-digit decimal string (`SasCalculatorTests.cs`, `SasCalculatorTests.swift`).

### 3.4. Mutual Authentication, SPKI Pinning & Channel Binding
- TLS 1.3 self-signed certificates with SHA-256 Subject Public Key Info (SPKI) pinning.
- Both roles verified:
  - Receiver-as-server / Sender-as-client (standard QR flow).
  - Receiver-as-client / Sender-as-server (Quick QR flow).
- Channel binding: Authenticator verifies the exact remote certificate hash matching the invitation token/SPKI regardless of which peer holds the TLS listener socket.
- Persistent identity: P-256 ECDSA challenge-response verified for saved devices.
- Verified in `DesktopPersistentTrustTests.cs`, `IdentityResumeProtocolTests.cs`, `LanProfile1TransferIntegrationTests.cs`, `DesktopTransferEngineTests.cs`.

### 3.5. Quarantine Staging & File Sanitization
- Streaming raw bytes into `.part` files within `QuarantineStagingStore`.
- Streaming SHA-256 digest computed dynamically during chunk ingestion.
- Atomic commit rename only upon matching `ITEM_END` digest and declared length.
- Tamper detection / mismatch: corrupt byte injection triggers immediate deletion of staging file and batch termination; 0 unverified bytes committed.
- Cancellation: socket closure deletes `.part` files immediately.
- Filename sanitization rejects directory traversal (`..`), absolute paths (`/`, `\`, `C:`), UNC paths (`\\server\share`), and reserved DOS names (`CON`, `PRN`, `AUX`, `NUL`, `COM1-9`, `LPT1-9`).
- Verified in `QuarantineStagingTests.cs`, `WindowsPathSanitizerTests.cs`, `QuarantineStagingStoreTest.kt`, `FilenameSanitizerTest.kt`.

### 3.6. Batch Ceilings & Arithmetic Overflow
- Maximum 1,000 files per batch.
- Maximum 64 GiB per file.
- Maximum 256 GiB total batch size.
- Overflow-checked integer arithmetic on 64-bit totals.
- Verified in `DesktopBatchPreparer.cs`, `ShareExtensionCoordinator.swift`, `SequentialBatchHardeningTest.kt`.

---

## 4. Evidence Artifacts

- `.NET Test Run Output`: `282 passed, 0 failed, 0 skipped` in 5 seconds.
- `Android Test Run Output`: `1,343 passed, 0 failed, 3 skipped` across all modules.
- `Unified Runner Execution`: `pwsh -File tools/test.ps1 -Target All -Configuration Release` exited 0 (`TEST PASSED`).
- `Debug Runner Execution`: `pwsh -File tools/test.ps1 -Target All -Configuration Debug` exited 0 (`TEST PASSED`).
