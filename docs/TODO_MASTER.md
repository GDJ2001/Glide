# Glide master task system

**Current status authority for active development.** The historical GLIDE-001–072 ledger is preserved in Git history at pre-HARD commit `b07f15bdde60bb5bfa9b20fdaba093b7a6a93785` and its earlier commits. Historical `COMPLETE`, release, shipped, or certified labels do not certify the current multi-platform candidate.

Current program: **HARD-001–HARD-043 — Code Review, Improvement, Hardening & Development Completion**.  
Plan: [`planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md`](planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md)  
Latest reconciliation: [`planning/HARD_002_SOURCE_DOC_CLAIM_RECONCILIATION.md`](planning/HARD_002_SOURCE_DOC_CLAIM_RECONCILIATION.md)  
Review baseline: `ea2e811a7f2d8aaddc552e894fe04782cd08a499`  
HARD-002 captured source boundary: `12681eabba26387fe7d1bbaf309971f345642e41`  
**Next recommended task: HARD-007 (IN_PROGRESS).**

Source/configuration commits after the HARD-002 captured boundary are not silently certified by HARD-002; the task that consumes them must inspect/reconcile them before relying on their claims.

## Status policy

- `NOT_STARTED`: prerequisites or qualifying implementation/evidence are incomplete.
- `READY`: prerequisites are complete and no named blocker remains.
- `IN_PROGRESS`: one active session owns the task.
- `REVIEW_REQUIRED`: implementation exists but acceptance/review/manual evidence is incomplete.
- `BLOCKED`: a named blocker prevents completion.
- `COMPLETE`: all acceptance criteria and required evidence for the task are satisfied.
- `DEFERRED`: explicitly outside the current hardening cycle.

Do not mark tasks complete because files exist, source-text checks pass, loopback tests pass, an emulator renders, or historical documents say a milestone was released. Physical/interoperability gates require their specified real environments.

## Program progress

| Milestone | Tasks | Complete | Status |
| --- | --- | ---: | --- |
| M0 Honest baseline | HARD-001–004 | 4 / 4 | COMPLETE |
| M1 Android production flow | HARD-005–010 | 2 / 6 | IN PROGRESS |
| M2 Desktop/native interoperability | HARD-011–017 | 0 / 7 | NOT STARTED |
| M3 Trust, folders and true resume | HARD-018–024 | 0 / 7 | NOT STARTED |
| M4 Secure browser sharing | HARD-025–030 | 0 / 6 | NOT STARTED |
| M5 Structure/developer experience | HARD-031–035 | 0 / 5 | NOT STARTED |
| M6 Product quality/hardening | HARD-036–040 | 0 / 5 | NOT STARTED |
| M7 Release/operations | HARD-041–043 | 0 / 3 | NOT STARTED |
| **Total** | **HARD-001–043** | **6 / 43** | **14.0%** |

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
| Deferred Apple partial runtime | Preserved/deferred; see HARD-002 reconciliation |
| Deferred remote partial ICE/TURN/E2EE runtime | Preserved/deferred; see HARD-002 reconciliation |

## Task index

| ID | Milestone | Status | Title |
| --- | --- | --- | --- |
| HARD-001 | M0 | COMPLETE | Publish evidence-backed review and completion plan |
| HARD-002 | M0 | COMPLETE | Reconcile source, docs, claims and post-review commits |
| HARD-003 | M0 | COMPLETE | Add regressions for confirmed review defects |
| HARD-004 | M0 | COMPLETE | Establish Android/Windows/Linux CI baseline |
| HARD-005 | M1 | COMPLETE | Introduce service-owned Android TransferRuntime |
| HARD-006 | M1 | COMPLETE | Wire production discovery and permissions |
| HARD-007 | M1 | IN_PROGRESS | Wire complete Nearby send/receive lifecycle |
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
- **Scope:** Documentation authority only.
- **Acceptance:** Review baseline/limitations, active scope, finding→task map and status authority are explicit; Apple/remote work is preserved but deferred.
- **Evidence:** [`planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md`](planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md) and [`development/sessions/2026-09-18-hard-001.md`](development/sessions/2026-09-18-hard-001.md).

<a id="hard-002"></a>
## HARD-002 — Reconcile source, docs, claims and post-review commits

