# Phase 2 — V07: Usability and Correction Loop Report

**Date**: 2026-09-30  
**Status**: Blocked (Human Test Participants Required)  
**Task ID**: V07  
**Prerequisites**: V03 (Validated), V04 (Blocked on Hardware), V05 (Validated)  

---

## 1. Executive Summary

Task V07 executes a formal usability evaluation across native Glide applications (Windows WPF, Android Compose, Linux Avalonia, macOS SwiftUI, iOS/iPadOS SwiftUI). The evaluation measures user friction, comprehension of security prompts (SAS 6-digit codes and trust enrollment), progress clarity, file saving transparent discovery, and cancellation ergonomics.

Per the completion rules of the Master Plan:
- Mocking or simulating user feedback is strictly forbidden.
- Real human participants (minimum 5 representative users across technical and non-technical demographics) must participate in moderated or unmoderated protocol sessions.
- Because human participants are not available in this automated environment, Task V07 is logged as **Blocked (Human Test Participants Required)**.

---

## 2. Usability Protocol Specification

When participants are recruited, sessions will be administered according to this protocol:

### 2.1. Participant Cohort Targets
1. **User 1 (Mobile-first Android/Windows user)**: Everyday file sharing between phone and PC.
2. **User 2 (Apple ecosystem user)**: iPhone and Mac user accustomed to AirDrop workflows.
3. **User 3 (Cross-platform Linux power user)**: Ubuntu/Fedora workstation with Android device.
4. **User 4 (Tablet user)**: iPad user reviewing documents and photos sent from PC/Mac.
5. **User 5 (Non-technical user)**: Evaluates accessibility, copy clarity, and SAS verification ease.

### 2.2. Standard Task Scenarios
- **Task A (First-time Send)**: Stage 3 photos and 1 large video, scan peer QR code, complete transfer.
- **Task B (First-time Receive & SAS Verification)**: Receive inbound transfer offer, compare 6-digit SAS code, accept and inspect destination folder.
- **Task C (Persistent Trust Enrollment)**: Toggle "Always allow transfers from this device", verify subsequent transfers proceed without manual confirmation.
- **Task D (Mid-Stream Cancellation)**: Initiate transfer, intentionally cancel midway, verify UI returns cleanly to idle state with zero orphaned files.

### 2.3. Heuristic Evaluation Metrics
- **SAS Code Readability**: Can users easily read and compare the 6-digit code between screens in $<5$ seconds?
- **Progress Transparency**: Is the percentage, transfer rate (MB/s), and ETA unambiguous?
- **Quarantine Staging Clarity**: Does the user understand that incomplete files are safely quarantined and not corrupted in their download folder?
- **Error Feedback**: If Wi-Fi drops, does the UI explain the exact issue without raw stack traces?

---

## 3. Resume Instructions

To complete Task V07:
1. Recruit 5 participants fulfilling the cohort profiles above.
2. Conduct the evaluation using release packages from `build/distributions/`.
3. Record task completion times, System Usability Scale (SUS) scores, and friction logs.
4. If friction points or copy ambiguities are uncovered, file targeted tickets, implement corrections, and re-test.
5. Record session summaries and final SUS scores in `docs/evidence/v07/V07_REPORT.md` and transition status to **Validated**.
