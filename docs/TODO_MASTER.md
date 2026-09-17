# Glide master task system

**Current status authority for active development.** The historical GLIDE-001–072 ledger is preserved in Git history at pre-HARD commit `b07f15bdde60bb5bfa9b20fdaba093b7a6a93785` and its earlier commits. Historical `COMPLETE` labels do not certify the current multi-platform candidate.

Current program: **HARD-001–HARD-043 — Code Review, Improvement, Hardening & Development Completion**.  
Plan: [`planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md`](planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md)  
Review baseline: `ea2e811a7f2d8aaddc552e894fe04782cd08a499`  
Initial HARD-001 publication base: `b07f15bdde60bb5bfa9b20fdaba093b7a6a93785`  
**Next recommended task: HARD-002.**

> **Concurrency rule:** Source development continued on `main` while HARD-001 documentation was being published. HARD-002 therefore covers **every non-HARD source/configuration commit after `ea2e811` through the HEAD captured when HARD-002 starts**, not a fixed commit count. New source commits that land before that capture are automatically in scope.

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

- **Milestone / status:** M0 / COMPLETE.
- **Dependencies:** None.
- **Affected modules/files:** Documentation only: hardening plan, `docs/README.md`, master plan, roadmap, AI guide, task ledger and session evidence.
- **Acceptance criteria:** Review baseline/limitations are explicit; confirmed gaps map to HARD tasks; release scope is explicit; Apple/remote work is preserved but deferred; status authority is unambiguous.
- **Regression tests:** Link/path inspection and Git diff review; no production/build behavior changed by HARD-001 commits.
- **Completion evidence:** `planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md` and `development/sessions/2026-09-18-hard-001.md`.

<a id="hard-002"></a>
## HARD-002 — Reconcile source, docs, claims and post-review commits

- **Milestone / status:** M0 / READY.
- **Dependencies:** HARD-001.
- **Affected modules/files:** Repository-wide source/config/docs; every non-HARD source/configuration commit after `ea2e811` through the HEAD captured at HARD-002 start, including Apple and remote additions; release docs, ADRs, README and status pages.
- **Acceptance criteria:** Capture starting HEAD; enumerate/review the full `ea2e811...<captured-head>` source/config delta while separating HARD documentation commits; classify every completion/release claim as implemented, automated-tested, physically/interoperability verified, released or unverified; keep deferred Apple/remote work outside the active release gate; correct stale contradictions without erasing history.
- **Regression tests:** Documentation link/status audit and source-vs-doc contract checks; no behavior change is required by this reconciliation task.
- **Completion evidence:** Captured HEAD, commit/diff inventory, reviewed files, corrected claims, unresolved evidence gaps and next READY task.

<a id="hard-003"></a>
## HARD-003 — Add regressions for confirmed review defects

- **Milestone / status:** M0 / NOT_STARTED.
- **Dependencies:** HARD-002.
- **Affected modules:** Android app/LAN wiring; Linux/desktop app, transport and tests; browser gateway tests/tooling.
- **Acceptance criteria:** Regressions expose disconnected production Android wiring, invalid/missing invitation acceptance, unsynchronized SAS authentication, sequence/pagination misuse, plaintext browser assumptions and whole-file production hashing.
- **Regression tests:** Production-path and cross-language tests with bounded-memory assertions; source-text-only checks do not qualify.
- **Completion evidence:** Reproducible red-before/fixed-later tests or failing fixtures with exact commands/results.

<a id="hard-004"></a>
## HARD-004 — Establish Android/Windows/Linux CI baseline

- **Milestone / status:** M0 / NOT_STARTED.
- **Dependencies:** HARD-003.
- **Affected modules:** `.github/workflows`, Gradle modules, `windows/Glide.slnx`, Linux packaging/tooling.
- **Acceptance criteria:** CI reaches checkout and executes Android build/lint/tests plus .NET build/tests and Linux-compatible shared build/package checks; action pins resolve; secrets remain protected.
- **Regression tests:** Clean HEAD succeeds and an intentional test failure blocks its job.
- **Completion evidence:** Workflow run IDs/URLs, tool versions, commands, artifacts and named hardware-only gaps.