- **Milestone / status:** M0 / COMPLETE.
- **Dependencies:** HARD-001.
- **Captured boundary:** `12681eabba26387fe7d1bbaf309971f345642e41`.
- **Scope:** Full `ea2e811...captured-head` source/config delta, historical completion/release claims, current status/spec surfaces.
- **Acceptance satisfied:** Eight substantive post-review Apple/remote source commits were reviewed separately from HARD docs and merge provenance; claims are classified as specified/implemented/automated-tested/physically verified/released/unverified; deferred Apple/remote code remains outside the active release gate; stale README/cross-platform/Apple/remote contradictions were corrected without rewriting historical sessions.
- **Evidence:** [`planning/HARD_002_SOURCE_DOC_CLAIM_RECONCILIATION.md`](planning/HARD_002_SOURCE_DOC_CLAIM_RECONCILIATION.md) and [`development/sessions/2026-09-18-hard-002.md`](development/sessions/2026-09-18-hard-002.md).

<a id="hard-003"></a>
## HARD-003 — Add regressions for confirmed review defects

- **Milestone / status:** M0 / COMPLETE.
- **Dependencies:** HARD-002.
- **Affected modules:** Android app/LAN production wiring; Linux/desktop app/transport/tests; browser gateway tests/tooling.
- **Acceptance satisfied:** Executable regressions expose disconnected Android production wiring, fixed/example invitation acceptance, unsynchronized SAS authentication, sequence/pagination misuse, plaintext browser-release assumptions and whole-file production hashing while remaining isolated from the ordinary green baseline.
- **Regression/evidence:** [HARD-003 regression evidence](development/sessions/2026-09-18-hard-003.md), including exact commands and expected-red CI evidence.

<a id="hard-004"></a>
## HARD-004 — Establish Android/Windows/Linux CI baseline

- **Milestone / status:** M0 / COMPLETE.
- **Dependencies:** HARD-003.
- **Scope:** `.github/workflows`, Gradle modules, `windows/Glide.slnx`, Linux-compatible packaging/tooling.
- **Acceptance satisfied:** CI reaches checkout and executes Android build/lint/tests plus .NET build/tests and Linux-compatible shared/package checks; action pins resolve; secrets remain protected.
- **Evidence:** [HARD-004 CI baseline evidence](development/sessions/2026-09-18-hard-004.md). Authoritative green run: **35286514435** at `234fb024f2fef2273c09e64ff15011b2b6663d15`; prior source-reaching failure run **35285947126** demonstrates an ordinary failing test blocks its job. Hardware-only gaps remain explicitly deferred to later gates.

<a id="hard-005"></a>
## HARD-005 — Introduce service-owned Android TransferRuntime

- **Milestone / status:** M1 / COMPLETE.
- **Dependencies:** HARD-004.
- **Acceptance satisfied:** One service-owned runtime exposes typed send/receive/accept/reject/cancel/resume commands and immutable discovery/pairing/transfer state; ViewModels do not construct transports or own active transfer scopes.
- **Evidence:** [HARD-005 runtime evidence](development/sessions/2026-09-18-hard-005.md). Authoritative green CI run **35288819509** at `e5060a1c2dd8555c4c4483de137e9ccbc253c523`; runtime ownership, recreation, duplicate command, stale cancel, pairing ownership and service-detach interruption tests passed.

<a id="hard-006"></a>
## HARD-006 — Wire production discovery and permissions

- **Milestone / status:** M1 / COMPLETE.
- **Dependencies:** HARD-005.
- **Acceptance satisfied:** Production ViewModels use real runtime/discovery dependencies; point-of-use permission approval/denial controls actual discovery/advertising; fake defaults cannot enter production paths.
- **Evidence:** [HARD-006 discovery/permission evidence](development/sessions/2026-09-18-hard-006.md). Authoritative green CI run **35291116962** at `9a00d95f0fde6e6090c9a16b6bca44a69fba738b`.

<a id="hard-007"></a>
## HARD-007 — Wire complete Nearby send/receive lifecycle

- **Milestone / status:** M1 / IN_PROGRESS.
- **Dependencies:** HARD-006.
- **Acceptance:** Selected sources survive navigation; explicit Nearby authentication and receiver offer consent precede bytes; multi-file batches use the production runtime.
- **Current focus:** bind discovered peer selection to the controller's real Nearby transport, route initiation/authentication through `TransferRuntime`, start sender/receiver coordinators only after authenticated connection, and preserve explicit receiver offer consent before any bulk bytes.
- **Tests/evidence:** Reject, cancel, peer loss, multi-file, stale action and source revocation through production-flow tests/manual journey trace.

