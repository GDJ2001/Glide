# Glide 1.0.0 Phase 1 Audit — End-to-End Path Traces, Refactoring Map & Release Performance Analysis

---

## 1. End-to-End Path Traces (Desktop Shared Core, Transport, Daemon & CLI)

### 1.1 Startup & Initialization
- **GUI (Windows `MainViewModel` / Linux `LinuxMainViewModel`)**:
  1. Single-instance check: On Linux, `LinuxSingleInstance` claims `$XDG_RUNTIME_DIR/glide/ui.sock` and forwards CLI args/staged files to the primary window.
  2. State & diagnostics initialization: Loads `DesktopStateStore` (`ReceiveDirectory`, `ListenerPort=42424`, `History` up to 500 items). **Divergence**: Linux UI explicitly passes `~/.local/share/glide/desktop-state.json` (`LinuxEnvironment.DataHome`), whereas `DesktopStateStore`'s default Linux path is `~/.local/state/Glide/desktop-state.json` (capital `Glide` in `XDG_STATE_HOME`), and `BoundedTransferDiagnosticSink` defaults to `~/.local/share/Glide/logs/glide-diagnostics.jsonl`.
  3. Identity & trust initialization: `DesktopPersistentTrustManager.Initialize()` calls `DesktopPersistentIdentityStore` (Windows: P-256 CNG `"GlideDeviceIdentityV1"` non-exportable; Linux: `$XDG_CONFIG_HOME/glide/identity-v1.pk8` validated 0700/0600, non-symlink) and loads `trust-v1.json`.
  4. Runtime & listener: Instantiates `DesktopTransferRuntime(new DesktopTransferEngine(...))`, `DesktopInvitationManager`, `MdnsDiscoveryService`, `DesktopPairingCoordinator`, and on Linux `DesktopListenerSupervisor` + `DesktopListenerOwnership.AcquireLinux()`.
- **CLI / Daemon (`Glide.App/Program.cs`)**:
  - Parses subcommand (`receive`, `daemon`, `send`, `identity`, `trust`, `web` -> fails with retired notice).
  - `receive` and `daemon` acquire `DesktopListenerOwnership.AcquireLinux()`, start `TlsSocketListener` directly (**bypassing `DesktopListenerSupervisor`**, and **never starting `MdnsDiscoveryService`**), and pass every accepted `TlsConnection` straight to `DesktopTransferEngine.ReceiveAsync` (Profile 1 only).
  - **Gap**: CLI `receive` / `daemon` ignores `GLD2` preamble (`LanSessionPreambleInspector`) and silently falls back to ephemeral port `0` if `--port` (or default `42424`) fails to bind (`Program.cs:267,449`).

### 1.2 Discovery (mDNS, Manual Entry, Duplicate Names, Stale Endpoints)
- **mDNS Responder & Browser (`MdnsDiscoveryService`)**:
  - Binds UDP `224.0.0.251:5353` per eligible UP IPv4 unicast interface (excludes loopback, `169.254/16`, and VirtualBox NAT `10.0.2.x` when non-NAT interfaces exist). **IPv6 is NOT supported** in mDNS sockets (`AddressFamily.InterNetwork` only).
  - Advertises `_glide._tcp.local` PTR/SRV/TXT/A with canonical TXT keys `v=1, tv=1, q=1, p=<presence>, dn=<name>, dt=<type>, pair=<0|1>, tr=<0|1>` (rejects forbidden keys `spki`, `pin`, `token`, `trust`, `fp`).
  - **Chatter amplification**: `ProcessIncomingPacket` re-announces whenever **any** mDNS query (`!header.IsResponse`) arrives on UDP 5353, without filtering questions for `_glide._tcp.local`.
  - **Cache & Eviction**: Keyed by presence/endpoint up to 256 entries, honors TTL=0 goodbye only for matching presence (preventing old presence goodbye from deleting a rotated replacement — tested in `LinuxAuditRegressionTests`), and advances `_generation` on debounced (500 ms) `NetworkAddressChanged` reconciliation so stale socket packets drop cleanly.
