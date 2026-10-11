# Glide Pair-Only Discovery and Trusted Reconnect — Baseline & Implementation Checklist

**Branch**: `codex/pair-only-and-trusted-reconnect`  
**Base Commit (`main`)**: `e35f037` (`Match Linux title bar to Glide appearance`)  
**Application Version**: `1.0.0` (preserved across all manifests, installers, and packages)  
**Contract Reference**: [`doc/protocol/PROFILE_2_PAIRING_AND_RECONNECT.md`](../../protocol/PROFILE_2_PAIRING_AND_RECONNECT.md)  
**Shared Golden Fixtures**: [`doc/protocol/fixtures/profile2-fixtures.json`](../../protocol/fixtures/profile2-fixtures.json)

---

## 1. Clean `main` Baseline Record

- **Git Status at Start**: Clean working tree on `main` at `e35f037`.
- **Desktop Core & Transport Tests (`Glide.Tests`)**: `322/322` passed (`0` failed, `0` skipped).
- **Windows UI Integration Test (`Glide.Windows.UI.Tests`)**: `1` existing test (`ChoosingOneSendMethod_ControlsPairingAndSendVisibility`) asserts legacy 500px resize under DPI scaling and legacy `Nearby device` / `ShowThisPcQrPanel` controls that are replaced in Stage 5.
- **Discovery Audit Findings on `main`**:
  1. `desktop/src/Glide.Transport/Discovery/MdnsDiscoveryService.cs` binds a single `UdpClient` to `IPAddress.Any:5353` and calls `JoinMulticastGroup(MulticastAddress)` without interface iteration or per-NIC outbound query transmission, causing wired multi-NIC PCs (e.g., Ethernet + WSL/Hyper-V/VirtualBox adapters) to miss multicast joins/queries on physical LAN interfaces.
  2. `apple/GlideCoreKit/Sources/GlideCoreKit/Transport/BonjourService.swift` advertises non-canonical TXT keys (`v, name, spki, presence`) including `spki`, diverging from the canonical `_glide._tcp` availability contract (`v=1, tv=1, q=1, p, dn, dt`).
  3. Listener lifecycle on desktop/Apple is currently tied to Receive readiness or hosted Send QR invitations rather than supporting pair-only readiness while file receiving remains off.

---

## 2. Staged Execution Checklist

### Stage 1: Baseline, Profile 2 Contract & Shared Fixtures
- [x] Record clean `main` baseline and test status in `doc/planning/native-platforms/PAIR_ONLY_AND_TRUSTED_RECONNECT_CHECKLIST.md`.
- [x] Author cross-platform Profile 2 specification in `doc/protocol/PROFILE_2_PAIRING_AND_RECONNECT.md`.
- [x] Create shared cross-language golden fixtures in `doc/protocol/fixtures/profile2-fixtures.json` covering:
  - `glide://pair/v1` backward compatibility vectors.
  - `glide://pair/v2` (`purpose=pair-only`) valid canonical URI and negative rejection vectors (duplicate keys, unknown fields, non-canonical base64url, expired timestamp, invalid purpose, off-link/loopback address).
  - Canonical `_glide._tcp` TXT records with `pair=1` and `tr=1`.
  - `GLD2` 4-byte preamble and Profile 2 control framing vectors.
  - BoringSSL SPAKE2 (`3fed4f207543470b3d639db6ed40737e30308b51`) deterministic test vectors, HKDF key confirmation, and `GLIDE-PAIR-V2` signed transcript bytes.

### Stage 2: Interface-Aware Discovery, Canonical Apple TXT, Pair Readiness & Listener Routing
- [x] Refactor `MdnsDiscoveryService` (`desktop/src/Glide.Transport/Discovery/`) to join IPv4 multicast groups and transmit queries/announcements across all eligible active LAN interfaces (`SystemNetworkSnapshotProvider` criteria), handling multiple NICs and VM adapters, and surfacing typed multicast/firewall errors.
- [x] Update `_glide._tcp` TXT codec across C# (`MdnsDiscoveryService`), Kotlin (`LanTxtCodec`), and Swift (`BonjourService`) to support optional `pair=1` (during 2-minute **Ready to pair** window) and `tr=1` (trusted reconnect capability), and remove `spki` from Apple Bonjour TXT.
- [x] Implement 2-minute **Ready to pair** window lifecycle that starts the shared TLS listener even when file receiving is off (without turning on file receiving) and cancels readiness on expiry, explicit Cancel, app close, or leaving Pair devices.
- [x] Implement 4-byte preamble inspection on the TLS listener (`TlsSocketListener` / `DesktopListenerSupervisor`, `LanListener`, `GlideNetworkListener`) under the handshake deadline:
  - Existing Profile 1 records (`0x01`, `0x02`, `0x10`, `0x20`, `0x21`) continue unchanged to the Profile 1 transfer runtime.
  - `GLD2` (`0x47, 0x4C, 0x44, 0x32`) routes to a bounded Profile 2 session (`PAIR_ONLY` or `TRUSTED_SEND`), failing closed on unknown versions/purposes or transfer records during `PAIR_ONLY`.

