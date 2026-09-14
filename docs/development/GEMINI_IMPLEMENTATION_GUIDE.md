# Gemini implementation guide
Start every new session here through [docs portal](../README.md). Glide is a local, account-free premium file-sharing app. Android↔Android is the only current application scope. Astra/Codex authored the plan; Gemini is the later implementation agent. Planning has created no application code.

## Locate authority
Read [master plan](../GLIDE_MASTER_PLAN.md), [TODO_MASTER](../TODO_MASTER.md), [roadmap](../ROADMAP.md), [dependency graph](../TASK_DEPENDENCY_GRAPH.md), then the selected task's linked architecture/ADR/design/security/tests. Task status lives only in TODO_MASTER; wire semantics in protocol; tokens in design system; decisions in ADRs. Existing raster assets are canonical. Root has a generic IDE project and brand pack at the planning snapshot, not a working Android build.

## First action and implementation order
First READY task is GLIDE-001: reproducible Android build. Do not begin random tasks, finish deferred branding first, or scaffold all possible future modules. Pick the next recommended READY item whose dependencies are COMPLETE and current milestone allows it. If code already exists in a later checkout, inspect and verify it before deciding task state.

Planned modules: app, transfer-core, transport-nearby, data, designsystem; benchmark later. Android min26/compile37/target37; exact versions in [technology research](../planning/TECHNOLOGY_RESEARCH.md). Final publisher application ID remains an owner decision before public distribution; provisional dev.glide.app is not an owned identity claim.

## Repeatable task execution protocol
1. Read context: task, master, relevant ADRs and linked specifications.
2. Inspect implementation: git status/diff, relevant code/resources/tests, recent session handoff; preserve unrelated edits.
3. Check dependencies: exact pins, platform/API requirements, module boundaries and any release-note changes since planning.
4. State bounded scope and acceptance; mark IN_PROGRESS with owner/date. Implement only that task; no unrelated refactor.
5. Build appropriate modules. After foundation, typical Windows commands are `.\gradlew.bat :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :transfer-core:test`; use actual task names after inspecting Gradle tasks. CI uses ./gradlew. Commands are planned, not currently runnable.
6. Test task-specific unit/integration/instrumentation and physical cases. `:app:connectedDebugAndroidTest` requires attached device; radio proof requires two physical phones.
7. Verify UI when affected: actual light/dark/dynamic, errors/empty/success, large text, TalkBack, back/resize and relevant screenshots.
8. Verify architecture/security/performance checklists. Run release-like checks when task affects shrinking, manifest or performance.
9. Update authoritative docs only for reviewed behavior changes; update task evidence/status and [session handoff](SESSION_HANDOFF_TEMPLATE.md) always.
10. Produce implementation report: changed files, decisions/deviations, exact commands/results, acceptance evidence, limitations, blockers and next READY task.

Use REVIEW_REQUIRED when implementation exists but review/manual validation is missing. COMPLETE requires [DoD](../DEFINITION_OF_DONE.md). No “tests passed” without actual command/exit evidence; no physical proof from emulator/fake adapters.

## Implementation rules by concern
- State: immutable UI snapshots, ViewModels, StateFlow/lifecycle collection; serialized coordinator state; explicit scope ownership.
- Networking: UI isolated from transport; Nearby P2P is an adapter, not domain; authenticated control then consent then stream.
- Protocol: exact V1 bounds/order/receipts/fixtures; no app-specific Kotlin serialization defaults silently defining wire format.
- Storage: bounded streaming and SHA256; private quarantine/commit; modern URI grants and explicit export.
- Security: compare full SDK digits on both devices, no trusted name, redacted logs/backups and safe exported components.
- Design: use committed logo files and documented tokens; restrained depth/motion, accessible adaptive layouts.
- Dependencies: stable and minimal; no cloud, image-loading Glide library, custom crypto or experimental UI without ADR.
- Uncertainty: stop the specific invalid assumption, research primary evidence, write change proposal/ADR and update affected tasks/specs before implementation; continue unrelated safe work.
- Avoid changing: original logos, unrelated IDE/user files, protocol limits, minSdk/app identity, permissions or signing policy outside task authorization.

## Session completion
Save a dated report under docs/development/sessions/ using the template. Reference commit/diff identity, task IDs and reproducible evidence. Recalculate milestone percentage from COMPLETE tasks only; update next recommendation and blockers. Leave remaining tasks visible. Never infer approval to publish, send messages or use signing secrets from a build task; release operations have explicit owner gates.

