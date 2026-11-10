# Glide — Code Review, Improvement, Hardening & Development Completion Plan

**Authority:** Current completion/hardening plan for the Glide repository.  
**Review baseline:** `ea2e811a7f2d8aaddc552e894fe04782cd08a499`  
**Publication base:** `b07f15bdde60bb5bfa9b20fdaba093b7a6a93785`  
**Task authority:** [`../TODO_MASTER.md`](../TODO_MASTER.md)

> The detailed code review was performed at `ea2e811`. Four commits landed afterward. They add Apple `GlideCoreKit` primitives and additional remote/Internet ICE, TURN, signaling, quota, metered-network and remote-session code. Those additions are preserved, but this review does not retroactively certify them. HARD-002 must reconcile them before any affected completion claim is made.

## 1. Review conclusion

Glide has substantial reusable code, but it is not yet complete end to end. The major gaps are application wiring, native interoperability, security enforcement, native platform transport security, recovery/trust integration and release verification that exercises the real product rather than source-text or loopback substitutes.

### Review-reported verification at `ea2e811`

| Check | Review result |
| --- | --- |
| Android debug build and lint | Passed |
| Fresh Android/JVM unit tests | 1,303 passed |
| .NET tests | 168 passed |
| Full desktop Release build | Passed |
| Physical Android transfers | Not verified; no devices connected |
| Linux runtime and native desktop user journeys | Not executed during the review |
| Repository changes during review | None |

These results are evidence for the review baseline only. They are not a verification claim for later commits.

### Confirmed gaps and remediation mapping

| ID | Priority | Finding | Primary remediation |
| --- | --- | --- | --- |
| F-01 | Critical | Android navigation creates discovery/receive ViewModels without real transport dependencies, so production wiring can diverge from tests. | HARD-003, HARD-005–010 |
| F-02 | Critical | Receive QR uses fixed/example endpoint/fingerprint data and scanning does not start a real transfer session. | HARD-003, HARD-008, HARD-010 |
| F-03 | Critical | Linux daemon can treat missing invitation state as valid and auto-accept incoming offers. | HARD-003, HARD-016, HARD-017 |
| F-04 | High | Android and desktop LAN authentication/discovery contracts are incompatible, including TLS client-certificate and DNS-SD metadata expectations. | HARD-012, HARD-017 |
| F-05 | High | Desktop send/receive loops do not implement one shared strict session machine, reuse/assume sequencing, and do not fully implement paged offers/correlation/terminal outcomes. | HARD-011, HARD-013, HARD-017 |
| F-06 | High | Desktop senders use whole-file memory hashing (`ReadAllBytesAsync`) before transfer. | HARD-003, HARD-014 |
| F-07 | High | Desktop SAS runtime paths derive codes with independently generated nonces; CLI confirmation is not a reliable authentication gate. | HARD-003, HARD-012, HARD-013 |
| F-08 | High | Historical browser transfer was local HTTP, exposed bearer/session credentials to plaintext LAN transport, had advisory proposals, incomplete host consent and optional checksums. | HARD-003; browser local sharing subsequently retired and removed from project scope (`native-platforms/BROWSER_REMOVAL_INVENTORY.md`) |
| F-09 | High | Trust/resume helpers exist without complete authenticated session integration; resume bookkeeping must only advance over contiguous verified chunks. | HARD-018–024 |
| F-10 | High | Release/completion documentation and CI evidence overstate readiness; CI coverage does not represent all supported platform journeys and physical gates were historically mislabeled. | HARD-002, HARD-004, HARD-040–043 |
| F-11 | Medium | Large Android Activity/ViewModel files, desktop `MainViewModel`, debug fixtures in production, global mutable ownership, duplicated assets and incomplete architecture rules increase regression risk. | HARD-031–035, HARD-040 |

## 2. Agreed release scope

### Active native platform scopes

- **Android** local sharing through Nearby Connections and LAN TLS 1.3 (`android/`).
- **Windows 11** local sharing through WPF (`Glide.Windows.UI`) and CLI (`desktop/windows/`).
- **Linux** local sharing through Avalonia UI (`Glide.Linux.UI`), CLI, and systemd daemon (`desktop/linux/`).
- **Apple (macOS, iOS, iPadOS)** native local sharing through `GlideCoreKit`, `GlideMac`, and `GlideiOS` (`apple/`).
- Multi-file and folder transfers across native endpoints.
- Persistent device trust and true resume.
- History, settings, accessibility, background behavior, packaging and release verification.

