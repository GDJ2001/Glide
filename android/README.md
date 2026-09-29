# Glide — Private Cross-Platform File Sharing

Glide is an account-free file-sharing project with substantial Android, .NET desktop/Linux, browser/LAN, Apple, and remote-transfer implementation work. The repository is currently in an evidence-backed **code review, improvement, hardening, and development-completion cycle**.

> **Planning and history:** [`doc/planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md`](../doc/planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md) and [`doc/TODO_MASTER.md`](../doc/TODO_MASTER.md). Check current source and fresh validation before using historical completion labels.

## Current status

The HARD review baseline is `ea2e811a7f2d8aaddc552e894fe04782cd08a499`. HARD-002 reconciles later source work and historical claims against the current multi-platform codebase.

Evidence terms used below:

- **Implemented:** relevant source exists in the repository.
- **Automated-tested:** repository evidence records automated/unit/fixture checks for the implementation; this is not physical interoperability proof.
- **Physically/interoperability verified:** requires the exact real-device/platform path named by the active HARD task.
- **Released:** requires an exact reviewed artifact plus external publication/distribution evidence. Historical release documents alone do not establish current release status.

| Platform / capability | Current evidence classification | Active HARD position |
| --- | --- | --- |
| Android Nearby + LAN | Production service/runtime wiring exists; automated checks cover components, while current device journeys require verification | Active local-sharing scope |
| Windows 11 desktop/CLI | Shared transfer runtime and engine exist; native interoperability requires current physical verification | Active local-sharing scope |
| Linux CLI/daemon | CLI and daemon exist; installation and receive lifecycle require current platform verification | Active local-sharing scope |
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
├── android/                     # Gradle project: app, core, data, design system, transports
├── desktop/                     # .NET solution: shared libraries, WPF, CLI, Linux packaging
├── apple/GlideCoreKit/          # Deferred Swift components
├── doc/                         # Protocol, planning, historical task and test evidence
└── tools/                       # Developer verification scripts
```

## Build and verification entry points

Inspect the selected HARD task before running commands. Historical scripts may verify source presence or isolated components without proving the current product path.

Typical developer commands include:

```powershell
# From the repository root; configure ANDROID_HOME or ignored local.properties first.
pwsh -File tools/doctor.ps1
pwsh -File tools/verify.ps1

# Android-only checks, from android/.
cd android
.\gradlew.bat checkDocumentation checkArchitecture checkLanGate test :android:app:assembleDebug :android:app:lintDebug

# Desktop checks, from the repository root.
cd ..
dotnet build desktop/Glide.slnx -c Release
dotnet test desktop/Glide.slnx -c Release --no-build
```

These commands validate local code and packaging prerequisites. Physical interoperability and released artifacts need separate evidence.

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

- [`doc/README.md`](../doc/README.md) — documentation portal
- [`doc/planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md`](../doc/planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md) — completion plan
- [`doc/TODO_MASTER.md`](../doc/TODO_MASTER.md) — task history
- [`doc/protocol/GLIDE_PROTOCOL_V1.md`](../doc/protocol/GLIDE_PROTOCOL_V1.md) — protocol authority
- [`doc/security/GLIDE_THREAT_MODEL.md`](../doc/security/GLIDE_THREAT_MODEL.md) — threat model

## Historical evidence

The repository contains extensive V1, cross-platform, session, testing, and release documentation. These files remain useful for provenance, prior decisions, commands, fixtures, and regression evidence. They are not deleted or rewritten simply because later review found that a completion label was too broad.

For current status, reconcile the HARD ledger with the checked-out source, tests, and device evidence.

## License

This checkout has no LICENSE file. Confirm distribution terms before publishing artifacts.
