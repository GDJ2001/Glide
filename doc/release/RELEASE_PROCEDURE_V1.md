# Glide v1.0.0 Production Release Procedure Runbook

**Document Version:** 1.0.0  
**Effective Date:** 2026-11-07  
**Scope:** Android, Windows, and Linux Multi-Platform Release, Google Play Closed Testing Integration, Verification Gates, and GitHub Publishing Workflow.  
**Target Application:** Glide — Fast Local Share (`dev.glide.app` / desktop / linux)

---

## 1. Executive Summary & Release Architecture

This runbook defines the authoritative, repeatable, end-to-end procedure for compiling, verifying, packaging, signing, cataloging, and publishing version `1.0.0` of Glide across its supported target platforms.

### Target Platforms for v1.0.0:
1. **Android**:
   - Primary Track: Production-signed Google Play Store App Bundle (`glide-1.0.0-vc1-release.aab`) distributed under **Closed Testing (Alpha)** on Google Play (`dev.glide.app`).
   - Direct Sideloading: Standalone signed release APK (`glide-1.0.0-vc1-release.apk`) attached directly to GitHub Releases for zero-friction installation.
   - Debug Builds: `app-debug.apk` and `app-debug.aab` packaged into distribution manifests.
2. **Windows (`net10.0-windows`, `win-x64`)**:
   - Portable Archive: Standalone self-contained single-file zip (`glide-windows-win-x64.zip`).
   - Windows Installer: Authenticode-ready NSIS graphical installer (`Glide-Setup-1.0.0-win-x64.exe`).
3. **Linux (`net10.0`, `linux-x64`)**:
   - Debian Package: `glide_1.0.0_amd64.deb` (`/usr/bin/glide`, systemd service, desktop entry).
   - Red Hat Package: `glide-1.0.0-1.x86_64.rpm`.
   - Portable Archive: `glide-1.0.0-linux-x64.tar.gz`.
4. **Apple (iOS / macOS)**:
   - Excluded from v1.0.0 binary deliverables per `doc/planning/native-platforms/RECOVERY_STATUS.md` (physical device signing and Apple Developer account provisioning are tracked under subsequent recovery milestones). Handled via `-AllowMissing` switch in manifest assembly.

---

## 2. Toolchain & Environment Prerequisites

All builds and verifications must be performed on a host conforming to `tools/doctor.ps1`:

| Component | Minimum Version | Verification Command | Notes |
| :--- | :--- | :--- | :--- |
| **PowerShell** | 7.0+ (`pwsh`) | `pwsh --version` | Required runner for all `tools/` scripts |
| **Java JDK** | 21+ (Temurin/OpenJDK) | `javac -version` | Android build and dexing |
| **Android SDK** | API 37, Build Tools 35+ | `sdkmanager --list` | `ANDROID_HOME` or `sdk.dir` in `local.properties` |
| **.NET SDK** | 10.0.100+ | `dotnet --version` | Desktop and Linux build toolchain |
| **NSIS** | 3.10+ | `makensis -VERSION` | Windows installer compilation |
| **Git** | 2.40+ | `git --version` | Working tree and tag management |
| **GitHub CLI** | 2.40+ (`gh`) | `gh --version` | Release creation and asset upload |

Run the doctor verification script:
```powershell
pwsh -File tools/doctor.ps1
```
Ensure all required components report `[OK]`.

---

## 3. Pre-Build Static Analysis & Architecture Integrity Verification

Glide enforces strict zero-leakage boundaries between domain models, protocol engines, transport adapters, and UI layers. Run the architecture audit before compiling release artifacts:

```powershell
# 1. Architecture and import boundary audit (enforces 7 rules)
pwsh -File tools/check-architecture.ps1 -FailFast

# 2. Android documentation and baseline contract checks
cd android; .\gradlew.bat checkDocumentation --console=plain; cd ..
```

