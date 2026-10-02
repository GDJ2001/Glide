# Glide Development-Build & Virtual-Validation Baseline (E0)

**Date:** 2026-10-03  
**Initial HEAD Commit:** `94e3f91cc43db359cd3f0ea420263f3bbf79671a`  
**Initial Branch:** `codex/development-virtual-validation`  
**Product Version:** `0.1.0` (Development baseline)  
**Package-Specific Revisions:**
- Windows: `0.1.0.0`
- Android: `0.1.0` (versionCode `1`)
- Linux: `1.0.0-1` (Debian/RPM upstream package format)
- macOS / iOS: `0.1.0` (CFBundleShortVersionString `0.1.0`, CFBundleVersion `1`)

---

## 1. Toolchains and Host Environment

### Windows Development Host
- **Machine:** `LAPTOP-H99OPQFQ`
- **OS:** Windows 11 Pro NT `10.0.26200.0` (x64)
- **.NET SDK:** `10.0.103` (Targeting `net10.0` and `net10.0-windows`)
- **Java Runtime:** Oracle / OpenJDK `26.0.1` (`26.0.1+8-34`)
- **Android SDK:** Installed at `C:\Users\GDJ\AppData\Local\Android\Sdk` (compileSdk 37, AGP 9.4.1, Gradle 9.4.1)
- **Android Emulator AVD:** `Pixel_10` (system-images/android-37.1/google_apis_playstore_ps16k/x86_64, Android 37.1)
- **Packaging:** NSIS `3.12` installed at `C:\Program Files (x86)\NSIS\makensis.exe`
- **Shell / Orchestrator:** PowerShell 7.5.0 (`pwsh`)

### WSL 2 Virtual Environment
- **WSL Kernel:** Linux `6.6.36.6-microsoft-standard-WSL2` x86_64
- **Primary Distro:** `Glide-Test-Ubuntu-24.04` (Ubuntu 24.04 LTS)
- **Storage:** Dedicated ext4 VHDX on `D:\WSL\Glide-Test-Ubuntu-24.04\ext4.vhdx` (non-root test user `glidetest`)
- **Installed Linux Toolchain:**
  - .NET SDK: `10.0.401`
  - dpkg-deb: `1.22.6`
  - rpmbuild: `4.18.2`
  - rpm2cpio / cpio: `2.15`
  - sha256sum / cmp: GNU coreutils `9.4`
- **WSL Preservation:** `Ubuntu-18.04` and `docker-desktop` preserved unchanged.

### CI Runner Configuration (GitHub Actions)
- **macOS Runner:** `macos-26` (macOS 26.6.2, Darwin 25.6.0 arm64, Xcode 26.6 Build `17F113`, Apple Swift `6.3.3`)
- **Linux Multi-Arch Runners:**
  - `ubuntu-24.04` (x86_64 native)
  - `ubuntu-24.04-arm` (aarch64 native)

---

## 2. Initial Task Ledger Status (E0 Baseline)

| Gate ID | Gate Name | Target Environment | Initial Status | Blocker / Dependency |
|:---:|---|---|:---:|---|
| **E0** | Baseline & Toolchain Audit | Windows 11 host, WSL 2, GitHub Actions | **PASS** | Completed in this record. |
| **E1** | Correct the Record | Documentation & matrix reconciliation | **NOT RUN** | Pending execution in E1. |
| **E2** | Finish Development Packaging | Windows, Android, Linux, Apple | **NOT RUN** | Pending UI handler & packaging audit in E2. |
| **E3** | Repair Release Metadata | `tools/assemble-release-manifest.ps1` | **NOT RUN** | Pending data-driven refactor in E3. |
| **E4** | Freeze and Build Candidate | All platforms from single clean commit | **NOT RUN** | Awaiting E1–E3 completion. |
| **E5-WIN-LINUX** | Windows ↔ WSL 2 Virtual Matrix | Win 11 physical ↔ Ubuntu 24.04 WSL 2 | **NOT RUN** | Awaiting E4 frozen build set. |
| **E5-FEDORA-RPM** | Fedora RPM Package Verification | Fedora 44 container / VM | **NOT RUN** | Awaiting E4 frozen build set. |
| **E5-UBUNTU-DEB** | Ubuntu DEB Lifecycle Verification | Ubuntu 24.04 WSL 2 | **NOT RUN** | Awaiting E4 frozen build set. |
| **E5-ANDROID-UNIT** | Android Unit Tests & Lint | Android Gradle SDK 37 | **NOT RUN** | Awaiting E4 frozen build set. |
| **E5-ANDROID-EMU** | Android Emulator Lifecycle & Navigation | `Pixel_10` emulator (API 37.1) | **NOT RUN** | Awaiting E4 frozen build set. |
| **E5-APPLE-BACKEND** | Apple Backend Matrix | macOS 26.6.2 GitHub Actions runner | **NOT RUN** | Awaiting E4 frozen build set. |
| **E5-APPLE-UI** | Apple UI Smoke Tests & Simulator Launch | iPhone / iPad Simulators (iOS 26.5) | **NOT RUN** | Awaiting E4 frozen build set. |
| **E5-A11Y** | Virtual Accessibility & Lifecycle Walkthrough | Windows (Narrator), Android (TalkBack), iOS (VoiceOver), Linux (Orca) | **NOT RUN** | Awaiting E4 frozen build set. |
| **E6** | Release Handoff & Provenance Audit | Manifest audit, report compilation | **NOT RUN** | Awaiting E5 completion. |
| **P-PHYS-ANDROID** | Physical Android Transfer Matrix | Physical Android hardware over Wi-Fi | **BLOCKED** | USB debugging bus currently has 0 attached devices. |
| **P-PHYS-APPLE** | Physical Apple Signed Deployment | Physical iPhone / iPad / Mac | **BLOCKED** | Apple Developer provisioning credentials for `group.dev.glide.shared` unavailable. |
| **P-PHYS-LINUX** | Independent Physical Linux LAN Transfers | Separate physical Linux PC on LAN | **BLOCKED** | No independent Linux machine currently attached to LAN. |
| **P-PUBLIC-RELEASE** | Production Signing & Store Publication | Windows Store, Google Play, App Store | **BLOCKED** | Outside scope of development-installable virtual validation run. |