- **Duplicate Device Names & Stale Endpoints (`TrustedEndpointResolver`)**:
  - `TrustedEndpointResolver.ResolveCandidates(savedPeer, discoveredPeers)` ranks up to **8** candidates: matching fresh discovery endpoints (matching device name + `SupportsTrustedReconnect`) FIRST, followed by saved `LastKnownHost:LastKnownPort`, deduplicating `host:port`.
  - Actual peer identity is **never** trusted from mDNS name or presence: `DesktopTransferEngine.SendTrustedAsync` connects to candidates sequentially and enforces cryptographic P-256 identity proof against `ExpectedPeerFingerprintHex` BEFORE sending `OFFER_BEGIN` (proven by `LinuxAuditRegressionTests` `"ubuntu"` collision test).
- **Manual IP:Port**: `DesktopPairingCoordinator` parses numeric IP/port via `LanEndpoint`; `Glide.App --connect` uses `Split(':')`, breaking IPv6.

### 1.3 Pairing (QR, Link, 6-digit SPAKE2 PIN, SPKI Binding)
- **Profile 1 (`glide://pair/v1#`)**: Single-use `LanInvitation` (`LanInvitationAuthority`) carrying endpoint list, `spkiSha256`, 32-byte unpadded base64url token, 120s TTL. Client pins `TlsSocketClient` to `invitation.SpkiSha256`, sends 5-byte `LanRecord.Bootstrap` (`token`, `invitationHash`, `sessionId`, `role`), receiver validates in constant time (`FixedTimeEquals`) and consumes token once.
- **Profile 2 Pair-Only (`GLD2`, `glide://pair/v2#` or 6-digit PIN)**:
  1. Responder opens `PairReadyWindowAuthority` (120s TTL, CSPRNG 6-digit PIN + `v2` QR URI) and broadcasts `pair=1` via mDNS.
  2. Initiator connects over TLS 1.3 (no initial SPKI pin because `RemoteSpkiSha256` is authenticated via SPAKE2!), writes `GLD2` preamble + `SESSION_OPEN(purpose="PAIR_ONLY", authMethod="spake2-pin-v1"|"qr-token-v2")`.
  3. `DesktopListenerSupervisor` -> `DesktopProfile2Router` -> `DesktopPairingCoordinator` -> `Profile2PairingEngine`:
     - Responder checks `!isBusy` and active window (`PAIR_WAIT` window checks PIN 3-attempt failure ceiling -> invalidates window on 3rd wrong PIN).
     - Runs BoringSSL SPAKE2 (`GlideSpake2Session`, pinned commit `3fed4f20...`), exchanges `PAIR_MSG`, derives `k_mac` + confirms `PAIR_CONFIRM`, exchanges ECDSA-P256 signatures over canonical `GLIDE-PAIR-V2` binary transcript binding `tlsServerSpkiSha256Hex`, `purpose`, nonces, and both public keys.
     - Both users explicitly confirm (`PAIR_APPROVAL` / `approvePeerAsync`) -> enrolls peer in `DesktopPersistentTrustManager` -> `PAIR_RESULT`.

### 1.4 Trusted Reconnect (`TRUSTED_SEND`)
1. UI calls `TrustedEndpointResolver.ResolveCandidates` -> `DesktopTransferEngine.SendTrustedAsync`.
2. **Sender (`RunSenderSessionAsync` + `SendTrustedAsync`)**:
   - Opens TLS 1.3 without initial SPKI pin (`expectedSpkiSha256: null`), writes `GLD2` + `SESSION_OPEN(purpose="TRUSTED_SEND")`, receives `SESSION_OPEN_ACK` (or `BUSY`/`REJECTED`), then transitions stream into `LanRecordChannel` and runs `HELLO` / `HELLO_ACK` (requiring `identity-v1`).
   - Runs `DesktopTrustedSessionAuthenticator` (`IDENTITY_CHALLENGE` / `IDENTITY_PROOF` signed with `GLIDE-SIGN-V1`, binding `channelBindingHex = RemoteSpkiSha256`, capability hash, nonces, and roles) BEFORE any offer leaves sender.
   - Upon success, `onPeerCryptographicallyAuthenticated` updates `LastKnownHost` / `LastKnownPort` in `DesktopTrustStore`.
   - **Bug in `DesktopTransferEngine.cs:734-786`**: `preOfferAuthToken` (`resolutionBudgetCts.Token`) cancels `HELLO`/`AuthenticateSenderAsync` if budget expires, which is caught by `catch (Exception ex) when (!peerAuthenticated)` (L756), treated as `sawUnrelatedCandidateIdentity = true; continue;` -> misreports `"CouldNotVerifyDevice"` instead of `"DeviceUnavailable"`, and renders L777/L782 `catch` blocks unreachable dead code.
