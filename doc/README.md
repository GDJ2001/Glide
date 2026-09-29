# Glide documentation portal

Glide is in an evidence-backed **code review, improvement, hardening and development-completion cycle**. Historical Android V1 and later cross-platform session records remain valuable evidence, but historical `COMPLETE`, release, or certification labels do not by themselves certify the current multi-platform checkout.

**Current development authority:** [Code Review, Improvement, Hardening & Development Completion Plan](planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md)  
**Task history:** [HARD-001–043 master task ledger](TODO_MASTER.md)
**Latest reconciliation:** [HARD-002 source/doc/claim reconciliation](planning/HARD_002_SOURCE_DOC_CLAIM_RECONCILIATION.md)  
**Current development status:** The HARD ledger records historical task claims. Reconcile those claims with the checked-out source and fresh tests before selecting or closing a task; the active Android runtime exists, while secure browser sharing and durable resume still require production integration.

The hardening review baseline is `ea2e811a7f2d8aaddc552e894fe04782cd08a499`. HARD-002 froze its reconciliation boundary at `12681eabba26387fe7d1bbaf309971f345642e41`, reviewed every source/configuration commit between those points, and separated component implementation/test evidence from physical interoperability and release claims. Apple and remote/Internet implementation is preserved but remains deferred from the active release gate.

## Start here

1. [Current completion/hardening plan](planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md)
2. [Current HARD task ledger](TODO_MASTER.md)
3. [HARD-002 reconciliation report](planning/HARD_002_SOURCE_DOC_CLAIM_RECONCILIATION.md)
4. [Current master plan](GLIDE_MASTER_PLAN.md)
5. [Current roadmap](ROADMAP.md)
6. [AI/Gemini implementation guide](development/GEMINI_IMPLEMENTATION_GUIDE.md)
7. [Definition of done / quality gates](DEFINITION_OF_DONE.md)
8. [Change control](development/CHANGE_CONTROL.md)

## Architecture and protocol

- [Android architecture and module boundaries](architecture/GLIDE_ARCHITECTURE.md)
- [State and data models](architecture/STATE_AND_DATA_MODEL.md)
- [Failure model](architecture/GLIDE_FAILURE_MODEL.md)
- [Architecture decision records](adr/README.md)
- [Glide Protocol V1](protocol/GLIDE_PROTOCOL_V1.md)
- [Cross-platform path](future/CROSS_PLATFORM_PATH.md)

## Security, testing and performance

- [Threat/privacy model](security/GLIDE_THREAT_MODEL.md)
- [Security checklist](security/SECURITY_CHECKLIST.md)
- [Test strategy](testing/GLIDE_TEST_STRATEGY.md)
- [Performance plan](performance/GLIDE_PERFORMANCE_PLAN.md)
- [Performance checklist](performance/PERFORMANCE_CHECKLIST.md)

## Platform and product references

- [Android permissions](android/PERMISSION_MATRIX.md)
- [Android storage/background execution](android/STORAGE_AND_BACKGROUND.md)
- [Android UI review, fixes and Pixel screenshots — 27 September 2026](android/ANDROID_UI_REVIEW_2026-09-27.md)
- [Design system](design/GLIDE_DESIGN_SYSTEM.md)
- [User flows](design/GLIDE_USER_FLOWS.md)
- [Release plan](release/GLIDE_RELEASE_PLAN.md)

## Historical planning and evidence

The original GLIDE-001–072 task ledger is preserved in Git history at pre-HARD commit [`b07f15b`](https://github.com/GDJ2001/Glide/blob/b07f15bdde60bb5bfa9b20fdaba093b7a6a93785/docs/TODO_MASTER.md). Historical V1 release reports, LAN/QR plans, cross-platform session reports and evidence remain useful for provenance, but the current checkout must satisfy the HARD ledger before present-day completion/release claims are made.

Useful historical sources include:

- [Original LAN/QR/Quick Share plan](planning/LAN_QR_QUICK_SHARE_MASTER_PLAN.md)
- [V1 closure and next-cycle handoff](release/V1_CLOSURE_AND_NEXT_CYCLE_HANDOFF.md)
- [Repository audit](planning/REPOSITORY_AUDIT.md)
- [Planning completeness audit](planning/PLANNING_COMPLETENESS_AUDIT.md)
- [Development session records](development/sessions/)

## Authority rule

For current development status, follow this order: **HARD task ledger → current hardening plan/reconciliation → current source/configuration/tests → relevant protocol/ADR/specification → historical evidence**. Never infer current completion from an old task label or planning document when executable source or current evidence disagrees.
