# Glide roadmap

Current roadmap authority is the [Code Review, Improvement, Hardening & Development Completion Plan](planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md). Task status lives in [TODO_MASTER.md](TODO_MASTER.md).

The old GLIDE V1 roadmap and GLIDE-001–072 completion record are historical. They describe prior planning, implementation sessions and release evidence but do not certify the present multi-platform checkout. The original task ledger remains available at immutable pre-HARD commit [`b07f15b`](https://github.com/GDJ2001/Glide/blob/b07f15bdde60bb5bfa9b20fdaba093b7a6a93785/docs/TODO_MASTER.md).

## Current program

| Milestone | Scope / task IDs | Exit gate |
| --- | --- | --- |
| M0 Honest baseline | HARD-001–004 | Current source/docs/status reconciled; regressions exist for confirmed defects; Android/Windows/Linux CI reaches and tests source |
| M1 Android production flow | HARD-005–010 | Two physical Android devices complete production send/receive in both directions with pairing, consent, integrity, rejection and cancellation |
| M2 Desktop/native interoperability | HARD-011–017 | Android↔Windows, Android↔Linux and Windows↔Linux pass actual interoperable multi-file/multi-page transfers |
| M3 Trust, folders and true resume | HARD-018–024 | Authenticated persistent trust and byte-identical resumable folder transfer pass cross-platform interruption/security tests |
| M4 Secure browser sharing | HARD-025–030 | Browser↔native sharing uses authenticated encrypted local WebRTC; plaintext HTTP transfer is absent from release builds |
| M5 Structure/developer experience | HARD-031–035 | Boundaries are maintainable; repository/tooling can be built and verified from a fresh checkout with documented commands |
| M6 Product quality/hardening | HARD-036–040 | Physical failure, security, accessibility, performance and CI gates pass with no unresolved Critical/High security issue |
| M7 Release/operations | HARD-041–043 | Exact reviewed artifacts have checksums/signing/evidence and release occurs only after explicit owner authorization |

## Current status

- HARD-001: COMPLETE — publish evidence-backed review and completion plan.
- HARD-002: READY — reconcile source, documentation, historical claims and commits after the review baseline.
- HARD-003–043: NOT_STARTED until their dependencies are met.

Overall current hardening progress: **1 / 43 tasks (2.3%)**.

## Scope boundary

This release cycle completes Android, Windows 11, Linux and secure browser local sharing, including folders, persistent trust and true resume. Native Apple clients and remote/Internet transfer are deferred. Existing Apple/remote implementation is preserved but does not advance the active release gate unless the task ledger is explicitly amended.

## Evidence rule

Milestone gates are evidence-based. Unit tests, source-text scanners, loopback transports and emulators are useful layers, but they cannot replace physical or independent-implementation tests where the gate explicitly requires them.
