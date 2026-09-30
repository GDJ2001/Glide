# Native Platforms Environment & Toolchain Manifest (D01)

## 1. Pinned Toolchains & SDKs

| Platform / Subsystem | Toolchain Component | Pinned Version / Release | Build Number / Reference |
|---|---|---|---|
| **Windows Desktop** | .NET SDK | **10.0.103** | Runtime `net10.0`, `net10.0-windows` |
| | C# Language Version | **13.0** | .NET 10 default |
| | Host OS Requirement | **Windows 11 (23H2/24H2)** | Required for native TLS 1.3 Schannel stack |
| **Android** | Java Development Kit (JDK) | **21+ (Installed: 26.0.1)** | Oracle OpenJDK / Temurin |
| | Android Gradle Plugin (AGP) | **9.4.1** | `com.android.application` / `library` |
| | Kotlin Compiler | **2.4.20** | `org.jetbrains.kotlin` |
| | Android SDK compileSdk | **API 37** | Android 15 / Baklava |
| | Android SDK minSdk | **API 26** | Android 8.0 (Oreo) |
| | Android SDK targetSdk | **API 35** | Android 15 |
| | Gradle Wrapper | **9.6.1** | Checksum verified |
| **Linux Desktop** | Avalonia UI | **12.1.3** | Stable release (X11 / XWayland backend) |
| | .NET SDK | **10.0.103** | Target `net10.0` |
| | Qualification Target 1 | **Ubuntu 24.04 LTS** | Noble Numbat (x64 and ARM64) |
| | Qualification Target 2 | **Fedora 41** | Stable Fedora release at kickoff (x64 and ARM64) |
| **Apple (iOS/macOS)** | Xcode IDE | **Xcode 16.2** | Build `16C5032a` |
| | Swift Compiler | **Swift 6.0.3** | Swift 6 language mode with 5.x interop |
| | iOS / iPadOS Deployment Floor| **iOS 16.0 / iPadOS 16.0**| Preserved deployment floor |
| | macOS Deployment Floor | **macOS 13.0 (Ventura)** | Preserved deployment floor |
| | macOS Build Host Requirement | **macOS 14.5+ or macOS 15**| Required host OS for Xcode 16.2 |
| | Swift Certificates Package | **1.6.1** | `apple/swift-certificates` pinned dependency |

---

## 2. Build Host Matrix & Prerequisite Status

| Host Type | Required Capabilities | Current Local Host Status | Status & Actions |
|---|---|:---:|---|
| **Windows Host** | Builds .NET 10, WPF (`net10.0-windows`), Avalonia Linux assemblies (`net10.0`), Android APKs via Gradle wrapper | **Present & Verified** (`LAPTOP-H99OPQFQ`) | Fully verified via `tools/doctor.ps1`. Can compile Windows UI, Android app, and Linux Avalonia projects. |
| **Linux Host / Container** | Builds native `.deb` and `.rpm` packages, runs systemd daemon | **WSL2 / Container Available** | Target Ubuntu 24.04 and Fedora 41 containerized packaging. |
| **Apple Mac Host** | Runs Xcode 16.2, compiles `GlideIOS`, `GlideMac`, code signs `.app` | **External Host Blocker** | The current local development machine is Windows 11. All Swift code, SwiftUI layouts, AppKit integration, and `GlideApple.xcodeproj` project files are authored and maintained directly in `apple/`, but binary compilation and simulator runs require a physical or remote macOS host. |

---

## 3. Package Output Matrix

| Platform | Target Architecture | Package Format | Tooling |
|---|---|---|---|
| **Windows** | x64 | Self-contained EXE installer / portable ZIP | Inno Setup / PowerShell zip packaging |
| **Android** | ARM64, x86_64 | APK (`app-debug.apk`), signed release AAB | Gradle `:android:app:assembleDebug` |
| **Linux** | x86_64 (`x64`), AArch64 (`arm64`) | `.deb` (Debian/Ubuntu), `.rpm` (Fedora/RHEL) | `dpkg-deb`, `rpmbuild` via packaging scripts |
| **iPhone / iPad** | ARM64 (device), x86_64/ARM64 (simulator) | `.app` simulator, `.xcarchive` device | Xcodebuild |
| **macOS** | Universal / Apple Silicon (`arm64`) & Intel (`x64`) | Standalone `.app`, development ZIP / DMG | Xcodebuild |
