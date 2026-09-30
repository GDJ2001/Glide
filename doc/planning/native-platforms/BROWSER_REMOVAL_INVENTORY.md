# Browser-Based Sharing Removal Inventory (D03)

## 1. Overview & Rationale

As part of delivering full native applications for **iPhone, iPad, macOS, and Linux**, browser-based file sharing is completely removed from Glide.

The local HTTP and WebRTC browser gateways introduced significant security surface area (unencrypted or transitional transport on local networks, bearer credentials in URLs, advisory consent, and platform limitations in browser filesystem storage). Native application-to-application transfer over TLS 1.3 replaces browser sharing entirely.

---

## 2. Removal Inventory by Module

### A. Android Workspace (`android/`)
| Component / File | Type | Status |
|---|---|---|
| `android/app/.../ui/web/WebShareSheet.kt` | UI Composable | **Removed** |
| `android/app/.../ui/web/WebShareViewModel.kt` | ViewModel | **Removed** |
| `android/app/.../navigation/GlideRoute.kt` | Route Definition | **Verified clean** (no WebShare route) |
| `android/app/.../navigation/GlideAppNavDisplay.kt` | Navigation Host | **Removed** `WebShareViewModel` and `WebShareSheet` |
| `android/app/.../ui/selection/SelectionScreen.kt` | Selection UI | **Removed** `onShareViaWeb` callback and "Share via Browser" buttons |
| `android/transport-lan/.../web/GlideWebGateway.kt` | HTTP Gateway | **Removed** |
| `android/transport-lan/.../web/GlideWebAssets.kt` | Embedded SPA Assets | **Removed** |
| `android/transport-lan/.../web/WebPathSanitizer.kt` | Path Sanitizer | **Removed** |
| `android/transport-webrtc/` | Module | **Removed** |
| `android/core/.../WebRtcTransport.kt` | Interface | **Removed** |
| `android/settings.gradle.kts` | Build Config | **Unlinked** `:android:transport-webrtc` |
| Build verification | `:android:app:assembleDebug` | **PASSED** (0 errors, APK generated) |

### B. Desktop Workspace (`desktop/`)
| Component / File | Type | Status |
|---|---|---|
| `desktop/windows/Glide.Windows.UI/.../MainViewModel.cs` | WPF ViewModel | **Removed** `_webGateway`, `WebShareUrl`, commands |
| `desktop/src/Glide.App/Program.cs` | CLI Executable | **Updated** `web` command exits with code 1; removed `RunWebAsync` |
| `desktop/linux/glide.desktop` | FreeDesktop Entry | **Updated** default `Exec=glide-desktop %F`, removed `WebShare` action |
| `desktop/src/Glide.Transport/Web/GlideWebGateway.cs` | HTTP Gateway | **Removed** |
| `desktop/src/Glide.Transport/Web/Assets/` | Embedded SPA Assets | **Removed** (`index.html`, `style.css`, `app.js`) |
| `desktop/src/Glide.Transport/WebRtc/SecureBrowserGateway.cs` | WebRTC Gateway | **Removed** |
| `desktop/src/Glide.Transport/WebRtc/GlideWebRtcAssets.cs` | WebRTC Assets | **Removed** |
| `desktop/src/Glide.Transport/WebRtc/DotNetWebRtcTransferTransport.cs`| WebRTC Transport | **Removed** |
| `desktop/src/Glide.Transport/WebRtc/Assets/` | Embedded SPA Assets | **Removed** (`index.html`, `style.css`, `app.js`) |
| `desktop/src/Glide.Core/Transport/WebRtc/IWebRtcTransferTransport.cs`| Interface | **Removed** |
| `desktop/tests/Glide.Tests/WebGatewayIntegrationTests.cs` | Unit Tests | **Removed** |
| `desktop/tests/Glide.Tests/WebSecurityConformanceTests.cs` | Unit Tests | **Removed** |
| `desktop/tests/Glide.Tests/Hard003WebGatewayRegressionTests.cs` | Unit Tests | **Removed** |
| `desktop/tests/Glide.Tests/Fakes/FakeWebRtcTransferTransport.cs` | Test Fake | **Removed** |
| `desktop/windows/.../InvitationService.cs` | UI Service | **Removed** unused `using Glide.Transport.Web;` |
| `desktop/windows/.../LanListenerService.cs` | UI Service | **Removed** unused `using Glide.Transport.Web;` |
| Build verification | `dotnet build desktop/Glide.slnx -c Release` | **PASSED** (0 warnings, 0 errors) |

### C. Tooling & Documentation (`tools/`, `doc/`)
| Component / File | Type | Status |
|---|---|---|
| `tools/verify-web-transfer-spec.ps1` | Script | **Removed**; it still contained checks requiring the retired browser gateway |
| `doc/future/ANDROID_BROWSER_WEB_SPECIFICATION.md` | Doc | **Marked Superseded by Native Platform Delivery** |

---

## 3. Preserved Native Components (Not Removed)
The following components are strictly preserved and remain the foundation of native transfer:
- `glide://pair/v1#` native QR invitations and URI scheme.
- TLS 1.3 socket listeners and clients.
- `_glide._tcp` mDNS discovery service.
- Bounded framing (`LengthPrefixedFrameChannel`, `LanRecordChannel`).
- `QuarantineStagingStore` and `WindowsPathSanitizer`.
- SAS 6-digit verification (`SasCalculator`).