<a id="hard-008"></a>
## HARD-008 — Wire real LAN listener and QR invitation lifecycle

- **Milestone / status:** M1 / NOT_STARTED.
- **Dependencies:** HARD-005, HARD-006.
- **Acceptance:** QR is produced from a live listener with actual endpoint/pin/fingerprint; invitation is atomically consumed; scan/paste enters the same production runtime.
- **Tests/evidence:** Reject fixed/example data, expiry/replay, bind failure and wrong pin; record live QR payload/listener/pairing traces.

<a id="hard-009"></a>
## HARD-009 — Complete service/session/background lifecycle

- **Milestone / status:** M1 / NOT_STARTED.
- **Dependencies:** HARD-007, HARD-008.
- **Acceptance:** One session survives supported rotation/background transitions; notification actions target the exact session; process death never reports false success; cancel tears down transport/storage safely.
- **Tests/evidence:** FGS restrictions, lock/background, process death, stale notification and duplicate service start with lifecycle traces.

<a id="hard-010"></a>
## HARD-010 — Prove two-device Android production journey

- **Milestone / status:** M1 / NOT_STARTED.
- **Dependencies:** HARD-007, HARD-008, HARD-009.
- **Acceptance:** Two physical Android devices complete selection → pairing/authentication → offer consent → transfer → SHA-256 verification → open/history in both directions; reject/cancel use the same production path.
- **Tests/evidence:** At least 10 MB and 1 GB payloads repeated both directions plus reject/cancel; record device/API/build identities, hashes, logs/screens and exact path trace.

<a id="hard-011"></a>
## HARD-011 — Extract shared desktop transfer engine

- **Milestone / status:** M2 / NOT_STARTED.
- **Dependencies:** HARD-004.
- **Acceptance:** WPF and CLI share one pairing/consent/sequencing/streaming/completion/cancellation engine; no duplicated protocol loop remains in UI/entrypoint code.
- **Tests/evidence:** CLI/WPF parity through the same engine fixtures/fakes and a dependency/responsibility map.

<a id="hard-012"></a>
## HARD-012 — Align native LAN discovery/TLS/bootstrap profile

- **Milestone / status:** M2 / NOT_STARTED.
- **Dependencies:** HARD-011.
- **Acceptance:** One DNS-SD TXT contract, ALPN, certificate/client-auth expectation, invitation format, 5-byte LAN framing and authenticated bootstrap works Android↔desktop; unpaired SAS is removed or repaired around one exchanged/bound nonce and explicit confirmation.
- **Tests/evidence:** Cross-language bootstrap/golden vectors, wrong pin/cert/ALPN/replay cases and actual interoperability traces.

<a id="hard-013"></a>
## HARD-013 — Implement strict desktop session/protocol state machine

- **Milestone / status:** M2 / NOT_STARTED.
- **Dependencies:** HARD-011, HARD-012.
- **Acceptance:** Monotonic sequence, paged offers, exact IDs/correlation, deadlines, consent ordering and truthful item/batch terminal outcomes match protocol semantics.
- **Tests/evidence:** Multi-page offers, duplicate/gapped sequence, mismatched IDs, failed middle item and lost terminal receipt; state-machine tests and golden parity.

<a id="hard-014"></a>
## HARD-014 — Make desktop hashing/storage bounded and shared

- **Milestone / status:** M2 / NOT_STARTED.
- **Dependencies:** HARD-011.
- **Acceptance:** Production sender hashing/transfer is streaming and bounded; no whole-file production digest read; safe paths, reservations, quarantine cleanup and commit are centralized.
- **Tests/evidence:** Empty/10 MB/1 GB/5+ GB, cancel, disk full, duplicate names, traversal/reparse; memory must not scale with file size.

<a id="hard-015"></a>
## HARD-015 — Complete desktop consent/history/settings/state

- **Milestone / status:** M2 / NOT_STARTED.
- **Dependencies:** HARD-013, HARD-014.
- **Acceptance:** Incoming offers require authorization; history/settings persist; UI updates are thread-safe; CLI/WPF report identical outcomes.
- **Tests/evidence:** Accept/reject, restart persistence, UI-thread marshaling and partial-batch journeys.

<a id="hard-016"></a>
## HARD-016 — Harden Linux CLI/daemon receiving lifecycle

