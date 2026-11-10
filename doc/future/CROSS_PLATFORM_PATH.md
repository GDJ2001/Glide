# Cross-platform path — current interpretation

This document records the strategic platform sequence and preserves links to earlier specifications. **Current development authority is the HARD program**, not the historical step numbers below: [hardening/completion plan](../planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md) · [task ledger](../TODO_MASTER.md).

Historical `Delivered`, `Approved`, `COMPLETE`, or milestone labels referenced by older sessions describe work performed at that time. They do not by themselves establish that the current checkout is physically interoperable, production-ready, or released.

## Evidence taxonomy

- **Specified:** an architecture/specification/ADR exists.
- **Implemented:** relevant source exists.
- **Automated-tested:** unit/fixture/script evidence exists; source-presence auditors alone are weaker than runtime tests.
- **Physically/interoperability verified:** the exact real platform/device path has been demonstrated with production wiring.
- **Released:** exact reviewed artifacts plus external publication/distribution evidence exist.

## Active native platform scopes

Glide's project scope consists strictly of the four native platform families:

1. **Android local sharing (`android/`)** — Native Kotlin / Jetpack Compose client (`dev.glide.app`) using Nearby Connections and LAN TLS 1.3 over TCP (`HARD-005–010`).
2. **Windows 11 + native desktop interoperability (`desktop/windows/`)** — Native C# / .NET 10 WPF client (`Glide.Windows.UI`) and CLI (`Glide.App`) sharing `Glide.Core`, `Glide.Transport`, and `Glide.Desktop` (`HARD-011–017`).
3. **Linux (`desktop/linux/`)** — Native C# / .NET 10 Avalonia UI (`Glide.Linux.UI`), CLI, and systemd daemon (`Glide.App`) (`HARD-016–017`).
4. **Apple iOS/iPadOS/macOS (`apple/`)** — Native Swift 6 shared core (`GlideCoreKit`), macOS AppKit client (`GlideMac`), and iOS/iPadOS SwiftUI client (`GlideiOS`) over `Network.framework` TLS 1.3 and Bonjour (`_glide._tcp`). Current native platform completion status is tracked in [`../planning/native-platforms/RECOVERY_STATUS.md`](../planning/native-platforms/RECOVERY_STATUS.md).
5. **Trust, folders and true resume** — Shared cross-platform capabilities across the four native platforms (`HARD-018–024`).

### Out of Scope / Retired Transports

- **Browser local sharing (`[RETIRED / REMOVED FROM SCOPE]`)** — Browser-based file sharing, HTTP/WebSocket gateways, embedded web clients, and WebRTC browser fallbacks have been retired and removed from project scope (`../planning/native-platforms/BROWSER_REMOVAL_INVENTORY.md`).
- **Remote/Internet/WAN (`[REMOVED FROM ACTIVE SCOPE]`)** — Non-local WAN/relay transfer is outside the active four-platform native local sharing scope.

## Relevant specifications

- Android/Windows LAN: [ANDROID_WINDOWS_LAN_SPECIFICATION.md](ANDROID_WINDOWS_LAN_SPECIFICATION.md)
- Apple: [APPLE_PLATFORMS_SPECIFICATION.md](APPLE_PLATFORMS_SPECIFICATION.md)
- Linux: [LINUX_PLATFORM_SPECIFICATION.md](LINUX_PLATFORM_SPECIFICATION.md)
- Browser work (Android↔Browser, ADR-0019 — `[SUPERSEDED / RETIRED]`): [ANDROID_BROWSER_WEB_SPECIFICATION.md](ANDROID_BROWSER_WEB_SPECIFICATION.md)
- Remote/Internet (`[OUT OF SCOPE]`): [REMOTE_INTERNET_TRANSFER_SPECIFICATION.md](REMOTE_INTERNET_TRANSFER_SPECIFICATION.md)

These specifications are technical/design authorities within their scope. Their existence is not an implementation-completion certificate.

## Portable contracts

Portable contracts include envelope/major negotiation, UUID format, decimal byte counts, capability limits, SHA-256 raw-byte digest, metadata validation, session/item outcomes, wire errors, receipts, cancellation semantics, LAN record framing, and golden fixtures. Portable conceptual domain does not imply shared Kotlin/C# binaries.

Platform-specific code still owns permissions, storage, lifecycle/background execution, native discovery/transport integration, key custody, UI and system security bindings. Every adapter must independently satisfy the authenticated transport and consent contract.

## Scope rule

Only the four native platform scopes (**Android, Windows, Linux, and Apple**) are valid active project scopes. Browser local sharing and non-native transports remain retired and excluded from project scope.