**Guardrail Rules Checked:**
- Pure Kotlin JVM isolation in `:transfer-core` (strictly zero Android, Compose, Room, or GMS dependencies).
- Zero transport/Nearby/socket imports inside ViewModel and UI layers.
- Zero blocking main-thread file I/O operations.
- Zero `allowMainThreadQueries()` in Room database configurations.
- Complete dependency catalog alignment without unreviewed dependencies.

---

## 4. Multi-Platform Compilation & Packaging

### 4.1 Android Packaging Workflow

#### 4.1.1 Debug Distribution Packages
Generates debug packages for distribution verification:
```powershell
pwsh -File tools/package-android.ps1 -Version 1.0.0
```
- **Outputs generated in `build/distributions/android/`:**
  - `app-debug.apk`: Debug APK.
  - `app-debug.aab`: Debug Android App Bundle.
  - `SHA256SUMS.txt`: SHA-256 digests.
  - `BUILD-MANIFEST.txt`: Build metadata and git provenance.

#### 4.1.2 Production Release Packaging (Play Store & Direct Sideload)
Compile the production-signed artifacts using the dedicated keystore:
```powershell
pwsh -File tools/build-play-release.ps1
```
- **Key Store Path:** `%USERPROFILE%\.glide-signing\glide-upload-keystore.jks` (Key alias: `glide-upload`, RSA-4096).
- **Outputs generated in `build/play-store-release/`:**
  - `glide-1.0.0-vc1-release.aab`: Production App Bundle submitted to Google Play Console.
  - `glide-1.0.0-vc1-release.apk`: Universal standalone release APK for direct GitHub Release distribution.
  - `mapping.txt`: R8 obfuscation mapping file for symbol deobfuscation.
  - `PLAY_UPLOAD_MANIFEST.json`: Complete bundle inspection record (validating zero `MANAGE_EXTERNAL_STORAGE`, zero `AD_ID`, 16 KB ELF page alignment, APK signature v2+v3).
  - `SHA256SUMS.txt`: Cryptographic checksums.

---

### 4.2 Windows Packaging Workflow

Build the self-contained single-file executable, portable archive, and NSIS installer:
```powershell
pwsh -File desktop/windows/package-windows.ps1 -Version 1.0.0
```
- **Outputs generated in `build/distributions/windows/`:**
  - `Glide-Setup-1.0.0-win-x64.exe`: NSIS Windows installer with start menu shortcut, uninstall registration, and protocol handler support.
  - `glide-windows-win-x64.zip`: Standalone portable ZIP package containing `Glide.Windows.UI.exe`, `glide.cmd`, and dependencies.
  - `SHA256SUMS-win-x64.txt`: SHA-256 checksums.
  - `BUILD-MANIFEST.txt`: Windows packaging metadata and commit hash.

---

### 4.3 Linux Packaging Workflow

Build native Linux packages (`.deb`, `.rpm`, `.tar.gz`) via the Linux build runner or GitHub Actions CI runner (`dotnet-linux` job):
```bash
# On a Linux host / container:
bash tools/package-linux-native.sh
```
- **Outputs generated in `build/distributions/linux/`:**
  - `glide_1.0.0_amd64.deb`: Debian/Ubuntu package with desktop shortcut and systemd unit.
  - `glide-1.0.0-1.x86_64.rpm`: Fedora/RHEL/openSUSE package.
  - `glide-1.0.0-linux-x64.tar.gz`: Portable binary tarball.
  - `SHA256SUMS-linux-x64.txt`: SHA-256 checksums.
  - `BUILD-MANIFEST.txt`: Linux packaging manifest.

---

## 5. Release Manifest Assembly & Integrity Verification

Assemble the consolidated release manifest from platform deliverables:
```powershell
pwsh -File tools/assemble-release-manifest.ps1 -Version 1.0.0 -AllowMissing
```
*(Use `-AllowDirty` if running with local uncommitted staging artifacts, or commit first).*

Verify that the catalog and manifest are correctly generated:
- `build/distributions/RELEASE_MANIFEST.json`
- `build/distributions/RELEASE_CATALOG.md`