<a id="hard-005"></a>
## HARD-005 — Introduce service-owned Android TransferRuntime

- **Milestone / status:** M1 / NOT_STARTED.
- **Dependencies:** HARD-004.
- **Affected modules:** `app`, `transfer-core`, Android DI/service/session packages.
- **Acceptance criteria:** One service-owned runtime exposes typed send/receive/accept/reject/cancel/resume commands and immutable discovery/pairing/transfer state; ViewModels do not construct transports or own active transfer scopes.
- **Regression tests:** Rotation/recreation, duplicate command, exact-session cancellation and runtime-ownership tests.
- **Completion evidence:** Architecture diff, tests and lifecycle traces.

<a id="hard-006"></a>
## HARD-006 — Wire production discovery and permissions

- **Milestone / status:** M1 / NOT_STARTED.
- **Dependencies:** HARD-005.
- **Affected modules:** Android navigation, discovery ViewModels, permission bridge and Nearby/LAN composition.
- **Acceptance criteria:** Production ViewModels receive real runtime/discovery dependencies; point-of-use permission approval/denial controls actual discovery/advertising; fake defaults cannot enter production paths.
- **Regression tests:** Permission matrices, DI wiring and discovery start/stop lifecycle.
- **Completion evidence:** Production wiring trace and permission evidence.

<a id="hard-007"></a>
## HARD-007 — Wire complete Nearby send/receive lifecycle

- **Milestone / status:** M1 / NOT_STARTED.
- **Dependencies:** HARD-006.
- **Affected modules:** Android selection, pairing, runtime, Nearby adapter and service/session UI.
- **Acceptance criteria:** Selected sources survive navigation; explicit Nearby authentication and receiver offer consent precede bytes; complete multi-file batches use the production runtime.
- **Regression tests:** Reject, cancel, peer loss, multi-file, stale action and source revocation.
- **Completion evidence:** Production-flow tests and manual journey trace.

<a id="hard-008"></a>
## HARD-008 — Wire real LAN listener and QR invitation lifecycle

- **Milestone / status:** M1 / NOT_STARTED.
- **Dependencies:** HARD-005, HARD-006.
- **Affected modules:** Android LAN transport, QR/pairing UI, navigation/runtime.
- **Acceptance criteria:** QR is produced from a live listener with actual endpoint/pin/fingerprint; invitation is atomically consumed; scan/paste enters the same production runtime.
- **Regression tests:** Fixed/example-data rejection, expiration/replay, bind failure and wrong pin.
- **Completion evidence:** QR payload, listener trace and pass/fail pairing evidence.

<a id="hard-009"></a>
## HARD-009 — Complete service/session/background lifecycle

- **Milestone / status:** M1 / NOT_STARTED.
- **Dependencies:** HARD-007, HARD-008.
- **Affected modules:** Android foreground service, notifications, runtime/session manager and reconciliation.
- **Acceptance criteria:** One session survives supported rotation/background transitions; notification actions target exact session; process death never reports false success; cancel tears down transport/storage safely.
- **Regression tests:** FGS restrictions, lock/background, process death, stale notification and duplicate service start.
- **Completion evidence:** Lifecycle tests and device/emulator traces.

<a id="hard-010"></a>
## HARD-010 — Prove two-device Android production journey

- **Milestone / status:** M1 / NOT_STARTED.
- **Dependencies:** HARD-007, HARD-008, HARD-009.
- **Affected modules:** Full Android launcher/UI/runtime path and evidence.
- **Acceptance criteria:** Two physical devices complete selection → pairing → authentication → consent → transfer → SHA-256 verification → open/history in both directions; reject/cancel use the same production path.
- **Regression tests:** At least 10 MB and 1 GB payloads, repeated both directions, plus reject/cancel.
- **Completion evidence:** Device/API/build identities, hashes, logs/screens and exact path trace.

<a id="hard-011"></a>
## HARD-011 — Extract shared desktop transfer engine

