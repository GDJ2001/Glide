# Glide master task system

**Current status authority for active development.** The historical GLIDE-001–072 ledger is preserved in Git history at pre-HARD commit `b07f15bdde60bb5bfa9b20fdaba093b7a6a93785` and its earlier commits. Historical `COMPLETE` labels do not certify the current multi-platform candidate.

Current program: **HARD-001–HARD-043 — Code Review, Improvement, Hardening & Development Completion**.  
Plan: [`planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md`](planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md)  
Review baseline: `ea2e811a7f2d8aaddc552e894fe04782cd08a499`  
Publication base: `b07f15bdde60bb5bfa9b20fdaba093b7a6a93785`  
**Next recommended task: HARD-002.**

## Status policy

- `NOT_STARTED`: no qualifying implementation/evidence yet or prerequisites incomplete.
- `READY`: prerequisites complete and no named blocker remains.
- `IN_PROGRESS`: one active session owns the task.
- `REVIEW_REQUIRED`: implementation exists but acceptance/review/manual evidence is incomplete.
- `BLOCKED`: a named external or prerequisite blocker prevents completion.
- `COMPLETE`: all acceptance criteria, regression tests and required evidence are satisfied.
- `DEFERRED`: explicitly outside the current hardening cycle.

Do not mark tasks complete because files exist, source-text checks pass, loopback tests pass, an emulator renders, or historical documents say a milestone was released. Physical/interoperability gates require their specified real environments.

## Program progress

| Milestone | Tasks | Complete | Status |
| --- | --- | ---: | --- |
| M0 Honest baseline | HARD-001–004 | 1 / 4 | IN PROGRESS |
| M1 Android production flow | HARD-005–010 | 0 / 6 | NOT STARTED |
| M2 Desktop/native interoperability | HARD-011–017 | 0 / 7 | NOT STARTED |
| M3 Trust, folders and true resume | HARD-018–024 | 0 / 7 | NOT STARTED |
| M4 Secure browser sharing | HARD-025–030 | 0 / 6 | NOT STARTED |
| M5 Structure/developer experience | HARD-031–035 | 0 / 5 | NOT STARTED |
| M6 Product quality/hardening | HARD-036–040 | 0 / 5 | NOT STARTED |
| M7 Release/operations | HARD-041–043 | 0 / 3 | NOT STARTED |
| **Total** | **HARD-001–043** | **1 / 43** | **2.3%** |

## Finding → remediation map

| Finding | Tasks |
| --- | --- |
| Android production wiring/discovery/receive graph | HARD-003, HARD-005–010 |
| Fixed/example receive QR and non-starting scan flow | HARD-003, HARD-008, HARD-010 |
| Linux daemon authentication/auto-accept weakness | HARD-003, HARD-016, HARD-017 |
| Android/desktop LAN TLS and DNS-SD incompatibility | HARD-012, HARD-017 |
| Desktop sequencing/pagination/session-engine gaps | HARD-011, HARD-013, HARD-017 |
| Whole-file desktop hashing | HARD-003, HARD-014 |
| Unsynchronized SAS runtime path | HARD-003, HARD-012, HARD-013 |
| Plain HTTP browser transfer / incomplete consent | HARD-003, HARD-025–030 |
| Partial trust/resume integration | HARD-018–024 |
| Overstated completion/release/CI evidence | HARD-002, HARD-004, HARD-040–043 |
| Maintainability / duplicated orchestration/assets | HARD-031–035, HARD-040 |

## Task index

