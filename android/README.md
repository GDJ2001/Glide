# Glide — Private Cross-Platform File Sharing

Glide is an account-free file-sharing project with substantial Android, .NET desktop/Linux, browser/LAN, Apple, and remote-transfer implementation work. The repository is currently in an evidence-backed **code review, improvement, hardening, and development-completion cycle**.

> **Current authority:** [`docs/planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md`](docs/planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md) and [`docs/TODO_MASTER.md`](docs/TODO_MASTER.md). Historical `COMPLETE`, `shipped`, `certified`, or release labels are retained as historical evidence but do not certify the current checkout.

## Current status

The HARD review baseline is `ea2e811a7f2d8aaddc552e894fe04782cd08a499`. HARD-002 reconciles later source work and historical claims against the current multi-platform codebase.

Evidence terms used below:

- **Implemented:** relevant source exists in the repository.
- **Automated-tested:** repository evidence records automated/unit/fixture checks for the implementation; this is not physical interoperability proof.
- **Physically/interoperability verified:** requires the exact real-device/platform path named by the active HARD task.
- **Released:** requires an exact reviewed artifact plus external publication/distribution evidence. Historical release documents alone do not establish current release status.

| Platform / capability | Current evidence classification | Active HARD position |
| --- | --- | --- |
| Android Nearby + LAN | Implemented; substantial historical automated/device evidence exists, but production wiring defects remain under review | Active scope; HARD-005–010 |
| Windows 11 desktop/CLI | Implemented; substantial automated tests exist, but shared orchestration and native interoperability are not currently certified | Active scope; HARD-011–017 |
| Linux CLI/daemon | Implemented; authentication/receive-lifecycle hardening remains | Active scope; HARD-016–017 |
| Browser local sharing | Implemented prototype over local HTTP/Web gateway; plaintext HTTP path is not release-acceptable | Active scope; replace/harden in HARD-025–030 |
| Persistent trust / folders / true resume | Partial design and implementation work exists; end-to-end integration is not complete | Active scope; HARD-018–024 |
| Apple iOS/iPadOS/macOS | `GlideCoreKit` models/codecs/storage/coordinator components exist and have historical unit-test evidence; native production app/transport lifecycle is not certified | Preserved but deferred in this hardening cycle |
| Remote / Internet transfer | ICE/STUN/TURN/signaling/quota/session components exist and have historical unit-test evidence; no live end-to-end remote product path is certified | Preserved but deferred in this hardening cycle |

No platform should currently be described from this README as "Production Shipped" or "Delivered & Certified". GitHub has no repository release objects at the HARD-002 snapshot, and external store/publication evidence has not been revalidated in the HARD program.

## Active release scope

This hardening cycle completes and verifies:

- Android local sharing via Nearby and LAN.
- Windows 11 native local sharing.
- Linux native local sharing.
- Android ↔ Windows ↔ Linux interoperability.
- Folder transfer.
- Persistent device trust and authenticated trusted sessions.
- True resume with durable journals and integrity checks.
- Secure browser-local sharing using an authenticated encrypted transport rather than the current plaintext HTTP release path.
- CI, security, accessibility, performance, packaging, release-candidate evidence, and explicitly authorized release operations.

Deferred for this cycle, while preserving existing code:

- Native Apple clients.
- Remote/Internet/WAN transfer and relay infrastructure.

## Repository layout

```text
Glide/
├── app/                         # Android application, Compose UI, DI, lifecycle/service integration
├── transfer-core/               # Pure Kotlin/JVM protocol and transfer domain
├── transport-nearby/            # Google Nearby transport
├── transport-lan/               # Android LAN transport and browser gateway work
├── data/                        # Android storage, Room, settings, recovery
├── designsystem/                # Android design system
├── windows/                     # .NET shared core, transport, Windows UI, CLI/daemon, tests
├── apple/GlideCoreKit/          # Deferred Swift shared-library/component work
├── docs/                        # Current HARD authority plus historical specifications/evidence
└── tools/                       # Verification/audit/developer scripts
```

## Build and verification entry points

Inspect the selected HARD task before running commands. Historical scripts may verify source presence or isolated components without proving the current product path.

Typical developer commands include:

```bash
# Android / Gradle
./gradlew test
./gradlew :app:assembleDebug

# Repository Android verification
pwsh -File tools/verify-local.ps1

# .NET shared/desktop tests
dotnet test windows/Glide.Tests/Glide.Tests.csproj -c Release
```

HARD-004 will establish the authoritative multi-platform CI baseline and exact fresh-checkout command set. Until then, do not turn a local script pass into a release or interoperability claim.

## Protocol and security

The portable contract is Glide Protocol V1 plus the versioned LAN/extension specifications and ADRs. The current hardening program specifically requires:

- explicit authenticated pairing and separate transfer consent;
- strict bounds, sequence/correlation validation, and truthful terminal states;
- streaming SHA-256 and quarantine/atomic commit;
- one aligned native LAN discovery/TLS/bootstrap profile;
- durable authenticated trust/resume semantics;
- no plaintext browser transfer in release builds;
- no claim of physical interoperability without the named real environments.

Start with:

- [`docs/README.md`](docs/README.md) — documentation portal
- [`docs/planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md`](docs/planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md) — current completion plan
- [`docs/TODO_MASTER.md`](docs/TODO_MASTER.md) — current task/status authority
- [`docs/protocol/GLIDE_PROTOCOL_V1.md`](docs/protocol/GLIDE_PROTOCOL_V1.md) — protocol authority
- [`docs/security/GLIDE_THREAT_MODEL.md`](docs/security/GLIDE_THREAT_MODEL.md) — threat model

## Historical evidence

The repository contains extensive V1, cross-platform, session, testing, and release documentation. These files remain useful for provenance, prior decisions, commands, fixtures, and regression evidence. They are not deleted or rewritten simply because later review found that a completion label was too broad.

For current status, always use the HARD ledger rather than inferring completion from historical session numbers or milestone names.

## License

Glide is released under the Apache License 2.0. See [`LICENSE`](LICENSE).