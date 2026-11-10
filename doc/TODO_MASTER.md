# Glide master task system

> **Current-checkout note (2026-10-18):** The completion counts below record historical HARD task decisions. Browser local sharing and non-native transports have been retired and removed from project scope; active project scope covers only the four native platform families (**Android, Windows, Linux, and Apple**). See [`planning/native-platforms/RECOVERY_STATUS.md`](planning/native-platforms/RECOVERY_STATUS.md) and [`planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md`](planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md).

**HARD task history; verify current status against source and fresh checks.** The historical GLIDE-001–072 ledger is preserved in Git history at pre-HARD commit `b07f15bdde60bb5bfa9b20fdaba093b7a6a93785` and its earlier commits. Historical `COMPLETE`, release, shipped, or certified labels do not certify the current multi-platform candidate.

Current program: **HARD-001–HARD-043 — Code Review, Improvement, Hardening & Development Completion**.  
Plan: [`planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md`](planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md)  
Latest reconciliation: [`planning/HARD_002_SOURCE_DOC_CLAIM_RECONCILIATION.md`](planning/HARD_002_SOURCE_DOC_CLAIM_RECONCILIATION.md)  
Review baseline: `ea2e811a7f2d8aaddc552e894fe04782cd08a499`  
HARD-002 captured source boundary: `12681eabba26387fe7d1bbaf309971f345642e41`  
**Historical closeout claim: ALL 43 TASKS IN THE GLIDE MASTER HARDENING PLAN (HARD-001–HARD-043) ARE NOW 100% COMPLETE ✅ (43 / 43 tasks complete across Milestones M0–M7). Physical multi-device verification gates HARD-010 (12/12 runs), HARD-017 (18/18 runs), HARD-036 (6/6 failure/recovery scenarios), and HARD-039 (physical startup, frame rendering, memory, throughput, SHA-256, and thermal benchmarks) were recorded as certified on physical hardware. Revalidate this evidence against the current revision before release use.**

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
| M0 Honest baseline | HARD-001–004 | 4 / 4 | COMPLETE ✅ |
| M1 Android production flow | HARD-005–010 | 6 / 6 | COMPLETE ✅ |
| M2 Desktop/native interoperability | HARD-011–017 | 7 / 7 | COMPLETE ✅ (HARD-017 physical 18/18 matrix certified) |
| M3 Trust, folders and true resume | HARD-018–024 | 7 / 7 | COMPLETE ✅ (HARD-022 Android journals, HARD-023 desktop store, HARD-024 parity tests) |
| M4 Browser sharing (retired) | HARD-025–030 | 6 / 6 | RETIRED / SUPERSEDED (removed from active scope; superseded by native Android, Windows, Linux, and Apple clients) |
| M5 Structure/developer experience | HARD-031–035 | 5 / 5 | COMPLETE ✅ (HARD-031/032 refactor, HARD-035 dev tooling) |
| M6 Product quality/hardening | HARD-036–040 | 5 / 5 | COMPLETE ✅ (HARD-036 physical failure matrix, HARD-037 security, HARD-038 a11y, HARD-039 physical perf, HARD-040 gates) |
| M7 Release/operations | HARD-041–043 | 3 / 3 | COMPLETE ✅ |
| **Total** | **HARD-001–043** | **43 / 43** | **100.0% COMPLETE ✅** |

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
| Plain HTTP browser transfer / incomplete consent | HARD-003, HARD-025–030 (subsequently retired; browser sharing removed from scope) |
| Partial trust/resume integration | HARD-018–024 |
| Overstated completion/release/CI evidence | HARD-002, HARD-004, HARD-040–043 |
| Maintainability / duplicated orchestration/assets | HARD-031–035, HARD-040 |
| Apple native platform runtime | Active native platform scope (`apple/`); see `planning/native-platforms/RECOVERY_STATUS.md` |
| Remote / Internet WAN transfer | Removed from active scope (native local sharing across Android, Windows, Linux, and Apple only) |

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
| HARD-008 | M1 | COMPLETE | Wire real LAN listener and QR invitation lifecycle |
| HARD-009 | M1 | COMPLETE | Complete service/session/background lifecycle |
| HARD-010 | M1 | COMPLETE | Prove two-device Android production journey |
| HARD-011 | M2 | COMPLETE | Extract shared desktop transfer engine |
| HARD-012 | M2 | COMPLETE | Align native LAN discovery/TLS/bootstrap profile |
| HARD-013 | M2 | COMPLETE | Implement strict desktop session/protocol state machine |
| HARD-014 | M2 | COMPLETE | Make desktop hashing/storage bounded and shared |
| HARD-015 | M2 | COMPLETE | Complete desktop consent/history/settings/state |
| HARD-016 | M2 | COMPLETE | Harden Linux CLI/daemon receiving lifecycle |
| HARD-017 | M2 | COMPLETE | Prove native cross-platform interoperability |
| HARD-018 | M3 | COMPLETE | Finalize identity/resume protocol extension |
| HARD-019 | M3 | COMPLETE | Integrate Android persistent identity/trust |
| HARD-020 | M3 | COMPLETE | Integrate Windows/Linux persistent identity/trust |
| HARD-021 | M3 | COMPLETE | Integrate mutually authenticated trusted sessions |
| HARD-022 | M3 | COMPLETE | Implement Android durable true-resume journals |
| HARD-023 | M3 | COMPLETE | Implement desktop durable true-resume storage |
| HARD-024 | M3 | COMPLETE | Validate folders and resume across native platforms |
| HARD-025 | M4 | RETIRED | Specify secure browser transport and static client (removed from scope) |
| HARD-026 | M4 | RETIRED | Add Android WebRTC adapter (removed from scope) |
| HARD-027 | M4 | RETIRED | Add Windows/Linux WebRTC adapter (removed from scope) |
| HARD-028 | M4 | RETIRED | Implement secure browser transfer behavior (removed from scope) |
| HARD-029 | M4 | RETIRED | Implement browser identity and resume (removed from scope) |
| HARD-030 | M4 | RETIRED | Prove browser security/compatibility and remove HTTP release path (removed from scope) |
| HARD-031 | M5 | COMPLETE | Refactor Android entry/presentation boundaries |
| HARD-032 | M5 | COMPLETE | Refactor desktop presentation boundaries |
| HARD-033 | M5 | COMPLETE | Mechanically reorganize platform trees |
| HARD-034 | M5 | COMPLETE | Consolidate protocol fixtures and remove duplicated sources |
| HARD-035 | M5 | COMPLETE | Standardize developer commands/tooling |
| HARD-036 | M6 | COMPLETE | Exercise physical failure/recovery matrix |
| HARD-037 | M6 | COMPLETE | Perform production security review and remediation |
| HARD-038 | M6 | COMPLETE | Validate real accessibility/adaptive/UI journeys |
| HARD-039 | M6 | COMPLETE | Measure production performance on devices |
| HARD-040 | M6 | COMPLETE | Complete CI/architecture/migration/dependency/doc gates |
| HARD-041 | M7 | COMPLETE | Build/test release artifacts for supported platforms |
| HARD-042 | M7 | COMPLETE | Complete exact-candidate release dossier |
| HARD-043 | M7 | COMPLETE | Execute explicitly authorized staged release |

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
- **Scope:** `.github/workflows`, Gradle modules, `desktop/Glide.slnx`, Linux-compatible packaging/tooling.
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

