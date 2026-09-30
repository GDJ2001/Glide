# Native Platform Interoperability & Validation Matrix

## 1. Scope & Execution Rules

This matrix governs **Phase 2 Validation**. It covers 6 native client classes:
1. **Windows** (Windows 11 WPF application)
2. **Android** (Android 13+ Compose application)
3. **iPhone** (iOS 16+ SwiftUI application)
4. **iPad** (iPadOS 16+ SwiftUI application)
5. **macOS** (macOS 13+ SwiftUI/AppKit application)
6. **Linux** (Ubuntu 24.04 / Fedora 41 Avalonia application)

**Validation Policy**:
- 15 client pairs $\times$ 2 directions = **30 directed transfer flows**.
- Physical transfers must be run on actual physical hardware across Wi-Fi LAN. Loopback tests, emulators, and synthetic mocks do not satisfy this gate.
- Bit-for-bit SHA-256 verification is mandatory across all received files.

---

## 2. Platform Pairings (15 Pairs / 30 Directed Flows)

| Pair # | Platform Pair | Flow Direction A $\to$ B | Flow Direction B $\to$ A | Phase 2 Status |
|:---:|---|---|---|:---:|
| **P01** | Windows ↔ Android | Windows $\to$ Android | Android $\to$ Windows | Pending Phase 2 |
| **P02** | Windows ↔ iPhone | Windows $\to$ iPhone | iPhone $\to$ Windows | Pending Phase 2 |
| **P03** | Windows ↔ iPad | Windows $\to$ iPad | iPad $\to$ Windows | Pending Phase 2 |
| **P04** | Windows ↔ macOS | Windows $\to$ macOS | macOS $\to$ Windows | Pending Phase 2 |
| **P05** | Windows ↔ Linux | Windows $\to$ Linux | Linux $\to$ Windows | Pending Phase 2 |
| **P06** | Android ↔ iPhone | Android $\to$ iPhone | iPhone $\to$ Android | Pending Phase 2 |
| **P07** | Android ↔ iPad | Android $\to$ iPad | iPad $\to$ Android | Pending Phase 2 |
| **P08** | Android ↔ macOS | Android $\to$ macOS | macOS $\to$ Android | Pending Phase 2 |
| **P09** | Android ↔ Linux | Android $\to$ Linux | Linux $\to$ Android | Pending Phase 2 |
| **P10** | iPhone ↔ iPad | iPhone $\to$ iPad | iPad $\to$ iPhone | Pending Phase 2 |
| **P11** | iPhone ↔ macOS | iPhone $\to$ macOS | macOS $\to$ iPhone | Pending Phase 2 |
| **P12** | iPhone ↔ Linux | iPhone $\to$ Linux | Linux $\to$ iPhone | Pending Phase 2 |
| **P13** | iPad ↔ macOS | iPad $\to$ macOS | macOS $\to$ iPad | Pending Phase 2 |
| **P14** | iPad ↔ Linux | iPad $\to$ Linux | Linux $\to$ iPad | Pending Phase 2 |
| **P15** | macOS ↔ Linux | macOS $\to$ Linux | Linux $\to$ macOS | Pending Phase 2 |

---

## 3. Required Test Scenarios

Every platform pair must be tested against these core operational scenarios:

| Scenario ID | Name | Description & Acceptance Requirement |
|---|---|---|
| **SC-01** | Receiver-Hosted Fresh Pairing | Receiver shows QR invitation; sender scans/enters URI; TLS 1.3 pins certificate; SAS 6-digit confirmed; offer accepted; transfer completes. |
| **SC-02** | Sender-Hosted Quick QR | Sender shows QR invitation; receiver scans; TLS client initiates receiving handshake; transfer completes without redundant clicks. |
| **SC-03** | Saved Device Mutual Auth | Previously enrolled device connects; `identity-v1` challenge-response verifies persistent keys; bypasses manual SAS confirmation. |
| **SC-04** | User Offer Rejection | Receiver rejects batch offer; sender receives `OFFER_DECISION {accepted: false, code: USER_REJECTED}`; 0 bytes written to disk. |
| **SC-05** | Mid-Stream Cancellation | Either peer cancels transfer midway; active socket closes; `.part` staging file deleted immediately; 0 unverified bytes committed. |
| **SC-06** | Abrupt Disconnect Recovery | Wi-Fi disabled during bulk streaming; transfer fails closed; orphan `.part` cleaned up on subsequent startup. |
| **SC-07** | Large File Transfer ($\ge 1\text{ GiB}$) | Single continuous stream $\ge 1\text{ GiB}$; memory footprint remains bounded ($O(1) \le 64\text{ KiB}$ buffer); 100% SHA-256 match. |
| **SC-08** | Paginated Batch ($>32$ items) | 35+ items across 2 `OFFER_PAGE` envelopes, including 0-byte file; contiguous ordinals verified; full batch committed. |
| **SC-09** | Path Traversal Rejection | Sender attempts to offer `../../etc/passwd` or `C:\Windows\System32`; receiver sanitizes to base filename or aborts batch. |
| **SC-10** | Digest Tampering Detection | File bytes modified in-flight; receiver streaming SHA-256 detects mismatch against `ITEM_END`; quarantine deleted; batch aborts. |

---

## 4. Evidence Record Schema

For each execution in Phase 2, record evidence using this schema:

```json
{
  "testRunId": "V04-P01-W2A-01",
  "timestamp": "2026-10-01T10:00:00Z",
  "scenario": "SC-01",
  "source": {
    "platform": "Windows",
    "osVersion": "Windows 11 24H2",
    "hardware": "x86_64",
    "buildCommit": "5c439b4"
  },
  "destination": {
    "platform": "Android",
    "osVersion": "Android 13",
    "hardware": "ARM64",
    "buildCommit": "5c439b4"
  },
  "connection": {
    "transport": "lan-tls",
    "role": "receiver-hosted",
    "port": 42424,
    "sasCode": "849201"
  },
  "corpus": {
    "itemCount": 35,
    "totalBytes": 115395664,
    "pages": 2
  },
  "result": {
    "status": "PASS",
    "sha256Match": true,
    "stagingClean": true,
    "durationMs": 2840,
    "averageThroughputMBps": 40.6
  },
  "evidencePath": "docs/evidence/v04/P01-W2A-01.json"
}
```
