# Glide documentation portal

Glide is in an evidence-backed **code review, improvement, hardening and development-completion cycle**. Historical Android V1 and later cross-platform session records remain valuable evidence, but historical `COMPLETE`, release, or certification labels do not by themselves certify the current multi-platform checkout.

**Current development authority:** [Code Review, Improvement, Hardening & Development Completion Plan](planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md)  
**Current task authority:** [HARD-001–043 master task ledger](TODO_MASTER.md)  
**Next READY task:** [HARD-002 — reconcile source, docs, claims and post-review commits](TODO_MASTER.md#hard-002)

The hardening review baseline is `ea2e811a7f2d8aaddc552e894fe04782cd08a499`. HARD-001 is published from a repository state whose pre-publication head was `b07f15bdde60bb5bfa9b20fdaba093b7a6a93785`, four commits later. Those commits contain additional Apple and remote/Internet work and therefore require HARD-002 revalidation before they can inherit review conclusions.

## Start here

1. [Current completion/hardening plan](planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md)
2. [Current HARD task ledger](TODO_MASTER.md)
3. [Current master plan](GLIDE_MASTER_PLAN.md)
4. [Current roadmap](ROADMAP.md)
5. [AI/Gemini implementation guide](development/GEMINI_IMPLEMENTATION_GUIDE.md)
6. [Definition of done / quality gates](DEFINITION_OF_DONE.md)
7. [Change control](development/CHANGE_CONTROL.md)

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
- [Design system](design/GLIDE_DESIGN_SYSTEM.md)
- [User flows](design/GLIDE_USER_FLOWS.md)
- [Release plan](release/GLIDE_RELEASE_PLAN.md)

## Historical planning and evidence

The original GLIDE-001–072 task ledger is preserved in Git history at pre-HARD commit [`b07f15b`](https://github.com/GDJ2001/Glide/blob/b07f15bdde60bb5bfa9b20fdaba093b7a6a93785/docs/TODO_MASTER.md). Historical V1 release reports, LAN/QR plans, session reports and evidence remain useful for provenance, but the current checkout must satisfy the HARD ledger before present-day completion/release claims are made.

Useful historical sources include:

- [Original LAN/QR/Quick Share plan](planning/LAN_QR_QUICK_SHARE_MASTER_PLAN.md)
- [V1 closure and next-cycle handoff](release/V1_CLOSURE_AND_NEXT_CYCLE_HANDOFF.md)
- [Repository audit](planning/REPOSITORY_AUDIT.md)
- [Planning completeness audit](planning/PLANNING_COMPLETENESS_AUDIT.md)
- [Development session records](development/sessions/)

## Authority rule

For current development status, follow this order: **HARD task ledger → current hardening plan → current source/configuration/tests → relevant protocol/ADR/specification → historical evidence**. Never infer current completion from an old task label or planning document when the executable source disagrees.
