# Phase 2 — V06: Browser-Removal Regression Audit Report

**Date**: 2026-09-30  
**Status**: Validated  
**Task ID**: V06  
**Execution Host**: Windows 11 (`LAPTOP-H99OPQFQ`)  

---

## 1. Executive Summary

Task V06 executes a regression audit to certify that browser-based file sharing (HTTP/WebSocket/WebRTC gateways, embedded Single-Page Applications, and associated UI/CLI routes) has been removed from all codebases without leaving orphaned listeners, assets, or regression failures.

Audit results confirm:
1. **Zero Browser UI Elements**: No "Share via Browser", QR web links, or web sheet dialogs exist in any UI layer.
2. **Zero HTTP/WebSocket Listeners**: All HTTP servers (`HttpListener`, Ktor, embedded Jetty/Netty) and WebRTC data channels for browser clients have been purged.
3. **Zero Web Asset Bundles**: No `index.html`, `app.js`, `style.css`, or web UI assets exist in production source trees.
4. **CLI `web` Deprecation**: Running `glide web` terminates immediately with exit code 1 and a deprecation advisory.
5. **Native Desktop Entry**: `desktop/linux/glide.desktop` targets `glide-desktop %F` exclusively.
6. **Native QR/Link Interoperability Intact**: Native LAN Profile 1 transfer (`glide://pair/v1#`) operates with 100% test pass rate across 1,632 automated tests.

---

## 2. Regression Audit Evidence

| Area | Audit Mechanism | Target / Query | Status |
|---|---|---|:---:|
| **CLI Web Route** | Command line invocation | `dotnet run --project desktop/src/Glide.App -- web` | **PASS (Exited 1 with deprecation error)** |
| **Linux Launcher** | Desktop entry inspection | `desktop/linux/glide.desktop` | **PASS (`Exec=glide-desktop %F`)** |
| **Desktop Asset Audit** | Filesystem scan | `desktop/src` for `*.html`, `*.js`, `*.css` | **PASS (0 files found)** |
| **Android Asset Audit** | Filesystem scan | `android/` source tree for `*.html`, `*.js`, `*.css` | **PASS (0 files in `src/`)** |
| **Windows Companion Check 7** | `tools/verify-windows-companion.ps1` | Native P2P exclusivity and browser removal check | **PASS (0 issues)** |
| **Android UI Surface** | Code review | `SelectionScreen.kt`, `GlideAppNavDisplay.kt` | **PASS (0 web controls)** |
| **Desktop UI Surface** | Code review | `MainViewModel.cs`, `MainWindow.axaml` | **PASS (0 web controls)** |
| **Native Wire Interop** | Automated test suite | `tools/test.ps1 -Target All -Configuration Release` | **PASS (1,632 tests passed, 0 failures)** |

---

## 3. Detailed Verification Items

### 3.1. CLI Invocation Behavior
Executing `glide web` produces:
```
=================================================================
        Glide.Windows — High-Speed Local File Sharing Companion  
               Zero Cloud • 100% Offline Local LAN               
=================================================================

[ERROR] The browser sharing gateway has been retired.
Glide provides dedicated native applications for Windows, Android, macOS, iOS, and Linux.
Please use direct native application-to-application transfer for secure local sharing.
```
Process exits immediately with exit code `1`. No sockets are opened, and no web server is instantiated.

### 3.2. Elimination of Web Gateway Dependencies
- `desktop/src/Glide.Transport/Web/` and `WebRtc/`: Deleted.
- `desktop/src/Glide.Core/Transport/WebRtc/`: Deleted.
- `android/transport-webrtc/`: Deleted and unlinked from `android/settings.gradle.kts`.
- `android/transport-lan/src/main/kotlin/dev/glide/transport/lan/web/`: Deleted.
- `android/transport-lan/src/main/resources/glide/web/`: Deleted.

### 3.3. Desktop Launchers & Menu Actions
- `desktop/linux/glide.desktop` specifies `Exec=glide-desktop %F` (Avalonia 12.1.3 GUI) and an optional `Receive` action running `glide receive`. All previous references to web share actions have been purged.

### 3.4. Native LAN Sharing Conformance
- Native peer-to-peer transfers continue to utilize TLS 1.3 over TCP on port 42424 with mDNS discovery (`_glide._tcp`) and QR link scheme `glide://pair/v1#`.
- All 289 desktop unit/integration tests and 1,343 Android tests execute cleanly with zero errors.