- **Milestone / status:** M2 / NOT_STARTED.
- **Dependencies:** HARD-004.
- **Affected modules:** `Glide.Core`, `Glide.Transport`, shared desktop application engine, WPF and CLI.
- **Acceptance criteria:** WPF/CLI share pairing, consent, sequencing, streaming, completion and cancellation orchestration; no duplicated protocol loop remains in UI/entrypoint code.
- **Regression tests:** CLI/WPF parity through the same engine fakes/fixtures.
- **Completion evidence:** Dependency map and engine parity coverage.

<a id="hard-012"></a>
## HARD-012 — Align native LAN discovery/TLS/bootstrap profile

- **Milestone / status:** M2 / NOT_STARTED.
- **Dependencies:** HARD-011.
- **Affected modules:** Android LAN transport, .NET discovery/TLS/framing and shared protocol fixtures/specs.
- **Acceptance criteria:** One DNS-SD TXT contract, ALPN, certificate/client-auth expectation, invitation format and authenticated bootstrap works Android↔desktop; unpaired SAS is removed or repaired with one correctly exchanged/bound nonce and explicit confirmation.
- **Regression tests:** Cross-language bootstrap vectors, wrong pin/cert/ALPN and replay.
- **Completion evidence:** Packet/record vectors and interoperability traces.

<a id="hard-013"></a>
## HARD-013 — Implement strict desktop session/protocol state machine

- **Milestone / status:** M2 / NOT_STARTED.
- **Dependencies:** HARD-011, HARD-012.
- **Affected modules:** Desktop engine, `Glide.Core` protocol and transports/tests.
- **Acceptance criteria:** Monotonic sequence, paged offers, exact IDs/correlation, deadlines, consent ordering and truthful item/batch terminal outcomes match protocol semantics.
- **Regression tests:** Multi-page offers, duplicate/gapped sequence, mismatched IDs, failed middle item and lost terminal receipt.
- **Completion evidence:** State-machine tests and golden-fixture parity.

<a id="hard-014"></a>
## HARD-014 — Make desktop hashing/storage bounded and shared

- **Milestone / status:** M2 / NOT_STARTED.
- **Dependencies:** HARD-011.
- **Affected modules:** Desktop engine/core/storage, WPF and CLI.
- **Acceptance criteria:** Production sender hashing/transfer is streaming and bounded; no whole-file `ReadAllBytesAsync` digest path; safe paths, reservations, quarantine cleanup and commit are centralized.
- **Regression tests:** Empty/10 MB/1 GB/5+ GB, cancel, disk full, duplicate names and traversal/reparse cases; memory does not scale with file size.
- **Completion evidence:** Memory measurements and production-path tests.

<a id="hard-015"></a>
## HARD-015 — Complete desktop consent/history/settings/state

- **Milestone / status:** M2 / NOT_STARTED.
- **Dependencies:** HARD-013, HARD-014.
- **Affected modules:** Desktop engine, WPF, CLI and persistence/settings.
- **Acceptance criteria:** Incoming offers require authorization; history/settings persist; UI updates are thread-safe; CLI/WPF report identical outcomes.
- **Regression tests:** Accept/reject, restart persistence, UI-thread marshaling and partial batch.
- **Completion evidence:** WPF/CLI journey and persistence tests.

<a id="hard-016"></a>
## HARD-016 — Harden Linux CLI/daemon receiving lifecycle

- **Milestone / status:** M2 / NOT_STARTED.
- **Dependencies:** HARD-013, HARD-014.
- **Affected modules:** Shared CLI/daemon, Linux packaging and systemd metadata.
- **Acceptance criteria:** Missing/invalid enrollment is rejected; daemon cannot auto-authorize arbitrary offers; headless receive needs explicit peer/destination/size policy; shutdown/cancel cleans resources.
- **Regression tests:** No invitation, wrong peer, unauthorized offer, SIGTERM/shutdown and disk limit.
- **Completion evidence:** Linux runtime/systemd traces.

<a id="hard-017"></a>
## HARD-017 — Prove native cross-platform interoperability

