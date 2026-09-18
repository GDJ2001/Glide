# HARD-010 — Two-device Android production journey preparation

**Date:** 2026-09-18  
**Status:** BLOCKED  
**Preparation head:** `460723cc1c774813770c88ee38a3242f9ceac9d2`

## Why HARD-010 is blocked

HARD-010 is a mandatory physical-device evidence gate. It cannot be completed with emulator, loopback, JVM, screenshot-only, or historical evidence.

Two independent blockers currently prevent completion:

1. **HARD-008 and HARD-009 remain REVIEW_REQUIRED** because GitHub Actions is not scheduling current-head push or pull-request runs.
2. The current execution environment does not expose two attached physical Android devices, so the required bidirectional hardware campaign cannot be performed here.

No physical success claim is made.

## Preparation delivered

### Physical evidence harness

Added:

- `tools/hard-010-physical-journey.ps1`

The harness has four modes:

- `Prepare`
- `BeginRun`
- `EndRun`
- `Audit`

It deliberately rejects emulator/ranchu/goldfish devices and requires two physical Android devices.

Prepare:

- builds an exact environment manifest around an already-built candidate APK;
- generates/reuses deterministic 10 MiB and 1 GiB fixtures;
- installs the exact APK on both devices;
- pushes both fixtures to both devices;
- verifies fixture SHA-256 independently on each device;
- records repository HEAD, APK SHA-256, manufacturer/model, Android/API, security patch, build fingerprint, GMS version, approximate RAM and data-filesystem information;
- emits the required physical-run matrix.

BeginRun:

- records exact sender/receiver roles;
- clears device logs;
- records receiver/sender app-private received/staging state when `run-as` is available;
- captures before screenshots;
- binds the run to the candidate repository/APK identity.

EndRun:

- captures after screenshots and logcat from both devices;
- re-reads app-private received/staging state;
- proves whether a **new** receiver-private file matches the expected fixture SHA-256;
- records whether staging is empty;
- detects Glide fatal-crash/ANR signatures;
- records human-observed authentication, consent, Open and History outcomes.

Audit:

- requires all mandatory matrix rows;
- requires success runs to include authentication, consent, a new exact SHA-256 match, empty staging, Open, History, and no Glide fatal crash/ANR;
- requires reject/cancel runs to leave no new full-payload commit and no staging residue;
- still requires human review before TODO completion.

### New authoritative runbook

Added:

- `docs/testing/HARD_010_TWO_DEVICE_ANDROID_RUNBOOK.md`

The runbook supersedes historical GLIDE-017 physical instructions for HARD-010 and defines the exact current gate.

## Required physical matrix

Both production transports are included:

- Nearby 10 MiB A→B
- Nearby 10 MiB B→A
- Nearby 1 GiB A→B
- Nearby 1 GiB B→A
- Nearby receiver reject
- Nearby mid-transfer cancel
- LAN QR 10 MiB A→B
- LAN QR 10 MiB B→A
- LAN QR 1 GiB A→B
- LAN QR 1 GiB B→A
- LAN QR receiver reject
- LAN QR mid-transfer cancel

A success run must follow the production path:

selection → transport authentication/pairing → explicit offer consent → bytes → SHA-256 verification → terminal receipt/result → Open → History.

Reject/cancel must use the same production path and prove that unverified content is not committed/exposed.

## Completion rule

HARD-010 may become COMPLETE only after:

1. HARD-008 and HARD-009 are current-head verified and COMPLETE;
2. two physical Android devices execute the complete required matrix against one exact candidate;
3. the evidence audit succeeds;
4. screenshots/logs and exact candidate/device identities are human-reviewed;
5. all failures/reruns remain visible in the final report.

Until then HARD-010 remains BLOCKED.
