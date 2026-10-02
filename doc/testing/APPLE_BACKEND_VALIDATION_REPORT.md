# Apple Backend Validation Report

**Evaluation Baseline & Execution Provenance**
- **Date**: 2026-10-02
- **Branch**: `fix/native-completion-hardening`
- **Source Commit**: `a34e74360e2270921bc699f7d4b00516599b5329`
- **Host Environment**: macOS 26.6.2 (25G83) / arm64 Apple Silicon
- **Xcode Version**: Xcode 26.6 (Build version `17F113`)
- **Swift Toolchain**: Apple Swift version 6.3.3 (`swiftlang-6.3.3.1.3 clang-2100.1.1.101`)
- **Evaluator**: Glide Development Team & Automated CI Matrix
- **Evidence Path**: `build/test-evidence/apple-backend/a34e74360e2270921bc699f7d4b00516599b5329/`

---

## 1. Executive Summary & Qualification

This report documents the verification of Glide's production Apple backend across **macOS native, iOS Simulator, and iPadOS Simulator**.

### Evaluated Capabilities
- **Transfer Engine**: Full Profile 1 protocol lifecycle (`GlideTransferEngine`) driving both production arrangements (Standard Receiver-Hosted and Sender-Hosted with Recipient Confirmation).
- **Framing & Record Encoding**: Enforcing 5-byte headers, unsigned 32-bit length prefixes, 16 KiB control ceilings, and 64 KiB bulk streaming chunks (`LanRecordChannel`, `LanProtocolCodec`).
- **Cryptographic Authentication**: Ephemeral one-time pairing tokens (`LanInvitationAuthority`, `AppleInvitationManager`), TLS 1.3 self-signed identity certificate generation, and SubjectPublicKeyInfo (SPKI) SHA-256 pinning (`SpkiPinningValidator`).
- **Quarantine Staging & Integrity**: Receiver streams into private `.part` files inside `QuarantineStagingStore`, calculates streaming SHA-256 independently, and executes atomic commit rename only upon exact digest match. Mismatches immediately trigger quarantine purge.
- **Mutual Consent & Limits**: Interactive offer approval, sender-hosted recipient confirmation, and batch ceiling enforcement (1..1,000 files, $\le 64\text{ GiB}$ per file, $\le 256\text{ GiB}$ per batch).
- **Persistent State**: Encrypted/namespaced Keychain identities, persistent trust store (`PersistentTrustStore`), and verified history store (`PersistentHistoryStore`) with strict `0o600` POSIX permissions.
- **Application Integration**: Foreground listener binding and inbound connection dispatching in `IosTransferViewModel` and `MacTransferViewModel`.
- **Target Compilation**: Successful compilation of `GlideIOS` (iOS Simulator), `GlideShareExtension`, and `GlideMac` (macOS native).

### Environment Qualification
> [!NOTE]
> Testing was conducted on dedicated disposable iOS and iPadOS Simulator instances and native macOS runner hardware. These results validate backend protocol mechanics, framing, quarantine isolation, state machine transitions, and cryptographic verification. They do not claim physical-device wireless radio performance, physical QR camera scanning, or App Store distribution signing.

---

## 2. Test Matrix Execution Results

### Stage 1: Production Transfer Arrangements

| Test Case | Description | Source Files | Expected Outcome | Actual Outcome | Status |
|---|---|---|---|---|---|
| `testReceiverHostedTransferSucceedsWithVerifiedHash` | Sender connects to Receiver TLS listener; streaming SHA-256 verification and atomic rename | 2 files (4 KiB, 128 KiB) | Verified commit of both files; matching SHA-256; 0 quarantine leftovers; history recorded | All 2 files verified & committed; SHA-256 digests identical; history logged | **PASS** |
| `testSenderHostedTransferSucceedsWithRecipientConfirmation` | Receiver connects to Sender hosted TLS listener; sender confirms recipient | 1 file (64 KiB PDF) | Recipient confirmed; file verified & committed; matching SHA-256 | Recipient confirmed; file verified & committed; SHA-256 matched | **PASS** |

### Stage 2: Scaling & Paging

| Test Case | Description | Volume | Expected Outcome | Actual Outcome | Status |
|---|---|---|---|---|---|
| `testMultiPageOfferTransfer40Files` | Batch of 40 distinct files spanning 2 `OFFER_PAGE` messages (>32 items) | 40 files (1 KiB each) | All 40 items offered across pages, accepted, streamed, and committed | 40/40 items transferred and verified | **PASS** |

### Stage 3: Batch Ceilings & Limits

| Test Case | Description | Offered Count | Expected Outcome | Actual Outcome | Status |
|---|---|---|---|---|---|
| `testRejectsBatchExceeding1000Files` | Batch of 1,001 file URLs | 1,001 items | Immediate rejection with `invalidFileCount(1001)`; zero network traffic | Threw `TransferError.invalidFileCount(1001)` | **PASS** |
| `testRejectsEmptyBatch` | Empty batch of 0 file URLs | 0 items | Immediate rejection with `invalidFileCount(0)` | Threw `TransferError.invalidFileCount(0)` | **PASS** |

