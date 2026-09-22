# Apple Platforms (iOS, iPadOS & macOS) Interoperability & Transport Specification

- **Document Version:** 1.1.0 (HARD-002 reconciliation)
- **Design Status:** Approved technical target
- **Implementation Status:** **PARTIAL / DEFERRED from the current HARD release scope**
- **Original Milestone:** M19 — Apple Platforms Native Architecture & Conformance Specification
- **Current Task Authority:** [HARD program](../TODO_MASTER.md)
- **Architectural Decision:** [ADR-0020](../adr/ADR-0020-apple-platforms-architecture.md)
- **Related Documents:** [GLIDE_PROTOCOL_V1.md](../protocol/GLIDE_PROTOCOL_V1.md), [ANDROID_WINDOWS_LAN_SPECIFICATION.md](ANDROID_WINDOWS_LAN_SPECIFICATION.md), [GLIDE_THREAT_MODEL.md](../security/GLIDE_THREAT_MODEL.md), [current hardening plan](../planning/CODE_REVIEW_IMPROVEMENT_AND_HARDENING_PLAN.md).

> **HARD-002 evidence note:** `apple/GlideCoreKit` contains meaningful Swift models, codecs, hashing/quarantine utilities, path sanitization, transport/discovery configuration helpers, lifecycle/share helpers, and macOS companion/drop-target coordinators with focused tests. That is **not equivalent to a native iOS/macOS product implementation**. At the HARD-002 snapshot there is no production `NWListener`/`NWConnection` transport engine, no real `UIApplication` background-task binding in the default runtime path, no complete iOS SwiftUI application/Share Extension target, no AppKit/`NSStatusItem` companion using a real listener, and no Apple↔Android/Windows physical interoperability evidence. Historical session files that said phases were `COMPLETE` are retained as evidence of component delivery, not current release certification.

---

## 1. Purpose and architectural invariants

This specification defines the **target** architecture, wire/security contract, lifecycle strategy, and storage behavior for native Glide clients on iOS, iPadOS, and macOS. It does not claim that the target is currently complete or released.

The target invariants are:

1. **Zero cloud / local by default.** Local discovery, pairing, and payload transfer use the local network without mandatory cloud storage, account, analytics, or relay services.
2. **Canonical protocol parity.** Apple clients implement the same Glide Protocol V1 and LAN Profile 1 semantics used by Android and desktop clients; no Apple-specific wire fork is allowed.
3. **Authenticated encrypted transport.** LAN transport uses TLS 1.3 with an explicitly reviewed identity/pinning/bootstrap design.
4. **Strict quarantine and integrity.** Incoming bytes remain private and unverified until exact length/SHA-256 validation succeeds, then commit atomically to user-visible storage.
5. **Native lifecycle/sandbox compliance.** iOS background execution, macOS sandboxing, local-network permissions, camera/photo permissions, and app-review constraints are handled with actual platform APIs rather than test doubles.
6. **No completion by source presence.** A helper type or source-presence auditor is not a substitute for executable app targets and physical interoperability tests.

---

## 2. Target native component mapping

The intended architecture is:

```text
+--------------------------------------------------------------+
|                    Apple platform clients                    |
|  iOS/iPadOS SwiftUI app + Share Extension                    |
|  macOS AppKit/menu-bar companion                             |
+-----------------------------+--------------------------------+
                              |
                    GlideCoreKit (Swift)
     protocol/codecs · SAS/invitation · storage · sanitization
                              |
        Network.framework TLS 1.3 + Bonjour/NWBrowser/NWListener
                              |
                    Glide LAN Profile 1
                              |
               Android / Windows / Linux peers
+--------------------------------------------------------------+
```

### HARD-002 implementation mapping

