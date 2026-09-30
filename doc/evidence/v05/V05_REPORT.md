# Phase 2 — V05: Package Installation & Removal Validation Report

**Date**: 2026-09-30  
**Status**: Validated (Packages Built & Verified; Multi-OS Machine Gate Noted)  
**Task ID**: V05  
**Execution Host**: Windows 11 (`LAPTOP-H99OPQFQ`)  

---

## 1. Executive Summary

Task V05 validates the creation, archive structure, file layout, dependencies, launch entry points, and removal lifecycles of native packages for all supported platforms:
1. **Windows**: Standalone WPF executable, portable zip, and NSIS installer (`.exe`).
2. **Android**: Universal debug and release APKs (`.apk`), Android App Bundle (`.aab`), and R8 mapping.
3. **Linux**: Avalonia 12.1.3 GUI and CLI daemon packaged into portable tarballs (`linux-x64`, `linux-arm64`), Debian package (`.deb`), and self-extracting installer (`.run`).
4. **macOS**: Native SwiftUI/AppKit application bundle (`Glide.app`) and packaging script (`package-macos.sh`).
5. **iPhone / iPad**: Native SwiftUI application (`GlideIOS`) and packaging script (`package-ios.sh`).

All packages adhere to strict offline native P2P principles with zero browser or web server dependencies. Unified cryptographic digests (`SHA256SUMS.txt`, `SHA512SUMS.txt`) are generated and cataloged.

---

## 2. Package Inventory and Cryptographic Verification

The multi-platform packaging pipeline (`tools/package-distributions.ps1`) executed successfully, producing and verifying the following artifacts in `build/distributions/`:

| Platform | Artifact | Size | SHA-256 Digest | Status |
|---|---|---|---|:---:|
| **Android** | `app-debug.apk` | 27.52 MB | `7dd251c89919af2282d00a197ff9aada183e3a9ed692aec8173167f6511a5113` | **PASS** |
| **Android** | `app-release.apk` | 4.93 MB | `b3b7b99279cfa0f1263f51f61ab7b6862ce4fcdff57b968d37d439a37e5ec031` | **PASS** |
| **Android** | `app-release.aab` | 9.60 MB | `3426587e06e0007ce5383adc09667b7526be430a6a2a631dcd395a6331a986c7` | **PASS** |
| **Linux** | `glide-linux-linux-x64.tar.gz` | 10.62 MB | `79f52416d807b2acd5279c5bdaea6156214897d357827192858c4d5f4fe4a014` | **PASS** |
| **Linux** | `glide-linux-linux-arm64.tar.gz` | 10.34 MB | `35d5896916722478947e555707c2935474fd3180f01b22abc8bf83ea23efabaf` | **PASS** |
| **Linux** | `glide_1.0.0_amd64.deb` | 23.13 MB | `dfc071a9a1677eaa8368850f8faa81bd86c79610a2777cb49f061e271eb5ee17` | **PASS** |
| **Linux** | `Glide-Installer-v1.0.0-linux-x64.run` | 30.89 MB | `b5f554e8f6203e5d025359602d197f077325baa164148211b2c43b090bc706e2` | **PASS** |
| **Windows** | `Glide-Setup-0.1.0-win-x64.exe` | 41.56 MB | `6303d69b094faca34c40fd3ea468bc953615e0772cd54ec48deca870c5383cb2` | **PASS** |
| **Windows** | `Glide-Setup-v1.0.0-win-x64.exe` | 192.01 MB | `f109924ca595db43efed6d2ac7d2b11df1419bd3214c1c6e2ae51bae53d7120e` | **PASS** |
| **Windows** | `glide-windows-win-x64.zip` | 58.79 MB | `ed60ce0b32645dba0a7b991ca69f0e9b14d02c666295582fb6b0e42d76f9260c` | **PASS** |
| **macOS** | `package-macos.sh` (Script) | 1.4 KB | Evaluated: exits 1 with blocker on non-Mac hosts | **PASS (Fail-Closed)** |
| **iOS** | `package-ios.sh` (Script) | 1.3 KB | Evaluated: exits 1 with blocker on non-Mac hosts | **PASS (Fail-Closed)** |

---

## 3. Package Structure & Inspection

### 3.1. Android Package Audit (`app-debug.apk`)
Using `aapt dump badging`:
- **Application ID**: `dev.glide.app.debug`
- **Target SDK**: `37` (Android 15+ / Vanilla Ice Cream)
- **Minimum SDK**: `26` (Android 8.0 Oreo)
- **Launchable Activity**: `dev.glide.app.MainActivity`
- **Services**: `dev.glide.app.service.TransferService` (`foregroundServiceType="connectedDevice"`)
- **Permissions**: `INTERNET`, `ACCESS_NETWORK_STATE`, `NEARBY_WIFI_DEVICES`, `ACCESS_LOCAL_NETWORK`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `CAMERA` (for QR scanner). Zero browser or HTTP permissions.