3. **Receiver (`DesktopListenerSupervisor` -> `DesktopProfile2Router` -> `DesktopTransferEngine.ReceiveTrustedAsync`)**:
   - Reads `SESSION_OPEN("TRUSTED_SEND")`; if runtime is busy, sends `SESSION_OPEN_ACK(accepted=false, code="BUSY")` immediately without blocking active transfer.
   - Otherwise wraps connection in `LanRecordChannel`, runs `HELLO`/`HELLO_ACK`, runs `DesktopTrustedSessionAuthenticator` (with `channelBindingHex = LocalSpkiSha256`), verifies peer is enrolled & non-revoked in `DesktopPersistentTrustManager` BEFORE showing offer consent.

### 1.5 Consent & Authorization
- **Interactive GUI (`Windows`/`Linux`)**:
  - Hosted-sender (`Profile 1` `role=receiver` invitation): prompts sender via `ConfirmRecipientAsync(peerName)`.
  - Receiver (Profile 1 & Profile 2 `TRUSTED_SEND`): prompts receiver via `ConfirmOfferAsync(offer)` BEFORE any `ITEM_READY` or `.quarantine` file creation. Declining sends `OFFER_DECISION(accepted=false)` and writes zero bytes to staging or destination.
- **Headless Daemon (`Glide.App daemon`)**:
  - Fails closed if `--accept-paired-offers` is passed without `--trusted-peer`.
  - When configured, `DesktopPersistentTrustManager.AuthorizeHeadlessOffer` requires `ExpectedPeerFingerprintHex` match AND `totalBytes <= MaxHeadlessOfferBytes`.
  - Wait: as noted in 1.1, `Glide.App daemon` currently only accepts Profile 1 connections, so `--trusted-peer` headless auto-accept only works for Profile 1 bootstrap if sender presents `identity-v1` (which isn't advertised in `ProductionOptional`!), meaning **Profile 2 `TRUSTED_SEND` daemon receive is currently unreachable in `Glide.App`**!

### 1.6 Transfer Engine, Framing & Finalization
- **Control Plane**: `LanRecord.Control` (5-byte header: `kind=1` + 4B BE length <= 16 KiB) carrying `StrictJsonParser`-validated envelopes with `DesktopControlSequence` (`seq >= 1` strictly monotonic per direction):
  - `HELLO` -> `HELLO_ACK` (version 1, capabilities, `limits`: 1000 items / 64 GiB item / 256 GiB batch / 16 KiB control).
  - `[IDENTITY_CHALLENGE <-> IDENTITY_PROOF]` (when trusted or expected fingerprint).
  - `OFFER_BEGIN` -> `OFFER_PAGE` (32 items/page, sanitized relative paths) -> `OFFER_END` -> `OFFER_DECISION`.
  - Per item: `ITEM_START(bulkRef, sizeBytes)` -> `ITEM_READY` -> `[LanRecord.Data* + LanRecord.StreamEnd]` -> `ITEM_END(actualBytes, sha256)` -> `ITEM_RESULT(verified|failed)`.
  - Batch close: `BATCH_END` -> `BATCH_RESULT`.
- **Payload Pipeline (`StreamSenderItemBoundedAsync` / `StreamReceiverItemBoundedAsync`)**:
  - Bounded `Channel<OwnedTransferBuffer>` (default 16 slots × 65,520 B chunks, capped at 8 MiB queue memory via `TransferBufferPool`).
  - Sender producer reads 65,520 B from `FileStream`, hashes incrementally (`IncrementalHash`), queues `OwnedTransferBuffer`; sender consumer writes `LanRecord.Data(bulkRef, span)` through `LanRecordChannel` (with optional 256 KiB / 5 ms coalescing).
  - Receiver consumer reads `LanRecord.OwnedData` from `LanRecordChannel` into `OwnedTransferBuffer`, queues to staging writer task which writes `.quarantine`, updates incremental SHA-256, and returns pooled buffer immediately in `finally`.
- **Finalization & Ack**:
  - Receiver finishes staging stream -> waits under `FinalizationDeadline = max(120s, min(6h, 60s + 3 * size / 10 MiB/s))` -> calls `QuarantineStagingStore.CommitVerifiedQuarantineFileWithMetricsAsync` -> sends `ITEM_RESULT(verified)`.
  - Sender waits for `ITEM_RESULT` using `FinalizationDeadline` and transitions phase to `WaitingForConfirmation` if commit exceeds ordinary control interval.

### 1.7 Quarantine Staging, Verification, Publication, Cleanup & Resumable State
- **Quarantine Staging (`QuarantineStagingStore`)**:
  - Preflight `VerifyDestinationWritableAsync` ensures writable directory before accepting offer.
  - Same-volume `.glide-staging/<sessionId>/<itemId>.quarantine` (or `$XDG_DATA_HOME/glide/staging` fallback) created with `FileMode.CreateNew`, locked ACLs (0700/0600 on Linux, explicit owner DACL on Windows), and `.glide-session.lease`.
  - **Publication (`CommitVerifiedQuarantineFileWithMetricsAsync`)**:
    1. Opens `.quarantine` via `OpenExclusiveVerificationStream` (on Windows: **`isAsync: false`**!).
    2. Calls `Flush(flushToDisk: true)` (`FlushFileBuffers` / `fsync`), then **re-reads & re-hashes the entire `.quarantine` file** from byte 0 in 256 KiB pooled buffers (`cryptographic zeroing` in `finally`).
    3. Verifies length + SHA-256 match `ITEM_END`, then publishes atomically with collision resolution (`name (1).ext`, ...):
       - **Windows**: `NtSetInformationFile` (`FileRenameInformation`, `ReplaceIfExists = false`) while handle open; fallback to verified copy if cross-volume.
       - **Linux**: `renameat2(..., RENAME_NOREPLACE)`; on `EXDEV`/`ENOSYS`/`EOPNOTSUPP`/`EINVAL`, falls back to `VerifiedCopyFallback`: writes `.glide-commit-<guid>.part` in destination, `fsync`s `.part`, **re-reads & re-hashes `.part` a THIRD time**, renames `.part` -> target, and `fsync`s parent directory!
    4. Watchdog bug (`QuarantineStagingStore.cs:930`): `RunWithProgressWatchdogAsync` throws `IOException(..., new TimeoutException(...))` instead of `TimeoutException`, bypassing `DesktopTransferEngine.cs:2671` `catch (TimeoutException)`.
- **Cleanup**: Session `finally` disposes lease and purges incomplete session dir; startup/store calls `PurgeAbandonedCommitFiles` (>15 min `.glide-commit-*.part`) and `PurgeAbandonedQuarantineSessions` (>24h stale `.quarantine`).
- **Resumable State (`DesktopResumeStore`)**:
  - Full 2,364-line chunk-level Merkle-verified store exists in `Glide.Core.Storage`, **completely disconnected from `DesktopTransferEngine`**. Never creates resume state in production transfers.

### 1.8 History, Settings, Daemon, Shutdown & Upgrades
- **History & Settings**: `DesktopStateStore` persists JSON (`schemaVersion=1`, `Settings`, max 500 `History` items) with `.corrupt-<timestamp>` recovery, without `WriteThrough` on `PersistLocked`.
- **Daemon**: Managed via `glide.service` (`systemd --user`, `glide daemon ...`), writing v1 QR URI to `$XDG_RUNTIME_DIR/glide/daemon-invitation.txt`. Missing `glide.service` argument quoting on `${GLIDE_DESTINATION}` and missing `GLD2` / mDNS support.
- **Shutdown**:
  - `DesktopTransferRuntime.DisposeAsync()` cancels `_activeCancellation` **without awaiting** `_activeBatch` / active transfer task completion — risking mid-cleanup abort of `QuarantineStagingStore` session purge on abrupt close.
  - `MdnsDiscoveryService.Dispose()` closes sockets synchronously without awaiting listener/query background loops.
  - `BoundedTransferDiagnosticSink.DisposeAsync()` completes bounded channel and awaits writer task flush cleanly (up to queue drain).
- **Upgrades**:
  - Canonical `.run` installer (`tools/package-linux-native.sh` + `installer-stub.sh`) installs atomically under `--prefix` (default `~/.local` or `/opt/glide`), writes `install-manifest.txt`, preserves `~/.config/glide` and `~/.local/share/glide` across upgrades/uninstalls, and refreshes `glide.service` + desktop entry.
  - Legacy `desktop/linux/build-linux-packages.ps1` is broken/obsolete and must not be shipped.

---

## 2. Specific Refactoring Map (Per R4)

### 2.1 Splitting `DesktopTransferEngine` (3,516 lines)
Preserve `DesktopTransferEngine` (and `IDesktopTransferExecutor`) as a thin backwards-compatible public facade delegating to 4 focused single-responsibility components inside `Glide.Desktop.Transfer`:

| Target Component | Extracted Responsibilities | Interfaces / Inputs & Outputs | Public Facade Compatibility |
|---|---|---|---|
| `DesktopSessionCoordinator` | Connection setup, Profile 1 `LanBootstrap` client/host auth, Profile 2 `GLD2` `SESSION_OPEN`/`ACK`, `HELLO`/`HELLO_ACK` capability negotiation, `DesktopTrustedSessionAuthenticator` invocation, `SendTrustedAsync` candidate loop & budget/identity error classification. | Takes `DesktopTimeoutPolicy`, `DesktopPersistentTrustManager?`, `LanInvitationAuthority?`; yields `AuthenticatedSessionContext` (`LanRecordChannel`, `SessionId`, negotiated capabilities, `DesktopControlSequence`, `PeerIdentity?`). | `DesktopTransferEngine.SendAsync`, `SendTrustedAsync`, `ReceiveAsync`, `ReceiveFromInvitationAsync`, `ReceiveTrustedAsync`, `SendHostedAsync` delegate session setup here. |
| `OfferExchangeHandler` | Folder/file manifest preparation, paginated `OFFER_BEGIN`/`OFFER_PAGE` (32 items)/`OFFER_END`, `OFFER_DECISION` exchange, `ConfirmRecipientAsync` / `ConfirmOfferAsync` consent, destination preflight & headless policy check. | Consumes `AuthenticatedSessionContext` + `IReadOnlyList<DesktopTransferFile>` or `IQuarantineStagingStore`; yields `ApprovedBatchPlan(BatchId, IReadOnlyList<ApprovedItem>)`. | Invoked immediately after `DesktopSessionCoordinator`. |
| `BoundedPayloadTransferPipeline` | `StreamSenderItemBoundedAsync` & `StreamReceiverItemBoundedAsync`, `TransferBufferPool`, disk read/write async tasks, incremental SHA-256 overlap, 5 Hz progress updates, `ITEM_START`/`ITEM_READY`/`ITEM_END` coordination. | Consumes `AuthenticatedSessionContext`, `ApprovedBatchPlan`, `DesktopTransferIoOptions`, `IQuarantineStagingStore`, `TransferAttemptDiagnostics`. | Cleanly isolates hot streaming loop for independent Release benchmarking & cancellation hardening. |
| `BatchFinalizer` | `CommitVerifiedQuarantineFileAsync`, `FinalizationDeadline` timer (`WaitingForConfirmation` phase), `ITEM_RESULT` & `BATCH_END`/`BATCH_RESULT` exchange, session cleanup & `DesktopHistoryEntry` emission. | Consumes `IQuarantineStagingStore`, `AuthenticatedSessionContext`, `ApprovedBatchPlan`, `IDesktopTransferInteraction`. | Centralizes finalization deadline, `TimeoutException` handling, and atomic verification metrics. |

*Additional R4 cleanup*: Consolidate duplicate `DesktopTransferSelection.cs` (Windows UI) and `DesktopBatchPreparer.cs` (Linux UI) into a single `DesktopBatchPreparer` in `Glide.Desktop.Services` with an option flag (`skipInvalidItems` vs `failOnInvalidItem`), keeping `DesktopTransferSelection` as a thin compatibility wrapper or removing it.

### 2.2 Splitting `QuarantineStagingStore` (2,597 lines) & Resumable-State / OS FS Interfaces
`QuarantineStagingStore` currently mixes OS P/Invoke syscalls (`kernel32`/`ntdll` on Windows, `libc` `renameat2`/`statx`/`fsync` on Linux), lease & collision policy, commit verification, and orphan cleanup in one 2,597-line class:

1. **`IQuarantineFileSystem` (already exists internally — elevate & split)**:
   - `Win32QuarantineFileSystem` (`NtSetInformationFile`, `CreateFile`/ACL hardening, `FlushFileBuffers`, `isAsync: true` handle wrapping).
   - `LinuxQuarantineFileSystem` (`renameat2(RENAME_NOREPLACE)`, `statx`, owner-uid/0700/0600 checks, symlink checks, `fsync` dir/file).
2. **`IQuarantineLeaseManager`**: Extracted session-lease counter, `.glide-session.lease` creation/disposal, startup `PurgeAbandonedQuarantineSessions` & `PurgeAbandonedCommitFiles`.
3. **`IPublicationVerifier` / `QuarantineCommitService`**: Handles collision resolution, optional verification pass, atomic rename vs `VerifiedCopyFallback`, parent dir `fsync`, and `Finalization` progress watchdog (propagating raw `TimeoutException` or typed `StorageProgressTimeoutException`).
4. **`IResumeStateStore` (over `DesktopResumeStore`)**: Define clean port interface (`TryGetResumableTransfer`, `RecordVerifiedChunkAsync`, `AssembleVerifiedAsync`) so `DesktopResumeStore` stays isolated and testable without coupling `DesktopTransferEngine` directly until `resume-v1` is promoted to `ProductionOptional`.

---

## 3. Release Performance, Buffer Pooling, Backpressure, Cancellation & Disposal Audit (R5 & R7)

### 3.1 Root Causes of 4.5–5 GB Transfer Slowdown & High Latency
1. **Triple/Double Full-File Disk I/O + Extra SHA-256 Passes on Receiver Commit (`QuarantineStagingStore.cs:1027-1099, 1455-1607`)**:
   - During payload streaming (`RunReceiverSessionCoreAsync`), the receiver ALREADY streams every byte to `.quarantine` while computing `IncrementalHash(SHA256)`.
   - On `ITEM_END`, `CommitVerifiedQuarantineFileWithMetricsAsync` opens `.quarantine`, flushes to disk (`Flush(flushToDisk: true)`), and **re-reads the entire 5 GB `.quarantine` file from byte 0 in 256 KiB chunks to re-compute SHA-256** (`VerificationReadBufferBytes`) before calling `renameat2`/`NtSetInformationFile`.
   - Worse, on Linux/Windows cross-volume fallback (`VerifiedCopyFallback`, e.g., when staging is on XDG `~/.local/share/glide/staging` on `/` while `~/Downloads` is on a separate mount/subvolume), Glide:
     1) Streams + hashes 5 GB to `.quarantine` (**Write 5 GB + Hash Pass 1**)
     2) Flushes + re-reads + hashes 5 GB from `.quarantine` (**Read 5 GB + Hash Pass 2**)
     3) Copies 5 GB `.quarantine` -> `.part` in destination, `fsync`s `.part` (**Read 5 GB + Write 5 GB**)
     4) **Re-reads + hashes `.part` 5 GB a THIRD time** before renaming (**Read 5 GB + Hash Pass 3**)
   - Result on a 5 GB file: **15–20 GB of local disk I/O + 2–3 full SHA-256 passes**, exploding commit latency cleanly into the multi-second/minute tail as OS page cache saturates around 4.5–5 GB!