| ID | Milestone | Status | Title |
| --- | --- | --- | --- |
| HARD-001 | M0 | COMPLETE | Publish evidence-backed review and completion plan |
| HARD-002 | M0 | READY | Reconcile source, docs, claims and post-review commits |
| HARD-003 | M0 | NOT_STARTED | Add regressions for confirmed review defects |
| HARD-004 | M0 | NOT_STARTED | Establish Android/Windows/Linux CI baseline |
| HARD-005 | M1 | NOT_STARTED | Introduce service-owned Android TransferRuntime |
| HARD-006 | M1 | NOT_STARTED | Wire production discovery and permissions |
| HARD-007 | M1 | NOT_STARTED | Wire complete Nearby send/receive lifecycle |
| HARD-008 | M1 | NOT_STARTED | Wire real LAN listener and QR invitation lifecycle |
| HARD-009 | M1 | NOT_STARTED | Complete service/session/background lifecycle |
| HARD-010 | M1 | NOT_STARTED | Prove two-device Android production journey |
| HARD-011 | M2 | NOT_STARTED | Extract shared desktop transfer engine |
| HARD-012 | M2 | NOT_STARTED | Align native LAN discovery/TLS/bootstrap profile |
| HARD-013 | M2 | NOT_STARTED | Implement strict desktop session/protocol state machine |
| HARD-014 | M2 | NOT_STARTED | Make desktop hashing/storage bounded and shared |
| HARD-015 | M2 | NOT_STARTED | Complete desktop consent/history/settings/state |
| HARD-016 | M2 | NOT_STARTED | Harden Linux CLI/daemon receiving lifecycle |
| HARD-017 | M2 | NOT_STARTED | Prove native cross-platform interoperability |
| HARD-018 | M3 | NOT_STARTED | Finalize identity/resume protocol extension |
| HARD-019 | M3 | NOT_STARTED | Integrate Android persistent identity/trust |
| HARD-020 | M3 | NOT_STARTED | Integrate Windows/Linux persistent identity/trust |
| HARD-021 | M3 | NOT_STARTED | Integrate mutually authenticated trusted sessions |
| HARD-022 | M3 | NOT_STARTED | Implement Android durable true-resume journals |
| HARD-023 | M3 | NOT_STARTED | Implement desktop durable true-resume storage |
| HARD-024 | M3 | NOT_STARTED | Validate folders and resume across native platforms |
| HARD-025 | M4 | NOT_STARTED | Specify secure browser transport and static client |
| HARD-026 | M4 | NOT_STARTED | Add Android WebRTC adapter |
| HARD-027 | M4 | NOT_STARTED | Add Windows/Linux WebRTC adapter |
| HARD-028 | M4 | NOT_STARTED | Implement secure browser transfer behavior |
| HARD-029 | M4 | NOT_STARTED | Implement browser identity and resume |
| HARD-030 | M4 | NOT_STARTED | Prove browser security/compatibility and remove HTTP release path |
| HARD-031 | M5 | NOT_STARTED | Refactor Android entry/presentation boundaries |
| HARD-032 | M5 | NOT_STARTED | Refactor desktop presentation boundaries |
| HARD-033 | M5 | NOT_STARTED | Mechanically reorganize platform trees |
| HARD-034 | M5 | NOT_STARTED | Consolidate fixtures and browser sources |
| HARD-035 | M5 | NOT_STARTED | Standardize developer commands/tooling |
| HARD-036 | M6 | NOT_STARTED | Exercise physical failure/recovery matrix |
| HARD-037 | M6 | NOT_STARTED | Perform production security review and remediation |
| HARD-038 | M6 | NOT_STARTED | Validate real accessibility/adaptive/UI journeys |
| HARD-039 | M6 | NOT_STARTED | Measure production performance on devices |
| HARD-040 | M6 | NOT_STARTED | Complete CI/architecture/migration/dependency/doc gates |
| HARD-041 | M7 | NOT_STARTED | Build/test release artifacts for supported platforms |
| HARD-042 | M7 | NOT_STARTED | Complete exact-candidate release dossier |
| HARD-043 | M7 | NOT_STARTED | Execute explicitly authorized staged release |

---

<a id="hard-001"></a>
## HARD-001 — Publish evidence-backed review and completion plan

- **Milestone:** M0 — Honest baseline
- **Status:** COMPLETE
- **Dependencies:** None
- **Affected modules/files:** documentation only; `docs/planning`, `docs/README.md`, `docs/GLIDE_MASTER_PLAN.md`, `docs/ROADMAP.md`, `docs/development/GEMINI_IMPLEMENTATION_GUIDE.md`, `docs/TODO_MASTER.md`.
- **Acceptance criteria:** Review baseline and limitations are explicit; confirmed gaps are mapped to tasks; active release scope is explicit; Apple/remote work is preserved but deferred; current task authority links are consistent.
- **Regression/verification:** Link/path inspection; compare review baseline to publication base; no application/source/build behavior modified.
- **Completion evidence:** [`planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md`](planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md) and HARD-001 session report under `development/sessions/`.

<a id="hard-002"></a>
## HARD-002 — Reconcile source, docs, claims and post-review commits