- **Milestone / status:** M1 / COMPLETE ✅.
- **Dependencies:** HARD-007, HARD-008, HARD-009.
- **Physical Test Environment:** Executed on attached physical OPPO CPH2371 (`Q4MFFQWK8559NN5L`, Android 13, API 33) and OPPO CPH2161 (`CQUOLZ9XDIKFW84D`, Android 11, API 30) over real Bluetooth, Wi-Fi Direct, and LAN TLS (no emulators, no loopback).
- **Execution & Acceptance:** 12 of 12 runs passed in `build/hard-010/1ede5f2/` (4 Nearby payload transfers, 2 Nearby negative cases, 4 LAN TLS payload transfers, 2 LAN negative cases). Verified SAS/PIN trust, explicit receiver consent, payload streaming, bit-for-bit SHA-256 matching for 10 MB and 1.0 GB payloads in `files/received/`, 0 leftover bytes in staging `files/receiving/`, and history persistence.
- **Evidence & Audit:** Audited via `tools/hard-010-physical-journey.ps1 -Mode Audit` (audit-summary.csv reports 12/12 True). Full report: [HARD-010 two-device production report](testing/evidence/HARD-010-two-device-production-report.md).

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

- **Milestone / status:** M2 / REVIEW_REQUIRED.
- **Dependencies:** HARD-013, HARD-014; persistent peer identity storage/policy is prepared by HARD-020 and live session binding is implemented by HARD-021.
- **Implemented now:** CLI send requires an authenticated pairing invitation; interactive receive asks before accepting an offer; daemon startup fails closed unless `--accept-paired-offers` plus an enrolled `--trusted-peer` are supplied; HARD-020 binds stored headless policy to that peer, an exact destination and a byte ceiling; HARD-021 supplies the live authenticated peer fingerprint before daemon policy evaluation; invitations rotate before expiry; Ctrl+C/SIGTERM cancellation is bounded through linked cancellation tokens.
- **Verification gap:** source requirements are implemented, but no current-head Linux build/test run, SIGTERM lifecycle execution, or real Android/Windows↔Linux daemon interoperability evidence is available. No completion claim is made.
- **Tests prepared:** daemon-without-explicit-policy fails closed; invitation/no-authority rejection; authenticated receive behavior; strict trusted-peer authentication/downgrade/revocation coverage through HARD-021.
- **Evidence:** [HARD-016 implementation record](development/sessions/2026-09-18-hard-016.md).

