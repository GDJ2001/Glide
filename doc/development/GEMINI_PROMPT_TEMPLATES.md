# Gemini prompt templates
Each template begins with repository documentation and inspection, not code generation from chat memory.

## Implement next task
Read docs/README.md, GLIDE_MASTER_PLAN.md, TODO_MASTER.md, TASK_DEPENDENCY_GRAPH.md and GEMINI_IMPLEMENTATION_GUIDE.md. Inspect git state and latest session report. Select the next recommended READY task with completed dependencies in the current milestone. Read its linked ADR/design/protocol/security/tests. Implement only that scope, run required verification, preserve existing logo masters and unrelated edits, update evidence/status and produce session handoff. Do not publish.

## Implement a specific task
Read the same entry documents, then GLIDE-XXX and every linked requirement. Verify prerequisites in current code; if not satisfied, report blocker rather than skipping. Implement scoped behavior, build/test/UI/security checks and attach acceptance evidence. Use change control for contradictions; no silent redesign.

## Review implementation
Read master/task/ADRs and engineering rules before reviewing the exact diff for GLIDE-XXX. Check behavior against acceptance, protocol/state/storage invariants, UI/accessibility and tests. Reproduce findings, distinguish blocking defects from preferences, do not mark COMPLETE until evidence passes.

## Fix failing tests
Read task, test strategy and latest handoff, inspect failing command output and reproduce. Fix root cause in task scope; do not delete assertions, weaken security, add sleeps or skip device gates to obtain green. Re-run relevant checks, report exact evidence and docs impact.

## Security review
Read threat model, protocol, storage/permissions, relevant ADRs and security checklist. Review current release-relevant code/diff against T01–T18, run adversarial tests and record reproducible findings/residual risks. Do not invent encryption or autoaccept shortcuts.

## UI review
Read design system, user flows and UI quality checklist. Inspect real screens in light/dark/dynamic, large text, TalkBack, RTL, compact/adaptive and motion-off. Verify consent, truthful progress, error recovery and brand fidelity. Attach evidence and bounded fixes.

## Performance review
Read performance plan, checklist and task. Measure release-like startup/frame/memory plus actual radio transfer where relevant on named devices. Compare controlled distributions, preserve hashing/consent, report regressions and justified tuning.

## Complete milestone
Read roadmap, task ledger and DoD. Audit every milestone task's acceptance/evidence and prerequisite gates. Execute missing verification, list external blockers honestly, update percentages from COMPLETE only, and recommend next READY task. Never equate code presence with completion or start future scope.