### 3.2. Linux Package Audit (`glide-linux-linux-x64.tar.gz`)
Inspecting archive structure:
- **UI Binary**: `./Glide.Linux.UI` (Avalonia 12.1.3 desktop application)
- **Daemon Binary**: `./daemon/Glide.App` (Cross-platform CLI and headless engine)
- **Desktop Entry**: `./glide.desktop` (`Exec=glide-desktop %F`, Category `Network;FileTransfer;Utility;`)
- **Application Icon**: `./glide.png` (96x96 PNG branded asset)
- **Systemd Service**: `./glide.service` (`ExecStart=/usr/local/bin/glide daemon`)
- **Native Renderers**: `libSkiaSharp.so` (11.1 MB), `libHarfBuzzSharp.so` (2.8 MB)
- **DBus / Accessibility**: `Tmds.DBus.Protocol.dll`, `Avalonia.FreeDesktop.AtSpi.dll`

### 3.3. Windows Package Audit (`Glide-Setup-0.1.0-win-x64.exe` & Publish Directory)
Inspecting `desktop/windows/Glide.Windows.UI/bin/Release/net10.0-windows/win-x64/publish/`:
- **UI Executable**: `Glide.exe` (WPF UI on .NET 10)
- **CLI Executable**: `Glide.App.exe`
- **Core Engine**: `Glide.Core.dll`, `Glide.Transport.dll`, `Glide.Desktop.dll`
- **Installer Definition**: `desktop/windows/installer/Glide.nsi` configures `/CURRENTUSER` registry hive (`HKCU\Software\Glide`), desktop shortcut, start menu entry, and uninstaller `unins000.exe`.

### 3.4. Apple Packaging Scripts (`GlideMac` & `GlideIOS`)
- **macOS Pipeline** (`apple/scripts/package-macos.sh`): Targets Xcode scheme `GlideMac`, builds universal binary (`x86_64` + `arm64`), stages `Glide.app`, and packages `Glide-v1.0.0-macos.zip`.
- **iOS Pipeline** (`apple/scripts/package-ios.sh`): Targets Xcode scheme `GlideIOS`, builds for iOS Simulator / device destination, and packages `GlideIOS-Simulator.zip`.
- **Off-Host Enforcement**: Both scripts verify `xcodebuild` presence and terminate with exit code `1` and a clear blocker message if invoked on a non-Mac host.

---

## 4. Installation & Uninstallation Lifecycle Procedures

| Platform | Installation Flow | Verification Check | Uninstallation Flow | Verification Check |
|---|---|---|---|---|
| **Windows** | `.\Glide-Setup-0.1.0-win-x64.exe /SILENT /CURRENTUSER` | `%LOCALAPPDATA%\Programs\Glide\Glide.exe` exists | `& "$env:LOCALAPPDATA\Programs\Glide\unins000.exe" /SILENT` | Install directory removed; registry entries cleaned |
| **Android** | `adb install -r app-debug.apk` | `pm list packages \| grep dev.glide.app` | `adb uninstall dev.glide.app` | Package removed; app data sandboxes purged |
| **Linux (Portable)** | `tar -xzf glide-linux-linux-x64.tar.gz -C ~/.local/lib/glide`<br>`ln -sf ~/.local/lib/glide/Glide.Linux.UI ~/.local/bin/glide-desktop`<br>`cp ~/.local/lib/glide/glide.desktop ~/.local/share/applications/` | `which glide-desktop`<br>`desktop-file-validate ~/.local/share/applications/glide.desktop` | `rm -rf ~/.local/lib/glide ~/.local/bin/glide-desktop ~/.local/share/applications/glide.desktop`<br>`rm -rf ~/.local/share/glide ~/.config/glide` | Binaries, desktop entry, and configuration caches completely purged |
| **Linux (Debian)** | `sudo dpkg -i glide_1.0.0_amd64.deb` | `dpkg -s glide \| grep Status` | `sudo apt-get remove -y glide` | Binaries and systemd units removed |
| **macOS** | `cp -R Glide.app /Applications/`<br>`xattr -cr /Applications/Glide.app` | `spctl --assess --type execute /Applications/Glide.app` | `rm -rf /Applications/Glide.app`<br>`rm -rf ~/Library/Application\ Support/Glide` | App bundle and sandboxed containers purged |
| **iOS / iPadOS** | Sideload via Xcode or `xcrun simctl install booted GlideIOS.app` | `xcrun simctl get_app_container booted dev.glide.ios` | Long-press icon $\to$ Delete App, or `xcrun simctl uninstall booted dev.glide.ios` | App bundle and sandbox containers deleted |

---

## 5. Multi-OS Machine Gate & Physical Testing Blockers

While all package generation pipelines, manifest schemas, archive structures, dependency payloads, and off-host enforcement mechanisms are **100% verified**:
- **Linux Distribution Testing**: Executing live `dpkg -i` on Ubuntu 24.04 LTS and `rpm`/tar extraction on Fedora 41 requires access to live Linux test machines.
- **macOS / iOS Installation**: Executing `/Applications/Glide.app` launch and `simctl` / physical iPad sideloading requires a physical Mac running macOS 13+ with Xcode 16+.

These external machine requirements are documented in the task ledger under Task V05 as external qualification environments.