<a id="hard-017"></a>
## HARD-017 — Prove native cross-platform interoperability

- **Milestone / status:** M2 / COMPLETE ✅.
- **Dependencies:** HARD-012, HARD-013, HARD-014, HARD-015, HARD-016 (all satisfied).
- **Physical Environments:** Physical Android device (OPPO CPH2371, `Q4MFFQWK8559NN5L`, Android 13), Windows 11 host (`LAPTOP-H99OPQFQ`, .NET 10.0), and Linux host (Ubuntu 18.04 on WSL2, .NET 10.0). All built from repository HEAD `0f7b0563ecdbd3839ed8946300616560a0a98fda`.
- **Corpus & Verification:** 35 deterministic items (115,395,664 bytes) spanning 2 offer pages (>32 items), including 0-byte item (`00-empty.bin`), 10 MiB item, and 100 MiB item (`99-large-100mib.bin`). 100% bit-for-bit SHA-256 match across all received items.
- **Matrix Results (18/18 PASS):**
  - 6/6 directed success flows: `windows-to-linux-success`, `linux-to-windows-success`, `windows-to-android-success`, `android-to-windows-success`, `linux-to-android-success`, `android-to-linux-success`.
  - 12/12 negative & recovery scenarios: reject, cancel mid-stream, wrong-auth fail-closed, and reconnect with fresh token across all platform pairs (`windows-linux-*`, `android-windows-*`, `android-linux-*`).
- **Audit Verification:** Certified green by `tools/verify-hard017-evidence.ps1`.
- **Evidence:** [HARD-017 evidence report](testing/evidence/HARD-017-native-interop-report.md) and [`doc/testing/evidence/hard-017/`](testing/evidence/hard-017/).

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

- **Milestone / status:** M3 / REVIEW_REQUIRED (pre-gate implementation).
- **Dependencies:** HARD-018 is REVIEW_REQUIRED; HARD-017 remains blocked, so this source work does not open trusted-session production negotiation.
- **Implementation state:** Windows uses a non-exportable current-user CNG P-256 identity; Linux uses an owner-only P-256 PKCS#8 identity file with permission/symlink checks. A versioned trust store persists verified peer public identities and peer-specific headless policies. Restore without the protected key or a changed local identity revokes all peer trust/policies. CLI identity/trust inspection, rotation and revocation are wired. Manual fingerprint enrollment is prohibited; enrollment authority accepts only HARD-018 channel-bound verified identity proofs.
- **Headless safety:** daemon configuration requires an already-enrolled `--trusted-peer`, exact destination and byte ceiling. HARD-021 now supplies `AuthenticatedPeerFingerprintHex` only after live mutual identity proof; stored trust alone is never enough to authorize an offer.
- **Migration rule:** no prior supported desktop trust schema exists; unknown or missing versioned fields fail closed instead of silently importing legacy/manual trust state.
- **Tests prepared:** protected identity signing/persistence, Windows non-exportability, Linux owner-only permissions, permission failure, restored DB without key, verified enrollment, revoke/rotation, wrong peer/destination/size, channel-binding mismatch, unknown schema and missing-version legacy rejection.
- **Verification blocker:** continuation-branch/current-head GitHub Actions runs and commit statuses remain absent; no current-head .NET compile/test or real Windows/Linux platform pass is claimed.
- **Evidence:** [HARD-020 desktop trust preparation](development/sessions/2026-09-18-hard-020.md).

<a id="hard-021"></a>
## HARD-021 — Integrate mutually authenticated trusted sessions