- **Milestone / status:** M2 / NOT_STARTED.
- **Dependencies:** HARD-012, HARD-013, HARD-014, HARD-015, HARD-016.
- **Affected modules:** Android, Windows and Linux native transfer paths.
- **Acceptance criteria:** Android↔Windows, Android↔Linux and Windows↔Linux pass both directions with multi-page/multi-file batches; failed items cannot yield successful batch status.
- **Regression tests:** Empty/large/mixed files, reject/cancel, wrong authentication and reconnect boundary.
- **Completion evidence:** Real platform/build identities, hashes and logs.

<a id="hard-018"></a>
## HARD-018 — Finalize identity/resume protocol extension

- **Milestone / status:** M3 / NOT_STARTED.
- **Dependencies:** HARD-017.
- **Affected modules:** Protocol specs/fixtures, Kotlin core and .NET core.
- **Acceptance criteria:** Versioned identity/resume messages, canonical signature inputs, peer/role/session/channel binding, capabilities and shared vectors are cross-language compatible.
- **Regression tests:** Replay, downgrade, role swap, changed key and malformed signatures.
- **Completion evidence:** Reviewed spec/ADR and golden vectors.

<a id="hard-019"></a>
## HARD-019 — Integrate Android persistent identity/trust

- **Milestone / status:** M3 / NOT_STARTED.
- **Dependencies:** HARD-018.
- **Affected modules:** Android Keystore, data/trust UI and runtime.
- **Acceptance criteria:** Explicit enrollment, protected key, peer key/fingerprint persistence, revocation and trusted-peer presentation are wired to production runtime.
- **Regression tests:** Key loss/rotation, revoke, duplicate enrollment and restored DB without key.
- **Completion evidence:** Keystore/trust lifecycle tests.

<a id="hard-020"></a>
## HARD-020 — Integrate Windows/Linux persistent identity/trust

- **Milestone / status:** M3 / NOT_STARTED.
- **Dependencies:** HARD-018.
- **Affected modules:** Desktop core/storage/engine and OS-specific protected key storage.
- **Acceptance criteria:** Protected persistent keys, explicit enrollment/revocation and peer-specific headless policy work on supported desktop OSes.
- **Regression tests:** Permission failure, revoke, migration and wrong peer.
- **Completion evidence:** Platform storage/permission evidence.

<a id="hard-021"></a>
## HARD-021 — Integrate mutually authenticated trusted sessions

- **Milestone / status:** M3 / NOT_STARTED.
- **Dependencies:** HARD-019, HARD-020.
- **Affected modules:** Android/desktop runtimes and protocol.
- **Acceptance criteria:** Both peers prove enrolled identity bound to fresh challenge and secure channel/session; replay/downgrade/revoked trust fails closed; trust never auto-accepts arbitrary files.
- **Regression tests:** MITM/pin mismatch, replay, revoked peer, challenge reuse and capability downgrade.
- **Completion evidence:** Cross-platform security tests.

<a id="hard-022"></a>
## HARD-022 — Implement Android durable true-resume journals

- **Milestone / status:** M3 / NOT_STARTED.
- **Dependencies:** HARD-018, HARD-021.
- **Affected modules:** Android Room/storage/runtime.
- **Acceptance criteria:** Journal is keyed by transfer+item, stores negotiated chunk identity and only contiguous verified progress; source/permission/peer changes are revalidated; partials expire after 24h.
- **Regression tests:** Missing/corrupt chunk, process death, modified source, expiry, revoked peer and disk pressure.
- **Completion evidence:** Migration/recovery tests and byte-identical result.

<a id="hard-023"></a>
## HARD-023 — Implement desktop durable true-resume storage

- **Milestone / status:** M3 / NOT_STARTED.
- **Dependencies:** HARD-018, HARD-021.
- **Affected modules:** Desktop engine/storage.
- **Acceptance criteria:** Same contiguous-verified resume semantics as Android with retained-partial policy, peer/source revalidation and final whole-file SHA-256.
- **Regression tests:** HARD-022 corruption/interruption matrix on Windows/Linux.
- **Completion evidence:** Restart/recovery traces and hashes.