| Target component | Source evidence at snapshot | Current classification |
| --- | --- | --- |
| Swift package + LAN models/codecs | `Package.swift`, `LanInvitation`, `LanRecord`, codecs, SAS/path utilities | Implemented component; historical unit-test evidence |
| Bonjour TXT parsing/config | `BonjourService`, `NetworkTransportConfig` | Implemented helper/config only |
| TLS SPKI digest comparison | `SpkiPinningValidator` | Implemented helper only; not connected to a real TLS verify callback |
| `NWListener` / `NWConnection` transport | No production transport engine found in the reviewed delta | Not implemented in product path |
| Quarantine writer + streaming SHA-256 | `QuarantineStagingStore` | Implemented component; historical unit-test evidence |
| Photos export | `PhotoLibraryExporter` classifies extensions only | Classification helper; real `PHPhotoLibrary` export not implemented here |
| iOS lifecycle | `IosLifecycleCoordinator` | Abstraction/state helper; default provider is a stub, not `UIApplication` integration |
| iOS SwiftUI app / Share Extension | theme/share batch helpers exist | Native app/extension target not demonstrated |
| macOS companion/drop target | coordinator/state helpers + plist/entitlements exist | Partial components; default listener is in-memory stub, no real AppKit/NW runtime proof |
| Cross-platform Apple interoperability | No physical Apple↔Android/Windows/Linux evidence in HARD-002 | Not verified |

---

## 3. Network.framework target transport and LAN wire mapping

### 3.1 Target listener/connection behavior

A production Apple adapter should use `Network.framework` (`NWListener`, `NWConnection`, `NWBrowser`) with TLS 1.3 and the canonical `_glide._tcp` service contract. `NetworkTransportConfig` currently records several intended constants, but configuration constants do not constitute an `NWListener`/`NWConnection` implementation.

The production implementation must:

- create and own TLS 1.3 listener/connection objects;
- bind a reviewed local identity/certificate model;
- validate the exact expected peer SPKI/identity during the platform TLS verification callback;
- apply the canonical authenticated bootstrap and explicit consent rules;
- propagate transport cancellation/timeouts/errors into the shared session state machine;
- never accept a connection because a TXT record, display name, or helper digest merely exists.

### 3.2 Bonjour discovery target

The canonical service is:

- type: `_glide._tcp`
- domain: `local.`
- required TXT semantics: protocol version, bounded display name, SPKI/identity fingerprint and presence/invitation correlation fields as finalized by HARD-012.

`BonjourService` currently builds/parses an in-memory dictionary. A production adapter still needs actual `NWBrowser`/service-registration integration and interoperability proof with Android/desktop DNS-SD behavior.

### 3.3 Canonical LAN Profile 1 framing — corrected by HARD-002

The previous version of this specification incorrectly described a 4-byte header and legacy record IDs. The current Kotlin, C#, and Swift codecs use the same **5-byte header**:

- byte `0`: record kind (`UInt8`);
- bytes `1..4`: payload length (`UInt32`, big-endian);
- then the exact payload bytes.

Canonical record kinds in the current codecs are:

| Kind | Value | Payload rule |
| --- | ---: | --- |
| Bootstrap | `0x01` | bounded bootstrap JSON |
| BootstrapResult | `0x02` | bounded bootstrap-result JSON |
| Control | `0x10` | protocol control envelope, max 16 KiB |
| Data | `0x20` | 16-byte bulk UUID + bounded data chunk |
| StreamEnd | `0x21` | 16-byte bulk UUID + 8-byte actual byte count |

The record payload ceiling used by the current LAN codecs is 64 KiB. HARD-012 remains responsible for final cross-language bootstrap/discovery/TLS alignment and golden-vector proof.

---

## 4. Pairing and authentication target

### 4.1 Ambient/untrusted LAN pairing

If untrusted LAN discovery is supported, both peers must derive the same authentication material from actually exchanged and cryptographically bound session inputs. A six-digit SAS is only meaningful when both peers derive it from one shared authenticated transcript/nonce and both users explicitly confirm the comparison before offer acceptance.

Historical specifications showed an HKDF-SHA256 SAS design. HARD-012 will decide whether to retain/fix that design or remove the ambient SAS path in favor of a simpler pinned invitation bootstrap. No Apple UI should claim authentication until that cross-platform decision is frozen.

### 4.2 Out-of-band invitation target

A QR/link invitation may carry bounded endpoint(s), protocol/profile, expiry, one-use correlation material and an expected identity/SPKI fingerprint. The current Swift `InvitationCodec` provides an encoded `glide://pair/v1#...` container and expiry checks; production pairing still requires connection-time pin validation and atomic one-use consumption.