- **Milestone:** M0 — Honest baseline
- **Status:** READY
- **Dependencies:** HARD-001
- **Affected modules/files:** repository-wide source/config/docs; especially Apple and remote additions after `ea2e811`, release docs, ADRs, README and status pages.
- **Acceptance criteria:** Every completion/release claim is classified as implemented/tested/physically verified/released; four post-review commits are source-reviewed; deferred Apple/remote work is clearly separated from active scope; stale contradictions are corrected without erasing history.
- **Regression tests:** Documentation link/status audit; source-vs-doc contract checks; no behavior change required.
- **Evidence required:** Commit/diff list, reviewed files, contradictions fixed, unresolved claims, next READY task.

<a id="hard-003"></a>
## HARD-003 — Add regressions for confirmed review defects

- **Milestone:** M0 — Honest baseline
- **Status:** NOT_STARTED
- **Dependencies:** HARD-002
- **Affected modules:** `app`, `transport-lan`, `windows/Glide.App`, `windows/Glide.Windows.UI`, `windows/Glide.Transport`, `windows/Glide.Tests`, test tooling.
- **Acceptance criteria:** Regressions fail against the reviewed defects: disconnected production Android wiring, invalid/missing invitation acceptance, unsynchronized SAS authentication, sequence/pagination misuse, plaintext browser assumptions and whole-file hashing path.
- **Regression tests:** Production-path tests, cross-language fixture tests and bounded-memory assertions; source-text-only tests do not satisfy this task.
- **Evidence required:** Demonstrated red-before/fixed-later tests or reproducible failing fixtures with exact commands.

<a id="hard-004"></a>
## HARD-004 — Establish Android/Windows/Linux CI baseline

- **Milestone:** M0 — Honest baseline
- **Status:** NOT_STARTED
- **Dependencies:** HARD-003
- **Affected modules:** `.github/workflows`, Gradle modules, `windows/Glide.slnx`, Linux packaging/tooling.
- **Acceptance criteria:** CI reaches source checkout and executes Android build/lint/tests plus .NET build/tests and Linux-compatible shared solution/package checks; action pins resolve; no signing secrets exposed.
- **Regression tests:** Clean CI runs on current HEAD; intentional test failure blocks the job.
- **Evidence required:** Workflow run IDs/URLs, commands, versions, artifacts and known hardware-only gaps.

<a id="hard-005"></a>
## HARD-005 — Introduce service-owned Android TransferRuntime

- **Milestone:** M1 — Android production flow
- **Status:** NOT_STARTED
- **Dependencies:** HARD-004
- **Affected modules:** `app`, `transfer-core`, Android DI/service/session packages.
- **Acceptance criteria:** One service-owned runtime exposes typed send/receive/accept/reject/cancel/resume commands and immutable discovery/pairing/transfer state; ViewModels do not construct transports or own transfer scopes.
- **Regression tests:** Rotation/recreation, duplicate command, exact-session cancellation and runtime ownership tests.
- **Evidence required:** Architecture diff, tests and lifecycle traces.

<a id="hard-006"></a>
## HARD-006 — Wire production discovery and permissions

- **Milestone:** M1
- **Status:** NOT_STARTED
- **Dependencies:** HARD-005
- **Affected modules:** Android navigation, discovery ViewModels, permission bridge, Nearby/LAN composition.
- **Acceptance criteria:** Production ViewModels receive real runtime/discovery dependencies; point-of-use permission denial/approval drives actual discovery/advertising; no fake constructor defaults in production path.
- **Regression tests:** Permission matrices, dependency-injection test and discovery start/stop lifecycle.
- **Evidence required:** Production wiring trace and device/emulator permission evidence.

<a id="hard-007"></a>
## HARD-007 — Wire complete Nearby send/receive lifecycle

- **Milestone:** M1
- **Status:** NOT_STARTED
- **Dependencies:** HARD-006
- **Affected modules:** Android selection, pairing, transfer runtime, Nearby adapter, service/session UI.
- **Acceptance criteria:** Selected sources survive navigation; explicit Nearby authentication and receiver offer consent precede bytes; complete multi-file batches use production runtime.
- **Regression tests:** Reject, cancel, peer loss, multi-file, stale action and source revocation.
- **Evidence required:** Production-flow tests and manual journey trace.

<a id="hard-008"></a>
## HARD-008 — Wire real LAN listener and QR invitation lifecycle

