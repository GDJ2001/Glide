# Glide master engineering plan

This is the current top-level engineering plan for Glide. The original September 12 Android-only planning baseline and its 72-task ledger are historical evidence, not present-day completion authority.

- **Current completion plan:** [Code Review, Improvement, Hardening & Development Completion](planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md)
- **Current task authority:** [TODO_MASTER — HARD-001–043](TODO_MASTER.md)
- **Historical GLIDE-001–072 ledger:** immutable pre-HARD commit [`b07f15b`](https://github.com/GDJ2001/Glide/blob/b07f15bdde60bb5bfa9b20fdaba093b7a6a93785/docs/TODO_MASTER.md)
- **Next READY task:** [HARD-002](TODO_MASTER.md#hard-002)

## 1. Product direction

Glide is an account-free file-sharing product built around a portable, authenticated transfer protocol. The hardening cycle must turn the existing Android, desktop, LAN/browser and recovery work into one verified product rather than treating isolated modules or source-text checks as end-to-end completion.

## 2. Active release scope

Complete and verify:

- Android local sharing via Nearby and LAN.
- Windows 11 and Linux local sharing.
- Secure browser sharing.
- Multi-file and folder transfer.
- Persistent device trust and true resume.
- History, settings, accessibility, background behavior, packaging and release verification.

Preserve but defer from this cycle:

- Native Apple clients.
- Remote/Internet transfer.

Apple and remote source already present in the repository must not be deleted. It is simply outside the current release gate until explicitly authorized.

## 3. Review and publication baselines

The evidence-backed hardening review inspected `main` at `ea2e811a7f2d8aaddc552e894fe04782cd08a499`. The pre-publication head for HARD-001 was `b07f15bdde60bb5bfa9b20fdaba093b7a6a93785`. Four intervening commits added Apple `GlideCoreKit` work and additional remote ICE/TURN/signaling/session code. HARD-002 must review and reconcile that delta before affected status claims are made.

## 4. Architecture direction

### Android

Keep `transfer-core` transport-independent. Introduce a service-owned `TransferRuntime`; ViewModels observe immutable state and issue typed commands rather than constructing transports or owning transfer scopes. Navigation carries opaque IDs instead of live transport/session objects or credentials.

### Desktop

Create one shared application/transfer engine consumed by WPF and CLI. Keep protocol/domain, orchestration, transport, storage and presentation responsibilities separate. WPF and CLI must exercise the same validation, consent, session and recovery implementation.

### Browser

The release browser path must use authenticated encrypted local WebRTC DataChannels loaded from a trusted static HTTPS client. File content and pairing secrets must not transit the web host, and release builds must not fall back to the existing plaintext HTTP transfer path.

## 5. Security invariants

- Encryption is not identity; every native session must have authenticated bootstrap/trust.
- QR/pinned bootstrap consumes invitations atomically and still requires offer consent.
- Persistent trust requires explicit enrollment, protected keys and proof of possession.
- Trusted peers do not imply arbitrary-file auto-acceptance.
- Protocol sequence, IDs, paging, consent and terminal results are validated identically across implementations.
- Unverified files stay quarantined.
- Hashing and transfer remain bounded-memory.
- Resume advances only through contiguous verified chunks, revalidates peer/source state and finishes with whole-file SHA-256.

## 6. Development program

The program has eight milestones and 43 tasks:

| Milestone | Scope | Tasks |
| --- | --- | --- |
| M0 | Honest baseline | HARD-001–004 |
| M1 | Android production flow | HARD-005–010 |
| M2 | Desktop/native interoperability | HARD-011–017 |
| M3 | Trust, folders and true resume | HARD-018–024 |
| M4 | Secure browser sharing | HARD-025–030 |
| M5 | Structure/developer experience | HARD-031–035 |
| M6 | Product quality/hardening | HARD-036–040 |
| M7 | Release/operations | HARD-041–043 |

Default order is M0 → M1 → M2 → M3 → M4 → M5 → M6 → M7. Deviations require an explicit task-ledger/change-control update.

## 7. Evidence standard

A feature is not complete merely because code exists or a source-pattern test passes. Completion requires the task's specified production-path tests and evidence. In particular:

- physical two-device tests cannot be replaced by emulator/loopback tests;
- interoperability requires independent implementations talking to each other;
- performance claims require real device measurements when the claim concerns device/network throughput;
- release status belongs to an exact artifact and commit;
- manual gates remain visible until actually performed.

## 8. Repository evolution

A later structural milestone may organize the repository under `android/`, `desktop/`, `web/` and `protocol/fixtures/`. That move is HARD-033 and must be mechanical. Do not mix path moves with behavior/protocol changes.

## 9. Immediate next action

HARD-001 publishes the current plan and status authority only. It does not modify product behavior or authorize deployment. The next task is **HARD-002 — reconcile current source, documentation, historical claims and all commits after the review baseline**.
