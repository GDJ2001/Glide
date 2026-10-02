# Linux–Windows Backend Validation Report

**Document Status:** Complete & Verified  
**Date:** 2026-10-02  
**Baseline Commit:** `ea739dcff5171d4dcf0000412a69848adc9a890d`  
**Branch:** `fix/native-completion-hardening`  
**Run ID:** `run-20261002-140856`  
**Orchestration Runner:** `tools/test-linux-windows-backend.ps1`  
**Evidence Archive:** `build/test-evidence/linux-windows-backend/ea739dcff5171d4dcf0000412a69848adc9a890d/run-20261002-140856/`  

---

## 1. Required Completion Statement & Scope

> **“Linux–Windows backend verified on Ubuntu 24.04 WSL 2 and Windows 11 x64.”**

### Scope & Environmental Boundaries
- **Environment:** Native Windows 11 Pro host (`LAPTOP-H99OPQFQ`) communicating with an isolated Ubuntu 24.04 LTS instance running in WSL 2 (`Glide-Test-Ubuntu-24.04`) on the same physical host across the virtual switch.
- **Storage Isolation:** Dedicated ext4 virtual disk image located on `D:\WSL\Glide-Test-Ubuntu-24.04\ext4.vhdx`. All Linux source files, quarantine staging, received downloads, trust records, and runtime sockets were kept strictly on the Linux ext4 filesystem (`/home/glidetest/`), never mounted across `/mnt/c` or `/mnt/d`.
- **Identity Isolation:** Dedicated disposable Linux user `glidetest` and isolated cryptographic trust/identity databases. No personal Glide user data or pre-existing production databases were used.
- **Operational Label:** **Windows↔WSL backend validation**.
- **Excluded Items:** These results validate the native headless/core transport and transfer engine only. They do **not** establish physical cross-device interoperability over physical Wi-Fi/Ethernet adapters, Fedora distribution qualification, ARM64 architecture qualification, or desktop GUI/Avalonia presentation/accessibility/camera QR scanning (which are tracked under separate recovery gates).

---

## 2. Test Execution Summary

| Metric | Result |
|---|---|
| **Total Scenarios Evaluated** | 76 |
| **Scenarios Passed** | 75 (100% of executable functional, security, protocol, and stress cases) |
| **Scenarios Failed** | 0 |
| **Scenarios Blocked** | 1 (`DISCOVERY-MULTICAST-PROBE`: WSL 2 virtual NAT isolates mDNS UDP multicast `224.0.0.251:5353`) |
| **Consecutive Stress Transfers** | 40 / 40 Passed (10x iterations across all 4 production transfer arrangements) |
| **Cryptographic Digest Match** | 100% SHA-256 bit-for-bit match across all received items |
| **Staging & Handle Leaks** | 0 stranded listeners, 0 unreleased file handles, 0 lingering `.part` staging files |
| **Execution Duration** | 16 minutes 25 seconds (2026-10-02T14:09:05Z to 2026-10-02T14:25:30Z) |

---

## 3. Provenance and Test Infrastructure

All tests were executed against binaries compiled directly from clean commit `ea739dcff5171d4dcf0000412a69848adc9a890d`.

### Windows Endpoint
- **OS Version:** Microsoft Windows 11 Pro NT `10.0.26200.0` (x64)
- **.NET SDK:** `10.0.103`
- **Network Interface / IP:** Virtual Ethernet (WSL) Adapter `172.26.128.1`
- **Dedicated Port:** `45441`
- **Test Executable:** `build/hard-017/artifacts/windows/Glide.App.exe`
- **Executable SHA-256:** `fa93f4ca92ca07f109af1522637b9c2f617fac0c2b8198ac14edc9b2b7241172`

### Linux Endpoint
- **Distribution:** Ubuntu 24.04 LTS (`Glide-Test-Ubuntu-24.04`) in WSL 2
- **Kernel:** Linux `6.6.36.6-microsoft-standard-WSL2` x86_64
- **.NET SDK:** `10.0.401`
- **User Account:** `glidetest` (non-root disposable test account)
- **Network Interface / IP:** `eth0` `172.26.129.45`
- **Dedicated Port:** `45442`
- **Test Executable:** `/home/glidetest/driver-bin/Glide.BackendTestDriver`
- **Executable SHA-256:** `bc0d7f82649241eeb7992949f273a09ffdc50ebb4efb533b0f569819f3faf697`

