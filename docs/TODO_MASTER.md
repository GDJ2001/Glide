# Glide master task system

**Current status authority for active development.** The historical GLIDE-001–072 ledger is preserved in Git history at pre-HARD commit `b07f15bdde60bb5bfa9b20fdaba093b7a6a93785` and its earlier commits. Historical `COMPLETE`, release, shipped, or certified labels do not certify the current multi-platform candidate.

Current program: **HARD-001–HARD-043 — Code Review, Improvement, Hardening & Development Completion**.  
Plan: [`planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md`](planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md)  
Latest reconciliation: [`planning/HARD_002_SOURCE_DOC_CLAIM_RECONCILIATION.md`](planning/HARD_002_SOURCE_DOC_CLAIM_RECONCILIATION.md)  
Review baseline: `ea2e811a7f2d8aaddc552e894fe04782cd08a499`  
HARD-002 captured source boundary: `12681eabba26387fe7d1bbaf309971f345642e41`  
**Next recommended verification action: restore current-head CI for the continuation branch, then review HARD-008/HARD-009/HARD-011–015 and HARD-018–019. HARD-010 and HARD-017 remain blocked on real-device/platform evidence. HARD-016 is IN_PROGRESS until peer-specific headless authorization binds to persistent identities in HARD-020/HARD-021. HARD-020 source preparation is the next safe development step; identity-v1/resume-v1 must remain disabled in production until HARD-019–021 are complete and verified.**

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
| M1 Android production flow | HARD-005–010 | 3 / 6 | IN PROGRESS |
| M2 Desktop/native interoperability | HARD-011–017 | 0 / 7 | IN PROGRESS |
| M3 Trust, folders and true resume | HARD-018–024 | 0 / 7 | IN PROGRESS |
| M4 Secure browser sharing | HARD-025–030 | 0 / 6 | NOT STARTED |
| M5 Structure/developer experience | HARD-031–035 | 0 / 5 | NOT STARTED |
| M6 Product quality/hardening | HARD-036–040 | 0 / 5 | NOT STARTED |
| M7 Release/operations | HARD-041–043 | 0 / 3 | NOT STARTED |
| **Total** | **HARD-001–043** | **7 / 43** | **16.3%** |

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
| HARD-007 | M1 | COMPLETE | Wire complete Nearby send/receive lifecycle |
| HARD-008 | M1 | REVIEW_REQUIRED | Wire real LAN listener and QR invitation lifecycle |
| HARD-009 | M1 | REVIEW_REQUIRED | Complete service/session/background lifecycle |
| HARD-010 | M1 | BLOCKED | Prove two-device Android production journey |
| HARD-011 | M2 | REVIEW_REQUIRED | Extract shared desktop transfer engine |
| HARD-012 | M2 | REVIEW_REQUIRED | Align native LAN discovery/TLS/bootstrap profile |
| HARD-013 | M2 | REVIEW_REQUIRED | Implement strict desktop session/protocol state machine |
| HARD-014 | M2 | REVIEW_REQUIRED | Make desktop hashing/storage bounded and shared |
| HARD-015 | M2 | REVIEW_REQUIRED | Complete desktop consent/history/settings/state |
| HARD-016 | M2 | IN_PROGRESS | Harden Linux CLI/daemon receiving lifecycle |
| HARD-017 | M2 | BLOCKED | Prove native cross-platform interoperability |
| HARD-018 | M3 | REVIEW_REQUIRED | Finalize identity/resume protocol extension |
| HARD-019 | M3 | REVIEW_REQUIRED | Integrate Android persistent identity/trust |
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

- **Milestone / status:** M1 / COMPLETE.
- **Dependencies:** HARD-006.
- **Acceptance satisfied:** Selected sources survive navigation; explicit Nearby authentication and receiver offer consent precede bytes; multi-file batches use the production runtime.
- **Evidence:** [HARD-007 Nearby lifecycle evidence](development/sessions/2026-09-18-hard-007.md). Authoritative green CI run **35293020239** at `3e79e7a80cae2c2383535952c8f2c8f6724e1908`.

<a id="hard-008"></a>
## HARD-008 — Wire real LAN listener and QR invitation lifecycle

