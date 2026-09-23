# Evidence Report — HARD-041: Multi-Platform Release Artifact Packaging & Operations

**Author:** Gemini Code Assistant  
**Date:** 2026-09-23  
**Milestone:** M7 — Release and operations  
**Task:** HARD-041: Build/test release artifacts for supported platforms  
**Status:** COMPLETE ✅ (Android AAB/APK, Windows Standalone Zip, Linux Distribution Tarball, Web Client Package Produced, Verified, and Cryptographically Cataloged)  

---

## 1. Executive Summary

HARD-041 delivers end-to-end release artifact packaging, minification, cryptographic signing, alignment, and multi-platform packaging for all supported Glide platforms: **Android**, **Windows**, **Linux**, and the embedded **Web Client**.

All produced artifacts conform to the multi-platform architecture established in Session 95 (`android/`, `desktop/`, `assets/`, `apple/`), ADR-0019 (Zero-Install Ephemeral Web Gateway), and the Master Task System (`doc/TODO_MASTER.md`):

1. **Android Release Candidate Packaging:**
   - Assembled Google Play App Bundle (`app-release.aab`, 9.61 MB) and signed release tester APK (`app-release.apk`, 4.93 MB).
   - Enforced full R8 code optimization, dead-code stripping, and resource shrinking (`isMinifyEnabled = true`, `isShrinkResources = true`), resulting in an 82% size reduction compared to debug APK (27.57 MB).
   - Produced non-empty R8 deobfuscation mapping file (`mapping.txt`, 67.63 MB).
   - Verified APK Signature Schemes v2, v3, and v4 (fs-verity) via Android SDK `apksigner`.
   - Verified 16 KB page-size memory alignment via Android SDK `zipalign -c -P 16 -v 4` (Android 15+ & 17 compliance).
   - Audited manifest badging via `aapt2`: `dev.glide.app`, `versionCode=1`, `versionName=0.1.0-alpha`, `minSdk=26`, `targetSdk=37`, non-debuggable (`application-debuggable` absent).

2. **Windows Desktop Packaging:**
   - Compiled `Glide.Windows.UI` targeting `win-x64` in `Release` configuration.
   - Packaged complete desktop distribution archive into `build/distributions/windows/glide-windows-win-x64.zip` (552 KB) containing the WPF application executable, core protocol engine (`Glide.Core.dll`), desktop transfer coordinator (`Glide.Desktop.dll`), and transport adapters (`Glide.Transport.dll`).
   - Implemented automated packaging pipeline script `desktop/windows/package-windows.ps1`.

3. **Linux Daemon & Service Packaging:**
   - Compiled `Glide.App` targeting `linux-x64` as a single-file self-contained release executable (`PublishSingleFile=true`).
   - Packaged distribution archive into `build/distributions/linux/glide-linux-linux-x64.tar.gz` (30.62 MB) bundling `Glide.App`, FreeDesktop `glide.desktop` entry, and systemd `glide.service` unit definition.
   - Executed via `desktop/linux/build-linux-packages.ps1`.

4. **Zero-CDN Embedded Web Client Packaging:**
   - Packaged the zero-install ephemeral Web transfer client assets (`index.html`, `style.css`, `app.js`) into `build/distributions/web/glide-web-client.zip` (10.9 KB).
   - Verified zero external CDN dependencies, strict Content-Security-Policy compliance, and identical SHA-256 parity between Android assets (`android/transport-lan/src/main/resources/glide/web/`) and Desktop assets (`desktop/src/Glide.Transport/WebRtc/Assets/`).

5. **Unified Multi-Platform Packaging Pipeline:**
   - Developed `tools/package-distributions.ps1` to assemble, package, clean staging artifacts, and generate unified SHA-256 and SHA-512 cryptographic manifests across all platform distributions (`build/distributions/SHA256SUMS.txt`, `doc/release/RELEASE_ARTIFACTS_SHA256.txt`).

---

## 2. Release Artifact Inventory & Distribution Catalog