Camera scanning via AVFoundation is a target app feature; it is not established by the shared library helpers currently in the repository.

---

## 5. iOS/iPadOS lifecycle target

A production iOS/iPadOS app should:

- use `UIApplication.shared.isIdleTimerDisabled` only while appropriate;
- call `beginBackgroundTask`/`endBackgroundTask` through real UIKit bindings;
- treat expiration as interruption/cancellation, never false success;
- persist only the recovery state intentionally supported by the protocol;
- avoid fake background modes such as audio/location merely to extend runtime;
- expose truthful user outcomes when iOS suspends/terminates the process.

`IosLifecycleCoordinator` models this lifecycle, but its default provider at the HARD-002 snapshot is an in-memory counter/stub. Integration with actual UIKit remains future Apple work.

---

## 6. macOS companion target

The intended macOS client is a sandboxed `LSUIElement` menu-bar application with:

- a real local TLS listener;
- Bonjour registration/browsing;
- explicit send/receive consent and transfer status;
- drag/drop or file-panel source selection;
- Downloads/user-selected destination access under sandbox rules;
- optional launch-at-login implemented with supported macOS APIs.

The current `MacCompanionCoordinator` and `MacDropTargetCoordinator` model state/menu/drop preparation, but the default listener only toggles an in-memory Boolean. The plist/entitlements resources and coordinators are therefore partial implementation evidence, not proof that a macOS companion executable is listening or transferring files.

---

## 7. Sandboxed storage and quarantine target

### 7.1 Quarantine

Incoming bytes should be written incrementally to app-private quarantine storage and hashed in the same pass using CryptoKit SHA-256. The existing `QuarantineStagingStore` demonstrates this component behavior and purges on digest mismatch.

Before product integration, Apple storage work still needs review for:

- exact byte-count enforcement in addition to digest;
- cancellation/close semantics and repeated-finalize behavior;
- durable crash/restart journal policy;
- disk-space/reserve checks;
- atomicity guarantees across the selected source/destination volume;
- interaction with sandbox bookmarks/security-scoped URLs where applicable.

### 7.2 Destination/export

The design target allows app Documents/Downloads or Photos export according to platform/user choice. The current `PhotoLibraryExporter` only classifies media by extension; it does not perform `PHPhotoLibrary` authorization or export.

### 7.3 Filename safety

Incoming names must be sanitized for traversal, separators, control/bidi characters, Unicode normalization, byte limits and collision policy. `DarwinPathSanitizer` is reusable evidence but must be covered by cross-platform conformance vectors before release claims.

---

## 8. Permissions and entitlements target

Expected iOS/macOS declarations include local-network usage, `_glide._tcp` Bonjour service permission, camera permission only when QR scanning is present, Photos add permission only when actual Photos export is present, and macOS sandbox network/file entitlements matching real features.

Committed plist/entitlement files are configuration inputs. They are not App Store conformance proof and should be removed or narrowed if the corresponding production capability does not ship.

---

## 9. Reconciled implementation roadmap

| Phase | Intended result | HARD-002 classification |
| --- | --- | --- |
| A1 Shared Swift core | protocol/invitation/LAN records/SAS/path helpers | **Partial implementation exists; historical unit tests** |
| A2 Network.framework transport | real listener/connection/TLS pinning/Bonjour | **Not complete; config/parser/pinning helpers only** |
| A3 quarantine/digest | bounded streaming quarantine and verified commit | **Component implemented; product/recovery integration incomplete** |
| A4 iOS app/share extension | executable SwiftUI app, scanner, share extension, lifecycle binding | **Not complete; helper/coordinator/theme code only** |
| A5 macOS companion | executable AppKit/menu app, real listener, drag/drop | **Not complete; coordinator/config components only** |
| A6 interoperability certification | Apple↔Android/Windows/Linux real transfer matrix | **Not performed / not evidenced** |

Apple implementation is deliberately **deferred** from HARD-001–043 active release scope. Existing code is retained for future work; no current task may mark Apple release readiness without an explicit scope amendment and the physical/interoperability evidence above.