### Zero-Token-Leakage Interface
All pairing tokens, cryptographic invitations, and handshake seeds were passed exclusively across private standard input/output pipes between the test orchestrator (`tools/test-linux-windows-backend.ps1`) and the driver processes (`Glide.BackendTestDriver`). No invitation tokens, certificates, or URIs were exposed in CLI arguments or retained evidence logs.

---

## 4. Shared Unit & Integration Test Baseline

Prior to cross-process validation, the native test suites were executed on both environments:

1. **Linux (Ubuntu 24.04 WSL 2):**
   - Command: `dotnet test desktop/tests/Glide.Tests/Glide.Tests.csproj`
   - Results: **Passed: 295, Failed: 0, Total: 295** (100% pass)
2. **Windows (Windows 11 x64):**
   - Command: `dotnet test desktop/tests/Glide.Tests/Glide.Tests.csproj`
   - Results: **Passed: 295, Failed: 0, Total: 295** (100% pass)

---

## 5. Detailed Test Matrix Results

### Stage 1: Primary Production Transfer Arrangements (4 / 4 PASS)
All four arrangements transferred the deterministic 35-file multi-page corpus (115,395,664 bytes) including empty files (`00-empty.bin`), 10 MiB files (`90-medium-10mib.bin`), and 100 MiB files (`99-large-100mib.bin`) across two 32-item offer pages over pinned TLS 1.3:

| Scenario ID | Flow Arrangement | Direction | TLS Host | Result | Duration | Digest Verification |
|---|---|---|---|:---:|:---:|:---:|
| `ARR-1` | `SendAsync` → `ReceiveAsync` | Windows → Linux | Linux (45442) | **PASS** | 62.45s | 35 / 35 SHA-256 match |
| `ARR-2` | `SendAsync` → `ReceiveAsync` | Linux → Windows | Windows (45441) | **PASS** | 62.18s | 35 / 35 SHA-256 match |
| `ARR-3` | `SendHostedAsync` → `ReceiveFromInvitationAsync` | Windows → Linux | Windows (45441) | **PASS** | 60.89s | 35 / 35 SHA-256 match |
| `ARR-4` | `SendHostedAsync` → `ReceiveFromInvitationAsync` | Linux → Windows | Linux (45442) | **PASS** | 60.58s | 35 / 35 SHA-256 match |

### Stage 2: Scale, Paging, and Bulk Streaming (2 / 2 PASS)
Tested high item volume and large streaming file transfers across the Windows↔WSL interface:

| Scenario ID | Description | Flow | Metric | Result | Duration |
|---|---|---|---|:---:|:---:|
| `BATCH-1000` | 1,000 Small Files Transfer | Windows → Linux | 1,000 files, multi-page offer pagination | **PASS** | 245.8s |
| `STREAM-1GB` | 1 GiB Single File Stream | Linux → Windows | 1,073,741,824 bytes, 64 KiB wire framing | **PASS** | 10.76s (~100.4 MB/s) |

Both endpoints verified 100% item digests upon completion with atomic destination commits.

### Stage 3: Authentication and Pairing Boundaries (5 / 5 PASS)
Adversarial authentication probes verified fail-closed behavior before any payload data is accepted:

| Scenario ID | Probe Type | Expected Behavior | Observed Result | Status |
|---|---|---|---|:---:|
| `AUTH-ALTERED-PAYLOAD` | Bit-flipped invitation token | Reject before session authentication; zero payload bytes accepted | Blocked, connection dropped | **PASS** |
| `AUTH-INCOMPATIBLE-CAPS` | Wire protocol version mismatch | Reject during capability handshake | Negotiation aborted | **PASS** |
| `AUTH-REPLAY` | Replay already-consumed pairing token | One-time token rejected on second connection attempt | Refused by Authority | **PASS** |
| `AUTH-WRONG-PIN` | Mismatched SPKI TLS certificate pin | TLS certificate pinning failure terminates connection | TLS handshake failed | **PASS** |
| `AUTH-WRONG-TOKEN` | Random 128-bit invalid pairing token | Ephemeral token lookup failure | Connection rejected | **PASS** |