### Stage 3: Pinned BoringSSL SPAKE2 Wrapper & Pair-Only Handshakes
- [x] Integrate pinned BoringSSL (`3fed4f207543470b3d639db6ed40737e30308b51`) SPAKE2 implementation behind a small private C ABI (`glide_spake2`), packaged for Windows (`win-x64`), Linux (`linux-x64`), and macOS, with license notices retained and known-answer tests (KATs) executed in unit tests.
- [x] Implement 6-digit desktop pairing (`PAIR_ONLY` + `spake2-pin-v1`):
  - Cryptographically random 6-digit code preserving leading zeroes, 120s expiry, one-use, max 3 failed attempts across the pairing window before invalidation.
  - Directional key-confirmation keys derived from SPAKE2 shared secret, bound to session ID, roles, both nonces, both persistent identity keys, advertised presence ID, actual TLS server certificate SPKI, and `PAIR_ONLY`.
  - Mutual ECDSA P-256 signature verification from both persistent identities and explicit approval by both users before enrollment.
  - Zero secret buffers after use.
- [x] Implement `glide://pair/v2#...` codec and pair-only QR/link handshake (`PAIR_ONLY` + `qr-token-v2`) in C#, Kotlin, and Swift:
  - Route `v1` invitations to transfer flow and `v2` invitations to pair-only flow; old v1-only parser path surfaces **Update Glide to pair this device** when encountering `v2`.
  - Enforce single-active-operation ownership so pairing during an active transfer (or vice versa) returns **This device is busy — try again**.
  - Clear/reconcile any partially completed handshake on restart.

### Stage 4: Trusted Reconnect (`TRUSTED_SEND`)
- [x] Implement saved-device connection without a fresh invitation in `DesktopTransferEngine` / `DesktopTransferRuntime` and Apple `GlideTransferEngine`:
  - Connect to discovered or manually validated local endpoint using `GLD2` (`TRUSTED_SEND`).
  - Perform mutual `identity-v1` challenge-response bound to the live TLS server certificate SPKI and expected saved peer fingerprint **before any file offer**.
  - Reject identity change (**Identity changed — pair again**) and capability downgrade; allow TLS certificate rotation only when persistent identity proof matches.
  - Enter existing `HELLO`/`OFFER`/`DATA` transfer flow; preserve explicit receiver offer consent even for paired peers.

### Stage 5: Platform UIs (Windows WPF, Linux Avalonia, macOS SwiftUI, Android Compose, iOS SwiftUI)
- [ ] **Send (Windows, Linux, macOS)**: Remove **Nearby device** and its navigation to Pair devices. Show only **Quick share with QR** and **Paired device**. Preserve staged files on connection failure.
- [ ] **Receive (Windows, Linux, macOS)**: Rename "Copy pairing link" to **Copy receive link**. No 6-digit PIN UI on Receive.
- [ ] **Pair devices (Windows, Linux, macOS)**: Compact header with **Find devices** and **Ready to pair**; single state-driven active panel below it, followed by **Paired devices** list, and secondary actions **Use QR or link** and **Can't find your device?** (local-address field + code and link-paste option). Match exact visible states and copy table.
- [ ] **Settings (Windows, Linux, macOS)**: Retain trusted-device inspection and revocation; show verified identity fingerprint and last verification in expandable/detail view, not in the primary row.
- [ ] **Android & iOS**: Add `glide://pair/v2` QR/link routing, peer approval, and pair result UI (no phone PIN entry page).
- [ ] Update WPF, Avalonia, Android, and Apple UI tests to match the new specifications.

### Stage 6: Accessibility, Security/Privacy Audit & Graphify Refresh
- [ ] Verify keyboard focus, Escape handling, modal focus restoration, screen-reader labels/announcements, minimum window sizes (`820×560` Windows, narrow Linux/macOS), 150–200% scaling, and Light/Dark/High-Contrast themes.
- [ ] Verify zero PINs, tokens, full URIs, or raw certificates in logs/notifications, and update `doc/security/NATIVE_APP_THREAT_MODEL.md`.
- [ ] Refresh Graphify index after architectural changes settle.

### Stage 7: Full Verification, Packaging, Physical/VM Evidence & Merge to `main`
- [ ] Run `pwsh -File tools/check-architecture.ps1 -FailFast`, `dotnet test desktop/Glide.slnx -c Release`, Android unit/lint/APK builds, Swift/macOS builds & tests, Linux `.deb`/`.rpm`/`.tar.gz`/`.run` packaging, and `pwsh -File tools/verify.ps1`.
- [ ] Execute live pairing and trusted reconnect validation across Windows Host, Ubuntu 26.04.1 Bridged VM, and Physical Android Phone (`Q4MFFQWK8559NN5L`), recording SHA-256 hashes and UI screenshots.
- [ ] Merge `codex/pair-only-and-trusted-reconnect` cleanly into `main` and remove temporary branch.