Run the manifest regression test suite:
```powershell
pwsh -File tools/test-release-manifest.ps1
```

---

## 6. Comprehensive Verification Gate

Execute the primary repository verification gate to validate the entire suite across all platforms:
```powershell
pwsh -File tools/verify.ps1
```

Verification pipeline steps executed:
1. Documentation conformance (`gradlew checkDocumentation`).
2. Architecture rules (`tools/check-architecture.ps1 -FailFast`).
3. Windows Companion & Conformance (`tools/verify-windows-companion.ps1`).
4. Build targets (`tools/build.ps1 -Configuration Debug -Target All`).
5. Unit and integration tests (`tools/test.ps1 -Target All`).
6. Static analysis and lints (`tools/lint.ps1 -Target All`).

---

## 7. Google Play Store Closed Testing & Early Release Status

### 7.1 Track Configuration and Current State
- **Package Name:** `dev.glide.app`
- **Play Console App ID:** `4974528153095614274`
- **Developer Account ID:** `7458929157853686023`
- **Track Status:**
  - **Internal Testing:** `Active`
  - **Closed Testing (`Alpha`):** `Active • 1 track` (`✓ Publish a closed testing release` completed)
  - **Production Access Gate:** Subject to Google Play's mandatory requirement of at least 12 opted-in testers for 14 continuous days before worldwide public production rollout.

### 7.2 Access Links for End Users & Testers
Users can obtain Glide for Android through two parallel channels:

1. **Google Play Store (Closed Testing Alpha):**
   - **Web Opt-in:** [https://play.google.com/apps/testing/dev.glide.app](https://play.google.com/apps/testing/dev.glide.app)
   - **Android Play Store Listing:** [https://play.google.com/store/apps/details?id=dev.glide.app](https://play.google.com/store/apps/details?id=dev.glide.app)
2. **Direct APK Sideload (GitHub Release — Recommended for immediate use):**
   - Direct download of `glide-1.0.0-vc1-release.apk` from the GitHub Release page.
   - Sideloaded APKs are signed with the official release key, fully functional, and bypass the 14-day Google Play closed testing opt-in queue.

---

## 8. GitHub Release Publication Procedure

### 8.1 Tag Creation
```powershell
git tag -a v1.0.0 -m "Glide v1.0.0 Release"
git push origin v1.0.0
```

### 8.2 Release Publishing via GitHub CLI
Publish the release directly to GitHub with all platform artifacts attached:
```powershell
gh release create v1.0.0 `
    --title "Glide v1.0.0" `
    --notes-file "build/distributions/RELEASE_CATALOG.md" `
    build/play-store-release/glide-1.0.0-vc1-release.apk `
    build/play-store-release/glide-1.0.0-vc1-release.aab `
    build/distributions/windows/Glide-Setup-1.0.0-win-x64.exe `
    build/distributions/windows/glide-windows-win-x64.zip `
    build/distributions/linux/glide_1.0.0_amd64.deb `
    build/distributions/linux/glide-1.0.0-1.x86_64.rpm `
    build/distributions/linux/glide-1.0.0-linux-x64.tar.gz `
    build/distributions/RELEASE_MANIFEST.json
```

---

## 9. Rollback & Patch Procedure

If a critical severity (SEV-1/SEV-2) vulnerability or crash regression is discovered post-release:
1. **Google Play Store**: Immediately halt rollout in Play Console under **Releases overview** -> **Halt rollout**.
2. **GitHub Releases**: Mark `v1.0.0` as "Pre-release" with a security warning banner pointing to the remediation issue.
3. **Patch Preparation**:
   - Create hotfix branch `hotfix/1.0.1` from tag `v1.0.0`.
   - Implement minimal remediation with regression test.
   - Increment `versionCode` to `2` and `versionName` to `1.0.1`.
   - Re-execute full verification pipeline (`tools/verify.ps1`).
   - Package and publish `v1.0.1` per this runbook.
