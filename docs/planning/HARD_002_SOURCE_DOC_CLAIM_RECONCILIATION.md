# HARD-002 — Source, Documentation, Claim & Post-Review Reconciliation

**Task:** HARD-002  
**Date:** 2026-09-18  
**Review baseline:** `ea2e811a7f2d8aaddc552e894fe04782cd08a499`  
**Captured HARD-002 start HEAD:** `12681eabba26387fe7d1bbaf309971f345642e41`  
**Scope:** documentation/status reconciliation and source review only; no runtime behavior change.

## 1. Boundary and commit inventory

GitHub compare reported the captured HEAD as **18 commits ahead** of `ea2e811`.

The delta separates into:

- **8 substantive post-review source/configuration commits** reviewed by HARD-002;
- **9 HARD-001 documentation commits** that established the current hardening authority;
- **1 merge commit** (`12681eab...`) combining the concurrent source and HARD documentation histories without adding an independent feature claim.

### Post-review source/configuration commits reviewed

| Commit | Area | What exists | HARD-002 classification |
| --- | --- | --- | --- |
| `6f1aa69f` | Remote | `IceCandidate`, signaling messages/client, candidate gatherer, tests | Implemented components; historical automated-test evidence; no live ICE/signaling interoperability proof |
| `16ce6a92` | Remote | TURN packet/allocation helpers, quota/rate policy, metered-network abstractions, tests | Implemented components; historical automated-test evidence; not a complete authenticated TURN deployment |
| `c99ebd50` | Remote | `RemoteTransferSession` in-memory state/orchestration model and tests | Implemented orchestration model; not a live E2EE remote transfer path |
| `b07f15bd` | Apple | Swift package, invitations, LAN record codec, SAS/path helpers, tests | Implemented shared-library components; historical unit-test evidence |
| `6015a5e7` | Apple | transport constants, SPKI helper, Bonjour TXT parser/config, tests | Implemented helpers/config; no production `NWListener`/`NWConnection` runtime |
| `94ee7805` | Apple | quarantine streaming SHA-256/commit helper, media classifier, tests | Implemented storage component; product/recovery integration incomplete |
| `2c7b695c` | Apple | iOS lifecycle abstraction, theme tokens, share-batch helper, tests | Implemented helpers; no complete iOS app/Share Extension/runtime binding |
| `2e68d68b` | Apple/macOS | plist/entitlements, menu/drop coordinators, focused tests | Partial macOS components; default listener is in-memory, not a real network listener |

The merge commit `12681eabba26387fe7d1bbaf309971f345642e41` has parents `2e68d68b...` and `09d4dc4e...`; it is provenance, not a ninth feature delivery.

## 2. Source-review findings for the post-review delta

### Remote work

The remote code is useful groundwork but historical session wording such as “Step 6 100% complete” was broader than the source evidence.

1. `RemoteTransferSession` selects candidate types/priorities already present in memory. It does not itself perform RFC 8445 connectivity checks, nominate a proven pair, execute signaling, establish TURN, create an authenticated encrypted channel, or move Glide payload bytes end to end.
2. `IceCandidateGatherer`/STUN helpers provide candidate inputs, not an ICE checklist/nomination engine proven across NATs.
3. `TurnClient` builds/parses Allocate/CreatePermission/indication/channel messages, but the reviewed source contains no long-term credential authentication flow (`USERNAME`, `REALM`, `NONCE`, `MESSAGE-INTEGRITY`). It is therefore not sufficient for a normal authenticated TURN deployment.
4. The approved remote design requires E2EE and peer binding, but the reviewed post-baseline delta does not provide a production remote encrypted payload channel. The previous “Noise IK or mutual TLS” wording was a design choice still requiring one frozen construction before implementation can be release-ready.
5. The metered-network code is a domain abstraction/prompt policy. Native OS detector/UI integration across supported platforms is not established by that helper.

**Result:** Remote work is classified as **partial implemented + historical automated-tested components; not physically/network interoperable verified; not released**. Remote work remains deferred from the active HARD release cycle.

### Apple work

The Swift delta contains reusable components but not a complete Apple product path.

1. `NetworkTransportConfig` stores intended TLS/listener constants; there is no production `NWListener`/`NWConnection` transport engine in the reviewed delta.
2. `SpkiPinningValidator` hashes/compares supplied SPKI bytes, but it is not connected to a real platform TLS verification callback.
3. `BonjourService` builds/parses TXT dictionaries; a real `NWBrowser`/service-registration runtime is not demonstrated.
4. `IosLifecycleCoordinator` models state, but its default background provider is an in-memory counter/stub rather than `UIApplication.beginBackgroundTask`/`endBackgroundTask` integration.
5. `ShareExtensionCoordinator` validates file URLs/batches; it is not a complete Share Extension target or extension lifecycle.
6. `PhotoLibraryExporter` classifies filenames by extension; it does not perform real Photos authorization/export.
7. `MacCompanionCoordinator` models menu/listener state, but its default listener simply toggles an in-memory Boolean; it does not open a real `NWListener` or prove an AppKit menu-bar executable.
8. No Apple↔Android/Windows/Linux physical interoperability evidence is part of the HARD-002 captured delta.

**Result:** Apple work is classified as **partial implemented + historical unit-tested library/components; not native-runtime complete; not physically interoperable verified; not released**. Apple work remains deferred from the active HARD release cycle.

## 3. Claim taxonomy used by the current repository