- **Milestone:** M1
- **Status:** NOT_STARTED
- **Dependencies:** HARD-005, HARD-006
- **Affected modules:** Android LAN transport, QR/pairing UI, navigation/runtime.
- **Acceptance criteria:** QR is generated from a live bound listener, real endpoint and actual pin/fingerprint; invitation is atomically consumed; scan/paste connects into the same production session runtime.
- **Regression tests:** Fixed/example data rejection, expired/replayed invitation, port/listener failure and wrong pin.
- **Evidence required:** QR payload capture, listener trace and successful/failed pairing evidence.

<a id="hard-009"></a>
## HARD-009 — Complete service/session/background lifecycle

- **Milestone:** M1
- **Status:** NOT_STARTED
- **Dependencies:** HARD-007, HARD-008
- **Affected modules:** Android foreground service, notifications, session manager/runtime, reconciliation.
- **Acceptance criteria:** One active session survives rotation/background where supported; notification actions target exact session; process death never creates false success; cancellation tears down transport/storage cleanly.
- **Regression tests:** API start restrictions, lock/background, process death, stale notification and duplicate service start.
- **Evidence required:** Lifecycle tests and device/emulator traces.

<a id="hard-010"></a>
## HARD-010 — Prove two-device Android production journey

- **Milestone:** M1
- **Status:** NOT_STARTED
- **Dependencies:** HARD-007, HARD-008, HARD-009
- **Affected modules:** full Android product path and evidence.
- **Acceptance criteria:** On two physical Android devices: launcher selection → discovery/QR pairing → authentication → offer consent → transfer → SHA-256 verification → open/history. Both directions plus reject/cancel pass.
- **Regression tests:** 10 MB and 1 GB minimum, repeated both directions, offline/local-only where applicable.
- **Evidence required:** Device/API/build identities, hashes, logs/screens and exact production paths.

<a id="hard-011"></a>
## HARD-011 — Extract shared desktop transfer engine

- **Milestone:** M2 — Desktop/native interoperability
- **Status:** NOT_STARTED
- **Dependencies:** HARD-004
- **Affected modules:** `windows/Glide.Core`, `windows/Glide.Transport`, shared application engine, WPF and CLI.
- **Acceptance criteria:** CLI and WPF call one orchestration engine for pairing, consent, protocol sequencing, streaming, completion and cancellation; no duplicate protocol loops in UI/entrypoint.
- **Regression tests:** CLI/WPF parity tests using the same engine fakes/fixtures.
- **Evidence required:** Dependency graph and test coverage.

<a id="hard-012"></a>
## HARD-012 — Align native LAN discovery/TLS/bootstrap profile

- **Milestone:** M2
- **Status:** NOT_STARTED
- **Dependencies:** HARD-011
- **Affected modules:** Android LAN transport, .NET discovery/TLS/framing and shared protocol fixtures/docs.
- **Acceptance criteria:** One canonical DNS-SD TXT contract, ALPN, certificate/client-auth expectation, invitation format and channel-authentication flow works Android↔desktop; unpaired SAS is removed from production or uses a correctly exchanged canonical nonce with explicit confirmation.
- **Regression tests:** Cross-language discovery/bootstrap vectors, wrong pin/cert/ALPN and replay.
- **Evidence required:** Packet/record vectors and interoperability traces.

<a id="hard-013"></a>
## HARD-013 — Implement strict desktop session/protocol state machine

- **Milestone:** M2
- **Status:** NOT_STARTED
- **Dependencies:** HARD-011, HARD-012
- **Affected modules:** desktop application engine, `Glide.Core` protocol and transport/tests.
- **Acceptance criteria:** Monotonic sequences, paged offers, exact IDs/correlation, deadlines, consent ordering and truthful item/batch terminal states match protocol semantics.
- **Regression tests:** Multi-page offers, duplicate/gapped sequence, mismatched IDs, failed middle item and lost terminal receipt.
- **Evidence required:** State-machine tests and golden fixture parity.

<a id="hard-014"></a>
## HARD-014 — Make desktop hashing/storage bounded and shared

- **Milestone:** M2
- **Status:** NOT_STARTED
- **Dependencies:** HARD-011
- **Affected modules:** desktop engine/core/storage, WPF and CLI.
- **Acceptance criteria:** Sender hashing and transfer are streaming/bounded; no `ReadAllBytesAsync` production hashing; path sanitization, disk reservation, quarantine cleanup and atomic commit are centralized.
- **Regression tests:** Empty/10 MB/1 GB/5+ GB, cancellation, disk full, duplicate names, traversal/reparse cases; memory does not scale with file size.
- **Evidence required:** Memory measurements and code-path tests.

<a id="hard-015"></a>
## HARD-015 — Complete desktop consent/history/settings/state

