# Linux–Windows Backend Validation Runbook

This document defines the operational procedures, architecture boundaries, and execution steps for cross-process backend validation between **native Windows 11 Pro x64** and **Ubuntu 24.04 LTS in WSL 2**.

---

## 1. Objective and Architecture Boundaries

The primary objective is to verify Glide's core transfer engine, TLS 1.3 transport, invitation authority, trust manager, quarantine staging store, and protocol codec bidirectionally between Windows and Linux.

### Key Scope Constraints
- **Scope**: Backend data transfer, framing, authentication, consent, integrity, storage safety, and cancellation.
- **Excluded**: Frontend visual appearances (WPF/Avalonia), UI accessibility, window behavior, and camera QR scanning (verified separately in desktop VMs or physical devices).
- **Environment Qualification**: Results establish **Windows↔WSL backend validation**. They do not assert physical-network Wi-Fi discovery, Fedora compatibility, ARM64 hardware support, or final frontend completion.

### Storage and Privacy Isolation
1. **Linux Filesystem Isolation**: Linux test corpora, quarantine staging (`.part` files), destination files, identity keys, and persistent trust stores reside strictly on **Linux ext4** (`/home/glidetest/`), never on `/mnt/c` or `/mnt/d`.
2. **Zero Token Leakage**: Pairing invitations and tokens are transferred strictly across private stdin/stdout process pipes between the test orchestrator and driver processes. Tokens never appear in command-line arguments, environment dumps, or retained evidence logs.
3. **No Destination Escapes**: All inbound file writes stream into private staging before atomic commit upon cryptographic SHA-256 digest match.

---

## 2. Infrastructure Setup & Network Prerequisites

### WSL 2 Distribution: `Glide-Test-Ubuntu-24.04`
- Dedicated distribution imported on drive `D:` (`D:\WSL\Glide-Test-Ubuntu-24.04\ext4.vhdx`).
- Existing distributions (`Ubuntu-18.04`, `docker-desktop`) are strictly preserved.
- Disposable non-root test user: `glidetest` (configured in `/etc/wsl.conf`).

### Network Coordinates
- **Windows Host (WSL Switch Gateway)**: `172.26.128.1` (TCP port `45441`)
- **WSL 2 Guest**: Extracted dynamically via `wsl.exe -d Glide-Test-Ubuntu-24.04 -- hostname -I` (default TCP port `45442`)
- **Firewall**: Inbound connections to port `45441` are permitted by Windows Firewall application rule for `build\hard-017\artifacts\windows\Glide.App.exe`.

---

## 3. Test Driver & Orchestration

The cross-process backend driver is located at `desktop/tests/Glide.BackendTestDriver/` and references `Glide.Core`, `Glide.Transport`, and `Glide.Desktop`.

### Driver Modes
- `listen-receiver`: Binds TLS listener, publishes canonical invitation JSON to stdout, evaluates consent policies, receives files into quarantine staging, verifies streaming SHA-256, and commits atomically.
- `connect-sender`: Reads invitation JSON from stdin, verifies TLS certificate SPKI pin, completes bootstrap handshake, negotiates capabilities, and streams files.
- `listen-hosted-sender`: Binds TLS listener, advertises invitation for sender-hosted QR flow, requires explicit recipient device approval, and streams offered batch.
- `connect-receiver`: Connects to hosted sender, approves offer, streams files into quarantine, and verifies digests.
- `adversarial-send`: Injects malformed frames, incorrect digests, truncated/excess streams, invalid sequences, wrong tokens, replayed tokens, and traversal filenames.
- `test-limits`: Asserts batch ceiling (1,000 items), file ceiling (64 GiB), and batch ceiling (256 GiB).
- `test-storage-safety`: Verifies path sanitization against directory traversal (`../../`) and DOS device names (`CON`, `PRN`).
- `test-trust`: Validates persistent trust store, verified enrollment, and identity key rotation.
- `test-ownership`: Verifies exclusive listener lock and duplicate acquisition prevention.
- `test-discovery`: Validates mDNS DNS-SD availability profile formatting and parsing.

---

## 4. Executing the Validation Suite

### Automated Orchestration
Run the automated PowerShell orchestrator from the repository root:

```powershell
pwsh -File tools/test-linux-windows-backend.ps1
```

The script automatically:
1. Records Git commit SHA, branch, OS versions, and .NET runtime baseline.
2. Compiles driver binaries for `win-x64` and `linux-x64` (standalone).
3. Computes SHA-256 hashes of compiled binaries for provenance.
4. Generates deterministic test corpora on Windows and Linux ext4.
5. Executes the 9-stage validation matrix.
6. Records per-scenario JSON evidence under `build/test-evidence/linux-windows-backend/<commit>/<run-id>/`.
7. Outputs an execution summary table and writes `summary.json`.

---

## 5. Required Validation Matrix