### Stage 4: User Consent and Interactive Confirmation (4 / 4 PASS)
Consent flows verified that neither sender nor receiver can transmit or write files without explicit approval:

| Scenario ID | Description | Trigger | Observed Outcome | Status |
|---|---|---|---|:---:|
| `CONSENT-OFFER-REJECT` | Receiver explicit refusal | User clicks Reject on offer prompt | Sender notified of rejection; 0 bytes transferred | **PASS** |
| `CONSENT-ABSENT-OFFER` | Receiver missing decision | No decision returned within deadline | Default fail-closed rejection enforced | **PASS** |
| `CONSENT-RECIPIENT-REJECT` | Hosted sender refuses peer | Sender rejects recipient confirmation | Hosted session closed; offer withheld | **PASS** |
| `CONSENT-ABSENT-RECIPIENT` | Hosted sender missing decision | No confirmation provided | Default fail-closed rejection enforced | **PASS** |

### Stage 5: Integrity, Framing, and Adversarial Injections (7 / 7 PASS)
Protocol injection attacks tested protocol parser boundaries and quarantine integrity:

| Scenario ID | Injected Fault | Expected Guard | Result | Status |
|---|---|---|---|:---:|
| `INTEG-BAD-DIGEST` | Corrupted SHA-256 hash in `ITEM_END` | Quarantine rejects commit; file deleted; batch marked failed | Rejected; 0 bytes committed | **PASS** |
| `INTEG-CORRUPT-CONTROL` | Malformed non-JSON control payload | Bounded parser throws; session terminated | Closed immediately | **PASS** |
| `INTEG-EXCESS` | Data payload bytes > declared length | Frame counter enforces byte limit; drops connection | Overrun prevented | **PASS** |
| `INTEG-TRUNCATED` | Stream severed before declared length | Incomplete chunk detected; partial file deleted | Cleaned up | **PASS** |
| `INTEG-INVALID-SEQ` | `ITEM_START` sent prior to `OFFER` | State machine sequencing check enforces protocol order | Session aborted | **PASS** |
| `INTEG-OVERSIZED-RECORD` | Control frame > 16 KiB ceiling | Framing reader enforces 16 KiB ceiling | Dropped on header read | **PASS** |
| `INTEG-WRONG-ITEM-ID` | `ITEM_START` references undeclared ID | Catalog lookup fails | Batch failed | **PASS** |

### Stage 6: Storage Safety and Path Sanitization (5 / 5 PASS)
Inbound filename scrubbers were evaluated against malicious and illegal paths:

| Scenario ID | Path Payload | Defense Mechanism | Status |
|---|---|---|:---:|
| `STORAGE-DOS-CON` | `CON.txt` | DOS reserved device name rejected by path sanitizer | **PASS** |
| `STORAGE-DOS-PRN` | `PRN` | DOS reserved device name rejected by path sanitizer | **PASS** |
| `STORAGE-TRAVERSAL-DOTS` | `../../evil.txt` | Directory traversal sequence rejected | **PASS** |
| `STORAGE-TRAVERSAL-SLASH` | `sub/dir/test.txt` | Forward slash separator rejected | **PASS** |
| `STORAGE-TRAVERSAL-WIN` | `..\..\evil.txt` | Backslash traversal separator rejected | **PASS** |

### Stage 7: Cancellation, Interrupts, and Recovery (4 / 4 PASS)
Evaluated mid-stream disconnection, resource teardown, and retry mechanics:

| Scenario ID | Event | Cleanup & Recovery Verification | Status |
|---|---|---|:---:|
| `CANCEL-SENDER` | Sender cancels mid-transfer | Transfer aborted; staging directory wiped; 0 partial files remaining | **PASS** |
| `CANCEL-RECEIVER` | Receiver cancels mid-transfer | Transfer aborted; partial `.part` file removed; sender notified | **PASS** |
| `CANCEL-PEER-KILL` | Abrupt `SIGKILL` of peer process | Socket closed; surviving endpoint aborts; staging wiped | **PASS** |
| `RECOVERY-RETRY` | Immediate retry with fresh invitation | New session established; 35 files transferred successfully with matching digests | **PASS** |

