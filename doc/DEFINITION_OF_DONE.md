# Definition of done and quality gates
[Task status](TODO_MASTER.md) is authoritative. Evidence is required; “code exists” is insufficient.

| Level | Required completion |
| --- | --- |
| Task done | Scope implemented, all acceptance/tests pass, build/lint clean for scope, relevant UI/security/performance checklist, docs/handoff updated, review complete, evidence linked |
| Feature done | All feature tasks complete, real end-to-end flow plus failure/recreation cases, no hidden fake adapter/placeholder, user-visible behavior matches spec |
| Milestone done | All in-scope task IDs COMPLETE, milestone gate evidence reviewed, dependent risks resolved or explicitly accepted within gate policy |
| Release candidate done | All V1 engineering gates, signed release artifact/install-update/physical tests, privacy/store/signing readiness, exact artifact hash approved |
| Production ready | RC gate plus owner-authorized publication, live store installation, health/rollback/support ownership and honest remaining limitations |

## Quality gates
| Gate | Pass conditions | Evidence owner |
| --- | --- | --- |
| Architecture | Module/port separation, domain/DTO/entity/UI isolation, deterministic state, no unapproved dependencies | Implementation agent + architecture reviewer |
| UX | Complete send/receive/share/error flows, truthful progress/receipts, brand fidelity and private-storage wording | UI reviewer |
| Security | Threat tests, release manifest/backup/log review, no open Critical/High issue | Security reviewer |
| Performance | Measured budgets/distributions, bounded memory,5GB where hardware permits, no main-thread I/O | Performance reviewer |
| Accessibility | TalkBack, keyboard,200% text, contrast, RTL, reduced motion and adaptive controls | Accessibility reviewer |
| Release | Exact signed build, current Play requirements, legal/identity/signing custody, store assets, physical evidence, rollback | Product/release owner |

A missing physical device does not fail a unit test, but it prevents the required physical gate from passing. A source-only compatibility argument does not validate OEM behavior. An accepted noncritical issue needs explicit owner, user impact, mitigation and follow-up; data loss, consent bypass or false integrity success cannot be waived as cosmetic.

M0 means documentation and handoff completeness only. No app build/security/performance production claim follows from M0. [Completeness audit](planning/PLANNING_COMPLETENESS_AUDIT.md).