- **Milestone / status:** M2 / NOT_STARTED.
- **Dependencies:** HARD-013, HARD-014.
- **Acceptance:** Missing/invalid enrollment is rejected; daemon cannot auto-authorize arbitrary offers; headless receive requires explicit peer/destination/size policy; shutdown/cancel cleans resources.
- **Tests/evidence:** No invitation, wrong peer, unauthorized offer, SIGTERM/shutdown and disk-limit runtime/systemd traces.

<a id="hard-017"></a>
## HARD-017 — Prove native cross-platform interoperability

- **Milestone / status:** M2 / NOT_STARTED.
- **Dependencies:** HARD-012, HARD-013, HARD-014, HARD-015, HARD-016.
- **Acceptance:** Android↔Windows, Android↔Linux and Windows↔Linux pass both directions with multi-page/multi-file batches; failed items cannot yield successful batch status.
- **Tests/evidence:** Empty/large/mixed files, reject/cancel, wrong authentication and reconnect boundaries with real platform/build identities, hashes and logs.

<a id="hard-018"></a>
## HARD-018 — Finalize identity/resume protocol extension

- **Milestone / status:** M3 / NOT_STARTED.
- **Dependencies:** HARD-017.
- **Acceptance:** Versioned identity/resume messages, canonical signature inputs, peer/role/session/channel binding, capabilities and shared vectors are cross-language compatible.
- **Tests/evidence:** Replay, downgrade, role swap, changed key and malformed signatures; reviewed spec/ADR and golden vectors.

<a id="hard-019"></a>
## HARD-019 — Integrate Android persistent identity/trust

- **Milestone / status:** M3 / NOT_STARTED.
- **Dependencies:** HARD-018.
- **Acceptance:** Explicit enrollment, protected Android key, peer key/fingerprint persistence, revocation and trusted-peer presentation are wired to production runtime.
- **Tests/evidence:** Key loss/rotation, revoke, duplicate enrollment and restored DB without key; Keystore/trust lifecycle evidence.

<a id="hard-020"></a>
## HARD-020 — Integrate Windows/Linux persistent identity/trust

- **Milestone / status:** M3 / NOT_STARTED.
- **Dependencies:** HARD-018.
- **Acceptance:** Protected persistent keys, explicit enrollment/revocation and peer-specific headless policy work on supported desktop OSes.
- **Tests/evidence:** Permission failure, revoke, migration and wrong peer with platform storage/permission evidence.

<a id="hard-021"></a>
## HARD-021 — Integrate mutually authenticated trusted sessions

- **Milestone / status:** M3 / NOT_STARTED.
- **Dependencies:** HARD-019, HARD-020.
- **Acceptance:** Both peers prove enrolled identity bound to fresh challenge and secure channel/session; replay/downgrade/revoked trust fails closed; trust never auto-accepts arbitrary files.
- **Tests/evidence:** MITM/pin mismatch, replay, revoked peer, challenge reuse and capability downgrade across platforms.

<a id="hard-022"></a>
## HARD-022 — Implement Android durable true-resume journals

- **Milestone / status:** M3 / NOT_STARTED.
- **Dependencies:** HARD-018, HARD-021.
- **Acceptance:** Journal is keyed by transfer+item, stores negotiated chunk identity and only contiguous verified progress; source/permission/peer changes are revalidated; partials expire after 24h.
- **Tests/evidence:** Missing/corrupt chunk, process death, modified source, expiry, revoked peer and disk pressure; migration/recovery and byte-identical result.

<a id="hard-023"></a>
## HARD-023 — Implement desktop durable true-resume storage

- **Milestone / status:** M3 / NOT_STARTED.
- **Dependencies:** HARD-018, HARD-021.
- **Acceptance:** Desktop uses the same contiguous-verified resume semantics, retained-partial policy, peer/source revalidation and final whole-file SHA-256.
- **Tests/evidence:** HARD-022 interruption/corruption matrix on Windows/Linux with restart/recovery traces and hashes.

<a id="hard-024"></a>
## HARD-024 — Validate folders and resume across native platforms

- **Milestone / status:** M3 / NOT_STARTED.
- **Dependencies:** HARD-022, HARD-023.
- **Acceptance:** Nested paths preserve safely; traversal/symlink/reparse attacks fail; interrupted large transfers resume from verified progress across native pairings.
- **Tests/evidence:** Nested folders, duplicate paths, cycles, links/reparse, changed sources and revoked trust across the native matrix.

<a id="hard-025"></a>
## HARD-025 — Specify secure browser transport and static client

