# Glide Native Applications: Installation & Uninstallation Guide (D10)

This document provides developer-level installation and uninstallation procedures for Glide across all supported native platforms.

---

## 1. Windows 11 / 10

### Packaging Output
- **Installer**: `build/distributions/windows/Glide-Setup-v1.0.0-win-x64.exe`
- **Portable**: `desktop/windows/Glide.Windows.UI/bin/Release/net10.0-windows/win-x64/publish/`

### Installation
Run the installer executable:
```powershell
.\Glide-Setup-v1.0.0-win-x64.exe /SILENT /CURRENTUSER
```
Or for portable execution:
```powershell
dotnet publish desktop/windows/Glide.Windows.UI/Glide.Windows.UI.csproj -c Release -r win-x64
.\desktop\windows\Glide.Windows.UI\bin\Release\net10.0-windows\win-x64\publish\Glide.exe
```

### Uninstallation
Using Windows Settings:
1. Open **Settings > Apps > Installed apps**.
2. Locate **Glide** and select **Uninstall**.

Or via command-line:
```powershell
& "$env:LOCALAPPDATA\Programs\Glide\unins000.exe" /SILENT
```

---

## 2. Android (API 26 – API 37)

### Packaging Output
- **Debug APK**: `android/app/build/outputs/apk/debug/app-debug.apk`
- **Release APK**: `android/app/build/outputs/apk/release/app-release.apk`

### Installation
Deploy via ADB:
```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

### Uninstallation
Via ADB:
```bash
adb uninstall dev.glide.app
```

---

## 3. Linux (Ubuntu 24.04 LTS & Fedora Workstation 44)

> The old staged `.deb` and tarballs are historical artifacts. The native GUI package pipeline now writes commit-keyed outputs under `build/distributions/runs/`; install these only after a Linux-host build and package verification. Fresh `.rpm` installation remains unverified.

### Packaging Outputs
- **Debian / Ubuntu package**: `build/distributions/runs/<commit>-<timestamp>/linux/glide_1.0.0_amd64.deb` or `_arm64.deb`
- **Fedora package**: the same run directory, `glide-1.0.0-1.x86_64.rpm` or `.aarch64.rpm`
- **Portable archive**: the same run directory, `glide-1.0.0-linux-x64.tar.gz` or `-linux-arm64.tar.gz`

The run directory contains `BUILD-MANIFEST.txt` and `SHA256SUMS-<runtime>.txt`. Verify those against the exact package before installation. No package from this recovery branch has yet passed a target-host installation test.

The optional user daemon requires an enrolled trusted peer and an explicit per-peer headless policy. Keep `~/.config/glide/daemon.env` readable only by your user (`chmod 600`). Its one-use pairing URI is written to `XDG_RUNTIME_DIR/glide/daemon-invitation.txt`, which is replaced on rotation and removed after use or shutdown; service logs contain only the path.

### Installation (Debian / Ubuntu)
```bash
sudo apt install ./build/distributions/runs/<commit>-<timestamp>/linux/glide_1.0.0_amd64.deb
```

### Uninstallation (Debian / Ubuntu)
```bash
sudo apt-get remove -y glide
# The user's received files, history, and trust data are retained by default.
```

### Installation (Fedora / RHEL / Portable Archive)
For the Fedora package, install the verified `.rpm` from the same run directory with `sudo dnf install ./glide-1.0.0-1.x86_64.rpm`. For a portable archive, extract the verified archive to `~/.local`:
```bash
mkdir -p ~/.local/opt/glide ~/.local/bin ~/.local/share/applications
tar -xzf build/distributions/runs/<commit>-<timestamp>/linux/glide-1.0.0-linux-x64.tar.gz -C ~/.local/opt/glide

# Create symlinks
ln -sf ~/.local/opt/glide/usr/lib/glide/desktop/Glide.Linux.UI ~/.local/bin/glide-desktop
ln -sf ~/.local/opt/glide/usr/lib/glide/daemon/Glide.App ~/.local/bin/glide

# Register desktop entry
cp ~/.local/opt/glide/usr/share/applications/glide.desktop ~/.local/share/applications/
update-desktop-database ~/.local/share/applications/ 2>/dev/null || true

# The packaged systemd unit uses /usr/bin/glide; it belongs to the .deb/.rpm install.
# Portable users can run ~/.local/bin/glide daemon with an explicit trusted-peer policy.
```

### Uninstallation (Portable)
```bash
rm -rf ~/.local/opt/glide ~/.local/bin/glide ~/.local/bin/glide-desktop ~/.local/share/applications/glide.desktop
# User data remains in ~/.local/share/glide and ~/.config/glide.
```

---

## 4. macOS (macOS 13+)

> Native Apple packages remain unbuilt and unverified until the macOS/Xcode implementation gate passes. These are development-host instructions, not release installation guidance.

### Packaging Outputs
- **Unsigned development app ZIP**: `build/distributions/runs/<commit>-<timestamp>/apple/macos/Glide-macos-unsigned.zip`
- **Evidence**: `BUILD-MANIFEST.txt` and `SHA256SUMS.txt` in the same run directory

### Packaging Command (macOS Build Host)
```bash
bash apple/scripts/package-macos.sh
```

### Installation
The current ZIP is an unsigned development artifact, not a notarized release installer. After the Apple implementation/build gate and a separate signed release build, verify its manifest, expand the ZIP with `ditto -x -k`, and move `GlideMac.app` to Applications. Do not bypass macOS quarantine to make an unverified bundle launch.

### Uninstallation
Quit Glide, then move `Glide.app` from Applications to Trash. Keep application support data unless the user explicitly chooses to erase transfer history and trust state.

---

## 5. iPhone & iPad (iOS 16+)

### Packaging Outputs
- **Unsigned simulator ZIP**: `build/distributions/runs/<commit>-<timestamp>/apple/ios/Glide-iOS-simulator-unsigned.zip`
- **Unsigned device archive ZIP**: the same run directory, `Glide-iOS-device-unsigned-archive.zip`
- **Evidence**: `BUILD-MANIFEST.txt` and `SHA256SUMS.txt` in the same run directory

### Packaging Command (macOS Build Host)
```bash
bash apple/scripts/package-ios.sh
```

### Installation (iOS Simulator)
After a successful Xcode build, expand the simulator ZIP in a temporary directory, boot an iOS 16+ Simulator, and install the extracted app:
```bash
xcrun simctl install booted /path/to/extracted/GlideIOS.app
xcrun simctl launch booted dev.glide.ios
```

### Installation (Physical iPhone / iPad)
The device archive is unsigned and cannot be installed as distributed. Open `apple/GlideApple.xcodeproj` in Xcode, select a development signing team, build for a connected device, and validate the app and Share Extension there. Release distribution requires a separately signed archive.

### Uninstallation
Touch and hold the **Glide** app icon on the Home Screen, tap **Remove App**, and confirm **Delete App**.