<a id="hard-024"></a>
## HARD-024 — Validate folders and resume across native platforms

- **Milestone / status:** M3 / NOT_STARTED.
- **Dependencies:** HARD-022, HARD-023.
- **Affected modules:** Android/desktop folder traversal, storage and protocol.
- **Acceptance criteria:** Nested paths preserve safely; traversal/symlink/reparse attacks fail; interrupted large transfers resume from verified progress across native pairings.
- **Regression tests:** Nested folders, duplicate paths, cycles, links/reparse points, changed sources and revoked trust.
- **Completion evidence:** Cross-platform folder/resume matrix.

<a id="hard-025"></a>
## HARD-025 — Specify secure browser transport and static client

- **Milestone / status:** M4 / NOT_STARTED.
- **Dependencies:** HARD-017.
- **Affected modules:** Authoritative web client/spec and native adapter boundaries.
- **Acceptance criteria:** HTTPS static client + local WebRTC DataChannel profile; QR/copy-paste authenticates DTLS fingerprint; no file/pairing secret crosses web host; no HTTP fallback.
- **Regression tests:** Wrong fingerprint, replay, no local candidate and host-offline-after-load behavior.
- **Completion evidence:** Threat review and conformance fixtures.

<a id="hard-026"></a>
## HARD-026 — Add Android WebRTC adapter

- **Milestone / status:** M4 / NOT_STARTED.
- **Dependencies:** HARD-025.
- **Affected modules:** Isolated Android native/JNI transport and runtime integration.
- **Acceptance criteria:** Data-only adapter has pinned native inputs, bounded buffers, lifecycle cleanup and no UI/native-library leakage into core.
- **Regression tests:** Channel open/close, cancel, JNI/native cleanup and process/background lifecycle.
- **Completion evidence:** Build provenance and adapter tests.

<a id="hard-027"></a>
## HARD-027 — Add Windows/Linux WebRTC adapter

- **Milestone / status:** M4 / NOT_STARTED.
- **Dependencies:** HARD-025.
- **Affected modules:** Desktop transport/native packaging.
- **Acceptance criteria:** Shared .NET adapter manages native resources deterministically and packages supported Windows/Linux binaries without changing core protocol semantics.
- **Regression tests:** Native load failure, channel lifecycle, cancellation and package smoke.
- **Completion evidence:** Package/runtime tests.

<a id="hard-028"></a>
## HARD-028 — Implement secure browser transfer behavior

- **Milestone / status:** M4 / NOT_STARTED.
- **Dependencies:** HARD-026, HARD-027.
- **Affected modules:** Web client, native adapters and protocol bridge.
- **Acceptance criteria:** Explicit offers/consent, bounded streaming, SHA-256 verification, truthful progress and safe output work browser↔native; unsupported durable APIs expose limitations instead of whole-file buffering.
- **Regression tests:** Modified content, large files, cancel, storage denial and unavailable output API.
- **Completion evidence:** Browser/native integration tests.

<a id="hard-029"></a>
## HARD-029 — Implement browser identity and resume

- **Milestone / status:** M4 / NOT_STARTED.
- **Dependencies:** HARD-028.
- **Affected modules:** Browser WebCrypto/storage and native identity/resume bridge.
- **Acceptance criteria:** Non-extractable browser identity, profile-scoped trust and resumable state with file reselection/source validation; clearing site data removes trust/resume.
- **Regression tests:** Profile clear, source change, storage eviction, tab close/reopen and wrong identity.
- **Completion evidence:** Supported-browser matrix.

<a id="hard-030"></a>
## HARD-030 — Prove browser security/compatibility and remove HTTP release path

- **Milestone / status:** M4 / NOT_STARTED.
- **Dependencies:** HARD-028, HARD-029.
- **Affected modules:** Browser/native release configuration and legacy web gateways.
- **Acceptance criteria:** Browser↔Android and browser↔desktop pass authenticated encrypted local channels; wrong fingerprints/replay/modification fail closed; plaintext HTTP transfer is excluded from release builds.
- **Regression tests:** Supported browsers, blocked LAN, profile clear and large-file scenarios.
- **Completion evidence:** Browser matrix and release-binary inspection.