- **Milestone / status:** M1 / REVIEW_REQUIRED.
- **Dependencies:** HARD-005, HARD-006.
- **Implementation state:** Live service-owned `LanNetworkScope` + `LanLeaseIdentity` + `LanListener` + `InvitationAuthority` + `LanTransferTransport` wiring is implemented; production navigation no longer contains the historical fixed endpoint/SPKI; QR scan/paste preserves the selected batch and enters the service-owned runtime.
- **Verification blocker:** GitHub Actions has produced no push-triggered run/check for the HARD-008 implementation commits. The last green run (**35293020239**) predates HARD-008 and cannot be used as completion evidence.
- **Evidence:** [HARD-008 implementation/verification record](development/sessions/2026-09-18-hard-008.md). HARD-008 remains open until a current-HEAD Android build/test/LAN-gate/lint run is green.
- **Tests prepared:** live listener/SPKI/bootstrap, wrong pin, bind failure, atomic consume/replay/expiry, sender/receiver runtime handoff, and non-replaying QR confirmation actions.

<a id="hard-009"></a>
## HARD-009 — Complete service/session/background lifecycle

- **Milestone / status:** M1 / REVIEW_REQUIRED.
- **Dependencies:** HARD-007, HARD-008.
- **Implementation state:** Service/session/background lifecycle hardening is implemented on `main`: terminal cleanup is awaited before service teardown, explicit service stop requests protocol cancellation, duplicate service starts are idempotent, stale notification actions remain exact-session, and startup reconciliation preserves any live in-process session while abandoned work is interrupted.
- **Verification blocker:** HARD-008 is still REVIEW_REQUIRED and GitHub Actions is not scheduling current-head push or pull-request runs. No compile/test/lint claim is made for the current HARD-009 source graph.
- **Evidence:** [HARD-009 lifecycle implementation record](development/sessions/2026-09-18-hard-009.md).
- **Tests prepared/retained:** FGS restriction matrix, activity/runtime rebind, lock-before-consent, process-death reconciliation, stale accept/reject/cancel actions, duplicate service-start idempotence, and terminal transport/storage cleanup ordering.

<a id="hard-010"></a>
## HARD-010 — Prove two-device Android production journey

- **Milestone / status:** M1 / BLOCKED.
- **Dependencies:** HARD-007, HARD-008, HARD-009.
- **Blockers:** HARD-008/HARD-009 still require current-head CI verification, and this execution environment has no two attached physical Android devices. Emulator/loopback evidence cannot substitute for this gate.
- **Preparation complete:** `tools/hard-010-physical-journey.ps1` and [HARD-010 two-device Android runbook](testing/HARD_010_TWO_DEVICE_ANDROID_RUNBOOK.md) define an auditable 12-run Nearby/LAN matrix with exact candidate/device identity, SHA-256, staging, logcat and screenshot capture.
- **Evidence record:** [HARD-010 physical gate preparation](development/sessions/2026-09-18-hard-010-preparation.md).
- **Acceptance remains:** Two physical Android devices complete selection → pairing/authentication → offer consent → transfer → SHA-256 verification → open/history in both directions; reject/cancel use the same production path. No physical pass has been claimed.

<a id="hard-011"></a>
## HARD-011 — Extract shared desktop transfer engine

- **Milestone / status:** M2 / REVIEW_REQUIRED.
- **Dependencies:** HARD-004.
- **Implementation state:** `Glide.Desktop` now owns the shared connected-session engine. CLI send/receive/daemon and WPF send/receive delegate to `DesktopTransferEngine`; neither entrypoint retains a `LanRecordChannel`/item protocol loop or sender whole-file hashing loop. `Glide.Desktop` is wired into the solution, CLI, WPF and test projects.
- **Behavioral evidence prepared:** real loopback TLS QR-paired two-file send/receive, offer rejection before commit, shared cancellation result, peer/invitation/history callbacks, plus architecture guards preventing protocol-loop regression into CLI/WPF.
- **HARD-003 compatibility:** the CLI receive-handler reflection seam and original SAS console format are retained as thin presentation wrappers so expected-red pagination/sequence/SAS/hashing tests continue exercising the deferred defects.
- **Verification blocker:** GitHub Actions still produces no current-head runs/checks. No compile/test claim is made for the HARD-011 source graph.
- **Evidence:** [HARD-011 shared desktop engine extraction](development/sessions/2026-09-18-hard-011.md).
- **Completion gate:** current-head Windows solution build + ordinary Windows/Linux tests + shared-engine tests + architecture guards + intended HARD-003 expected-red behavior must pass before HARD-012 is activated.

