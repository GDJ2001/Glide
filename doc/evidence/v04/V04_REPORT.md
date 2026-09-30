# Phase 2 — V04: Physical Interoperability Matrix Report

**Date**: 2026-09-30  
**Status**: Blocked (Physical Hardware Gate)  
**Task ID**: V04  
**Prerequisites**: V01 (Validated), V02 (Validated), V03 (Validated)  

---

## 1. Executive Summary

Task V04 requires live end-to-end execution of the **15 native platform pairs $\times$ 2 directions = 30 directed transfer flows** over a shared physical local Wi-Fi network:

1. **Windows** (Windows 11 24H2 WPF)
2. **Android** (Android 13+ Compose)
3. **iPhone** (iOS 16+ SwiftUI)
4. **iPad** (iPadOS 16+ SwiftUI)
5. **macOS** (macOS 13+ SwiftUI/AppKit)
6. **Linux** (Ubuntu 24.04 LTS / Fedora 41 Avalonia 12.1.3)

Per the strict quality gates defined in the Master Plan, **synthetic loopbacks, emulators, and mock transports are prohibited from satisfying this gate**. A successful compile or simulated transfer cannot establish physical cross-platform interoperability.

---

## 2. Platform Pairing Matrix & Status Ledger

| Pair # | Platform Pair | Direction A $\to$ B | Direction B $\to$ A | Scenarios Required | Status |
|:---:|---|---|---|---|:---:|
| **P01** | Windows ↔ Android | Windows $\to$ Android | Android $\to$ Windows | SC-01 – SC-10 | Blocked (Physical Wi-Fi LAN setup required) |
| **P02** | Windows ↔ iPhone | Windows $\to$ iPhone | iPhone $\to$ Windows | SC-01 – SC-10 | Blocked (Physical iPhone required) |
| **P03** | Windows ↔ iPad | Windows $\to$ iPad | iPad $\to$ Windows | SC-01 – SC-10 | Blocked (Physical iPad required) |
| **P04** | Windows ↔ macOS | Windows $\to$ macOS | macOS $\to$ Windows | SC-01 – SC-10 | Blocked (Physical Mac required) |
| **P05** | Windows ↔ Linux | Windows $\to$ Linux | Linux $\to$ Windows | SC-01 – SC-10 | Blocked (Physical Linux box required) |
| **P06** | Android ↔ iPhone | Android $\to$ iPhone | iPhone $\to$ Android | SC-01 – SC-10 | Blocked (Physical iPhone required) |
| **P07** | Android ↔ iPad | Android $\to$ iPad | iPad $\to$ Android | SC-01 – SC-10 | Blocked (Physical iPad required) |
| **P08** | Android ↔ macOS | Android $\to$ macOS | macOS $\to$ Android | SC-01 – SC-10 | Blocked (Physical Mac required) |
| **P09** | Android ↔ Linux | Android $\to$ Linux | Linux $\to$ Android | SC-01 – SC-10 | Blocked (Physical Linux box required) |
| **P10** | iPhone ↔ iPad | iPhone $\to$ iPad | iPad $\to$ iPhone | SC-01 – SC-10 | Blocked (Physical Apple hardware required) |
| **P11** | iPhone ↔ macOS | iPhone $\to$ macOS | macOS $\to$ iPhone | SC-01 – SC-10 | Blocked (Physical Apple hardware required) |
| **P12** | iPhone ↔ Linux | iPhone $\to$ Linux | Linux $\to$ iPhone | SC-01 – SC-10 | Blocked (Physical Apple + Linux required) |
| **P13** | iPad ↔ macOS | iPad $\to$ macOS | macOS $\to$ iPad | SC-01 – SC-10 | Blocked (Physical Apple hardware required) |
| **P14** | iPad ↔ Linux | iPad $\to$ Linux | Linux $\to$ iPad | SC-01 – SC-10 | Blocked (Physical Apple + Linux required) |
| **P15** | macOS ↔ Linux | macOS $\to$ Linux | Linux $\to$ macOS | SC-01 – SC-10 | Blocked (Physical Mac + Linux required) |

---

## 3. Required Test Corpus & Scenario Suite

When physical devices are attached to a common local subnet, test operators must execute the 10 defined scenarios from `docs/planning/native-platforms/VALIDATION_MATRIX.md`:

1. **SC-01 (Receiver-Hosted Fresh Pairing)**: QR code scanned; TLS 1.3 certificate pinned; SAS 6-digit confirmed; transfer completes.
2. **SC-02 (Sender-Hosted Quick QR)**: Sender displays QR; receiver scans and connects as TLS client; instant transfer initiation.
3. **SC-03 (Saved Device Mutual Auth)**: Previously enrolled identity reconnects; `identity-v1` challenge-response verified; manual SAS bypassed.
4. **SC-04 (User Offer Rejection)**: Inbound batch declined by receiver; 0 bytes written to staging or destination.
5. **SC-05 (Mid-Stream Cancellation)**: Aborted midway; socket closed; `.part` staging file immediately deleted.
6. **SC-06 (Abrupt Disconnect Recovery)**: Wi-Fi toggled off during streaming; fails closed; reconciler purges orphans on next launch.
7. **SC-07 (Large File Transfer $\ge 1\text{ GiB}$)**: Stream large single file; constant memory profile ($O(1) \le 64\text{ KiB}$ buffer); 100% SHA-256 match.
8. **SC-08 (Paginated Batch $>32$ items)**: 35+ items across 2 `OFFER_PAGE` frames; contiguous ordinals; zero-byte files handled.
9. **SC-09 (Path Traversal Rejection)**: Malicious filenames (`../../passwd`, `C:\Windows\System32`) sanitized or rejected.
10. **SC-10 (Digest Tampering Detection)**: Byte alteration in-flight caught by streaming SHA-256 check against `ITEM_END`; quarantine deleted.

---

## 4. Hardware Prerequisites & Resume Instructions

To clear this blocker and mark V04 as validated:
1. Provide a physical local Wi-Fi 6 / 5 GHz router (AP isolation disabled).
2. Connect physical reference test devices:
   - Windows 11 PC (Build 26100+)
   - Android 13+ phone (Pixel or Samsung Galaxy)
   - iPhone running iOS 16+
   - iPad running iPadOS 16+
   - Mac running macOS 13+ (Apple Silicon or Intel)
   - Linux PC running Ubuntu 24.04 LTS or Fedora 41 (X11 / XWayland)
3. Deploy corresponding application packages from `build/distributions/`.
4. Execute pairs P01 through P15 across scenarios SC-01 through SC-10, recording JSON test run evidence into `docs/evidence/v04/`.
