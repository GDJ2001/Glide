# Glide master engineering plan
Planning baseline2026-09-12. Primary human/Astra/Gemini entry point. **Documentation only: application implementation has not started.** Begin with [portal](README.md), then [next task](TODO_MASTER.md#glide-001).

## 1. Executive summary
Build a premium, private, account-free local Android↔Android sharing app. Prove a real authenticated one-file transfer early, then batch UX, background reliability, polish, hardening and release. Existing accepted branding remains canonical. Gemini implements later; Astra supplies requirements and review.

## 2. Product vision
Glide should feel smooth, trustworthy and direct: no advertisements, mandatory account or cloud upload. Long-term native universal transfer is enabled by portable protocol semantics, not by building all platforms now.

## 3. Current scope
Android file selection, nearby discovery, verified connection, separate transfer consent, sequential multi-file batches, bounded large-file streaming, SHA256, private received library/Open/Export, progress/cancel/manual retry, background best effort, history, settings, Share Sheet and adaptive accessible UI. [Roadmap](ROADMAP.md) classifies P0/P1/P2.

## 4. Non-goals
No true pause/resume, folders, trusted-device autoaccept, simultaneous peers, remote transfers, backend, account, ads, own telemetry SDK, custom crypto or non-Android app. P3/P4/P5 work remains a separate future bucket in [tasks](TODO_MASTER.md).

## 5. Repository status
201 tracked files at main3b3ad6a before planning; brand pack and generic IntelliJ project only. No Android/Gradle/source/tests/CI/application ID. IDE JDK25 is not the chosen build baseline. [Repository audit](planning/REPOSITORY_AUDIT.md) records evidence and conflicts.

## 6. Brand status
178 manifest graphics:20 raster masters,156 PNG exports,2 ICOs, plus3 presentation PNGs outside that manifest. Accepted horizontal/stacked/symbol/wordmark/app variants retained. [Per-file inventory](design/BRAND_ASSET_INVENTORY.md), [gap checklist](design/BRAND_ASSET_CHECKLIST.md), [catalog](../asset/index.html). Native adaptive/themed/notification/splash integration, editable vectors and store marketing remain gaps.

## 7. Current technology stack
Selected stable Kotlin2.4.20, AGP9.4.0/Gradle9.6/JDK17, Compose BOM2026.09.00, stable Material3/Adaptive, Navigation3, Hilt, Room2, DataStore and Nearby19.5. Versions, alternatives and compatibility gate are authoritative in [technology research](planning/TECHNOLOGY_RESEARCH.md); primary references in [sources](planning/SOURCES.md). No dependencies installed by planning.

## 8. Architecture overview
Single Activity, Compose UDF, ViewModels/StateFlow, serialized core coordinator, explicit ports and lifecycle scopes. UI never calls transport. [Architecture](architecture/GLIDE_ARCHITECTURE.md), [engineering rules](development/GEMINI_ENGINEERING_RULES.md).

## 9. Module architecture
Five production modules app/transfer-core/transport-nearby/data/designsystem; benchmark test module later. Feature packages instead of excessive Gradle modules. Dependency diagram and prohibited edges in [architecture](architecture/GLIDE_ARCHITECTURE.md).

## 10. Transfer architecture
Nearby P2P is initial adapter; BYTES control and STREAM bulk chosen for private output and bounded hashing. One peer/batch, sequential items. Physical STREAM-versus-FILE proof required before wider work. [Transport comparison](planning/TRANSPORT_RESEARCH.md), [transport ADR](adr/ADR-0007-transport.md).

## 11. Protocol architecture
[Glide Protocol V1](protocol/GLIDE_PROTOCOL_V1.md) defines version negotiation, bounded messages/paged offers, consent, bulk mapping, exact lengths/SHA256, cancellation and durable receipts. Reliable authenticated channel is required from every adapter. Future clients implement the same semantics independently.

## 12. Security model
Full SDK authentication digits compared on both phones; separate offer acceptance; private quarantine; strict parsing/rate/path limits; secure random session IDs; no persistent trust V1. [Threat model](security/GLIDE_THREAT_MODEL.md), [security checklist](security/SECURITY_CHECKLIST.md).

## 13. Privacy model
No own backend, analytics or account. Nearby SDK diagnostics require accurate disclosure; do not claim zero third-party collection. Private history/files, explicit exports, backup/log/notification controls. Privacy authority is [threat model](security/GLIDE_THREAT_MODEL.md).

## 14. Storage model
Modern granted content URIs, bounded staging when unknown/transient, private received-file catalog and atomic file visibility with crash journal. Export verified files explicitly to MediaStore/SAF. [Storage specification](android/STORAGE_AND_BACKGROUND.md).

## 15. Permission model
Point-of-use, API-specific nearby/Bluetooth/Wi-Fi/location compatibility and API37 local-network permission; notification request separate. No broad media/storage grants for selected design. [Permission matrix](android/PERMISSION_MATRIX.md).

## 16. Background execution
User-started connectedDevice FGS for live session; no permanent receiver or guaranteed process survival. WorkManager maintains orphans, never resumes live transfer. [Execution behavior table](android/STORAGE_AND_BACKGROUND.md).

## 17. Data architecture
Separate domain/DTO/entity/UI models, Room history/catalog/journal, DataStore preferences. Deterministic orthogonal discovery/link/session/item machines and terminal races in [state/data model](architecture/STATE_AND_DATA_MODEL.md); [failure taxonomy](architecture/GLIDE_FAILURE_MODEL.md).

## 18. UI architecture
Home/History/Settings navigation, shared selection/discovery/verification/offer/transfer/result routes, no screen per transient state. [Screen inventory and complete flows](design/GLIDE_USER_FLOWS.md).

## 19. Design system
Accepted blue/cyan/indigo identity; restrained tonal depth, premium system typography, shared spacing/shapes, branded default and dynamic-color opt-in. [Design tokens](design/GLIDE_DESIGN_SYSTEM.md), [UI checklist](design/UI_QUALITY_CHECKLIST.md).

## 20. Motion
Centralized durations/easing, state-explaining progress/connection transitions, no simulated bytes or excessive glow. System animation-off/reduced-motion and lifecycle stop respected. [Motion system](design/GLIDE_DESIGN_SYSTEM.md).

## 21. Adaptive UI
Window-size-driven compact bar/rail/list-detail; posture/height/input aware, same state and navigation across resize. No orientation lock or separate tablet app. [Adaptive rules](design/GLIDE_DESIGN_SYSTEM.md).

## 22. Accessibility
TalkBack, code reading, keyboard/switch access,48dp targets,200% text, RTL, contrast, magnification, non-color status and throttled announcements. [Design requirements](design/GLIDE_DESIGN_SYSTEM.md), [quality gate](DEFINITION_OF_DONE.md).

## 23. Performance
Measured startup/frame/RAM/CPU/thermal/size budgets; bounded multi-GB pipeline, honest device throughput and Baseline Profiles. [Performance plan](performance/GLIDE_PERFORMANCE_PLAN.md), [checklist](performance/PERFORMANCE_CHECKLIST.md).

## 24. Testing
Unit/contract/golden/fuzz, storage/Room integration, Compose/system UI, actual two-device proof and OEM chaos/soak. Emulator tests cannot validate radio reliability. [Test strategy](testing/GLIDE_TEST_STRATEGY.md).

## 25. CI/CD
Pinned build, lint/tests, dependency/secret/security checks, protected release signing, artifact provenance/retention and physical evidence gates. [CI/CD plan](development/CI_CD_PLAN.md).

## 26. Release strategy
Developer→internal→required closed testing→approved RC→controlled production→health/maintenance. Owner resolves identity/licenses/signing/Console policy and approves publication. [Release plan](release/GLIDE_RELEASE_PLAN.md).

## 27. Risks
GMS/OEM reliability, STREAM performance, process interruption, no resume, disk/provider behavior, recent compiler compatibility, branding/native gaps and release identity. [Risk register](RISK_REGISTER.md), [assumptions](ASSUMPTIONS.md), [open questions](OPEN_QUESTIONS.md).

## 28. Roadmap
13 V1 lifecycle milestones including documentation M0; future bucket M13 excluded. Early proof M3 includes hash/Open/history before broad polish. [Roadmap](ROADMAP.md), [end-to-end workflow](GLIDE_END_TO_END_WORKFLOW.md).

## 29. Task system
72 tasks:68 current V1 delivery and4 future planning items. All application work is READY/NOT_STARTED; none complete. Exact prerequisites/acceptance/testing/evidence in [TODO_MASTER](TODO_MASTER.md), graph in [dependencies](TASK_DEPENDENCY_GRAPH.md).

## 30. Gemini implementation process
[Implementation guide](development/GEMINI_IMPLEMENTATION_GUIDE.md) → [rules](development/GEMINI_ENGINEERING_RULES.md) → selected READY task. [Prompt templates](development/GEMINI_PROMPT_TEMPLATES.md), [session handoff](development/SESSION_HANDOFF_TEMPLATE.md), [change control](development/CHANGE_CONTROL.md) and [ADR index](adr/README.md) prevent drift. Read current code before trusting a status.

## 31. Cross-platform future
Android→Windows→browser→iOS/macOS→Linux→remote transfer. Reuse protocol/fixtures; replace native permissions/storage/execution/transport. [Strategic path](future/CROSS_PLATFORM_PATH.md). No future implementation now.

## 32. Definition of done
Build/tests + actual acceptance + UI/security/performance/accessibility review + evidence/docs, not source existence. [DoD and gates](DEFINITION_OF_DONE.md), [planning completeness audit](planning/PLANNING_COMPLETENESS_AUDIT.md).

## 33. Current next action
Open [GLIDE-001](TODO_MASTER.md#glide-001) in a later Gemini coding session, prove the pinned build and compiler/processor combination, preserve artwork and stay within task scope. Do not start it during this planning run.