<a id="hard-031"></a>
## HARD-031 — Refactor Android entry/presentation boundaries

- **Milestone / status:** M5 / NOT_STARTED.
- **Dependencies:** HARD-010.
- **Affected modules:** Android Activity/navigation/ViewModels/screens/debug fixtures.
- **Acceptance criteria:** Bounded entry/navigation composition; debug fixtures in debug/test sources; presentation maps domain models rather than persistence entities; no behavior change.
- **Regression tests:** Existing production-flow and UI-state tests remain green.
- **Completion evidence:** Refactor-only diff and parity tests.

<a id="hard-032"></a>
## HARD-032 — Refactor desktop presentation boundaries

- **Milestone / status:** M5 / NOT_STARTED.
- **Dependencies:** HARD-015.
- **Affected modules:** WPF ViewModels/views and desktop application engine.
- **Acceptance criteria:** Networking, protocol, filesystem and long-running orchestration are outside ViewModels; UI remains state-driven.
- **Regression tests:** WPF state/command tests and engine parity.
- **Completion evidence:** Responsibility map and coupling/size reduction.

<a id="hard-033"></a>
## HARD-033 — Mechanically reorganize platform trees

- **Milestone / status:** M5 / NOT_STARTED.
- **Dependencies:** HARD-031, HARD-032, HARD-030.
- **Affected modules:** Repository paths only; target `android/`, `desktop/`, `web/`, `protocol/fixtures/` structure.
- **Acceptance criteria:** Package IDs/logical modules/protocol behavior unchanged; references/tooling/docs updated; move commits contain no functional edits.
- **Regression tests:** Full build/test before and after move.
- **Completion evidence:** Path map and zero-behavior-diff review.

<a id="hard-034"></a>
## HARD-034 — Consolidate fixtures and browser sources

- **Milestone / status:** M5 / NOT_STARTED.
- **Dependencies:** HARD-033.
- **Affected modules:** Protocol fixtures and browser source/build embedding.
- **Acceptance criteria:** One authoritative fixture set and browser source; embedded/generated copies are reproducible, not manually synchronized.
- **Regression tests:** Cross-language fixture parity and deterministic generated-asset diff.
- **Completion evidence:** Generation commands and determinism checks.

<a id="hard-035"></a>
## HARD-035 — Standardize developer commands/tooling

- **Milestone / status:** M5 / NOT_STARTED.
- **Dependencies:** HARD-033, HARD-034.
- **Affected modules:** Root tooling/config, EditorConfig, toolchain pins and developer docs.
- **Acceptance criteria:** Root `doctor/build/test/lint/verify/preview` entrypoints; JDK/.NET/Node/native versions pinned/documented; IDE launch configs documented.
- **Regression tests:** Fresh-checkout command validation on supported development OSes.
- **Completion evidence:** Doctor output and clean-machine runbook.

<a id="hard-036"></a>
## HARD-036 — Exercise physical failure/recovery matrix

- **Milestone / status:** M6 / NOT_STARTED.
- **Dependencies:** HARD-024, HARD-030, HARD-035.
- **Affected modules:** All supported runtime paths.
- **Acceptance criteria:** Real network/lifecycle/storage/permission failure behavior is truthful and has no false success/data exposure.
- **Regression tests:** Disconnect, lock, kill, permission revoke, disk full, duplicate names, volume changes and resume corruption.
- **Completion evidence:** Physical/runtime matrix with device/build identities.

<a id="hard-037"></a>
## HARD-037 — Perform production security review and remediation

- **Milestone / status:** M6 / NOT_STARTED.
- **Dependencies:** HARD-036.
- **Affected modules:** Auth, protocol parsing, storage, browser/native entry points, dependencies and packaging.
- **Acceptance criteria:** No unresolved Critical/High finding; verified issues fixed/retested; residual Medium risks explicitly owned.
- **Regression tests:** Hostile inputs, replay/downgrade, path/storage attacks and dependency/config review.
- **Completion evidence:** Severity ledger with reproduction/fix evidence.

