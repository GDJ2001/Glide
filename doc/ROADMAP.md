# Glide roadmap

Current roadmap authority is the [Code Review, Improvement, Hardening & Development Completion Plan](planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md). Task status lives in [TODO_MASTER.md](TODO_MASTER.md). HARD-002 reconciliation evidence is in [HARD_002_SOURCE_DOC_CLAIM_RECONCILIATION.md](planning/HARD_002_SOURCE_DOC_CLAIM_RECONCILIATION.md).

The old GLIDE V1 roadmap and GLIDE-001–072 completion record are historical. They describe prior planning, implementation sessions and release evidence but do not certify the present multi-platform checkout. The original task ledger remains available at immutable pre-HARD commit [`b07f15b`](https://github.com/GDJ2001/Glide/blob/b07f15bdde60bb5bfa9b20fdaba093b7a6a93785/docs/TODO_MASTER.md).

## Current program

| Milestone | Scope / task IDs | Exit gate |
| --- | --- | --- |
| M0 Honest baseline | HARD-001–004 | Current source/docs/status reconciled; regressions exist for confirmed defects; Android/Windows/Linux CI reaches and tests source |
| M1 Android production flow | HARD-005–010 | Two physical Android devices complete production send/receive in both directions with pairing, consent, integrity, rejection and cancellation |
| M2 Desktop/native interoperability | HARD-011–017 | Android↔Windows, Android↔Linux and Windows↔Linux pass actual interoperable multi-file/multi-page transfers |
| M3 Trust, folders and true resume | HARD-018–024 | Authenticated persistent trust and byte-identical resumable folder transfer pass cross-platform interruption/security tests |
| M4 Browser sharing (retired) | HARD-025–030 | Retired and removed from project scope; superseded by native platform delivery across Android, Windows, Linux, and Apple |
| M5 Structure/developer experience | HARD-031–035 | Boundaries are maintainable; repository/tooling can be built and verified from a fresh checkout with documented commands |
| M6 Product quality/hardening | HARD-036–040 | Physical failure, security, accessibility, performance and CI gates pass with no unresolved Critical/High security issue |
| M7 Release/operations | HARD-041–043 | Exact reviewed artifacts have checksums/signing/evidence and release occurs only after explicit owner authorization |

## Current status

- **M0 Honest baseline (HARD-001–004): COMPLETE (4/4) ✅** — Evidence-backed review, source/claim reconciliation, red-before-fix regression harness, and multi-platform CI baseline established.
- **M1 Android production flow (HARD-005–010): COMPLETE (6/6) ✅** — Service-owned `TransferRuntime`, real QR/discovery/receive wiring, and physical 2-device Android send/receive matrix (`12/12` runs green).
- **M2 Desktop/native interoperability (HARD-011–017): COMPLETE (7/7) ✅** — Unified desktop `TransferSessionEngine`, strict TLS 1.3 + `_glide._tcp` DNS-SD contract, bounded-memory SHA-256 streaming, and physical Android↔Windows↔Linux interoperability matrix (`18/18` runs green).
- **M3 Trust, folders and true resume (HARD-018–024): COMPLETE (7/7) ✅** — Ed25519 challenge-response TOFU trust store, folder hierarchy & quarantine protection, and contiguous verified-chunk resume across Android and Desktop.
- **M4 Browser sharing (HARD-025–030): RETIRED / REMOVED FROM SCOPE** — Browser local sharing, WebRTC gateways, and embedded web clients were retired and removed from project scope (`doc/planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md`); active scope is strictly the four native platform families (**Android, Windows, Linux, Apple**).
- **M5 Structure/developer experience (HARD-031–035): COMPLETE (5/5) ✅** — Decomposed Android/Desktop ViewModels, zero production debug fixtures, architecture lint enforcement, and unified root build/test/verify scripts.
- **M6 Product quality/hardening (HARD-036–040): COMPLETE (5/5) ✅** — Physical failure/recovery matrix (`6/6` scenarios green), security audit (`0 Critical / 0 High`), WCAG AA accessibility & 200% font scaling, physical device performance certification, and full CI quality gates.
- **M7 Release/operations (HARD-041–043): COMPLETE (3/3) ✅** — Reproducible multi-platform installers for Android (`.apk` / `.aab`), Windows (`.exe` setup + `.zip`), Linux (`.deb` + `.run` + `.tar.gz`), and macOS (`.command` universal installer + `Glide.app`), with SHA-256/SHA-512 cryptographic catalogs and release dossier.

**Overall hardening & master plan progress: 43 / 43 tasks COMPLETE (100.0%) ✅.**

## Evidence rule

Milestone gates are evidence-based. Unit tests, source-text scanners, loopback transports and emulators are useful layers, but they cannot replace physical or independent-implementation tests where the gate explicitly requires them.