2. **Synchronous Windows File Handle in Verification Read (`QuarantineStagingStore.cs:2167-2168`)**:
   - `OpenExclusiveVerificationStream` constructs `new FileStream(handle, FileAccess.ReadWrite, bufferSize, isAsync: false)` on Windows, forcing `.NET` `ReadAsync` onto synchronous blocking I/O (threadpool starvation + extra syscall transitions).
   - Every verification pass also calls `CryptographicOperations.ZeroMemory(buffer)` on 256 KiB buffers (`L1097, L1614`) even though `buffer` only holds received user file plaintext (not keys).
3. **Sender Sequential Hash + Read Coupling (`DesktopTransferEngine.cs:3109-3170`)**:
   - `StreamSenderItemBoundedAsync` performs `FileStream.ReadAsync` + `hash.AppendData` **serially in `producerTask`** before `boundedQueue.Writer.WriteAsync`, whereas `consumerTask` calls `LanRecordChannel.WriteDataChunkAsync` and `FlushPendingCoreAsync` holding `_writeLock` across socket writes. At 2.5–10 Gbps LAN speeds or when TLS encryption contends on one core, serializing read + SHA-256 on the producer stalls filling the 16-slot queue.
4. **Spurious 256 KiB Pool Churn When Coalescing Is Disabled (`LanRecordChannel.cs:420`)**:
   - `WriteDataChunkAsync` calls `EnsureWriteBuffer()` which rents a 256 KiB `_writeBuffer` (`_coalesceBufferBytes`) from `ArrayPool<byte>.Shared` **even when `_coalesceWrites == false`**.