- **Milestone / status:** M4 / NOT_STARTED.
- **Dependencies:** HARD-017.
- **Acceptance:** HTTPS static client + local WebRTC DataChannel profile; QR/copy-paste authenticates DTLS fingerprint; no file/pairing secret crosses web host; no HTTP fallback.
- **Tests/evidence:** Wrong fingerprint, replay, no local candidate and host-offline-after-load; threat review and conformance fixtures.

<a id="hard-026"></a>
## HARD-026 — Add Android WebRTC adapter

- **Milestone / status:** M4 / NOT_STARTED.
- **Dependencies:** HARD-025.
- **Acceptance:** Data-only adapter has pinned native inputs, bounded buffers, lifecycle cleanup and no UI/native-library leakage into core.
- **Tests/evidence:** Channel open/close, cancel, JNI/native cleanup and process/background lifecycle plus build provenance.

<a id="hard-027"></a>
## HARD-027 — Add Windows/Linux WebRTC adapter

- **Milestone / status:** M4 / NOT_STARTED.
- **Dependencies:** HARD-025.
- **Acceptance:** Shared .NET adapter manages native resources deterministically and packages supported Windows/Linux binaries without changing core protocol semantics.
- **Tests/evidence:** Native load failure, channel lifecycle, cancellation and package smoke.

<a id="hard-028"></a>
## HARD-028 — Implement secure browser transfer behavior

- **Milestone / status:** M4 / NOT_STARTED.
- **Dependencies:** HARD-026, HARD-027.
- **Acceptance:** Explicit offers/consent, bounded streaming, SHA-256 verification, truthful progress and safe output work browser↔native; unsupported durable APIs expose limitations instead of whole-file buffering.
- **Tests/evidence:** Modified content, large files, cancel, storage denial and unavailable output API through browser/native integration tests.

<a id="hard-029"></a>
## HARD-029 — Implement browser identity and resume

- **Milestone / status:** M4 / NOT_STARTED.
- **Dependencies:** HARD-028.
- **Acceptance:** Non-extractable browser identity, profile-scoped trust and resumable state with file reselection/source validation; clearing site data removes trust/resume.
- **Tests/evidence:** Profile clear, source change, storage eviction, tab close/reopen and wrong identity across supported browsers.

<a id="hard-030"></a>
## HARD-030 — Prove browser security/compatibility and remove HTTP release path

- **Milestone / status:** M4 / NOT_STARTED.
- **Dependencies:** HARD-028, HARD-029.
- **Acceptance:** Browser↔Android and browser↔desktop pass authenticated encrypted local channels; wrong fingerprints/replay/modification fail closed; plaintext HTTP transfer is excluded from release builds.
- **Tests/evidence:** Supported browser matrix, blocked LAN, profile clear and large-file scenarios plus release-binary inspection.

<a id="hard-031"></a>
## HARD-031 — Refactor Android entry/presentation boundaries

- **Milestone / status:** M5 / NOT_STARTED.
- **Dependencies:** HARD-010.
- **Acceptance:** Bounded entry/navigation composition; debug fixtures live in debug/test sources; presentation maps domain models rather than persistence entities; no behavior change.
- **Tests/evidence:** Existing production-flow/UI-state tests stay green; refactor-only diff/parity evidence.

<a id="hard-032"></a>
## HARD-032 — Refactor desktop presentation boundaries

- **Milestone / status:** M5 / NOT_STARTED.
- **Dependencies:** HARD-015.
- **Acceptance:** Networking, protocol, filesystem and long-running orchestration are outside WPF ViewModels; UI remains state-driven.
- **Tests/evidence:** WPF state/command tests, engine parity and responsibility/coupling map.

<a id="hard-033"></a>
## HARD-033 — Mechanically reorganize platform trees

- **Milestone / status:** M5 / NOT_STARTED.
- **Dependencies:** HARD-031, HARD-032, HARD-030.
- **Acceptance:** Mechanical target layout (`android/`, `desktop/`, `web/`, `protocol/fixtures/`) without package/protocol behavior changes; references/tooling/docs updated separately from functional edits.
- **Tests/evidence:** Full build/test before and after move; path map and zero-behavior-diff review.

<a id="hard-034"></a>
## HARD-034 — Consolidate fixtures and browser sources

- **Milestone / status:** M5 / NOT_STARTED.
- **Dependencies:** HARD-033.
- **Acceptance:** One authoritative fixture set and browser source; embedded/generated copies are reproducible rather than manually synchronized.
- **Tests/evidence:** Cross-language fixture parity and deterministic generated-asset diff with generation commands.

