# Apple Backend Validation Runbook

This document defines the operational procedures, architecture boundaries, and execution steps for the backend validation of Glide's Apple platforms (**macOS native, iOS Simulator, and iPadOS Simulator**).

---

## 1. Objective and Architecture Boundaries

The primary objective is to verify Glide's Apple backend implementation across all supported platforms (`macOS`, `iOS`, `iPadOS`). This encompasses the production transfer engine (`GlideTransferEngine`), TLS 1.3 socket transport (`GlideNetworkListener`, `GlideNetworkClient`, `LanRecordChannel`), pairing invitation authority (`LanInvitationAuthority`, `AppleInvitationManager`), quarantine staging store (`QuarantineStagingStore`), persistent trust store (`PersistentTrustStore`), and verified history store (`PersistentHistoryStore`).

### Key Scope & Constraints
- **Scope**: Production Profile 1 protocol engine, framing, authentication, consent, SHA-256 stream integrity, quarantine staging, atomic commit rename, cancellation, single-session concurrency locks, and persistence.
- **Excluded**: Frontend visual appearances, SwiftUI animations, accessibility walkthroughs, and physical camera QR scanning (verified separately on physical devices).
- **Environment Qualification**: Results establish **Apple backend validation on iOS/iPadOS Simulator and native macOS**. They do not claim physical-device radio interoperability or physical camera scanning.

### Security and Storage Invariants
1. **Private Quarantine**: All incoming file data streams strictly into private staging (`.part` files) inside `QuarantineStagingStore`. Files are never written directly into destination folders.
2. **Independent SHA-256 Verification**: Receiver independently computes SHA-256 during chunk reception and compares against `ITEM_END` digest before atomic commit rename. Any mismatch immediately purges quarantine and emits `.failed`.
3. **Framing & Ceilings**:
   - Control payloads $\le 16\text{ KiB}$ (`LanProtocolCodec.maxControlBytes = 16384`).
   - Extensions $\le 1\text{ KiB}$.
   - Bulk stream chunks bounded to $\le 65,520\text{ bytes}$ (within 64 KiB record limits).
   - Batch limits: 1..1,000 files, $\le 64\text{ GiB}$ per file, $\le 256\text{ GiB}$ per batch.
4. **Zero Cleartext / Zero Browser Sharing**: Transfers occur exclusively over TLS 1.3 with SHA-256 SPKI pinned certificates. Browser file sharing remains permanently retired.
5. **Private Credentials & 0o600 Permissions**: Persistent trust and history files are stored with POSIX permissions `0o600` inside private application support paths.

---

## 2. Infrastructure Setup & Environment Baseline

### CI & Toolchain Pinned Baseline
- **OS**: macOS 26.6.2 (25G83) / arm64 Apple Silicon.
- **Xcode**: Xcode 26.6 (Build version `17F113`).
- **Swift**: Apple Swift version 6.3.3 (`swiftlang-6.3.3.1.3 clang-2100.1.1.101`).
- **Deployment Floors**: iOS/iPadOS 16.0+, macOS 13.0+.

### Disposable Simulators
Tests requiring application container or iOS/iPadOS lifecycle boundaries instantiate dedicated disposable simulators:
- **iPhone**: `GlideTest-iPhone-<RUN_ID>` (`iPhone 16`, runtime iOS 18.0+)
- **iPad**: `GlideTest-iPad-<RUN_ID>` (`iPad Air (11-inch) (M2)`, runtime iOS 18.0+)
- Simulators are booted, executed against, and deleted immediately upon test script completion via `trap ... EXIT`.

---

## 3. Production Components Under Test

| Component | Path | Responsibility |
|---|---|---|
| `GlideTransferEngine` | `apple/GlideCoreKit/Sources/.../GlideTransferEngine.swift` | Coordinates sender and receiver Profile 1 session lifecycles, manages session locking, drives quarantine staging, verifies streaming digests, and records history. |
| `LanControlSequence` | `apple/GlideCoreKit/Sources/.../LanControlSequence.swift` | Generates monotonic sequence numbers (`outboundSeq`) and verifies inbound sequence monotonicity over `LanRecordChannel`. |
| `LanProtocol` & `LanProtocolCodec` | `apple/GlideCoreKit/Sources/.../LanProtocol.swift` | Wire message envelopes (`HELLO`, `OFFER_PAGE`, `ITEM_START`, `ITEM_END`, `BATCH_END`), JSON validation, 16 KiB bounding, and UTF-8 BOM rejection. |
| `LanRecordChannel` | `apple/GlideCoreKit/Sources/.../LanRecordChannel.swift` | 5-byte framed record reader/writer over `Network.framework` `NWConnection`. |
| `QuarantineStagingStore` | `apple/GlideCoreKit/Sources/.../QuarantineStagingStore.swift` | Streams bytes to private `.part` file, computes incremental SHA-256, and executes atomic commit rename upon verified match. |
| `PersistentTrustStore` | `apple/GlideCoreKit/Sources/.../PersistentTrustStore.swift` | Secure storage of trusted peer certificates and fingerprints with 0o600 permissions. |
| `PersistentHistoryStore` | `apple/GlideCoreKit/Sources/.../PersistentHistoryStore.swift` | Verified transfer audit history with 500-entry ceiling and 0o600 permissions. |
| `IosTransferViewModel` | `apple/GlideIOS/ViewModels/IosTransferViewModel.swift` | iOS transfer supervision, Keychain TLS listener binding, invitation generation, and inbound connection dispatch. |
| `MacTransferViewModel` | `apple/GlideMac/ViewModels/MacTransferViewModel.swift` | macOS transfer supervision, Keychain TLS listener binding, invitation generation, and inbound connection dispatch. |

