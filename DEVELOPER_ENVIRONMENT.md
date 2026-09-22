# Glide Developer Environment (HARD-035)

Standardized tooling, scripts, and IDE configurations for the Glide repository.

---

## 1. Required Toolchains

| Tool | Minimum Version | Required By | Install |
|:---|:---|:---|:---|
| **JDK** | **21+** (25 recommended, 17 for bytecode target) | Android Gradle Plugin, Kotlin compiler | [Oracle JDK 25](https://www.oracle.com/java/technologies/downloads/) or [JetBrains Runtime 25](https://github.com/JetBrains/JetBrainsRuntime) |
| **Android SDK** | compileSdk **37**, minSdk **26**, targetSdk **37** | Android platform, build-tools, AGP | [Android Studio](https://developer.android.com/studio) |
| **Gradle** | wrapper-managed (embedded in repo) | Android & Kotlin builds | Included via `gradlew.bat` |
| **.NET SDK** | **10.0.100+** | Windows companion client (Glide.slnx) | [.NET 10 SDK](https://dotnet.microsoft.com/download/dotnet/10.0) |
| **Node.js** | **18+** (optional, present: v24.12.0) | No Node tooling currently used | [Node.js LTS](https://nodejs.org/) |
| **PowerShell** | **7.0+** | Root-level dev scripts (`doctor.ps1`, `build.ps1`, etc.) | [PowerShell 7+](https://github.com/PowerShell/PowerShell) |
| **Git** | any 2.x | Version control | Bundled with OS or [git-scm.com](https://git-scm.com) |

### Native / Platform Tools

| Tool | Version | Used By |
|:---|:---|:---|
| **adb** (Android Debug Bridge) | Platform-tools 35+ | Device install, logcat, preview |
| **Android Emulator** | API 26, 33, 37 system images | Instrumentation tests, preview |
| **keytool** (JDK) | Bundled with JDK | Keystore generation for signing |
| **apksigner / zipalign / aapt2** | Build-tools latest | APK signing, alignment, badging |

### Currently Installed (Developer Machine)

```
JDK:            26.0.1 (Java SE Runtime Environment)
.NET SDK:       10.0.103
Node.js:        v24.12.0
PowerShell:     7.6.6
Kotlin:         2.4.20 (via Gradle plugin)
AGP:            9.4.1
Compose BOM:    2026.09.00
Hilt:           2.60.1
Room:           2.8.5
```

---

## 2. Root-Level Developer Scripts

All scripts are PowerShell 7+ (`.ps1`) with batch (`.cmd`) wrappers for convenience.

| Script | Purpose | Example |
|:---|:---|:---|
| `doctor.ps1` | Validate all required toolchains are present | `pwsh -File doctor.ps1` |
| `build.ps1` | Build Android + Windows | `pwsh -File build.ps1 -Configuration Debug -Target All` |
| `test.ps1` | Run test suites (Android unit + Windows xUnit) | `pwsh -File test.ps1` |
| `lint.ps1` | Run Android Lint + dotnet format | `pwsh -File lint.ps1` |
| `verify.ps1` | Full CI gate (build, test, lint, architecture) | `pwsh -File verify.ps1` |
| `preview.ps1` | Install/launch on device or run Windows app | `pwsh -File preview.ps1 -Target Android` |

### Batch Wrappers

Each script has a `.cmd` wrapper for quick invocation:

```cmd
doctor.cmd
build.cmd Debug All
test.cmd All Debug
lint.cmd
verify.cmd
preview.cmd Android Debug
```

### Script Parameters

| Parameter | Values | Default | Used By |
|:---|:---|:---|:---|
| `-Configuration` | `Debug`, `Release` | `Debug` | build, test, lint, preview |
| `-Target` | `All`, `Android`, `Windows`, `WindowsUI`, `WindowsCLI` | varies | build, test, lint, verify, preview |
| `-Filter` | test filter expression | (none) | test |
| `-Fix` | auto-fix switch | off | lint |
| `-SkipTests`, `-SkipLint`, `-SkipArchitecture` | skip switches | off | verify |

---

## 3. IDE Launch Configurations

### Android Studio / IntelliJ IDEA

Project JDK is configured in `.idea/misc.xml`:

```xml
<component name="ProjectRootManager" version="2"
           languageLevel="JDK_17" project-jdk-name="jbr-25" project-jdk-type="JavaSDK" />
```

**JDK 25 (JetBrains Runtime)** is selected as the project SDK. Bytecode target is **17** (`.idea/compiler.xml`).

#### Run Configurations (`.idea/runConfigurations/`)

No custom run configurations are checked into `.idea/`. Use the Gradle tool window or root scripts instead.

#### Quick Android Studio Launch

```powershell
# After opening the repo root in Android Studio:
# 1. File → Project Structure → SDK → Select JDK 25 (jbr-25)
# 2. Use Gradle tool window → Tasks → build → assembleDebug
# 3. Or use terminal tab:
pwsh -File build.ps1 -Configuration Debug -Target Android
```

#### Useful Gradle Tasks (Android)

| Task | Description |
|:---|:---|
| `:app:assembleDebug` | Build debug APK |
| `:app:assembleRelease` | Build release APK (requires keystore) |
| `:app:testDebugUnitTest` | Run unit tests |
| `:app:lintDebug` | Run Android Lint |
| `:checkArchitecture` | Architecture enforcement gate |
| `:checkLanGate` | LAN verification gate |
| `:checkDocumentation` | Documentation presence check |

### Visual Studio 2022+

The Windows companion uses `.NET 10` and `net10.0` / `net10.0-windows` target frameworks.

#### Solution File

```
windows/Glide.slnx
```

#### Run Configurations (Visual Studio)

| Project | Output | Launch |
|:---|:---|:---|
| `Glide.Windows.UI` | WPF App | F5 to run the WPF desktop client |
| `Glide.App` | Console App | F5 or Ctrl+F5 for CLI tool |
| `Glide.Tests` | Test Project | Test Explorer → Run All |

#### Visual Studio Launch Settings (`Properties/launchSettings.json`)

Create in `windows/Glide.Windows.UI/Properties/launchSettings.json`:

```json
{
  "profiles": {
    "Glide.Windows.UI": {
      "commandName": "Project",
      "workingDirectory": "$(SolutionDir)"
    }
  }
}
```

Create in `windows/Glide.App/Properties/launchSettings.json`:

```json
{
  "profiles": {
    "Glide.App": {
      "commandName": "Project",
      "commandLineArgs": "receive --port 42424",
      "workingDirectory": "$(SolutionDir)"
    }
  }
}
```

### Visual Studio Code

Create `.vscode/launch.json` at the repository root:

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "name": "Build All (PowerShell)",
      "type": "PowerShell",
      "request": "launch",
      "script": "${workspaceFolder}/build.ps1",
      "args": ["-Configuration", "Debug", "-Target", "All"]
    },
    {
      "name": "Test All (PowerShell)",
      "type": "PowerShell",
      "request": "launch",
      "script": "${workspaceFolder}/test.ps1",
      "args": ["-Target", "All"]
    },
    {
      "name": "Run Windows UI (.NET)",
      "type": "coreclr",
      "request": "launch",
      "preLaunchTask": "build-windows",
      "program": "${workspaceFolder}/windows/Glide.Windows.UI/bin/Debug/net10.0-windows/Glide.Windows.UI.dll",
      "args": [],
      "cwd": "${workspaceFolder}/windows/Glide.Windows.UI",
      "console": "internalConsole"
    },
    {
      "name": "Run Windows CLI (.NET)",
      "type": "coreclr",
      "request": "launch",
      "preLaunchTask": "build-windows",
      "program": "${workspaceFolder}/windows/Glide.App/bin/Debug/net10.0/Glide.App.dll",
      "args": ["receive", "--port", "42424"],
      "cwd": "${workspaceFolder}/windows/Glide.App",
      "console": "internalConsole"
    }
  ]
}
```

Create `.vscode/tasks.json`:

```json
{
  "version": "2.0.0",
  "tasks": [
    {
      "label": "build-android",
      "type": "shell",
      "command": "pwsh",
      "args": ["-File", "${workspaceFolder}/build.ps1", "-Configuration", "Debug", "-Target", "Android"],
      "group": { "kind": "build", "isDefault": true },
      "problemMatcher": "$msCompile"
    },
    {
      "label": "build-windows",
      "type": "shell",
      "command": "dotnet",
      "args": ["build", "${workspaceFolder}/windows/Glide.slnx", "-c", "Debug", "--nologo"],
      "group": "build",
      "problemMatcher": "$msCompile"
    },
    {
      "label": "test-all",
      "type": "shell",
      "command": "pwsh",
      "args": ["-File", "${workspaceFolder}/test.ps1", "-Target", "All"],
      "group": { "kind": "test", "isDefault": true },
      "problemMatcher": []
    },
    {
      "label": "verify-all",
      "type": "shell",
      "command": "pwsh",
      "args": ["-File", "${workspaceFolder}/verify.ps1"],
      "problemMatcher": []
    }
  ]
}
```

Create `.vscode/settings.json`:

```json
{
  "dotnet.defaultSolution": "windows/Glide.slnx",
  "java.configuration.runtimes": [
    {
      "name": "JavaSE-25",
      "path": "C:/Program Files/Java/jdk-25",
      "default": true
    }
  ],
  "java.compile.nullAnalysis.mode": "automatic",
  "powershell.scriptAnalysis.enable": true,
  "files.exclude": {
    "**/.gradle": true,
    "**/build": true,
    "**/bin": true,
    "**/obj": true
  }
}
```

---

## 4. Quick Start

```powershell
# 1. Verify environment
pwsh -File doctor.ps1

# 2. Build everything
pwsh -File build.ps1

# 3. Run tests
pwsh -File test.ps1

# 4. Full CI gate
pwsh -File verify.ps1

# 5. Preview on device/emulator
pwsh -File preview.ps1 -Target Android
```

### Windows Companion Quick Start

```powershell
# Build
dotnet build windows/Glide.slnx -c Release

# Test
dotnet test windows/Glide.Tests/Glide.Tests.csproj -c Release

# Run WPF app
dotnet run --project windows/Glide.Windows.UI/Glide.Windows.UI.csproj -c Release

# Run CLI
dotnet run --project windows/Glide.App/Glide.App.csproj -- receive --port 42424
```

### Android Quick Start

```powershell
# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew testDebugUnitTest

# Run lint
./gradlew :app:lintDebug

# Architecture enforcement
./gradlew checkArchitecture
```

---

## 5. Architecture & Verification Gates

| Gate | Script | Description |
|:---|:---|:---|
| Architecture Enforcement | `tools/check-architecture-ps1` | 7-rule static analysis (import boundaries, core isolation, I/O prevention, version catalog) |
| LAN Verification | Gradle `:checkLanGate` | TLS loopback, protocol vectors, network/storage chaos, storage correctness |
| Documentation | Gradle `:checkDocumentation` | Required docs presence + local markdown link integrity |
| Windows Companion | `tools/verify-windows-companion.ps1` | 8-check audit: toolchain, build, golden fixtures, path sanitizer, framing, LAN records, mDNS, isolation |
| RC Build | `tools/build-and-verify-rc.ps1` | Release candidate build, signing, 16KB alignment, checksums |
| Production Release Gate | `tools/verify-production-release-gate.ps1` | Frozen hash audit, DoD reconciliation, store readiness, approval record |
| Local Verification | `tools/verify-local.ps1` | Brand assets → Architecture → Build → Lint |