HARD tasks now distinguish these states:

- **Specified:** architecture/spec/ADR exists.
- **Implemented:** source exists in a relevant runtime/library.
- **Automated-tested:** executable unit/integration/fixture tests have evidence; source-presence scripts alone do not raise a claim to this level.
- **Physically/interoperability verified:** the actual production path ran on the named real devices/platforms/browsers with evidence.
- **Released:** an exact reviewed artifact/commit has external publication/distribution evidence.
- **Unverified:** the stronger claim is not supported by current HARD evidence.

Historical session documents retain their original wording for provenance. Their `COMPLETE` labels mean the session's bounded component/milestone work was reported complete, not that the current multi-platform product is released.

## 4. Current platform/feature claim matrix

| Area | Specified | Implemented | Automated-tested evidence | Physical/interoperability verified for current candidate | Released/current publication evidence |
| --- | --- | --- | --- | --- | --- |
| Android local sharing | Yes | Yes | Extensive historical evidence | **Not current-certified**; HARD review found production wiring gaps | **Unverified** in HARD program |
| Windows 11 desktop/CLI | Yes | Yes | Extensive .NET historical evidence | **Not current-certified**; shared orchestration/native interop gaps remain | **Unverified** |
| Linux CLI/daemon | Yes | Yes | Historical build/test/packaging evidence | **Not current-certified**; auth/receive hardening remains | **Unverified** |
| Browser local gateway | Yes | Yes (HTTP/Web prototype) | Historical automated/browser evidence | Existing path is not release-acceptable secure transport | **Not current-certified** |
| Trust/folders/resume | Yes | Partial | Component/spec tests exist | No active cross-platform certification yet | Not released |
| Apple | Yes | Partial library/components | Historical Swift unit-test evidence | No | No |
| Remote/Internet | Yes | Partial components | Historical .NET unit/conformance evidence | No live WAN/E2EE/relay proof | No |

GitHub's Releases API returned an empty release list at HARD-002. That does **not** prove another store was never used; it means the current repository has no GitHub release object that can substantiate the README's former “Production Shipped” labels. Play/store/publication status was not independently revalidated in this task, so it is classified **unverified**, not assumed false or true.

## 5. Documentation contradictions corrected

HARD-002 corrected the following current surfaces without rewriting historical evidence:

1. **Root `README.md`**
   - removed “Production Shipped” / “Delivered & Certified” platform labels;
   - removed stale fixed test-count badge/claims as current authority;
   - replaced them with the HARD evidence taxonomy and current platform matrix;
   - separated active scope from deferred Apple/remote work.
2. **`docs/future/CROSS_PLATFORM_PATH.md`**
   - changed from historical step labels as implied completion authority to a status-aware strategic path governed by HARD tasks.
3. **Apple specification**
   - changed implementation language to target/partial/deferred status;
   - recorded the missing native-runtime pieces;
   - corrected LAN Profile 1 framing from the stale 4-byte/legacy-kind description to the actual 5-byte header and `0x01/0x02/0x10/0x20/0x21` kinds used by Kotlin, C#, and Swift.
4. **Remote specification**
   - changed implementation language to target/partial/deferred status;
   - distinguished candidate/helper/unit tests from real ICE/TURN/E2EE execution;
   - removed any implication that R5 unit tests are end-to-end WAN certification.
5. **HARD status surfaces**
   - HARD-002 completion and HARD-003 readiness are recorded in the master ledger and top-level HARD entry points.

## 6. Historical documents intentionally preserved

The following are not rewritten merely to make their historical status labels disappear:

- GLIDE-001–072 historical task/release evidence;
- V1 closure/release reports;
- sessions 85–91 and earlier cross-platform delivery sessions;
- accepted ADR history.

They remain useful provenance. Current readers are directed to HARD authority before using those records as present-day claims.

## 7. Verification performed by HARD-002

- Captured `main` at `12681eabba26387fe7d1bbaf309971f345642e41` before reconciliation.
- Compared `ea2e811...12681eab`; GitHub reported 18 commits and enumerated the changed files.
- Enumerated recent commits and separated 8 source/config commits, 9 HARD docs commits, and the merge commit.
- Read the eight source commits and the relevant Apple/remote implementation files.
- Inspected the root README, current HARD docs, cross-platform path, Apple specification, remote specification, and current release claims.
- Queried GitHub Releases; repository returned `[]` at the snapshot.
- Checked LAN record implementations across Kotlin/C#/Swift and reconciled the Apple spec framing mismatch.
- No production code, build logic, dependencies, packaging, secrets, deployment, or runtime behavior was changed by HARD-002.
- No build/test suite was rerun in HARD-002; current executable baseline is deliberately assigned to HARD-003/HARD-004 rather than inferred from historical session output.

## 8. Remaining evidence gaps / handoff

- Current Android/desktop/browser defect reproductions need regression tests: **HARD-003**.
- Authoritative Android/Windows/Linux CI/fresh-checkout build evidence needs to be established: **HARD-004**.
- External Play/store publication status remains unverified until a later release dossier has independent account/artifact evidence.
- Apple and remote implementation remain preserved but deferred; their new source findings are not blockers for the active local-sharing release unless scope changes.
- Source commits that land **after** captured HEAD `12681eab...` were not part of HARD-002's frozen review boundary and must be evaluated by the task that consumes them.

## 9. Next READY task

**HARD-003 — Add regressions for confirmed review defects.**