---

## 4. Executing the Validation Suite

### Automated Test Runner
Execute the unified Apple backend test script on a macOS host:

```bash
chmod +x apple/scripts/run-backend-tests.sh
./apple/scripts/run-backend-tests.sh
```

### GitHub Actions CI Workflow
Validation runs automatically via `.github/workflows/apple-development-build.yml` on push to `fix/native-completion-hardening` or manually via:

```bash
gh workflow run apple-development-build.yml --ref fix/native-completion-hardening
```

---

## 5. Required Validation Matrix

| Area | Scenario ID | Description | Expected Status |
|---|---|---|---|
| **Production Arrangements** | `ARR-MAC-RECEIVE` | Sender connects to Receiver listener; verified SHA-256 commit | PASS |
| | `ARR-MAC-HOSTED` | Receiver connects to Sender hosted listener; recipient confirmed | PASS |
| **Paging & Scale** | `PAGING-40-FILES` | Batch of 40 files spanning multiple `OFFER_PAGE` messages (>32 items) | PASS |
| **Batch Ceilings** | `LIMIT-1001-FILES` | Batch of 1,001 items rejected with `invalidFileCount(1001)` | REJECTED |
| | `LIMIT-EMPTY` | Empty batch (0 items) rejected with `invalidFileCount(0)` | REJECTED |
| | `LIMIT-NONEXISTENT` | Non-existent source file rejected with `fileNotFound` | REJECTED |
| **Mutual Consent** | `CONSENT-REJECT` | Receiver explicitly refuses offer; sender gets `offerRejected`; 0 files committed | REJECTED |
| | `RECIPIENT-REJECT` | Sender explicitly refuses recipient; transfer aborted | REJECTED |
| **Authentication & Framing** | `AUTH-EXPIRED` | Expired pairing invitation rejected with `protocolViolation` | REJECTED |
| | `FRAMING-16KIB` | Control payload exceeding 16 KiB rejected with `limitExceeded` | REJECTED |
| | `FRAMING-BOM` | Envelope with UTF-8 BOM rejected with `malformedMessage` | REJECTED |
| | `SEQ-MONOTONIC` | Monotonic sequence numbering enforced on outbound and inbound envelopes | PASS |
| **Storage & Quarantine** | `STORAGE-DIGEST` | Streaming SHA-256 match commits atomically to destination | PASS |
| | `STORAGE-PURGE` | Corrupted digest triggers quarantine purge; `.part` file deleted | PASS |
| | `STORAGE-SANITIZATION`| Directory traversal (`../../`) and unsafe filenames stripped or rejected | PASS |
| **Persistence & Trust** | `TRUST-PERSIST` | Trusted peer enrollment, query, revocation, and 0o600 file permissions | PASS |
| | `HIST-PERSIST` | Verified transfer history recording, 500-entry ceiling, and 0o600 file permissions | PASS |
| **Target Buildability** | `BUILD-IOS-SIM` | Compile `GlideIOS` application target for iOS Simulator | PASS |
| | `BUILD-SHARE-EXT` | Compile embedded `GlideShareExtension` and lint `Info.plist` | PASS |
| | `BUILD-MACOS` | Compile `GlideMac` application target for native macOS | PASS |

---

## 6. Trace Evidence & Artifact Structure

Test results and logs are deposited under:
```
build/test-evidence/apple-backend/<commit-sha>/<run-id>/
├── environment.txt             # Toolchain, OS, Xcode, Swift, and Git details
├── simulators.txt              # Disposable simulator UDIDs and runtime metadata
├── available-runtimes.txt      # Simctl available runtimes inventory
├── macos-backend.log           # Full console output from SPM test execution
├── ios-simulator.log           # Xcodebuild test execution output on iOS Simulator
├── ipados-simulator.log        # Xcodebuild test execution output on iPadOS Simulator
├── ios-simulator.xcresult      # Apple machine-readable test result bundle
├── ipados-simulator.xcresult   # Apple machine-readable test result bundle
└── test-summary.txt            # Final matrix digest and completion statement
```