### Removed from project scope / Retired

- **Browser local sharing (`[RETIRED / REMOVED FROM SCOPE]`):** Browser-based file sharing, HTTP/WebSocket gateways, embedded web clients, and WebRTC browser fallbacks have been retired and removed from project scope (see [`native-platforms/BROWSER_REMOVAL_INVENTORY.md`](native-platforms/BROWSER_REMOVAL_INVENTORY.md)).
- **Remote/Internet transfers (`[REMOVED FROM ACTIVE SCOPE]`):** Non-native WAN/relay transfers are outside the active four-platform native local sharing scope.

### Platform choices

- Android remains Kotlin/Compose (`android/`).
- Desktop remains C#/.NET 10 with WPF on Windows (`desktop/windows/`), Avalonia UI on Linux (`desktop/linux/`), and shared .NET engine/CLI (`desktop/src/`).
- Apple uses Swift 6 (`GlideCoreKit`), AppKit on macOS (`GlideMac`), and SwiftUI on iOS/iPadOS (`GlideiOS`) (`apple/`).
- Existing branding and Android application identifiers are preserved.
- Windows support starts at Windows 11 because the current native transport design requires modern TLS 1.3 support. See Microsoft Schannel protocol support documentation: <https://learn.microsoft.com/en-us/windows/win32/secauthn/protocols-in-tls-ssl--schannel-ssp-/>

## 3. Target runtime boundaries

### Android

Introduce a service-owned `TransferRuntime` exposing typed commands and immutable state:

- Start sending an identified selection to an authenticated pairing.
- Start/stop receive availability.
- Accept/reject an offer.
- Cancel or resume an exact session.
- Observe discovery, pairing, transfer and completion state.

ViewModels must not construct transports or own transfer execution scopes. Navigation carries opaque identifiers, not credentials, coordinators or transport instances.

### Desktop

Create one shared desktop application engine used by WPF and CLI. Separate protocol/domain models, orchestration, transport and storage. WPF and CLI must exercise the same validation, transfer, consent and recovery implementation.

### Protocol rule

Preserve the transport-independent Glide protocol. Correct incompatible implementations rather than weakening Android checks to accommodate desktop behavior.

## 4. Security and recovery rules

- Initial native pairing uses pinned TLS plus an atomically consumed invitation and explicit offer consent.
- Trusted pairing requires explicit enrollment and proof of possession of persistent keys.
- Identity proofs bind both peers, roles, fresh challenges, session, capabilities and the actual secure channel. Signing an isolated nonce is insufficient.
- Store keys in Android Keystore, Windows protected key storage, Apple Keychain, and restricted Linux key storage.
- Version trust/resume extensions explicitly and advertise them only after implementation passes interoperability tests.
- Resume uses fixed negotiated chunks, authenticated content hashes, a durable contiguous-progress journal and final whole-file SHA-256 verification.
- Revalidate changed sources, revoked permissions, retained chunks and peer identity before resuming.
- Retained partials expire after 24 hours, respect storage limits and are removed on explicit cancellation.
- Trusted devices never automatically authorize arbitrary incoming files. Headless receiving requires a separately enabled peer-specific destination and size policy.

## 5. Browser direction — `[RETIRED / REMOVED FROM SCOPE]`

> **Status:** Browser local sharing (both the early plaintext HTTP prototype and the historical M4 WebRTC gateway) has been retired and removed from project scope in favor of native client applications across the four active platform scopes (**Android, Windows, Linux, and Apple**). See [`native-platforms/BROWSER_REMOVAL_INVENTORY.md`](native-platforms/BROWSER_REMOVAL_INVENTORY.md).

## 6. Completion roadmap

Task IDs are authoritative in [`../TODO_MASTER.md`](../TODO_MASTER.md). Every task requires dependencies, affected modules, acceptance criteria, regression tests and completion evidence.

### M0 — Establish an honest baseline