5. **128 KiB ArrayPool Bucket Mismatch (`TransferBufferPool.cs` / `DesktopTransferIoOptions.cs`)**:
   - `ChunkSizeBytes = 65,520` (`> 65,536`? No, 65,520 <= 65,536, so `ArrayPool<byte>.Shared` returns a **65,536 B** bucket — wait: `LanRecordCodec.HeaderSize (5) + 16 (BulkRef) + 65,520 = 65,541 B`! In `LanRecordChannel` or `LanRecordCodec`, any frame buffer sized `65,541` > `65,536` steps up to the **131,072 B (128 KiB)** `ArrayPool` bucket, doubling retained buffer footprint!).
6. **Redundant `FileInfo.Length` Syscalls (`DesktopTransferModels.cs:39`)**:
   - `DesktopTransferFile.SizeBytes` calls `new FileInfo(FilePath).Length` on every property read (`SendAsync`, `SendTrustedAsync`, `BuildOfferPages`, `RunSenderSessionCoreAsync` stat each file multiple times).

### 3.2 Bounded Streaming, Backpressure, Cancellation Latency & Disposal
- **Bounded Memory & Backpressure**:
  - `BoundedChannelOptions(maxQueueBuffers=16)` with `BoundedChannelFullMode.Wait` caps in-flight bulk chunks cleanly (`<= 1 MiB` raw payload + 16 × 64 KiB/128 KiB pooled buffers <= 8 MiB ceiling enforced by `TransferBufferPool`).
  - **Defect**: `Diagnostics.UpdateQueueOccupancy` in `StreamSenderItemBoundedAsync` (`L3147`) passes `0, 0` for receiver depths, and `StreamReceiverItemBoundedAsync` (`L3273`) passes `0, 0` for sender depths — harmless in multi-process, misleading in single-process loopback tests.