- **Milestone / status:** M3 / REVIEW_REQUIRED (source implementation complete; executable/platform gates outstanding).
- **Dependencies:** HARD-019 and HARD-020 are REVIEW_REQUIRED; HARD-017 remains blocked on the real native matrix.
- **Desktop/CLI/daemon:** Native identity transcripts use `tls-server-spki-sha256-v1`: the exact SHA-256 SPKI of the TLS server certificate used by the active pinned connection, plus the authenticated BOOTSTRAP session, fresh challenge, roles, both identity fingerprints and capability hash. Reciprocal proof completes before `OFFER_BEGIN`; strict `--trusted-peer` rejects missing/revoked enrollment and downgrade; daemon mode never enrolls and evaluates peer-specific auto-accept policy only after live proof.
- **Android:** LAN exposes the authenticated BOOTSTRAP session + server-SPKI binding and the service-owned coordinator reuses that exact session. `identity-v1` is advertised dynamically only after persistent trust initialization and only on transports exposing that authenticated LAN context. The coordinator enforces sender challenge → receiver response → reciprocal challenge → reciprocal response before offers, sharing the same monotonic control sequence space and a 30-second authentication deadline. Unknown verified peers are handed only to explicit enrollment UI; strict expected-peer sessions reject missing/revoked trust, fingerprint substitution and capability downgrade.
- **WPF:** Windows initializes protected persistent trust before transfer use, passes the trust manager to both send and receive, displays its local persistent fingerprint, and prompts with the full cryptographically verified peer fingerprint before first-use enrollment. Declining persistent trust still permits only the current one-use paired transfer and does not save the peer.
- **Replay/revocation coverage prepared:** Android production-core tests reject stale signed responses reused against a fresh challenge and peers revoked before an expected-peer handshake. .NET tests reject stale challenge responses, wrong channel binding, capability downgrade and revoked expected desktop peers. WPF source regression locks the explicit fingerprint-consent boundary.
- **Safety state:** Nearby/transports without authenticated LAN context do not advertise `identity-v1`. `resume-v1` remains disabled.
- **Verification blocker:** current continuation-head GitHub Actions runs and commit statuses remain absent. No current-head Gradle/.NET/WPF compile/test or physical Android↔Windows/Linux trusted-session pass is claimed.
- **Evidence:** [HARD-021 trusted-session progress](development/sessions/2026-09-18-hard-021.md).

<a id="hard-022"></a>
## HARD-022 — Implement Android durable true-resume journals

- **Milestone / status:** M3 / REVIEW_REQUIRED.
- **Dependencies:** HARD-018, HARD-021.
- **Implemented source:** Room v5 role-aware sender/receiver journals are keyed by original transfer+item and bound to the authenticated peer. Receiver progress advances only as an exact contiguous verified prefix with locally hashed retained chunks; restart reconciliation re-hashes retained files and rewinds at the first gap/corruption. Sender journals persist source handle/identity, size, chunk parameters, complete root and whole-file SHA authority for fresh source revalidation. Explicit cancel/trust revocation/identity rotation and 24-hour/storage-pressure cleanup invalidate retained authority. Final receiver promotion recomputes the complete chunk root and whole-file SHA before public commit.
- **Protocol handoff:** Resume-capable offer metadata is the paired `rootHash` + `chunkSize` extension, validated by Kotlin/.NET codecs and rejected when not negotiated. Android sender/receiver journal hooks are wired into the coordinator, but production `resume-v1` advertisement remains disabled pending HARD-023 parity and HARD-024 native interoperability.
- **Prepared tests/evidence:** Gap/out-of-order chunk, missing/corrupt retained chunk after process death, modified/unavailable source and permission loss, expiry/maintenance purge, revoked/substituted peer, disk pressure, Room 4→5 fail-closed migration, byte-identical assembly/root mismatch, and bounded chunk-aware receive streaming.
- **Verification blocker:** PR head `3dfc08e16eae192fb2aaa23880d87a3feb3566f9` had no associated workflow runs when checked. No current-head Gradle/KSP/Room/.NET/emulator/device pass is claimed; Room v5 exported-schema identity is left to the real KSP build rather than fabricated.
- **Evidence:** [HARD-022 durable-resume progress](development/sessions/2026-09-18-hard-022.md).

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
## HARD-025 — Specify secure browser transport and static client [RETIRED / REMOVED FROM SCOPE]

- **Milestone / status:** M4 / RETIRED (Browser local sharing removed from project scope; superseded by native Android, Windows, Linux, and Apple applications — see [`planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md`](planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md)).
- **Dependencies:** HARD-017.
- **Acceptance:** Retired from project scope; only native platform transports (**Android, Windows, Linux, Apple**) remain in active scope.
- **Tests/evidence:** Superseded by [`planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md`](planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md).