<a id="hard-012"></a>
## HARD-012 — Align native LAN discovery/TLS/bootstrap profile

- **Milestone / status:** M2 / REVIEW_REQUIRED.
- **Dependencies:** HARD-011 (still REVIEW_REQUIRED because current-head CI is unavailable).
- **Implementation state:** Continuation branch `hardening/continue-2026-09-18` now aligns desktop DNS-SD with Android's `v/tv/q/p/dn/dt` availability profile, removes discovery authentication material, requires TLS 1.3 + ALPN `glide-lan/1`, uses server-authenticated pinned TLS for invitation pairing, atomically consumes canonical one-use invitations, and removes the unauthenticated direct/SAS production path.
- **Tests prepared:** canonical/legacy DNS-SD profile checks, no-client-certificate pinned TLS loopback, unpaired-send rejection, invitation replay rejection and authenticated shared-engine journeys.
- **Verification blocker:** no GitHub Actions run/check is being scheduled for the continuation branch; actual Android↔desktop interoperability is HARD-017.
- **Evidence:** [HARD-012 implementation record](development/sessions/2026-09-18-hard-012.md).

<a id="hard-013"></a>
## HARD-013 — Implement strict desktop session/protocol state machine

- **Milestone / status:** M2 / REVIEW_REQUIRED.
- **Dependencies:** HARD-011, HARD-012; both await current-head executable verification.
- **Implementation state:** Shared desktop control state now uses independent strictly increasing per-direction sequence numbers, 32-item paged offers, exact session/batch/item/bulk-reference correlation, bounded control/data read deadlines, bootstrap-before-HELLO ordering and truthful terminal aggregation. A failed item cannot be surfaced as a fully successful batch.
- **Tests prepared:** 33-item pagination, exact multi-item sequence continuity, offer rejection, authenticated two-file transfer, replay rejection and correlation checks in production-engine paths.
- **Verification blocker:** current branch has no build/test run; lost-terminal and adversarial cross-language runtime traces still belong to review/HARD-017.
- **Evidence:** [HARD-013 implementation record](development/sessions/2026-09-18-hard-013.md).

<a id="hard-014"></a>
## HARD-014 — Make desktop hashing/storage bounded and shared

- **Milestone / status:** M2 / REVIEW_REQUIRED.
- **Dependencies:** HARD-011.
- **Implementation state:** Sender SHA-256 is computed incrementally in the same bounded streaming pass that transmits data; receiver hashing is incremental, declared sizes and STREAM_END counts are enforced, failures purge quarantine state, safe relative paths flow through the shared sanitizer and successful items commit through `QuarantineStagingStore`.
- **Tests prepared:** bounded allocation regression with a 32 MiB source plus ordinary shared-engine empty/multi-file/cancel/reject coverage.
- **Verification/manual gaps:** no current-head build/test evidence; required 1 GB/5+ GB, disk-full, duplicate-name and reparse-point scenarios remain part of the later verification matrix.
- **Evidence:** [HARD-014 implementation record](development/sessions/2026-09-18-hard-014.md).

<a id="hard-015"></a>
## HARD-015 — Complete desktop consent/history/settings/state

- **Milestone / status:** M2 / REVIEW_REQUIRED.
- **Dependencies:** HARD-013, HARD-014.
- **Implementation state:** WPF now presents an explicit authenticated incoming-offer accept/reject modal, persists non-secret receive settings and verified history through `DesktopStateStore`, rotates receive invitations, uses the configured receive directory and marshals interaction callbacks onto the UI dispatcher. Interactive CLI receive also requires explicit offer consent.
- **Tests prepared:** state restart persistence/no-secret persistence, offer rejection before commit and shared-engine history callbacks.
- **Verification/manual gaps:** WPF compile/UI journey, restart UI rendering, keyboard/accessibility and partial-batch behavior need executable/manual evidence.
- **Evidence:** [HARD-015 implementation record](development/sessions/2026-09-18-hard-015.md).

<a id="hard-016"></a>
## HARD-016 — Harden Linux CLI/daemon receiving lifecycle

