# Native Platforms Environment & Toolchain Manifest (D01)

Recovery correction, 2026-10-01: the Apple baseline stays pinned to Xcode 26.6 for reproducibility even though Apple's requirements table may list newer releases. Recheck the exact compatible host image before running CI. Sources: [Apple Xcode requirements](https://developer.apple.com/xcode/system-requirements), [Fedora Workstation downloads](https://www.fedoraproject.org/workstation/download/). Build/packaging rows describe required targets, not completed output.

## 1. Pinned Toolchains & SDKs

| Platform / Subsystem | Toolchain Component | Pinned Version / Release | Build Number / Reference |
|---|---|---|---|
| **Windows Desktop** | .NET SDK | **10.0.103** | Runtime `net10.0`, `net10.0-windows` |
| | C# Language Version | SDK default for .NET 10 | Record `dotnet --info` in each build manifest |
| | Host OS Requirement | **Windows 11 (23H2/24H2)** | Required for native TLS 1.3 Schannel stack |
| **Android** | Java Development Kit (JDK) | **21+ (Installed: 26.0.1)** | Oracle OpenJDK / Temurin |
| | Android Gradle Plugin (AGP) | **9.4.1** | `com.android.application` / `library` |
| | Kotlin Compiler | **2.4.20** | `org.jetbrains.kotlin` |
| | Android SDK compileSdk | **API 37** | Defined in `android/app/build.gradle.kts` |
| | Android SDK minSdk | **API 26** | Android 8.0 (Oreo) |
| | Android SDK targetSdk | **API 37** | Defined in `android/app/build.gradle.kts` |
| | Gradle Wrapper | **9.6.1** | Checksum verified |
| **Linux Desktop** | Avalonia UI | **12.1.3** | Stable release (X11 / XWayland backend) |
| | .NET SDK | **10.0.103** | Target `net10.0` |
| | Qualification Target 1 | **Ubuntu 24.04 LTS** | Noble Numbat (x64 and ARM64) |
| | Qualification Target 2 | **Fedora Workstation 44** | Current qualification target; validate both architectures on target hosts |
| **Apple (iOS/macOS)** | Xcode IDE | **Xcode 26.6 baseline** | Record exact `xcodebuild -version` output on the Mac builder |
| | Swift Compiler | **Xcode 26.6 bundled compiler** | Record exact `swift --version`; package currently declares Swift tools 5.9 |
| | iOS / iPadOS Deployment Floor| **iOS 16.0 / iPadOS 16.0**| Preserved deployment floor |
| | macOS Deployment Floor | **macOS 13.0 (Ventura)** | Preserved deployment floor |
| | macOS Build Host Requirement | **macOS Tahoe 26.2–26.x** | Required by the pinned Xcode 26.6 baseline |
| | Swift Certificates Package | **Not integrated** | `GlideCoreKit/Package.swift` currently has no third-party dependency; TLS identity creation is open |

---

## 2. Build Host Matrix & Prerequisite Status

| Host Type | Required Capabilities | Current Local Host Status | Status & Actions |
|---|---|:---:|---|
| **Windows Host** | Builds .NET 10, WPF (`net10.0-windows`), Avalonia Linux assemblies (`net10.0`), Android APKs via Gradle wrapper | **Present & Verified** (`LAPTOP-H99OPQFQ`) | Fully verified via `tools/doctor.ps1`. Can compile Windows UI, Android app, and Linux Avalonia projects. |
| **Linux Host / Container** | Builds native `.deb` and `.rpm` packages, runs the GUI and daemon | **Not verified for this recovery** | Qualify on Ubuntu 24.04 and Fedora Workstation 44; Windows cross-compilation is not a Linux runtime check. |
| **Apple Mac Host** | Runs Xcode 26.6, compiles `GlideIOS` and `GlideMac`, signs development archives | **External host blocker** | This Windows host cannot compile or run the SwiftUI/AppKit app targets. |

---

## 3. Package Output Matrix

| Platform | Target Architecture | Package Format | Tooling |
|---|---|---|---|
| **Windows** | x64 | Self-contained EXE installer / portable ZIP | NSIS / PowerShell zip packaging |
| **Android** | ARM64, x86_64 | APK (`app-debug.apk`), signed release AAB | Gradle `:android:app:assembleDebug` |
| **Linux** | x86_64 (`x64`), AArch64 (`arm64`) | `.deb` (Debian/Ubuntu), `.rpm` (Fedora/RHEL) | `dpkg-deb`, `rpmbuild` via packaging scripts |
| **iPhone / iPad** | ARM64 (device), x86_64/ARM64 (simulator) | `.app` simulator, `.xcarchive` device | Xcodebuild |
| **macOS** | Universal / Apple Silicon (`arm64`) & Intel (`x64`) | Standalone `.app`, development ZIP / DMG | Xcodebuild |