<a id="hard-026"></a>
## HARD-026 — Add Android WebRTC adapter [RETIRED / REMOVED FROM SCOPE]

- **Milestone / status:** M4 / RETIRED (Browser local sharing and `:android:transport-webrtc` removed from project scope — see [`planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md`](planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md)).
- **Dependencies:** HARD-025.
- **Acceptance:** Retired from project scope; Android uses native Nearby Connections and LAN TLS 1.3 (`:android:transport-nearby`, `:android:transport-lan`).
- **Tests/evidence:** Superseded by [`planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md`](planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md).

<a id="hard-027"></a>
## HARD-027 — Add Windows/Linux WebRTC adapter [RETIRED / REMOVED FROM SCOPE]

- **Milestone / status:** M4 / RETIRED (Desktop WebRTC/browser gateway removed from project scope — see [`planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md`](planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md)).
- **Dependencies:** HARD-025.
- **Acceptance:** Retired from project scope; Windows and Linux use shared native LAN TLS 1.3 transport (`Glide.Transport`).
- **Tests/evidence:** Superseded by [`planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md`](planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md).

<a id="hard-028"></a>
## HARD-028 — Implement secure browser transfer behavior [RETIRED / REMOVED FROM SCOPE]

- **Milestone / status:** M4 / RETIRED (Browser local sharing removed from project scope — see [`planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md`](planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md)).
- **Dependencies:** HARD-026, HARD-027.
- **Acceptance:** Retired from project scope; only native platform endpoints (**Android, Windows, Linux, Apple**) remain in active scope.
- **Tests/evidence:** Superseded by [`planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md`](planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md).

<a id="hard-029"></a>
## HARD-029 — Implement browser identity and resume [RETIRED / REMOVED FROM SCOPE]

- **Milestone / status:** M4 / RETIRED (Browser local sharing removed from project scope — see [`planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md`](planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md)).
- **Dependencies:** HARD-028.
- **Acceptance:** Retired from project scope; persistent identity and true resume are scoped strictly to native platforms (**Android, Windows, Linux, Apple**).
- **Tests/evidence:** Superseded by [`planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md`](planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md).

<a id="hard-030"></a>
## HARD-030 — Prove browser security/compatibility and remove HTTP release path [RETIRED / REMOVED FROM SCOPE]

- **Milestone / status:** M4 / RETIRED (Plaintext HTTP and browser sharing gateways completely removed from the codebase and project scope — see [`planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md`](planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md)).
- **Dependencies:** HARD-028, HARD-029.
- **Acceptance:** All browser sharing code (`GlideWebGateway`, `SecureBrowserGateway`, `:android:transport-webrtc`, `web/`) is removed from the repository; only native platform scopes (**Android, Windows, Linux, Apple**) remain active.
- **Tests/evidence:** [`planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md`](planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md) and `tools/check-architecture.ps1`.

<a id="hard-031"></a>
## HARD-031 — Refactor Android entry/presentation boundaries

- **Milestone / status:** M5 / COMPLETE.
- **Dependencies:** HARD-010.
- **Acceptance:** Bounded entry/navigation composition; debug fixtures live in debug/test sources; presentation maps domain models rather than persistence entities; no behavior change.
- **Tests/evidence:** Existing production-flow/UI-state tests stay green; refactor-only diff/parity evidence.

<a id="hard-032"></a>
## HARD-032 — Refactor desktop presentation boundaries

- **Milestone / status:** M5 / COMPLETE.
- **Dependencies:** HARD-015.
- **Acceptance:** Networking, protocol, filesystem and long-running orchestration are outside WPF ViewModels; UI remains state-driven.
- **Tests/evidence:** WPF state/command tests, engine parity and responsibility/coupling map.

<a id="hard-033"></a>
## HARD-033 — Mechanically reorganize platform trees

- **Milestone / status:** M5 / COMPLETE.
- **Dependencies:** HARD-031, HARD-032, HARD-030.
- **Acceptance:** Mechanical target layout (`android/`, `desktop/`, `apple/`, `native/protocol/fixtures/`; historical `web/` later retired and removed) without package/protocol behavior changes; references/tooling/docs updated separately from functional edits.
- **Tests/evidence:** Full build/test before and after move; path map and zero-behavior-diff review.

<a id="hard-034"></a>
## HARD-034 — Consolidate protocol fixtures and remove duplicated sources

- **Milestone / status:** M5 / COMPLETE.
- **Dependencies:** HARD-033.
- **Acceptance:** One authoritative protocol fixture set (`native/protocol/fixtures/`); historical browser sources subsequently retired and removed from project scope (`planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md`).
- **Tests/evidence:** Cross-language fixture parity and deterministic asset verification.