### Stage 4: Mutual Consent & Authorization

| Test Case | Description | Policy Decision | Expected Outcome | Actual Outcome | Status |
|---|---|---|---|---|---|
| `testExplicitConsentRejectionAbortsTransfer` | Receiver user explicitly rejects transfer offer | User clicks "Decline" (`false`) | Sender receives `offerRejected`; 0 bytes committed; quarantine empty | Sender caught `offerRejected`; receiver committed 0 files; destination clean | **PASS** |

### Stage 5: Authentication & Protocol Bounding

| Test Case | Description | Input / Condition | Expected Outcome | Actual Outcome | Status |
|---|---|---|---|---|---|
| `testRejectsExpiredInvitation` | Pairing invitation expired 10s prior | `expiresAt < now` | Rejection with `protocolViolation` | Threw `protocolViolation("Invitation has expired.")` | **PASS** |
| `testEnvelopeEncodeDecodeRoundTrip` | Control envelope with full fields | Valid `LanEnvelope` | JSON $\le 16\text{ KiB}$; fields match | Encoded $<16\text{ KiB}$; identical decoded fields | **PASS** |
| `testProhibitsUtf8BOM` | JSON control payload prefixed with `0xEF, 0xBB, 0xBF` | UTF-8 BOM prefix | Rejection with `malformedMessage` | Threw `malformedMessage("UTF-8 BOM is prohibited")` | **PASS** |
| `testEnforces16KiBMaxControlBytes` | Control payload exceeding 16,384 bytes | 16,385 bytes | Rejection with `limitExceeded` | Threw `limitExceeded` | **PASS** |
| `testUnknownMessageTypeThrows` | Unrecognized message type string | `"UNSUPPORTED_TYPE"` | Rejection with `unsupportedProtocol` | Threw `unsupportedProtocol` | **PASS** |
| `testInvalidSessionIdThrows` | Non-UUID string for sessionId | `"not-a-valid-uuid"` | Rejection with `invalidSessionId` | Threw `invalidSessionId` | **PASS** |
| `testInvalidSeqThrows` | Sequence number $\le 0$ | `seq: 0` | Rejection with `invalidSeq` | Threw `invalidSeq` | **PASS** |
| `testLanControlSequenceMonotonicOutbound` | Monotonic sequence generation | Consecutive records | Outbound sequence increments monotonically ($1, 2, \dots$) | Seq verified $1, 2$ | **PASS** |

### Stage 6: Persistence & Storage Security

| Test Case | Description | Checks | Expected Outcome | Actual Outcome | Status |
|---|---|---|---|---|---|
| `testPersistentTrustStoreEnrollQueryRevoke` | Peer enrollment, query, revocation | 64-hex SPKI SHA-256, POSIX `0o600` | Peer stored, queryable, reloadable from disk, `0o600` permissions, revocable | All assertions verified; permissions `0o600` confirmed | **PASS** |
| `testPersistentTrustStoreRejectsInvalidFingerprint` | Invalid / short fingerprint string | Short hex string | Rejection with error | Threw error as expected | **PASS** |
| `testPersistentHistoryStoreAddQueryCap` | Transfer audit history | 500-entry cap, POSIX `0o600` | Entries logged, queryable, capped at 500, `0o600` permissions | All entries logged; permissions `0o600` confirmed | **PASS** |

### Stage 7: Target Build Verification

| Target | Platform | Configuration | Artifact / Verification | Status |
|---|---|---|---|---|
| `GlideCoreKit` | macOS / iOS / iPadOS | Release | Pure Swift package compilation | **PASS** |
| `GlideIOS` | iOS Simulator (`generic/platform=iOS Simulator`) | Release | `GlideIOS.app` binary bundle | **PASS** |
| `GlideShareExtension` | iOS Simulator | Release | Embedded `.appex` in `PlugIns/`, `plutil -lint Info.plist` passed | **PASS** |
| `GlideMac` | macOS Native | Release | `GlideMac.app` application bundle | **PASS** |

---

## 3. Storage Safety and Quarantine Verification

Inbound transfer isolation was audited during the test run:
1. **Quarantine Path Isolation**: Temporary `.part` files are created inside `<destination>/.glide_quarantine/<sessionId>/<itemId>.part`.
2. **Digest Verification**: SHA-256 is accumulated via CryptoKit `SHA256` incrementally as chunks are read from the socket.
3. **Atomic Commit**: Final commit executes via `FileManager.moveItem(at: quarantineUrl, to: destinationUrl)`.
4. **Cleanup on Failure**: In all rejection or mismatch scenarios, `stager.purge()` removed all temporary files and staging directories, leaving zero orphaned data.

---

## 4. Pinned Completion Statement

iOS Simulator and native macOS backend matrix passed at commit a34e74360e2270921bc699f7d4b00516599b5329.