<a id="hard-035"></a>
## HARD-035 — Standardize developer commands/tooling

- **Milestone / status:** M5 / NOT_STARTED.
- **Dependencies:** HARD-033, HARD-034.
- **Acceptance:** Root `doctor/build/test/lint/verify/preview` entrypoints; JDK/.NET/Node/native versions pinned/documented; IDE launch configs documented.
- **Tests/evidence:** Fresh-checkout command validation on supported development OSes plus doctor output/runbook.

<a id="hard-036"></a>
## HARD-036 — Exercise physical failure/recovery matrix

- **Milestone / status:** M6 / NOT_STARTED.
- **Dependencies:** HARD-024, HARD-030, HARD-035.
- **Acceptance:** Real network/lifecycle/storage/permission failure behavior is truthful and has no false success/data exposure.
- **Tests/evidence:** Disconnect, lock, kill, permission revoke, disk full, duplicate names, volume changes and resume corruption across real supported environments.

<a id="hard-037"></a>
## HARD-037 — Perform production security review and remediation

- **Milestone / status:** M6 / NOT_STARTED.
- **Dependencies:** HARD-036.
- **Acceptance:** No unresolved Critical/High finding; verified issues fixed/retested; residual Medium risks explicitly owned.
- **Tests/evidence:** Hostile inputs, replay/downgrade, path/storage attacks and dependency/config review with severity ledger/reproduction/fix evidence.

<a id="hard-038"></a>
## HARD-038 — Validate real accessibility/adaptive/UI journeys

- **Milestone / status:** M6 / NOT_STARTED.
- **Dependencies:** HARD-036.
- **Acceptance:** Real TalkBack/keyboard, 200% text, adaptive layouts, light/dark, reduced motion and truthful errors pass supported UI platforms.
- **Tests/evidence:** Accessibility semantics plus manual assistive-tech journeys with platform/device/browser evidence.

<a id="hard-039"></a>
## HARD-039 — Measure production performance on devices

- **Milestone / status:** M6 / NOT_STARTED.
- **Dependencies:** HARD-036.
- **Acceptance:** Startup/frame/memory/throughput/hash/battery/thermal measurements use release-like builds and real devices; loopback results are never presented as device/network throughput.
- **Tests/evidence:** 10 MB/100 MB/1 GB/5+ GB and 1,000-file workloads with raw measurements/environments/budgets.

<a id="hard-040"></a>
## HARD-040 — Complete CI/architecture/migration/dependency/doc gates

- **Milestone / status:** M6 / NOT_STARTED.
- **Dependencies:** HARD-037, HARD-038, HARD-039.
- **Acceptance:** CI covers supported builds/tests/browser tests/instrumentation where feasible; architecture rules cover LAN/storage/all source sets; migration/dependency/doc-link gates execute.
- **Tests/evidence:** Deliberate violations fail their gates; green workflow evidence retained.

<a id="hard-041"></a>
## HARD-041 — Build/test release artifacts for supported platforms

- **Milestone / status:** M7 / NOT_STARTED.
- **Dependencies:** HARD-040.
- **Acceptance:** Exact Android/Windows/Linux/web artifacts install/run/update as applicable; signatures/checksums/provenance recorded; packaging excludes insecure/deferred paths and includes required native dependencies.
- **Tests/evidence:** Clean install/update, transfer smoke and artifact inspection with hashes/signature/package reports.

<a id="hard-042"></a>
## HARD-042 — Complete exact-candidate release dossier

- **Milestone / status:** M7 / NOT_STARTED.
- **Dependencies:** HARD-041.
- **Acceptance:** Privacy, licenses, key custody, support matrix, install/update verification and candidate evidence all match the exact artifacts/commit.
- **Tests/evidence:** Evidence/link consistency and candidate-hash reconciliation; no release-gate manual check outstanding.

<a id="hard-043"></a>
## HARD-043 — Execute explicitly authorized staged release

- **Milestone / status:** M7 / NOT_STARTED.
- **Dependencies:** HARD-042.
- **Acceptance:** Release occurs only after explicit owner authorization; rollout/rollback/incident/maintenance ownership is active; published artifacts match reviewed checksum/commit.
- **Tests/evidence:** Preflight install/smoke and rollback tabletop plus authorization record, release identifiers, hashes and monitoring handoff.

## Execution rule

Default execution is M0 → M1 → M2 → M3 → M4 → M5 → M6 → M7 unless the ledger is deliberately amended with evidence. After HARD-002, the only current READY task is **HARD-003**.