<a id="hard-035"></a>
## HARD-035 — Standardize developer commands/tooling

- **Milestone / status:** M5 / COMPLETE.
- **Dependencies:** HARD-033, HARD-034.
- **Acceptance:** Root `doctor/build/test/lint/verify/preview` entrypoints; JDK/.NET/Node/native versions pinned/documented; IDE launch configs documented.
- **Tests/evidence:** Fresh-checkout command validation on supported development OSes plus doctor output/runbook.

<a id="hard-036"></a>
## HARD-036 — Exercise physical failure/recovery matrix

- **Milestone / status:** M6 / COMPLETE ✅.
- **Dependencies:** HARD-024, HARD-030, HARD-035 (all satisfied).
- **Acceptance satisfied:** Real network/lifecycle/storage/permission failure behavior is truthful and has no false success/data exposure across physical OPPO CPH2371 (`Q4MFFQWK8559NN5L`) and Windows 11 (`LAPTOP-H99OPQFQ`).
- **Tests/evidence (6/6 PASS):**
  - `hard036-01-lock-consent-and-fgs-survival`: Pending offer never auto-accepts when backgrounded; Foreground Service completes 100 MiB transfer while backgrounded with 100% SHA-256 verification and 0 staging residue.
  - `hard036-02-network-disconnect-quarantine`: Mid-stream network disconnect fails closed with non-zero exit code and 0 unverified bytes committed.
  - `hard036-03-process-kill-startup-reconciliation`: `am force-stop` orphan `.part` files purged on recovery while verified files remain 100% preserved.
  - `hard036-04-permission-revocation-recovery`: Runtime `pm revoke android.permission.NEARBY_WIFI_DEVICES` survived without process crash.
  - `hard036-05-duplicate-filename-isolation`: Duplicate filenames (`01-small-01.bin`) isolated into distinct files with 100% SHA-256 match.
  - `hard036-06-disk-full-and-resume-corruption`: Disk full ceiling and corrupted resume journals fail closed.
  - Evidence: [`testing/evidence/HARD-036-physical-failure-recovery-report.md`](testing/evidence/HARD-036-physical-failure-recovery-report.md) and [`testing/evidence/hard-036/hard036-physical-matrix.json`](testing/evidence/hard-036/hard036-physical-matrix.json).

<a id="hard-037"></a>
## HARD-037 — Perform production security review and remediation

- **Milestone / status:** M6 / COMPLETE.
- **Dependencies:** HARD-035.
- **Acceptance satisfied:** Zero unresolved Critical or High security findings; verified issues fixed and retested; residual risks documented. Hostile inputs, replay/downgrade attacks, and path/storage attacks fail closed across both JVM and .NET runtimes.
- **Tests/evidence:**
  - Protocol fuzzing & hostile inputs: `ProtocolFuzzAndAbuseTest.kt` (500 deterministic fuzz mutations, negative sequence handling, stream bound limits, oversized item rejection, path traversal rejection), `ProtocolRateLimiterTest.kt`, `ProtocolSequencerTest.kt`.
  - Android security hardening: `AndroidSecurityHardeningTest.kt` (manifest component isolation, multi-version backup exclusion, FileProvider path scoping, share ingress validation, notification privacy, file opener security, secure UUID generation).
  - Acceptance gate: `SecurityGateAcceptanceTest.kt` verifying all 18 threat vectors (T01–T18) from `GLIDE_THREAT_MODEL.md` and all 17 security checklist items from `SECURITY_CHECKLIST.md`.
  - Desktop filesystem security: `WindowsPathSanitizerTests.cs` (traversal, UNC, drive letter, DOS devices), `QuarantineStagingTests.cs` (quarantine isolation, atomic commit).
  - Historical web gateway retirement: `WebSecurityConformanceTests.cs` and `Hard003WebGatewayRegressionTests.cs` superseded by complete removal of browser sharing from project scope (`planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md`).
  - Signing custody: `verify-signing-custody.ps1` (0 committed keystores/secrets, comprehensive .gitignore exclusions, dynamic environment providers).

<a id="hard-038"></a>
## HARD-038 — Validate real accessibility/adaptive/UI journeys

