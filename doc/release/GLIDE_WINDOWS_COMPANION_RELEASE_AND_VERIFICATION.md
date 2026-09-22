# Glide.Windows Companion Client Release & Verification Report

**Release Artifact:** `Glide.Windows` (C# .NET 10 desktop client & CLI)  
**Specification Authority:** [`ANDROID_WINDOWS_LAN_SPECIFICATION.md`](../future/ANDROID_WINDOWS_LAN_SPECIFICATION.md), [`ADR-0017`](../adr/ADR-0017-android-windows-interoperable-lan-slice.md), [`ADR-0018`](../adr/ADR-0018-lan-qr-authentication-and-routing.md), [`GLIDE_PROTOCOL_V1.md`](../protocol/GLIDE_PROTOCOL_V1.md)  
**Date:** 2026-09-17  
**Status:** VERIFIED & RELEASE-READY ✅  

---

## 1. Executive Summary

The **Glide.Windows** companion desktop client has been fully designed, implemented, and verified to provide 100% protocol and transport parity with Android Glide (`dev.glide.app`). It operates completely offline over the local Wi-Fi / Ethernet subnet without requiring cloud relays or internet connectivity.

### Delivered Projects in `windows/Glide.slnx`:
1. **`Glide.Core` (.NET 10 Class Library):**
   - Protocol V1 JSON envelope models and `ProtocolCodec` with canonical depth and length validation.
   - LAN Profile 1 framing models (`LanRecord`, `LanRecordCodec`, `LanUuidCodec`) adhering to the 5-byte header and 64 KiB ceiling.
   - Out-of-band QR pairing invitation codec (`InvitationCodec`) for `glide://pair/v1#` URIs and `LanBootstrap` handshake.
   - Security primitives: `SasCalculator` (canonical `ExtractDecimal6` formula).
   - Storage safety: `WindowsPathSanitizer` (POSIX-to-Windows traversal, UNC, drive letter, and DOS device filtering) and `QuarantineStagingStore` (sandboxed staging with atomic rename).
2. **`Glide.Transport` (.NET 10 Class Library):**
   - Zero-dependency RFC 6762 / RFC 6763 mDNS responder and browser (`MdnsDiscoveryService`) on `224.0.0.251:5353` advertising `_glide._tcp.local.`.
   - Length-prefixed framing channel (`LengthPrefixedFrameChannel`) and serialized LAN record channel (`LanRecordChannel`).
   - TLS 1.3 socket client and listener (`TlsSocketClient`, `TlsSocketListener`) with ALPN `glide-lan/1` / `glide-v1` and out-of-band SubjectPublicKeyInfo (SPKI) SHA-256 fingerprint pinning.
3. **`Glide.Windows.UI` (.NET 10 WPF Desktop Application):**
   - Modern dark-themed user interface implementing MVVM (`MainViewModel`, `DiscoveredPeerViewModel`).
   - Automatic mDNS peer discovery card list with 1-click target selection.
   - "Pair via Link / QR" dialog for entering out-of-band pairing invitations.
   - Receive mode with ephemeral QR invitation generation and 1-click clipboard copy.
   - Progress bar, speed tracking, active file name indicators, and automatic quarantine commit to `Downloads`.
4. **`Glide.App` (.NET 10 Console CLI Application):**
   - High-throughput command-line tool with subcommands: `send`, `receive`, `pair`, `list`, and `version`.
   - Full support for `--pair <uri>` out-of-band SPKI pinning and Profile 1 record streaming.
5. **`Glide.Tests` (.NET 10 xUnit Conformance Suite):**
   - 107 unit and integration tests covering protocol JSON envelopes, LAN binary records, path sanitizer, quarantine staging, SAS calculation, TLS loopback, and end-to-end QR-paired batch transfers.

---

## 2. Verification Proofs

### 1. Cross-Platform Conformance Test Results (`windows/Glide.Tests`)
```powershell
dotnet test windows/Glide.Tests/Glide.Tests.csproj -c Release --logger "console;verbosity=normal"
```
**Result:** **107 / 107 Passed (100% success rate)** in 1.1s.
- Conformance against 20+ Protocol V1 golden JSON envelopes.
- 100% byte-for-byte matching across all 7 LAN Profile 1 golden binary fixtures (`fixtures/lan/`).
- Out-of-band SPKI mismatch immediately aborts TLS handshake before application bytes leave client.
- Zero-byte files stream `LanRecord.StreamEnd` cleanly and verify empty SHA-256 digest (`e3b0c44...`).
- In-flight corrupted data chunks fail quarantine verification and trigger immediate file purge.
- Invalid or expired bootstrap tokens reject transfer with `AUTHENTICATION_FAILED`.

### 2. Windows Companion Auditor (`tools/verify-windows-companion.ps1`)
```powershell
pwsh -File tools/verify-windows-companion.ps1
```
**Result:** **ALL 7 CHECKS PASSED (0 violations)**:
- [Check 1/7] Toolchain & solution existence (.NET 10.0.103)
- [Check 2/7] Release solution build (`Glide.slnx` compiles all 5 projects with 0 errors)
- [Check 3/7] Conformance test suite (107/107 unit & integration tests passed)
- [Check 4/7] Windows filesystem security & quarantine rules
- [Check 5/7] Length-prefixed TLS 1.3 framing & SAS derivation
- [Check 6/7] LAN Profile 1 Records, QR Invitations, mDNS Discovery, LanRecordChannel & SPKI Pinning
- [Check 7/7] Zero leakage into Android build tree (`tools/check-architecture.ps1` 0 issues)

### 3. Full Repository Verification (`tools/verify-local.ps1`)
```powershell
pwsh -File tools/verify-local.ps1
```
**Result:** **ALL CHECKS PASSED**:
- 174 Android Gradle tasks clean and up-to-date.
- 0 Android Lint errors.
- 0 Architecture boundary violations.

---

## 3. Operational Security & Filesystem Invariants

1. **Denial-of-Service & Resource Bound Limits:**
   - Control frames capped at 16 KiB ($16,384\text{ bytes}$).
   - Data record chunks capped at 64 KiB ($65,536\text{ bytes}$).
   - JSON parsing depth bounded to $\le 4$ levels.
   - URI length bounded to $\le 2,768$ characters.
2. **Untrusted Filesystem Path Sanitization:**
   - Every inbound path is stripped of parent directory traversals (`..`), drive letters (`C:`), UNC prefixes (`\\`), and control characters.
   - Reserved Windows device names (`CON`, `PRN`, `AUX`, `NUL`, `COM1-9`, `LPT1-9`) are rejected or escaped.
3. **Quarantine Staging & Data Sanctity Invariant:**
   - Unverified incoming bytes are written strictly into `%LocalAppData%\Glide\staging\<sessionId>\`.
   - On completion, the incrementally calculated SHA-256 digest is compared to `ITEM_END.expectedSha256`.
   - On digest match: atomically moved to `%USERPROFILE%\Downloads\`.
   - On digest mismatch or connection abort: the quarantine file is immediately deleted. Zero unverified or corrupted bytes ever reach the user's downloads folder.