<a id="hard-038"></a>
## HARD-038 — Validate real accessibility/adaptive/UI journeys

- **Milestone / status:** M6 / NOT_STARTED.
- **Dependencies:** HARD-036.
- **Affected modules:** Android, WPF and browser UI.
- **Acceptance criteria:** Real TalkBack/keyboard, 200% text, adaptive layouts, light/dark, reduced motion and truthful errors pass supported platforms.
- **Regression tests:** Accessibility semantics plus manual assistive-tech journeys.
- **Completion evidence:** Platform/device/browser evidence.

<a id="hard-039"></a>
## HARD-039 — Measure production performance on devices

- **Milestone / status:** M6 / NOT_STARTED.
- **Dependencies:** HARD-036.
- **Affected modules:** Transfer/hashing/storage/UI hot paths on supported platforms.
- **Acceptance criteria:** Startup/frame/memory/throughput/hash/battery/thermal measurements use release-like builds and real devices; no loopback result is presented as device throughput.
- **Regression tests:** 10 MB/100 MB/1 GB/5+ GB and 1,000-file workloads.
- **Completion evidence:** Raw measurements, environments and budgets.

<a id="hard-040"></a>
## HARD-040 — Complete CI/architecture/migration/dependency/doc gates

- **Milestone / status:** M6 / NOT_STARTED.
- **Dependencies:** HARD-037, HARD-038, HARD-039.
- **Affected modules:** CI/tooling, architecture scanners, migrations, dependency review and docs links.
- **Acceptance criteria:** CI covers supported builds/tests/browser tests/instrumentation where feasible; architecture rules cover LAN/storage/all source sets; migration/dependency/doc-link gates execute.
- **Regression tests:** Deliberate violations fail their gates.
- **Completion evidence:** Green workflows plus deliberate-failure proof.

<a id="hard-041"></a>
## HARD-041 — Build/test release artifacts for supported platforms

- **Milestone / status:** M7 / NOT_STARTED.
- **Dependencies:** HARD-040.
- **Affected modules:** Android APK/AAB, Windows packages, Linux packages and versioned web artifacts.
- **Acceptance criteria:** Exact artifacts install/run/update as applicable; signatures/checksums/provenance recorded; packaging excludes insecure/deferred paths and includes required native dependencies.
- **Regression tests:** Clean install/update, transfer smoke and artifact inspection.
- **Completion evidence:** Artifact hashes/signature/package reports.

<a id="hard-042"></a>
## HARD-042 — Complete exact-candidate release dossier

- **Milestone / status:** M7 / NOT_STARTED.
- **Dependencies:** HARD-041.
- **Affected modules:** Release/legal/privacy/support/signing docs and evidence.
- **Acceptance criteria:** Privacy, licenses, key custody, support matrix, install/update verification and candidate evidence all match exact artifacts/commit.
- **Regression tests:** Evidence/link consistency and candidate-hash reconciliation.
- **Completion evidence:** Approved dossier with no release-gate manual check outstanding.

<a id="hard-043"></a>
## HARD-043 — Execute explicitly authorized staged release

- **Milestone / status:** M7 / NOT_STARTED.
- **Dependencies:** HARD-042.
- **Affected modules:** Release operations only.
- **Acceptance criteria:** Release occurs only after explicit owner authorization; rollout/rollback/incident/maintenance ownership is active; published artifacts match reviewed checksum/commit.
- **Regression tests:** Preflight install/smoke and rollback tabletop; documentation completion alone never triggers deployment.
- **Completion evidence:** Authorization record, release identifiers, hashes and monitoring/rollback handoff.

## Execution rule

Default execution is M0 → M1 → M2 → M3 → M4 → M5 → M6 → M7 unless the ledger is deliberately amended with evidence. After HARD-001, the only current READY task is **HARD-002**.