- **HARD-001:** Publish this evidence-backed review and completion plan with source references, scope and verification limitations.
- **HARD-002:** Reconcile existing TODOs, ADRs, README/release claims and post-review commits against the actual checkout.
- **HARD-003:** Add regression cases for disconnected production wiring, authentication bypass, incompatible sequencing and unbounded file hashing.
- **HARD-004:** Establish Android, Windows and Linux build/test jobs before further development.

**Gate:** Implemented, tested, physically verified and released are distinct statuses. Historical approvals cannot authorize the current candidate.

### M1 — Complete the Android production flow

- **HARD-005:** Implement service-owned runtime commands, session ownership and state observation.
- **HARD-006:** Inject real discovery dependencies and connect point-of-use permission requests.
- **HARD-007:** Wire Nearby selection, authentication, complete batches and receiver consent.
- **HARD-008:** Wire live LAN listeners, actual QR endpoints/pins, invitation consumption and selected-source preservation.
- **HARD-009:** Complete foreground-service startup, cancellation, notification actions, rotation and process-death reconciliation.
- **HARD-010:** Execute the real two-Android-device send/receive journey through the launcher UI.

**Gate:** Selection → pairing → consent → bytes → verification → open → history works on two physical devices. Rejection and cancellation use the same production path.

### M2 — Repair desktop and native interoperability

- **HARD-011:** Extract a shared desktop transfer engine from CLI and WPF.
- **HARD-012:** Align DNS-SD metadata, TLS certificate expectations, ALPN and bootstrap rules with Android.
- **HARD-013:** Implement monotonic sequences, paged offers, exact identifier matching, deadlines and truthful batch outcomes.
- **HARD-014:** Replace whole-file hashing with streaming; centralize safe paths, storage reservations, cleanup and commit behavior.
- **HARD-015:** Complete desktop offer consent, persistent history, settings and UI-thread-safe state updates.
- **HARD-016:** Harden Linux CLI/daemon lifecycle; reject unauthorized receiving and support bounded cancellation/shutdown.
- **HARD-017:** Run Android↔Windows, Android↔Linux and Windows↔Linux interoperability tests in both directions.

**Gate:** Multi-page, multi-file transfers succeed across actual implementations. A failed item cannot produce a successful batch notification.

### M3 — Complete trust, folders and true resume

- **HARD-018:** Finalize versioned identity/resume messages, canonical signature inputs, capability rules and shared vectors.
- **HARD-019:** Integrate Android identity storage, enrollment, revocation and trusted-peer presentation models.
- **HARD-020:** Implement equivalent Windows/Linux identity persistence and explicit headless receiving policies.
- **HARD-021:** Integrate mutually authenticated session establishment and downgrade/replay rejection.
- **HARD-022:** Implement Android resumable journals/migrations keyed by transfer and item; recover only contiguous verified chunks.
- **HARD-023:** Implement equivalent desktop resume storage and engine recovery.
- **HARD-024:** Validate folder preservation and resumed transfers across platforms, including changed sources and revoked trust.

**Gate:** Interrupted large transfers resume from verified progress, reject stale/forged authority and produce exactly the original bytes. No partial becomes completed prematurely.

### M4 — Browser sharing (`[RETIRED / REMOVED FROM SCOPE]`)

- **HARD-025–030:** Historical browser sharing milestone. Browser local sharing, WebRTC adapters, and static web client distributions have been retired and removed from project scope (`native-platforms/BROWSER_REMOVAL_INVENTORY.md`), superseded by native applications across Android, Windows, Linux, and Apple.

### M5 — Refactor structure and developer experience

- **HARD-031:** Split Android entry-point/debug fixtures, route composition, screen sections and presentation mapping.
- **HARD-032:** Split desktop presentation responsibilities; networking/filesystem work stays outside ViewModels.
- **HARD-033:** Move platform trees in separate mechanical changes while preserving package IDs, logical module names and protocol behavior.
- **HARD-034:** Consolidate protocol fixtures and remove duplicated sources.
- **HARD-035:** Add consistent setup, verification, launch, preview, formatting and dependency-management commands.

**Gate:** A fresh checkout builds using documented commands. Folder moves introduce no behavior changes and fixture/documentation paths resolve.

