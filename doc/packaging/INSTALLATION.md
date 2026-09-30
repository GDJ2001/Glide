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
- **Debian / Ubuntu package**: `build/distributions/linux/glide_1.0.0_amd64.deb`
- **Portable archive**: `build/distributions/linux/glide-linux-linux-x64.tar.gz` and `glide-linux-linux-arm64.tar.gz`

### Installation (Debian / Ubuntu)
```bash
sudo dpkg -i build/distributions/linux/glide_1.0.0_amd64.deb || sudo apt-get install -f -y
```

### Uninstallation (Debian / Ubuntu)
```bash
sudo apt-get remove -y glide
# To remove user configuration and staging caches:
rm -rf ~/.local/share/glide ~/.config/glide
```

### Installation (Fedora / RHEL / Portable Archive)
Extract the portable archive to `~/.local`:
```bash
mkdir -p ~/.local/lib/glide ~/.local/bin ~/.local/share/applications ~/.config/systemd/user
tar -xzf build/distributions/linux/glide-linux-linux-x64.tar.gz -C ~/.local/lib/glide

# Create symlinks
ln -sf ~/.local/lib/glide/Glide.Linux.UI ~/.local/bin/glide-desktop
ln -sf ~/.local/lib/glide/daemon/Glide.App ~/.local/bin/glide

# Register desktop entry
cp ~/.local/lib/glide/glide.desktop ~/.local/share/applications/
update-desktop-database ~/.local/share/applications/ 2>/dev/null || true

# (Optional) Enable headless background daemon
cp ~/.local/lib/glide/glide.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now glide.service
```

### Uninstallation (Portable)
```bash
systemctl --user stop glide.service 2>/dev/null || true
systemctl --user disable glide.service 2>/dev/null || true
rm -rf ~/.local/lib/glide ~/.local/bin/glide ~/.local/bin/glide-desktop ~/.local/share/applications/glide.desktop ~/.config/systemd/user/glide.service
rm -rf ~/.local/share/glide ~/.config/glide
```

---

## 4. macOS (macOS 13+)

> Native Apple packages remain unbuilt and unverified until the macOS/Xcode implementation gate passes. These are development-host instructions, not release installation guidance.

### Packaging Outputs
- **App Bundle**: `build/distributions/apple/macos/Glide.app`
- **Zip Archive**: `build/distributions/apple/macos/Glide-v1.0.0-macos.zip`

### Packaging Command (macOS Build Host)
```bash
bash apple/scripts/package-macos.sh
```

### Installation
1. Move `Glide.app` to `/Applications`:
```bash
cp -R build/distributions/apple/macos/Glide.app /Applications/
```
2. Launch from Spotlight, Applications folder, or terminal after verifying the app's signing and provenance:
```bash
open /Applications/Glide.app
```

### Uninstallation
Quit Glide, then move `Glide.app` from Applications to Trash. Keep application support data unless the user explicitly chooses to erase transfer history and trust state.

---

## 5. iPhone & iPad (iOS 16+)

### Packaging Outputs
- **Simulator App**: `build/distributions/apple/ios/GlideIOS-Simulator.zip`
- **Xcode Archive**: `apple/GlideApple.xcodeproj` (Scheme: `GlideIOS`)

### Packaging Command (macOS Build Host)
```bash
bash apple/scripts/package-ios.sh
```

### Installation (iOS Simulator)
Boot an iOS 16+ Simulator and install:
```bash
xcrun simctl boot "iPhone 15" 2>/dev/null || true
xcrun simctl install booted apple/build/DerivedData/Build/Products/Release-iphonesimulator/GlideIOS.app
xcrun simctl launch booted dev.glide.ios
```

### Installation (Physical iPhone / iPad)
1. Open `apple/GlideApple.xcodeproj` in Xcode.
2. Select development signing team in Signing & Capabilities.
3. Select connected iOS device and press **Cmd + R** to run.

### Uninstallation
Touch and hold the **Glide** app icon on the Home Screen, tap **Remove App**, and confirm **Delete App**.