- **Milestone:** M2
- **Status:** NOT_STARTED
- **Dependencies:** HARD-013, HARD-014
- **Affected modules:** desktop engine, WPF, CLI and persistence/settings.
- **Acceptance criteria:** Incoming offers require explicit authorization; history/settings persist; UI updates are thread-safe; CLI and WPF report identical outcomes.
- **Regression tests:** Accept/reject, restart persistence, UI-thread marshaling and partial batch.
- **Evidence required:** WPF/CLI journey tests and persistence evidence.

<a id="hard-016"></a>
## HARD-016 — Harden Linux CLI/daemon receiving lifecycle

- **Milestone:** M2
- **Status:** NOT_STARTED
- **Dependencies:** HARD-013, HARD-014
- **Affected modules:** shared CLI/daemon and Linux packaging/systemd.
- **Acceptance criteria:** Missing/invalid enrollment is rejected; daemon never auto-authorizes arbitrary offers; headless receive requires explicit peer/destination/size policy; cancellation/shutdown cleans resources.
- **Regression tests:** No invitation, wrong peer, unauthorized offer, SIGTERM/shutdown and disk limit.
- **Evidence required:** Linux runtime/systemd traces.

<a id="hard-017"></a>
## HARD-017 — Prove native cross-platform interoperability

- **Milestone:** M2
- **Status:** NOT_STARTED
- **Dependencies:** HARD-012, HARD-013, HARD-014, HARD-015, HARD-016
- **Affected modules:** Android, Windows and Linux native/local transfer paths.
- **Acceptance criteria:** Android↔Windows, Android↔Linux and Windows↔Linux pass in both directions with multi-page multi-file batches; failed items cannot yield successful batch result.
- **Regression tests:** Empty/large/mixed files, cancel/reject, wrong authentication and reconnect boundary.
- **Evidence required:** Real platform/build identities, hashes and logs.

<a id="hard-018"></a>
## HARD-018 — Finalize identity/resume protocol extension

- **Milestone:** M3 — Trust, folders and true resume
- **Status:** NOT_STARTED
- **Dependencies:** HARD-017
- **Affected modules:** protocol specs/fixtures, Kotlin core and .NET core.
- **Acceptance criteria:** Versioned messages, canonical signature inputs, peer/role/session/channel binding, capability negotiation and shared vectors are defined and cross-language compatible.
- **Regression tests:** Replay, downgrade, role swap, changed key and malformed signatures.
- **Evidence required:** Reviewed spec/ADR and golden vectors.

<a id="hard-019"></a>
## HARD-019 — Integrate Android persistent identity/trust

- **Milestone:** M3
- **Status:** NOT_STARTED
- **Dependencies:** HARD-018
- **Affected modules:** Android Keystore/data/trust UI/runtime.
- **Acceptance criteria:** Explicit enrollment, protected private key, peer fingerprint/public-key persistence, revocation and trusted-peer UI are wired to production runtime.
- **Regression tests:** Key loss/rotation, revoke, duplicate enrollment and restored database without key.
- **Evidence required:** Keystore/trust lifecycle tests.

<a id="hard-020"></a>
## HARD-020 — Integrate Windows/Linux persistent identity/trust

- **Milestone:** M3
- **Status:** NOT_STARTED
- **Dependencies:** HARD-018
- **Affected modules:** desktop core/storage/engine, OS-protected key storage and Linux permissions.
- **Acceptance criteria:** Protected persistent key material, explicit enrollment/revocation and peer-specific headless policy are implemented on supported desktop OSes.
- **Regression tests:** Key permission failure, revoke, migration and wrong peer.
- **Evidence required:** Platform storage/permission evidence.

<a id="hard-021"></a>
## HARD-021 — Integrate mutually authenticated trusted sessions

- **Milestone:** M3
- **Status:** NOT_STARTED
- **Dependencies:** HARD-019, HARD-020
- **Affected modules:** Android/desktop runtimes and protocol.
- **Acceptance criteria:** Both peers prove enrolled identity bound to fresh challenges and secure channel/session; downgrade/replay/stale trust fails closed; trust never auto-accepts arbitrary files.
- **Regression tests:** MITM/pin mismatch, replay, revoked peer, challenge reuse and capability downgrade.
- **Evidence required:** Cross-platform security tests.

<a id="hard-022"></a>
## HARD-022 — Implement Android durable true-resume journals

