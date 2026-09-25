# Evidence Report — HARD-036: Physical Failure & Recovery Matrix Verification

**Author:** Antigravity Agent  
**Date:** 2026-09-25  
**Milestone:** M6 — Product Quality & Hardening  
**Task:** `HARD-036` — Exercise physical failure/recovery matrix  
**Status:** COMPLETE ✅ (All Physical Lifecycle, Network, Storage, Permission & Corruption Recovery Scenarios Passed)  

---

## 1. Executive Summary

HARD-036 proves that the Glide native data plane and control plane respond truthfully and fail closed across real operating system, network, storage, and process lifecycle failures on attached physical hardware (`OPPO CPH2371`, `Q4MFFQWK8559NN5L`, Android 13 / ColorOS 13) communicating with Windows 11 (`LAPTOP-H99OPQFQ`) and Linux WSL2 (`Ubuntu 18.04`).

All required invariants were empirically proven on physical devices via [`tools/run_hard036_and_hard039_physical.py`](file:///d:/GITHub/Glide/tools/run_hard036_and_hard039_physical.py):
- **Zero Consent Bypass:** Backgrounding or sleeping the device while an incoming transfer offer is pending never auto-accepts or commits files; `files/received/` remains empty until explicit user approval.
- **Foreground Service (`TransferService`) Resilience:** Backgrounding the Activity mid-stream during a 100 MiB transfer allows the Foreground Service to complete the transfer with 100% SHA-256 verification (`c0b3a0592a173...`) and 0 staging residue.
- **Mid-Stream Network Severing & Quarantine Purge:** Abrupt socket/network disconnection mid-flight during a 100 MiB transfer causes immediate fail-closed termination (`exit code 1`), with 0 unverified bytes promoted to `files/received/`.
- **Process Death (`am force-stop` / `SIGKILL`) & Startup Reconciliation:** Orphan `.part` files left in `files/receiving/` after process termination are purged upon startup reconciliation, while previously verified files in `files/received/` remain 100% preserved.
- **Runtime Permission Revocation:** Revoking `android.permission.NEARBY_WIFI_DEVICES` at runtime (`pm revoke`) is handled gracefully without process crash (`PID 28331` remained alive and responsive).
- **Duplicate Filename Collision Isolation:** Sending identical filenames (`01-small-01.bin`) across back-to-back sessions commits 2 distinct, non-overwriting files in `files/received/`, both verified against SHA-256 `045a28a52565...`.
- **Disk Full & Corrupted Resume Journal Fail-Closed Enforcement:** Pre-flight storage ceiling reservations and corrupted/tampered resume journal entries reject fail-closed without data exposure or partial commit.

---

## 2. Physical Test Environment

| Parameter | Physical Android Endpoint | Desktop Endpoint |
|---|---|---|
| **Device / Host** | OPPO CPH2371 (`Q4MFFQWK8559NN5L`) | `LAPTOP-H99OPQFQ` |
| **OS / Platform** | Android 13 (API 33, ColorOS 13, ARM64) | Windows 11 Enterprise x64 / Ubuntu WSL2 |
| **Artifact SHA-256** | `c1c0cf0debc724640b4c3216697aadf00ade70b5522d72828ea4a1e8a7f0b30c` | `9d582a8fef63bfe5e0ad0e660a462eed2396e04e2d3cfe1c41a7784fbf4d86fb` |
| **Repository Commit** | `0f7b0563ecdbd3839ed8946300616560a0a98fda` | `0f7b0563ecdbd3839ed8946300616560a0a98fda` |
| **Evidence JSON** | [`doc/testing/evidence/hard-036/hard036-physical-matrix.json`](file:///d:/GITHub/Glide/doc/testing/evidence/hard-036/hard036-physical-matrix.json) | [`build/hard-036/hard036-physical-matrix.json`](file:///d:/GITHub/Glide/build/hard-036/hard036-physical-matrix.json) |

---

## 3. Physical Failure & Recovery Matrix Results (6 / 6 PASS)

| # | Scenario ID | Failure / Recovery Vector | Observed Physical Outcome | Status |
|:---:|---|---|---|:---:|
| 1 | `hard036-01-lock-consent-and-fgs-survival` | Background/Lock before consent + FGS background survival on 100 MiB transfer | 0 files auto-accepted while backgrounded; after consent, 100 MiB transfer completed in background with 100% SHA-256 match and 0 staging residue. | **PASS** |
| 2 | `hard036-02-network-disconnect-quarantine` | Mid-stream socket/network disconnect during 100 MiB transfer | Sender exited with non-zero error (`code 1`); 0 unverified files committed to `files/received/` on Android. | **PASS** |
| 3 | `hard036-03-process-kill-startup-reconciliation` | `am force-stop` mid-flight with orphan `.part` files (`e1b3dd17...part`, `orphan_partial.part`) | All `.part` staging files purged on recovery; verified `intact.bin` in `files/received/` 100% preserved. | **PASS** |
| 4 | `hard036-04-permission-revocation-recovery` | Runtime `pm revoke android.permission.NEARBY_WIFI_DEVICES` | Process `PID 28331` survived revocation without crash; recovered cleanly upon re-grant. | **PASS** |
| 5 | `hard036-05-duplicate-filename-isolation` | Back-to-back transfers of identical filename (`01-small-01.bin`) | Committed 2 distinct UUID-isolated files in `files/received/`; both matched SHA-256 `045a28a52565...`. | **PASS** |
| 6 | `hard036-06-disk-full-and-resume-corruption` | Storage ceiling exceeded (`INSUFFICIENT_STORAGE`) & corrupted resume journal | Pre-flight storage check and resume store integrity checks failed closed with 0 partial bytes exposed. | **PASS** |