### M6 — Product quality and hardening

- **HARD-036:** Exercise physical network, lifecycle, storage, permission and recovery failures.
- **HARD-037:** Perform a production-entry-point security review and fix findings.
- **HARD-038:** Review real UI journeys for accessibility, adaptive layout, keyboard operation, themes and truthful errors.
- **HARD-039:** Measure startup, frame rendering, memory, throughput, hashing overhead, battery and thermals.
- **HARD-040:** Complete CI coverage for native tests, instrumentation, architecture boundaries, migrations, dependency review and documentation links.

**Gate:** No unresolved Critical/High security issue or blocking functional defect. Performance claims derive from device measurements, not loopback throughput.

### M7 — Release and operations

- **HARD-041:** Produce and test Android APK/AAB, Windows signed packages, Linux packages and macOS/Apple artifacts.
- **HARD-042:** Complete install/update verification, privacy disclosures, licenses, signing custody, support matrices and exact-candidate evidence dossier.
- **HARD-043:** Execute the explicitly authorized staged-release process with rollback, incident handling, maintenance ownership and developer handoff.

**Gate:** Every published artifact maps to a reviewed commit, checksums, test evidence and operator approval. Deployment remains a separate authorized action.

Default execution order is M0 → M1 → M2 → M3 → M5 → M6 → M7 (with M4 retired). Physical gates cannot be bypassed by relabeling unit, source-text, loopback or emulator tests.

## 7. Target repository structure

Target structure beneath the repository root:

```text
android/              Android Gradle project and modules
desktop/              Shared .NET engine, transports, storage, CLI, Windows WPF, Linux Avalonia
apple/                Shared Swift GlideCoreKit, macOS AppKit, and iOS/iPadOS SwiftUI clients
native/               Shared cross-language conformance fixtures
asset/                Existing canonical brand assets
doc/                  Product, architecture, tasks, review, evidence
tools/                Common entrypoint and platform-specific tooling
.github/              CI and release workflows
```

The move itself is HARD-033 and must be mechanical and separately reviewed. Do not perform it opportunistically during behavioral fixes.

## 8. Developer workflow requirements

- One repository-root command interface for `doctor`, `build`, `test`, `lint`, `verify` and UI preview tasks.
- Pin compatible JDK, .NET SDK, Node, native toolchain and dependency versions; resolve local/CI JDK mismatches.
- Add EditorConfig, formatting checks, dependency locks/checksums and documented IDE launch configurations.
- Keep debug fixtures in debug/test sources.
- Provide Compose previews, WPF/Avalonia design-time states and SwiftUI previews using synthetic data.
- Extend architecture checks to LAN, storage entities, fully qualified references and every production source set.
- Keep file moves, formatting, behavioral fixes and protocol changes in separate reviewable changes.

## 9. Verification and completion contract

Required scenarios include:

- Empty files; 10 MB, 100 MB, 1 GB and 5+ GB files; 1,000 small files; multi-page offers; nested folders.
- Both directions for every supported native pairing across **Android, Windows, Linux, and Apple**.
- Wrong pins, missing invitations, simultaneous redemption, replay, malformed metadata, sequence gaps and unauthorized offers.
- Disconnect, lock, process death, permission revocation, disk full, duplicate names, symlinks/reparse points and different destination volumes.
- Resume with missing chunks, corrupted retained data, modified sources, expired journals, changed keys and revoked peers.
- Real TalkBack/VoiceOver/keyboard journeys, 200% text scaling, adaptive layouts and reduced motion.
- Fresh installation and upgrade without losing verified files or corrupting history.

Every task report must state implementation/review commit(s), files changed, commands run, observed results, manual checks outstanding, acceptance evidence, known limitations/blockers and the next READY task.

Tests must call production behavior. Test-local constants, source-text checks, simulated radio results or loopback throughput cannot certify UI, security, interoperability, performance or release readiness.

## 10. Current execution state

- **HARD-001:** documentation publication task; completed by the commit that introduces this plan and current HARD ledger.
- **Next READY:** HARD-002 — reconcile current source/docs/status, including the four commits after the review baseline.
- No feature implementation, deployment or publishing is authorized by HARD-001.