- **Milestone:** M3
- **Status:** NOT_STARTED
- **Dependencies:** HARD-018, HARD-021
- **Affected modules:** Android Room/storage/runtime.
- **Acceptance criteria:** Resume journal is keyed by transfer+item, records fixed negotiated chunk identity and only contiguous verified chunks; source/permission/peer changes are revalidated; partials expire after 24h.
- **Regression tests:** Missing/corrupt chunk, process death, modified source, expired journal, revoked peer and disk pressure.
- **Evidence required:** Migration/recovery tests and byte-identical result.

<a id="hard-023"></a>
## HARD-023 — Implement desktop durable true-resume storage

- **Milestone:** M3
- **Status:** NOT_STARTED
- **Dependencies:** HARD-018, HARD-021
- **Affected modules:** desktop engine/storage.
- **Acceptance criteria:** Same resume semantics as Android with durable contiguous progress, retained-partial policy, peer/source revalidation and whole-file final SHA-256.
- **Regression tests:** Same corruption/interruption matrix as HARD-022 across Windows/Linux.
- **Evidence required:** Restart/recovery traces and hashes.

<a id="hard-024"></a>
## HARD-024 — Validate folders and resume across native platforms

- **Milestone:** M3
- **Status:** NOT_STARTED
- **Dependencies:** HARD-022, HARD-023
- **Affected modules:** Android/desktop folder traversal/storage/protocol.
- **Acceptance criteria:** Nested folder paths are preserved safely; traversal/symlink/reparse attacks fail; interrupted large transfers resume from verified progress across native pairings.
- **Regression tests:** Nested folders, duplicate paths, cycles, symlinks/reparse points, changed sources and revoked trust.
- **Evidence required:** Cross-platform folder/resume matrix.

<a id="hard-025"></a>
## HARD-025 — Specify secure browser transport and static client

- **Milestone:** M4 — Secure browser sharing
- **Status:** NOT_STARTED
- **Dependencies:** HARD-017
- **Affected modules:** authoritative browser client/specs and native adapter boundaries.
- **Acceptance criteria:** HTTPS-hosted static client + local WebRTC DataChannel profile is specified; pairing authenticates DTLS fingerprints via QR/copy-paste; no file/pairing secret crosses web host; no HTTP fallback.
- **Regression tests:** Wrong fingerprint, replay, no local candidate and host-offline-after-load behavior.
- **Evidence required:** Threat review and conformance fixtures.

<a id="hard-026"></a>
## HARD-026 — Add Android WebRTC adapter

- **Milestone:** M4
- **Status:** NOT_STARTED
- **Dependencies:** HARD-025
- **Affected modules:** isolated Android native/JNI transport module and runtime integration.
- **Acceptance criteria:** Data-only WebRTC adapter has pinned native inputs, bounded buffers, lifecycle cleanup and no UI/native-library leakage into core.
- **Regression tests:** Channel open/close, cancellation, JNI/native cleanup and process/background lifecycle.
- **Evidence required:** Build provenance and adapter tests.

<a id="hard-027"></a>
## HARD-027 — Add Windows/Linux WebRTC adapter

- **Milestone:** M4
- **Status:** NOT_STARTED
- **Dependencies:** HARD-025
- **Affected modules:** desktop transport/native packaging.
- **Acceptance criteria:** Shared .NET adapter manages native resources deterministically and packages supported Windows/Linux binaries without changing core protocol semantics.
- **Regression tests:** Native load failure, channel lifecycle, cancellation and package smoke.
- **Evidence required:** Package/runtime tests.

<a id="hard-028"></a>
## HARD-028 — Implement secure browser transfer behavior

- **Milestone:** M4
- **Status:** NOT_STARTED
- **Dependencies:** HARD-026, HARD-027
- **Affected modules:** web client, native adapters and protocol bridge.
- **Acceptance criteria:** Explicit offers/consent, bounded streaming, SHA-256 verification, truthful progress and safe file output work browser↔native; unsupported durable APIs expose limitations rather than buffering whole files.
- **Regression tests:** Modified content, large files, cancellation, storage denial and output API unavailable.
- **Evidence required:** Browser/native integration tests.

<a id="hard-029"></a>
## HARD-029 — Implement browser identity and resume