- **Milestone / status:** M2 / IN_PROGRESS.
- **Dependencies:** HARD-013, HARD-014; persistent peer identity binding is completed by HARD-020/HARD-021.
- **Implemented now:** CLI send requires an authenticated pairing invitation; interactive receive asks before accepting an offer; daemon startup fails closed unless `--accept-paired-offers` is explicitly supplied; daemon policy has a configured destination and byte ceiling; invitations rotate before expiry; Ctrl+C/SIGTERM cancellation is bounded through linked cancellation tokens.
- **Remaining acceptance gap:** current initial-pairing profile authenticates the receiver but does not yet give the receiver a persistent cryptographic sender identity, so headless authorization cannot honestly be called peer-specific. HARD-020/HARD-021 must bind this policy to an enrolled peer before HARD-016 can complete.
- **Tests prepared:** daemon-without-explicit-policy fails closed; invitation/no-authority rejection; authenticated receive behavior.
- **Evidence:** [HARD-016 implementation record](development/sessions/2026-09-18-hard-016.md).

<a id="hard-017"></a>
## HARD-017 — Prove native cross-platform interoperability

- **Milestone / status:** M2 / BLOCKED.
- **Dependencies:** HARD-012, HARD-013, HARD-014, HARD-015, HARD-016.
- **Acceptance:** Android↔Windows, Android↔Linux and Windows↔Linux pass both directions with multi-page/multi-file batches; failed items cannot yield successful batch status.
- **Tests/evidence:** Empty/large/mixed files, reject/cancel, wrong authentication and reconnect boundaries with real platform/build identities, hashes and logs.

<a id="hard-018"></a>
## HARD-018 — Finalize identity/resume protocol extension

- **Milestone / status:** M3 / REVIEW_REQUIRED (pre-gate implementation).
- **Dependencies:** HARD-017 remains BLOCKED; this source work does not open the M3 release gate.
- **Implementation state:** `identity-v1`/`resume-v1` now have explicit versioned control schemas; canonical binary signature transcripts bind active/original session, peer fingerprints, roles, fresh nonces, negotiated capability hash and an active secure-channel binding. Kotlin and .NET implementations share `protocol/fixtures/identity_resume_v1_vectors.json`. Raw nonce-only signing was removed. `resume-v1` requires `identity-v1`, and neither trust extension is advertised by default before HARD-019–021.
- **Additional repair:** desktop HELLO/HELLO_ACK capability negotiation was corrected to `stream-v1` + `sha256` with `folder-v1` optional instead of the incompatible empty/`lan-v1` shape.
- **Tests prepared:** shared Kotlin/.NET golden bytes; version/profile validation; role swap; capability downgrade/dependency; changed known key; malformed canonical hashes/signatures; invalid resume offset/chunk-count math.
- **Verification blocker:** GitHub Actions still schedules no current-head run/check, and HARD-017 physical/native interoperability is unproven. No compile/test or runtime channel-binding claim is made.
- **Evidence:** [HARD-018 protocol preparation](development/sessions/2026-09-18-hard-018.md).

<a id="hard-019"></a>
## HARD-019 — Integrate Android persistent identity/trust

- **Milestone / status:** M3 / REVIEW_REQUIRED (pre-gate implementation).
- **Dependencies:** HARD-018 is REVIEW_REQUIRED; HARD-017 remains blocked, so this does not open trusted-session production capability negotiation.
- **Implementation state:** Android Keystore implements the platform-neutral persistent-identity port; Room implements the trusted-peer store; `PersistentTrustManager` owns initialization, verified enrollment, duplicate refresh, revocation, key rotation and restored-DB-without-key invalidation. `TransferRuntime` exposes trust state plus explicit approve/reject/revoke operations. Settings uses the runtime trust authority in production, and Transfer UI contains an explicit verified-device enrollment prompt.
- **Safety state:** UI/discovery code cannot manufacture `VerifiedPeerIdentity`; enrollment requires successful HARD-018 transcript verification. Trust never auto-accepts file offers. `identity-v1`/`resume-v1` remain disabled by default until HARD-021.
- **Tests prepared:** key loss/database restore, rotation, duplicate enrollment, decline, revoke, wrong approval fingerprint, channel-binding replay failure, runtime service-independent trust management and Settings runtime authority.
- **Verification blocker:** current branch head has no GitHub Actions/check suite; real Android Keystore/device evidence is still required.
- **Evidence:** [HARD-019 Android trust preparation](development/sessions/2026-09-18-hard-019.md).

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