- **Cancellation Latency**:
  - `LanRecordChannel.CancelAndAbortTransport()` immediately calls `abortTransport()` (`socket.Close(0)` via `TlsSocketListener`/`TlsSocketClient`), breaking blocked socket reads/writes in **< 200 ms** (verified in `LinuxAuditRegressionTests`).
  - `StreamReceiverItemBoundedAsync` links `writerCts` to abort `readerTask` immediately if disk staging fails (`< 5s` verified, typically ms).
  - **Gap**: `DesktopTransferRuntime.DisposeAsync()` cancels `_activeCancellation` and returns immediately without awaiting the running batch task (`DesktopTransferRuntime.cs:349`).
- **Resource Disposal**:
  - `TransferBufferPool` uses `OwnedTransferBuffer` with `Interlocked` single-return refcounting (`RentedBufferCount == 0` verified under fault tests).
  - `MdnsDiscoveryService.Dispose()` skips awaiting `_listenTasks`/`_queryLoopTask` and `StopAdvertising()` calls `.GetAwaiter().GetResult()`.
  - `LinuxPresentationTests` / `LinuxRenderedUiTests` omit `using` disposal on ~19 `LinuxMainViewModel` instances.

---

## 4. Dead / Unused Code & Dependency Candidates (Verified via Repo-Wide Search)