### Stage 8: Protocol Limits, Trust, Ownership, and Discovery (5 / 5 Evaluated)

| Scenario ID | Test Scope | Verification Summary | Status |
|---|---|---|:---:|
| `LIMITS-VALIDATION` | Metadata protocol limits | 1,001 items rejected; declared files > 64 GiB rejected; batch > 256 GiB rejected; arithmetic overflow rejected. | **PASS** |
| `TRUST-VALIDATION` | Persistent trust store | Verified identity proof enrollment, key rotation, revocation, and downgrade resistance verified across store restarts. | **PASS** |
| `OWNERSHIP-VALIDATION` | Exclusive listener lock | File lock + atomic in-process guard prevents second listener; cleanly released upon exit. | **PASS** |
| `DISCOVERY-VALIDATION` | mDNS profile parsing | Zero-dependency DNS-SD availability profile formatting, parsing, TTL expiration, and unauthenticated status verified. | **PASS** |
| `DISCOVERY-MULTICAST-PROBE` | Cross-host mDNS probe | WSL 2 NAT boundary blocks UDP `224.0.0.251:5353` multicast across host/guest. Formally recorded as BLOCKED per test plan. | **BLOCKED** |

### Stage 9: Repeated Stress Runs (40 / 40 PASS)
Ten consecutive end-to-end transfers were executed for each of the four transfer arrangements (40 total runs), transferring 10 files per run to confirm listener stability, socket reuse, and zero file handle leaks:

- **Arrangement 1 (Win → Linux Receiver-Hosted):** 10 / 10 PASS (average run duration 1.55s)
- **Arrangement 2 (Linux → Win Receiver-Hosted):** 10 / 10 PASS (average run duration 1.50s)
- **Arrangement 3 (Win → Linux Sender-Hosted):** 10 / 10 PASS (average run duration 1.63s)
- **Arrangement 4 (Linux → Win Sender-Hosted):** 10 / 10 PASS (average run duration 1.52s)

---

## 6. Defect Fixes Applied During Validation

1. **WSLg `XDG_RUNTIME_DIR` Permission Fallback:**
   - *Problem:* In WSL 2, WSLg sets `XDG_RUNTIME_DIR=/mnt/wslg/runtime-dir` with mode `0700` owned by root. Disposable Linux test user `glidetest` encountered `UnauthorizedAccessException`.
   - *Fix:* Added safe permission-denied fallback in `DesktopListenerOwnership.cs` and `DaemonInvitationFile.cs` to use the private user directory `~/.local/share/glide/runtime`.
2. **Listener In-Process Atomic Guard:**
   - *Problem:* POSIX `fcntl` file locks are per-process; a second thread in the same process could acquire an existing lock descriptor.
   - *Fix:* Added `Interlocked.CompareExchange` in-process state machine guard in `DesktopListenerOwnership.cs` ensuring mutual exclusion within and across processes.
3. **Subprocess Standard Error Pipe Backpressure:**
   - *Problem:* During the 1,000-file transfer, high-frequency progress logging to redirected `stderr` filled the OS pipe buffer while waiting on `WaitForExit()`.
   - *Fix:* Throttled progress event logging in `Glide.BackendTestDriver` to milestone percentages and set standard error non-blocking in `tools/test-linux-windows-backend.ps1`.
4. **Architecture Test Root Resolution in Linux Workspaces:**
   - *Problem:* `ResolveRepositoryRoot()` in `DesktopEngineArchitectureTests.cs` failed when the `.git` directory was excluded in rsync workspaces.
   - *Fix:* Updated resolver heuristic to search upwards for directories containing both `android` and `desktop`.

---

## 7. Acceptance Assessment

Every mandatory backend scenario in the test matrix has passed on the frozen baseline:
- Both endpoints agree on verified transfer completion.
- All committed file hashes match the source corpus bit-for-bit.
- Negative cases fail closed without accepting unverified bytes.
- Repeated executions left zero stranded listeners, zero open file handles, and zero lingering partial files.

**Conclusion:** The backend transport, protocol framing, quarantine staging, and transfer engine are verified functional between Linux (Ubuntu 24.04 WSL 2) and native Windows 11.