| Platform | Artifact Filename | Distribution Path | File Size | Description / Target Purpose |
|---|---|---|:---:|---|
| **Android** | `app-release.aab` | `build/distributions/android/app-release.aab` | 9.61 MB | Production Google Play App Bundle with R8 optimization |
| **Android** | `app-release.apk` | `build/distributions/android/app-release.apk` | 4.93 MB | Signed tester APK (v2, v3, v4 signed, 16 KB aligned) |
| **Android** | `app-release.apk.idsig` | `build/distributions/android/app-release.apk.idsig` | 46.6 KB | Android fs-verity streaming signature metadata file |
| **Android** | `mapping.txt` | `build/distributions/android/mapping.txt` | 67.63 MB | Complete R8 deobfuscation symbol mapping |
| **Windows** | `glide-windows-win-x64.zip` | `build/distributions/windows/glide-windows-win-x64.zip` | 552 KB | Standalone Windows x64 WPF application & runtime DLLs |
| **Linux** | `glide-linux-linux-x64.tar.gz` | `build/distributions/linux/glide-linux-linux-x64.tar.gz` | 30.62 MB | Self-contained single-file daemon + systemd/desktop files |
| **Web** | `glide-web-client.zip` | `build/distributions/web/glide-web-client.zip` | 10.9 KB | Air-gapped zero-CDN Web transfer SPA (HTML/CSS/JS) |

---

## 3. Cryptographic Checksums (Supply-Chain Integrity)

### SHA-256 Digest Manifest (`doc/release/RELEASE_ARTIFACTS_SHA256.txt`)

```text
a2d12fb51e3fa293e9190849abae9ed72fb906dff05a2a18eb069ef1a5d941c2  android/app-release.aab
22d5db96b773c370a90c7fdf65f2b779e52437ecf4a2ccff94037b83d1489493  android/app-release.apk
e4c1de9006da57630b4c79773ae456d582cc14a8f9db5751db5edca631491cfd  android/mapping.txt
8596e909c7305fcd31ad8ae46aaf21cedb0cb1fd3cdb0f3c1b027244a65f1b9c  linux/glide-linux-linux-x64.tar.gz
b899693a97234cfdff611a16358449c50debb2b79bfae899fa3fad24558476d0  web/glide-web-client.zip
aa395c77c4e8d732d96579a802a76dfe062565d6e0f9ea1cee5bdd041d212282  windows/glide-windows-win-x64.zip
```

### SHA-512 Digest Manifest (`doc/release/RELEASE_ARTIFACTS_SHA512.txt`)

```text
f5e75f1d2643dd1847a43d0a907e199cda08338500c086d7fdc11d78322c8e4a69c6277fb09fde169a248d2324d7296f8b5254b9ce629e1f6cae2bbaec751875  android/app-release.aab
b412966b24469cc2cfa15c48be9e9fb28c649724a49e818a177a22b993aff61d79c4303cfb414c5eabe70e82c90aba9dc530cc9c75a7cba6f37438d7c75be301  android/app-release.apk
8863cf4a432b5ce22729204b4006d0ca70e1f6766cf780839e86d83694ea00e0dae140fbbb8a951a80b50552501d7ca33fb0762521a467398f47636a27294210  android/mapping.txt
047bf39298bbebde3f028231c0c0751fdef7cf0ac8dcbba7dbe47d076ce0db2f9c55f5b20707842cb3991826a6b974d97a137fcc54dd01ff778cb9bd3ff2c2b0  linux/glide-linux-linux-x64.tar.gz
affdc8b5df36529ea18ae0fc712b93167ff93a6e4e09cb8f0269ee0f90c4bf12a9e42b43b3b14afd51ba53b43a290dba46d1a00b3e1a8266341e440030aa7af5  web/glide-web-client.zip
e56a2c792be5641e128695b9438b34d1c9fdefdc8626b3169b531b24ab99d8e4244ec1fc1d513cde2e4a171cdd39a8bc0d55c4546f738a319b0032d0bc6e60af  windows/glide-windows-win-x64.zip
```

---

## 4. Android Signature Scheme Verification (`apksigner`)

Verification executed via `apksigner.bat verify --verbose --print-certs --v4-signature-file app-release.apk.idsig app-release.apk`:

```text
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v4 scheme (APK Signature Scheme v4): true
Verified for SourceStamp: false
Number of signers: 1
Signer #1 certificate DN: CN=Glide Release Candidate, OU=Mobile Release, O=Glide P2P, L=Mountain View, ST=California, C=US
Signer #1 key algorithm: RSA
Signer #1 key size (bits): 4096
```

- **v1 scheme (JAR):** Disabled (target/minSdk 26+ uses v2+ APK signing blocks).
- **v2 scheme (APK v2):** Verified `true`.
- **v3 scheme (APK v3):** Verified `true`.
- **v4 scheme (fs-verity):** Verified `true` via `app-release.apk.idsig`.
- **Result:** **100% PASS ✅**

---

## 5. 16 KB Page Alignment Verification (`zipalign`)

Verification executed via `zipalign.exe -c -P 16 -v 4 app-release.apk`:

```text
...
Verification successful
```

- **Alignment:** 16 KB boundary alignment verified across all uncompressed native libraries and zip entries.
- **Compliance:** Android 15 (API 35) and Android 17 (API 37) native page size requirements satisfied.
- **Result:** **100% PASS ✅**

---

## 6. Manifest Badging & Security Audit (`aapt2`)

Extracted via `aapt2.exe dump badging app-release.apk`:

- **Package Name:** `dev.glide.app`
- **Version Code:** `1`
- **Version Name:** `0.1.0-alpha`
- **Min SDK:** `26` (Android 8.0 Oreo)
- **Target SDK:** `37` (Android 17 Baklava)
- **Application Debuggable:** `false` (`application-debuggable` flag absent)
- **Result:** **100% PASS ✅**

---

## 7. Windows Desktop Distribution Contents

Contents of `build/distributions/windows/glide-windows-win-x64.zip` (552 KB):

```text
Glide.Core.dll                      (244,736 bytes) - Core protocols, cryptographic primitives, trust stores
Glide.Desktop.dll                   ( 93,184 bytes) - Desktop state machine, session engine, settings
Glide.Transport.dll                 (167,936 bytes) - LAN sockets, mDNS, WebRTC, Web gateway
Glide.Windows.UI.dll                (156,160 bytes) - WPF UI controls, views, viewmodels
Glide.Windows.UI.exe                (236,032 bytes) - Win-x64 executable entry point
Glide.Windows.UI.deps.json          (  1,772 bytes) - Runtime dependency resolution
Glide.Windows.UI.runtimeconfig.json (    519 bytes) - .NET runtime configuration
Debug Symbols (*.pdb)               - Private debugging symbols
```

---

## 8. Linux Distribution Contents

Contents of `build/distributions/linux/glide-linux-linux-x64.tar.gz` (30.62 MB):

```text
Glide.App       (74,063,308 bytes) - Single-file self-contained Linux executable (.NET 10)
glide.desktop   (       366 bytes) - FreeDesktop menu and application launcher metadata
glide.service   (       779 bytes) - Systemd user service unit for headless daemon operation
Debug Symbols   (*.pdb)            - Deobfuscation and diagnostics symbols
```

---

## 9. Web Client Distribution Contents

Contents of `build/distributions/web/glide-web-client.zip` (10.9 KB):

```text
index.html      (  3,703 bytes) - Zero-CDN responsive HTML5 single-page application
style.css       (  4,539 bytes) - Pure CSS styling (light/dark theme, responsive mobile/desktop)
app.js          (  7,804 bytes) - Vanilla ES2022 client for ephemeral WebRTC/LAN transfers
```

- **CDN Invariant:** Zero external resources loaded.
- **CSP Security:** Strict `default-src 'self'` policy.

---

## 10. Verification Verdict

All criteria for **HARD-041** are fully satisfied:
- Release packages for Android, Windows, Linux, and Web have been compiled, packed, inspected, and verified.
- R8 minification and resource shrinking verified.
- Signatures (v2, v3, v4) and 16 KB page alignment verified.
- Cryptographic SHA-256 and SHA-512 catalogs written and committed.
- Packaging scripts (`tools/package-distributions.ps1`, `desktop/windows/package-windows.ps1`, `desktop/linux/build-linux-packages.ps1`) verified.
- **Task Status:** **COMPLETE ✅**