All callers checked across `desktop/`, `android/`, and `tools/` via `git grep`:

| Symbol / File | Tracked Location | Production Callers | Test / Tool References Only | Recommendation |
|---|---|---|---|---|
| **Remote Subsystem** (`IceCandidate`, `MeteredNetworkProtection`, `RelayQuotaTracker`, `RemoteInvitation`, `RemoteInvitationCodec`, `RemoteTransferSession`, `IceCandidateGatherer`, `RemoteSignalingClient`, `SignalingMessages`, `StunClient`, `TurnClient`) | `Glide.Core/Remote/*` (6 files), `Glide.Transport/Remote/*` (5 files) | **0** | 9 unit test files in `Glide.Tests` + `tools/verify-remote-transfer-spec.ps1` | Remove-candidate (or split into deferred experimental branch/project so 1.0.0 binary carries zero unshipped WebSocket/STUN/TURN attack surface). |
| `LengthPrefixedFrameChannel` | `Glide.Transport/Framing/LengthPrefixedFrameChannel.cs` | **0** | `FramingAndTransportTests.cs`, `tools/verify-windows-companion.ps1` | Remove-candidate (update spec script). |
| `ProtocolCodec.EncodeFramed` / `DecodeFramed` | `Glide.Core/Codec/ProtocolCodec.cs:1044-1099` | **0** | **0** | Remove dead methods (4-byte framing conflicts with 5-byte `LanRecordCodec`). |
| `BulkProgress` | `Glide.Core/Models/BulkProgress.cs` | **0** | **0** | Remove-candidate (unused Kotlin port artifact). |
| `SasCoordinator` & `SasCalculator` | `Glide.Desktop/Services/SasCoordinator.cs`, `Glide.Core/Security/SasCalculator.cs` | **0** | `SasCalculatorTests.cs`, `tools/verify-windows-companion.ps1` | Remove-candidate (superseded by SPAKE2 + `GLIDE-PAIR-V2` fingerprints). |
| `TransferEvent` subclasses | `Glide.Desktop/TransferEvent.cs:27-59` | **0** | **0** | Remove unused `TransferEvent` union (keep `TransferErrorCategory` enum). |
| `WindowsPathSanitizer.SanitizeWebFilename` | `Glide.Core/Storage/WindowsPathSanitizer.cs:109-177` | **0** | `WindowsPathSanitizerTests.cs` | Remove (web sharing retired per `AGENTS.md`). |
| `DesktopResumeStore` & `DesktopResumeSourceVerifier` | `Glide.Core/Storage/DesktopResumeStore.cs` (2,364 lines) | **0** | `DesktopResumeStoreTests.cs`, `tools/run_hard036_and_hard039_physical.py` | Unwired in production! Either keep behind R4 `IResumeStateStore` interface for post-1.0.0 activation or exclude from release path until `resume-v1` is implemented. |
| `DesktopTransferSelection` (duplicate) | `Glide.Desktop/DesktopTransferSelection.cs` | Only `Glide.Windows.UI/ViewModels/MainViewModel.cs:928` | `DesktopTransferSelectionTests.cs` | Merge with `DesktopBatchPreparer.cs` (used by Linux UI) and delete duplicate. |
| `desktop/linux/build-linux-packages.ps1` | `desktop/linux/build-linux-packages.ps1` | **0** | `desktop/README.md:21` | **Remove** — stale, broken (missing `Glide.Linux.UI`, wrong `arm64` `.deb` arch, weak stub). Superseded by `tools/package-linux-native.sh`. |
| `desktop/linux/glide-daemon-policy.example.json` | `desktop/linux/glide-daemon-policy.example.json` | **0** | `desktop/README.md:23`, `desktop/linux/README.md` | **Remove** or implement JSON policy reader (currently unparsed/misleading). |
| `Glide.App/Properties/launchSettings.json` | Profiles `"list"`, `"--version"` | Dev IDE only | None | Clean up invalid profiles (neither command exists in `Program.cs`). |
| `Glide.Desktop/DesktopTransferEngine.cs:777-786` | `catch (CryptographicException)` / `AuthenticationFailed` after `when (!peerAuthenticated)` | Unreachable | None | Fix unreachable dead `catch` blocks during R4 engine refactor. |
