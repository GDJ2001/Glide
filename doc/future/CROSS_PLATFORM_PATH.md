# Cross-platform path — current interpretation

This document records the strategic platform sequence and preserves links to earlier specifications. **Current development authority is the HARD program**, not the historical step numbers below: [hardening/completion plan](../planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md) · [task ledger](../TODO_MASTER.md).

Historical `Delivered`, `Approved`, `COMPLETE`, or milestone labels referenced by older sessions describe work performed at that time. They do not by themselves establish that the current checkout is physically interoperable, production-ready, or released.

## Evidence taxonomy

- **Specified:** an architecture/specification/ADR exists.
- **Implemented:** relevant source exists.
- **Automated-tested:** unit/fixture/script evidence exists; source-presence auditors alone are weaker than runtime tests.
- **Physically/interoperability verified:** the exact real platform/device path has been demonstrated with production wiring.
- **Released:** exact reviewed artifacts plus external publication/distribution evidence exist.

## Current platform sequence

1. **Android local sharing** — implemented with substantial historical test evidence, but the HARD review found production wiring/discovery/receive-path gaps. Current completion is governed by HARD-005–010.
2. **Windows 11 + native desktop interoperability** — substantial C#/.NET implementation and automated tests exist. Shared orchestration, aligned native LAN security/framing, and real Android↔Windows↔Linux proof remain under HARD-011–017.
3. **Linux** — CLI/daemon/packaging implementation exists, but authentication and unattended receive behavior require hardening before interoperability certification. Current work is HARD-016–017.
4. **Secure browser local sharing** — the current embedded HTTP/Web gateway is an implementation prototype, not a release-certified secure browser transport. HARD-025–030 replaces/hardens this path around authenticated encrypted local transport.
5. **Trust, folders and true resume** — design and partial implementation exist, but integrated authenticated trusted sessions and durable resume are incomplete. Current work is HARD-018–024.
6. **Apple iOS/iPadOS/macOS** — Apple architecture/specification plus `apple/GlideCoreKit` components exist. HARD-002 source review found models/codecs/quarantine/coordinator abstractions but not a certified native app/runtime path. Apple work is preserved and **deferred** for this hardening cycle.
7. **Remote/Internet/WAN** — architecture/specification plus ICE/STUN/TURN/signaling/quota/session components exist. HARD-002 source review found isolated/unit-tested components rather than a live, encrypted, end-to-end production remote path. Remote work is preserved and **deferred** for this hardening cycle.

## Relevant specifications

- Android/Windows LAN: [ANDROID_WINDOWS_LAN_SPECIFICATION.md](ANDROID_WINDOWS_LAN_SPECIFICATION.md)
- Browser work (Android↔Browser, ADR-0019): [ANDROID_BROWSER_WEB_SPECIFICATION.md](ANDROID_BROWSER_WEB_SPECIFICATION.md)
- Apple: [APPLE_PLATFORMS_SPECIFICATION.md](APPLE_PLATFORMS_SPECIFICATION.md)
- Linux: [LINUX_PLATFORM_SPECIFICATION.md](LINUX_PLATFORM_SPECIFICATION.md)
- Remote/Internet: [REMOTE_INTERNET_TRANSFER_SPECIFICATION.md](REMOTE_INTERNET_TRANSFER_SPECIFICATION.md)

These specifications are technical/design authorities within their scope. Their existence is not an implementation-completion certificate.

## Portable contracts

Portable contracts include envelope/major negotiation, UUID format, decimal byte counts, capability limits, SHA-256 raw-byte digest, metadata validation, session/item outcomes, wire errors, receipts, cancellation semantics, LAN record framing, and golden fixtures. Portable conceptual domain does not imply shared Kotlin/C# binaries.

Platform-specific code still owns permissions, storage, lifecycle/background execution, native discovery/transport integration, key custody, UI and system security bindings. Every adapter must independently satisfy the authenticated transport and consent contract.

## Scope rule for the current hardening cycle

Do not advance deferred Apple or remote/Internet work merely because components already exist. The active order is the dependency order in `TODO_MASTER.md`: honest baseline → Android production path → desktop/native interoperability → trust/folders/resume → secure browser → structural cleanup → hardening → release evidence.