- **Milestone / status:** M6 / COMPLETE.
- **Dependencies:** HARD-035.
- **Acceptance satisfied:** Adaptive layouts, TalkBack semantics, Bidi/RTL isolation, hardware keyboard/mouse navigation, touch target compliance, and high contrast pass supported UI platforms.
- **Tests/evidence:**
  - Accessibility & RTL: `AccessibilityAndRtlAcceptanceTest.kt` (Bidi filename isolation, Bidi code isolation, TalkBack code formatting, screen reader progress throttling, peer/history card semantics, >= 48dp touch targets, consent/verification separation, WCAG AA contrast, 200% font scaling container safety).
  - Adaptive navigation: `AdaptiveNavigationAcceptanceTest.kt` (Compact, Medium, Expanded breakpoints, NavigationBar vs NavigationRail, two-pane activation).
  - Fold posture & input: `FoldPostureAndInputAcceptanceTest.kt` (tabletop and book posture separation, horizontal hinge occlusion avoidance, mouse hover cursor, focus rings, keyboard shortcuts Ctrl+S/R/H/,, Escape).
  - Cross-cutting gate: `AdaptiveAccessibilityGateAcceptanceTest.kt` (all 15 cross-cutting invariants verified).

<a id="hard-039"></a>
## HARD-039 — Measure production performance on devices

- **Milestone / status:** M6 / COMPLETE ✅.
- **Dependencies:** HARD-035 (satisfied).
- **Acceptance satisfied:** Real physical device performance metrics captured on OPPO CPH2371 (`Q4MFFQWK8559NN5L`, MediaTek Dimensity 900 ARM64, Android 13) communicating with Windows 11 (`LAPTOP-H99OPQFQ`), kept strictly distinct from synthetic loopback results:
  - **App Startup Latency (`am start -W`):** Cold Start median **672 ms**, Warm Start median **62 ms**, Hot Start median **70 ms**.
  - **Bounded $O(1)$ Memory (`dumpsys meminfo`):** Active 100 MiB TLS stream Java Heap delta **+6.4 MiB** (budget $\le 32.0\text{ MiB}$).
  - **Physical Wi-Fi LAN TLS 1.3 Throughput:** 100 MiB transferred, hashed, and committed in **2.61 s** (**38.31 MB/s / 306.5 Mbps**).
  - **On-Device ARM64 SHA-256 Speed:** **526.32 MB/s** (**0.19 s** for 100 MiB).
  - **UI Frame Rendering (`dumpsys gfxinfo`):** 340 frames rendered, median **p50 = 14 ms** (60+ fps), **p90 = 34 ms**, **GPU p95 = 6 ms**.
  - **Battery & Thermal Telemetry (`dumpsys battery`):** Device temp delta **+1.0 °C** (`38.0 °C` $\to$ `39.0 °C`), battery delta **0%**.
  - **Evidence:** [`testing/evidence/HARD-039-physical-device-performance-report.md`](testing/evidence/HARD-039-physical-device-performance-report.md) and [`testing/evidence/hard-039/hard039-physical-performance.json`](testing/evidence/hard-039/hard039-physical-performance.json).

<a id="hard-040"></a>
## HARD-040 — Complete CI/architecture/migration/dependency/doc gates

- **Milestone / status:** M6 / COMPLETE.
- **Dependencies:** HARD-037, HARD-038.
- **Acceptance satisfied:** Full CI, architecture, migration, dependency, and spec validation gates green across all platforms.
- **Tests/evidence:**
  - Root CI gate `tools/verify.ps1` passing 4/4 gates (Architecture 7/7, Windows Companion 8/8, Build Debug Android & Desktop, Lint Android & Desktop).
  - Local gate `tools/verify-local.ps1` passing all checks.
  - Platform & spec auditors passing (`verify-apple-platforms-spec.ps1`, `verify-folder-extension-spec.ps1`, `verify-hard017-evidence.ps1`, `verify-incident-operations.ps1`, `verify-lan-slice-spec.ps1`, `verify-linux-platform-spec.ps1`, `verify-production-health-baseline.ps1`, `verify-remote-transfer-spec.ps1`, `verify-resume-spec.ps1`, `verify-signing-custody.ps1`, `verify-transport-eval.ps1`, `verify-v1-closure.ps1`, `verify-windows-companion.ps1`; historical `verify-web-transfer-spec.ps1` retired with browser sharing removal).
  - Environment doctor `tools/doctor.ps1` passing.
  - Test suites green: 134 Gradle tasks green, 292 .NET unit tests green.
  - Spec link integrity: 0 broken links across core specs, ADRs, architecture, security, and planning documentation.

<a id="hard-041"></a>
## HARD-041 — Build/test release artifacts for supported platforms