| Stage | Scenario ID | Description | Expected Status |
|---|---|---|---|
| **Stage 1: Production Arrangements** | `ARR-1` | Windows $\to$ Linux (Linux Receiver Hosted) | PASS |
| | `ARR-2` | Linux $\to$ Windows (Windows Receiver Hosted) | PASS |
| | `ARR-3` | Windows $\to$ Linux (Windows Sender Hosted) | PASS |
| | `ARR-4` | Linux $\to$ Windows (Linux Sender Hosted) | PASS |
| **Stage 2: Files & Paging Scale** | `BATCH-1000` | 1,000 files batch transfer across multiple offer pages | PASS |
| | `STREAM-1GB` | 1 GiB single file continuous stream transfer | PASS |
| **Stage 3: Consent & Authorization** | `CONSENT-OFFER-REJECT` | Receiver explicitly refuses transfer offer | REJECT |
| | `CONSENT-RECIPIENT-REJECT` | Hosted sender explicitly refuses recipient device | REJECT |
| | `CONSENT-ABSENT-OFFER` | Missing offer decision defaults to rejection | REJECT |
| | `CONSENT-ABSENT-RECIPIENT` | Missing recipient confirmation defaults to rejection | REJECT |
| **Stage 4: Authentication Security** | `AUTH-WRONG-PIN` | TLS SPKI pin mismatch (handshake rejected) | ATTACK_BLOCKED |
| | `AUTH-WRONG-TOKEN` | Invalid one-time pairing token rejected | ATTACK_BLOCKED |
| | `AUTH-ALTERED-PAYLOAD` | Altered invitation hash rejected | ATTACK_BLOCKED |
| | `AUTH-INCOMPATIBLE-CAPS` | Missing protocol capabilities rejected | ATTACK_BLOCKED |
| | `AUTH-REPLAY` | Replaying consumed token rejected with `INVITATION_USED` | PASS |
| **Stage 5: Framing & Integrity** | `INTEG-BAD-DIGEST` | Mismatched SHA-256 in `ITEM_END` rejected, 0 bytes committed | ATTACK_BLOCKED |
| | `INTEG-TRUNCATED` | Truncated payload stream rejected, quarantine cleaned | ATTACK_BLOCKED |
| | `INTEG-EXCESS` | Excess payload bytes stream rejected, quarantine cleaned | ATTACK_BLOCKED |
| | `INTEG-WRONG-ITEM-ID` | Undeclared item ID in `ITEM_START` rejected | ATTACK_BLOCKED |
| | `INTEG-INVALID-SEQ` | Out-of-order sequence (`ITEM_START` before `OFFER`) rejected | ATTACK_BLOCKED |
| | `INTEG-CORRUPT-CONTROL` | Corrupt non-JSON control payload rejected | ATTACK_BLOCKED |
| | `INTEG-OVERSIZED-RECORD`| Record exceeding 16 KiB ceiling rejected | ATTACK_BLOCKED |
| **Stage 6: Storage Safety** | `STORAGE-TRAVERSAL-DOTS` | Relative path `../../evil.txt` scrubbed and rejected | ATTACK_BLOCKED |
| | `STORAGE-TRAVERSAL-SLASH`| Relative path with separators scrubbed and rejected | ATTACK_BLOCKED |
| | `STORAGE-TRAVERSAL-WIN`  | Relative path `..\..\evil.txt` scrubbed and rejected | ATTACK_BLOCKED |
| | `STORAGE-DOS-CON`       | DOS device `CON.txt` rejected | ATTACK_BLOCKED |
| | `STORAGE-DOS-PRN`       | DOS device `PRN` rejected | ATTACK_BLOCKED |
| **Stage 7: Cancellation & Recovery** | `CANCEL-SENDER` | Sender cancels mid-stream, staging cleaned, history failed | CANCEL |
| | `CANCEL-RECEIVER` | Receiver cancels mid-stream, staging cleaned, history failed | CANCEL |
| | `CANCEL-PEER-KILL` | Connector process killed abruptly, listener cleans and exits | CANCEL |
| | `RECOVERY-RETRY` | Fresh invitation succeeds immediately following cancellation | PASS |
| **Stage 8: Limits, Trust, Ownership & Discovery** | `LIMITS-VALIDATION` | 1,001 items, >64 GiB file, >256 GiB batch rejected | PASS |
| | `TRUST-VALIDATION` | Trust store and identity rotation verified | PASS |
| | `OWNERSHIP-VALIDATION` | Listener exclusive lock and release verified | PASS |
| | `DISCOVERY-VALIDATION` | DNS-SD advertisement encoding and parsing verified | PASS |
| | `DISCOVERY-MULTICAST-PROBE` | Windows $\leftrightarrow$ WSL mDNS multicast (WSL 2 NAT isolated) | BLOCKED |
| **Stage 9: Consecutive Reliability** | `STRESS-ARR-1` | 10 consecutive successful runs of Arrangement 1 | PASS |
| | `STRESS-ARR-2` | 10 consecutive successful runs of Arrangement 2 | PASS |
| | `STRESS-ARR-3` | 10 consecutive successful runs of Arrangement 3 | PASS |
| | `STRESS-ARR-4` | 10 consecutive successful runs of Arrangement 4 | PASS |

---

## 6. Manual Scenario Execution

To run a specific scenario manually:

### Example: Windows Sender $\to$ Linux Receiver (Arrangement 1)

1. **On Linux (WSL)**:
   ```bash
   /home/glidetest/driver-bin/Glide.BackendTestDriver listen-receiver \
     --port 45442 \
     --advertised-ip 172.26.129.45 \
     --staging-dir /home/glidetest/staging \
     --dest-dir /home/glidetest/destination \
     --decision-offer accept
   ```
   *The listener prints `{"event":"invitation","uri":"glide://pair/v1?..."}` to stdout.*

2. **On Windows (PowerShell)**:
   ```powershell
   $inv = 'glide://pair/v1?...' # From Linux stdout
   $inv | build\hard-017\artifacts\windows\Glide.App.exe connect-sender `
     --source-dir build\corpus-matrix
   ```

3. **Verify Integrity on Linux**:
   ```bash
   /home/glidetest/driver-bin/Glide.BackendTestDriver verify-corpus \
     --corpus-dir /home/glidetest/corpus \
     --dest-dir /home/glidetest/destination
   ```