- **Milestone:** M4
- **Status:** NOT_STARTED
- **Dependencies:** HARD-028
- **Affected modules:** browser WebCrypto/storage and native identity/resume bridge.
- **Acceptance criteria:** Non-extractable browser identity, profile-scoped trust, resumable state with file reselection/source validation; clearing site data removes trust/resume state.
- **Regression tests:** Profile clear, changed source, storage eviction, tab close/reopen and wrong identity.
- **Evidence required:** Supported-browser matrix.

<a id="hard-030"></a>
## HARD-030 — Prove browser security/compatibility and remove HTTP release path

- **Milestone:** M4
- **Status:** NOT_STARTED
- **Dependencies:** HARD-028, HARD-029
- **Affected modules:** browser/native release configuration and legacy web gateways.
- **Acceptance criteria:** Browser↔Android and browser↔desktop pass over authenticated encrypted local channels; wrong fingerprints/replays/modification fail closed; plaintext HTTP transfer is excluded from release builds.
- **Regression tests:** Supported Chrome/Edge/Firefox/Safari variants, blocked LAN, profile clear and large files.
- **Evidence required:** Browser matrix and release-binary inspection.

<a id="hard-031"></a>
## HARD-031 — Refactor Android entry/presentation boundaries

- **Milestone:** M5 — Structure/developer experience
- **Status:** NOT_STARTED
- **Dependencies:** HARD-010
- **Affected modules:** Android Activity/navigation/ViewModels/screens/debug fixtures.
- **Acceptance criteria:** Entry/navigation composition is bounded, debug fixtures move to debug/test sources, presentation maps domain models instead of exposing persistence entities, with no behavior change.
- **Regression tests:** Existing Android production flow and UI-state tests unchanged/passing.
- **Evidence required:** Refactor-only diff and parity tests.

<a id="hard-032"></a>
## HARD-032 — Refactor desktop presentation boundaries

- **Milestone:** M5
- **Status:** NOT_STARTED
- **Dependencies:** HARD-015
- **Affected modules:** WPF ViewModels/views and desktop application engine.
- **Acceptance criteria:** Networking, protocol, filesystem and long-running orchestration are outside ViewModels; UI remains thin/state-driven.
- **Regression tests:** WPF state/command tests and engine parity.
- **Evidence required:** Responsibility map and size/coupling reduction.

<a id="hard-033"></a>
## HARD-033 — Mechanically reorganize platform trees

- **Milestone:** M5
- **Status:** NOT_STARTED
- **Dependencies:** HARD-031, HARD-032, HARD-030
- **Affected modules:** repository paths only; target `android/`, `desktop/`, `web/`, `protocol/fixtures/` structure.
- **Acceptance criteria:** Package IDs/logical module names/protocol behavior unchanged; references/tooling/docs updated; move commits contain no functional edits.
- **Regression tests:** Full build/test before and after tree move.
- **Evidence required:** Path map and zero-behavior-diff review.

<a id="hard-034"></a>
## HARD-034 — Consolidate fixtures and browser sources

- **Milestone:** M5
- **Status:** NOT_STARTED
- **Dependencies:** HARD-033
- **Affected modules:** protocol fixtures and browser sources/build embedding.
- **Acceptance criteria:** One authoritative fixture set and one authoritative browser source; generated/embedded copies come from reproducible build steps, not manual synchronization.
- **Regression tests:** Cross-language fixture parity and generated-asset diff check.
- **Evidence required:** Generation command and determinism checks.

<a id="hard-035"></a>
## HARD-035 — Standardize developer commands/tooling

- **Milestone:** M5
- **Status:** NOT_STARTED
- **Dependencies:** HARD-033, HARD-034
- **Affected modules:** root tools/config, EditorConfig, toolchain pins and docs.
- **Acceptance criteria:** Root commands exist for doctor/build/test/lint/verify/previews; JDK/.NET/Node/native versions are documented/pinned; IDE launch paths are documented.
- **Regression tests:** Fresh-checkout command validation on supported developer OSes.
- **Evidence required:** Doctor output and clean-machine runbook.

<a id="hard-036"></a>
## HARD-036 — Exercise physical failure/recovery matrix

- **Milestone:** M6 — Product quality/hardening
- **Status:** NOT_STARTED
- **Dependencies:** HARD-024, HARD-030, HARD-035
- **Affected modules:** all supported runtime paths.
- **Acceptance criteria:** Real network/lifecycle/storage/permission failures match documented behavior with no false success/data exposure.
- **Regression tests:** Disconnect, lock, kill, permission revoke, disk full, duplicate names, volume changes and resume corruption.
- **Evidence required:** Physical/runtime matrix with device/build identities.

