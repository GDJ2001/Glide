# Glide roadmap

[Task ledger](TODO_MASTER.md) holds statuses/percentages; [graph](TASK_DEPENDENCY_GRAPH.md) holds exact ordering. This is milestone scope and gate authority. **13 V1 lifecycle milestones including M0**, plus one future bucket M13.68 V1 delivery tasks and4 future planning tasks. No calendar estimates are promised before physical proof.

| Milestone | Scope / task IDs | Exit gate |
| --- | --- | --- |
| M0 Planning | Documentation-only blueprint | Completeness/link/dependency audit; no app implementation |
| M1 Reproducible foundation | GLIDE-001–GLIDE-004 | Clean pinned build, module boundaries, accessible minimal shell and CI baseline |
| M2 Core contracts and protocol | GLIDE-005–GLIDE-009 | Strict tested protocol/state/permission contracts |
| M3 First real device transfer | GLIDE-010–GLIDE-018 | Two real phones: discover→verify→offer→stream→hash→open→history; STREAM benchmark decision |
| M4 Foreground MVP | GLIDE-019–GLIDE-027 | Usable foreground batch/share/export/history/settings flows |
| M5 Background and recovery | GLIDE-028–GLIDE-034 | Background/lock/interruption/journal behavior physically proven |
| M6 Premium UI | GLIDE-035–GLIDE-043 | Consistent premium light/dark/dynamic UI, all states and faithful platform brand resources |
| M7 Adaptive and accessibility | GLIDE-044–GLIDE-047 | Adaptive windows, assistive/input/RTL/large text gate |
| M8 Security hardening | GLIDE-048–GLIDE-050 | Threat-model tests and no open Critical/High finding |
| M9 Performance | GLIDE-051–GLIDE-054 | Measured startup/frame/memory/radio/thermal budgets |
| M10 Release-candidate quality | GLIDE-055–GLIDE-059 | Complete integration/emulator/physical/regression gate |
| M11 Release preparation | GLIDE-060–GLIDE-064 | Signed RC, identity/privacy/store/closed-testing/owner approval |
| M12 Production and maintenance | GLIDE-065–GLIDE-068 | Published exact artifact, monitored health, incident/maintenance handoff (COMPLETE ✅) |
| M13 Future work (outside V1) | GLIDE-069–GLIDE-072 | Folder, persistent trust/resume, advanced transport, and cross-platform LAN research (COMPLETE ✅) |

Priority definitions: P0 technical architecture/proof (M1–M3); P1 MVP (M4); P2 public production V1 (M5–M12); P3 V1.x enhancements; P4 advanced Android; P5 cross-platform. Folder/tree sharing, persistent trust, true resume, non-GMS alternatives and desktop/browser/iOS clients are outside V1. Motion/adaptive/security are specified early but full polish follows real transfer proof. No months of empty infrastructure before M3.

**V1 Milestone Status:** All 13 V1 lifecycle milestones (M0–M12, 68/68 tasks) are COMPLETE ✅. Glide V1 (`0.1.0-alpha`, `versionCode 1`) is delivered, verified, and closed. See [V1 Closure & Next-Cycle Handoff](release/V1_CLOSURE_AND_NEXT_CYCLE_HANDOFF.md) for full retrospective and operational governance.

**Milestone M13 Status:** All 4 post-V1 strategic planning tasks (GLIDE-069–GLIDE-072) are COMPLETE ✅. Specifications, ADRs, pure JVM models, and compliance verification suites are 100% delivered and validated.