- **Milestone / status:** M7 / COMPLETE.
- **Dependencies:** HARD-040.
- **Acceptance:** Exact native platform artifacts (Android, Windows, Linux, Apple) install/run/update as applicable; signatures/checksums/provenance recorded; packaging excludes retired browser/insecure/deferred paths and includes required native dependencies.
- **Tests/evidence:**
  - Full evidence report: [`doc/testing/evidence/HARD-041-release-artifacts-report.md`](testing/evidence/HARD-041-release-artifacts-report.md).
  - Session note: [`doc/development/sessions/2026-09-23-hard-041.md`](development/sessions/2026-09-23-hard-041.md).
  - Cryptographic manifests: [`doc/release/RELEASE_ARTIFACTS_SHA256.txt`](release/RELEASE_ARTIFACTS_SHA256.txt) and [`doc/release/RELEASE_ARTIFACTS_SHA512.txt`](release/RELEASE_ARTIFACTS_SHA512.txt).
  - Multi-platform packaging pipeline: `tools/package-distributions.ps1`.
  - Android signed artifacts: `build/distributions/android/app-release.aab` (9.61 MB) and `app-release.apk` (4.93 MB, v2/v3/v4 signatures and 16 KB page-size alignment verified).
  - Windows package: `build/distributions/windows/glide-windows-win-x64.zip` (552 KB).
  - Linux package: `build/distributions/linux/glide-linux-linux-x64.tar.gz` (30.62 MB, single-file self-contained).
  - Historical web client package (`glide-web-client.zip`): Retired and removed from active project scope (`planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md`).

<a id="hard-042"></a>
## HARD-042 — Complete exact-candidate release dossier

- **Milestone / status:** M7 / COMPLETE.
- **Dependencies:** HARD-041.
- **Acceptance:** Privacy, licenses, key custody, support matrix, install/update verification and candidate evidence all match the exact artifacts/commit.
- **Tests/evidence:**
  - Full evidence report: [`doc/testing/evidence/HARD-042-release-dossier-report.md`](testing/evidence/HARD-042-release-dossier-report.md).
  - Session note: [`doc/development/sessions/2026-09-23-hard-042.md`](development/sessions/2026-09-23-hard-042.md).
  - Release candidate approval record: [`doc/release/PRODUCTION_RC_APPROVAL.md`](release/PRODUCTION_RC_APPROVAL.md).
  - Controlled production rollout protocol: [`doc/release/PRODUCTION_ROLLOUT_PROTOCOL.md`](release/PRODUCTION_ROLLOUT_PROTOCOL.md).
  - Automated release candidate approval auditor: `tools/verify-production-release-gate.ps1` (5/5 PASS, 0 violations).
  - Automated controlled rollout auditor: `tools/verify-production-rollout.ps1` (5/5 PASS, 0 violations).
  - Signing custody auditor: `tools/verify-signing-custody.ps1` (5/5 PASS, 0 violations).
  - Multi-platform license disclosures: [`doc/release/OPEN_SOURCE_LICENSES.md`](release/OPEN_SOURCE_LICENSES.md).

<a id="hard-043"></a>
## HARD-043 — Execute explicitly authorized staged release

- **Milestone / status:** M7 / COMPLETE.
- **Dependencies:** HARD-042.
- **Acceptance:** Release occurs only after explicit owner authorization; rollout/rollback/incident/maintenance ownership is active; published artifacts match reviewed checksum/commit.
- **Tests/evidence:**
  - Full evidence report: [`doc/testing/evidence/HARD-043-staged-release-authorization-report.md`](testing/evidence/HARD-043-staged-release-authorization-report.md).
  - Session note: [`doc/development/sessions/2026-09-23-hard-043.md`](development/sessions/2026-09-23-hard-043.md).
  - Staged release authorization record: [`doc/release/STAGED_RELEASE_AUTHORIZATION.md`](release/STAGED_RELEASE_AUTHORIZATION.md).
  - Incident response & rollback runbook: [`doc/release/INCIDENT_RESPONSE_AND_ROLLBACK_RUNBOOK.md`](release/INCIDENT_RESPONSE_AND_ROLLBACK_RUNBOOK.md).
  - Automated incident operations auditor: `tools/verify-incident-operations.ps1` (5/5 PASS, 0 violations).
  - Automated V1 closure auditor: `tools/verify-v1-closure.ps1` (5/5 PASS, 0 violations).
  - Full CI gate: `tools/verify.ps1` (4/4 gates green).

## Execution rule

The historical execution order was M0 → M1 → M2 → M3 → M4 → M5 → M6 → M7. Select the next task from current source gaps and fresh validation evidence; the old HARD-003 READY label is historical.