<a id="hard-037"></a>
## HARD-037 — Perform production security review and remediation

- **Milestone:** M6
- **Status:** NOT_STARTED
- **Dependencies:** HARD-036
- **Affected modules:** authentication, protocol parsing, storage, browser/native entry points, dependencies and packaging.
- **Acceptance criteria:** No unresolved Critical/High findings; verified findings fixed and retested; residual Medium risks explicitly owned.
- **Regression tests:** Hostile inputs, replay/downgrade, path/storage attacks and dependency/config review.
- **Evidence required:** Severity ledger and reproduction/fix evidence.

<a id="hard-038"></a>
## HARD-038 — Validate real accessibility/adaptive/UI journeys

- **Milestone:** M6
- **Status:** NOT_STARTED
- **Dependencies:** HARD-036
- **Affected modules:** Android, WPF and browser interfaces.
- **Acceptance criteria:** Real TalkBack/keyboard flows, 200% text, adaptive layouts, light/dark, reduced motion and truthful errors pass supported platforms.
- **Regression tests:** Accessibility semantics plus manual assistive-tech journeys.
- **Evidence required:** Platform/device/browser evidence.

<a id="hard-039"></a>
## HARD-039 — Measure production performance on devices

- **Milestone:** M6
- **Status:** NOT_STARTED
- **Dependencies:** HARD-036
- **Affected modules:** transfer/hashing/storage/UI hot paths on supported platforms.
- **Acceptance criteria:** Startup/frame/memory/throughput/hash/battery/thermal measurements use release-like builds and real devices; no loopback result is marketed as device throughput.
- **Regression tests:** 10 MB/100 MB/1 GB/5+ GB and 1,000-file workloads.
- **Evidence required:** Raw measurements, environment and budgets.

<a id="hard-040"></a>
## HARD-040 — Complete CI/architecture/migration/dependency/doc gates

- **Milestone:** M6
- **Status:** NOT_STARTED
- **Dependencies:** HARD-037, HARD-038, HARD-039
- **Affected modules:** CI/tooling, architecture scanners, migrations, dependency review and documentation links.
- **Acceptance criteria:** CI covers supported platform builds/tests/browser tests/instrumentation where feasible; architecture rules cover LAN/storage/all source sets; migration/dependency/doc-link gates are executable.
- **Regression tests:** Deliberate violations fail gates.
- **Evidence required:** Green workflow runs plus deliberate-failure proof.

<a id="hard-041"></a>
## HARD-041 — Build/test release artifacts for supported platforms

- **Milestone:** M7 — Release/operations
- **Status:** NOT_STARTED
- **Dependencies:** HARD-040
- **Affected modules:** Android APK/AAB, Windows packages, Linux packages and versioned web artifacts.
- **Acceptance criteria:** Exact artifacts install/run/update as applicable; signatures/checksums/provenance recorded; packaging includes required native dependencies and excludes insecure/deferred paths.
- **Regression tests:** Clean install/update, transfer smoke and artifact inspection.
- **Evidence required:** Artifact hashes/signature/package reports.

<a id="hard-042"></a>
## HARD-042 — Complete exact-candidate release dossier

- **Milestone:** M7
- **Status:** NOT_STARTED
- **Dependencies:** HARD-041
- **Affected modules:** release/legal/privacy/support/signing documentation and evidence.
- **Acceptance criteria:** Privacy disclosures, licenses, key custody, support matrix, install/update verification and candidate evidence all match exact artifacts/commit.
- **Regression tests:** Evidence/link consistency and candidate-hash reconciliation.
- **Evidence required:** Approved dossier with no release-gate manual checks outstanding.

<a id="hard-043"></a>
## HARD-043 — Execute explicitly authorized staged release

- **Milestone:** M7
- **Status:** NOT_STARTED
- **Dependencies:** HARD-042
- **Affected modules:** release operations only.
- **Acceptance criteria:** Release occurs only after explicit owner authorization; rollout/rollback/incident/maintenance ownership is active; every published artifact matches reviewed checksum/commit.
- **Regression tests:** Preflight install/smoke and rollback tabletop; no deployment from documentation completion alone.
- **Evidence required:** Authorization record, release identifiers, artifact hashes and monitoring/rollback handoff.

## Execution rule

Default execution is strictly M0 → M1 → M2 → M3 → M4 → M5 → M6 → M7 unless this ledger is deliberately amended with evidence. After HARD-001, the only current READY task is **HARD-002**.
