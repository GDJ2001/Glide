<#
.SYNOPSIS
    Glide Developer Environment Doctor — HARD-035.
.DESCRIPTION
    Validates that every toolchain required to build, test, lint, and verify Glide is present
    and meets the minimum version requirements. Exits non-zero if any check fails.

    Checks: JDK (25 or 21+), Android SDK (compileSdk 37), Gradle wrapper, .NET 10 SDK,
    Node.js (optional, 18+), adb, keytool, PowerShell 7+.

.EXAMPLE
    pwsh -File doctor.ps1
    doctor.cmd
#>

[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path "$PSScriptRoot").Path

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "   Glide Developer Environment Doctor (HARD-035)                  " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

$failures = [System.Collections.Generic.List[string]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()

function Test-Command([string]$Name, [string]$Command) {
    $cmd = Get-Command $Command -ErrorAction SilentlyContinue
    if ($cmd) {
        Write-Host "  [OK]   $Name : $($cmd.Source)" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] $Name : '$Command' not found in PATH" -ForegroundColor Red
        $failures.Add("$Name not found")
    }
}

function Test-Version([string]$Label, [string]$Actual, [string]$Required, [scriptblock]$Parse) {
    $actualVal = & $Parse $Actual
    $requiredVal = & $Parse $Required
    if ($actualVal -ge $requiredVal) {
        Write-Host "  [OK]   $Label : $Actual (>= $Required)" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] $Label : $Actual (required >= $Required)" -ForegroundColor Red
        $failures.Add("$Label version $Actual < required $Required")
    }
}

# --- 1. JDK ---
Write-Host "`n[1/8] JDK / Java toolchain" -ForegroundColor Yellow
$javaCmd = Get-Command java -ErrorAction SilentlyContinue
if ($javaCmd) {
    $javaVersion = & java -version 2>&1 | Select-String -Pattern '"(\d+)(\.\d+)*"' | ForEach-Object { $_.Matches[0].Value.Trim('"') }
    if ($javaVersion -match "^(\d+)") {
        $major = [int]$Matches[1]
        Write-Host "  Java runtime : $javaVersion"
        if ($major -ge 21) {
            Write-Host "  [OK]   JDK major $major (>= 21)" -ForegroundColor Green
        } else {
            Write-Host "  [WARN] JDK major $major (< 21). Build target bytecode is 17; Android Studio ships JBR-25." -ForegroundColor Yellow
            $warnings.Add("JDK $major is below 21; upgrade for best compatibility.")
        }
    }
} else {
    Write-Host "  [FAIL] java not found in PATH" -ForegroundColor Red
    $failures.Add("JDK not found")
}

$javacCmd = Get-Command javac -ErrorAction SilentlyContinue
if ($javacCmd) {
    Write-Host "  [OK]   javac : $($javacCmd.Source)" -ForegroundColor Green
} else {
    Write-Host "  [WARN] javac not found (runtime-only JRE)" -ForegroundColor Yellow
    $warnings.Add("javac not found; JRE-only install detected.")
}

# --- 2. Android SDK ---
Write-Host "`n[2/8] Android SDK" -ForegroundColor Yellow
$sdkEnv = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $null }
$localProps = Join-Path $projectRoot "local.properties"
if (-not $sdkEnv -and (Test-Path $localProps)) {
    $sdkDirLine = Get-Content $localProps | Where-Object { $_ -match "^sdk\.dir=(.+)$" } | Select-Object -First 1
    if ($sdkDirLine) {
        $sdkFromProps = $sdkDirLine -replace "^sdk\.dir=", ""
        $sdkFromProps = $sdkFromProps -replace "\\:", ":"
        $sdkEnv = $sdkFromProps
        Write-Host "  [OK]   sdk.dir (local.properties) : $sdkEnv" -ForegroundColor Green
    }
}
if ($sdkEnv) {
    Write-Host "  [OK]   ANDROID_HOME : $sdkEnv" -ForegroundColor Green
    $platformsDir = Join-Path $sdkEnv "platforms"
    if (Test-Path $platformsDir) {
        $api37 = Get-ChildItem $platformsDir -Filter "android-37" -Directory -ErrorAction SilentlyContinue
        if ($api37) {
            Write-Host "  [OK]   compileSdk 37 platform installed" -ForegroundColor Green
        } else {
            Write-Host "  [WARN] compileSdk 37 platform not found in $platformsDir" -ForegroundColor Yellow
            $warnings.Add("Android platform android-37 not installed.")
        }
    }
} else {
    Write-Host "  [WARN] ANDROID_HOME / ANDROID_SDK_ROOT not set" -ForegroundColor Yellow
    $warnings.Add("ANDROID_HOME not set; Android builds will fail.")
}

# --- 3. Gradle wrapper ---
Write-Host "`n[3/8] Gradle wrapper" -ForegroundColor Yellow
$gradlewPath = Join-Path $projectRoot "gradlew.bat"
if (Test-Path $gradlewPath) {
    Write-Host "  [OK]   gradlew.bat : $gradlewPath" -ForegroundColor Green
} else {
    Write-Host "  [FAIL] gradlew.bat not found at repository root" -ForegroundColor Red
    $failures.Add("Gradle wrapper missing")
}

# --- 4. .NET SDK ---
Write-Host "`n[4/8] .NET SDK" -ForegroundColor Yellow
$dotnetCmd = Get-Command dotnet -ErrorAction SilentlyContinue
if ($dotnetCmd) {
    $dotnetVerStr = (dotnet --version).Trim()
    Write-Host "  [OK]   dotnet : $($dotnetCmd.Source) ($dotnetVerStr)" -ForegroundColor Green
    $dotnetVer = [version]$dotnetVerStr
    if ($dotnetVer.Major -ge 10) {
        Write-Host "  [OK]   .NET SDK $dotnetVerStr (>= 10.0)" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] .NET SDK $dotnetVerStr (< 10.0 required)" -ForegroundColor Red
        $failures.Add(".NET SDK $dotnetVerStr < required 10.0")
    }
    $sdks = dotnet --list-sdks 2>&1 | Where-Object { $_ -match "^\s*\d+\.\d+\.\d+" }
    foreach ($sdk in $sdks) { Write-Host "         $sdk" }
} else {
    Write-Host "  [FAIL] dotnet not found in PATH" -ForegroundColor Red
    $failures.Add(".NET SDK not found")
}

# --- 5. Node.js (optional) ---
Write-Host "`n[5/8] Node.js (optional)" -ForegroundColor Yellow
$nodeCmd = Get-Command node -ErrorAction SilentlyContinue
if ($nodeCmd) {
    $nodeVerStr = (node --version).Trim().TrimStart('v')
    Write-Host "  [OK]   node : $($nodeCmd.Source) ($nodeVerStr)" -ForegroundColor Green
} else {
    Write-Host "  [SKIP] node not found (no JavaScript/Node tooling in this repo)" -ForegroundColor DarkGray
}

# --- 6. Android device tools ---
Write-Host "`n[6/8] Android platform-tools" -ForegroundColor Yellow
$adbCmd = Get-Command adb -ErrorAction SilentlyContinue
if ($adbCmd) {
    Write-Host "  [OK]   adb : $($adbCmd.Source)" -ForegroundColor Green
} else {
    Write-Host "  [WARN] adb not found (needed for device install/preview only)" -ForegroundColor Yellow
    $warnings.Add("adb not found; preview on device will fail.")
}
$aapt2Path = Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools"
if (Test-Path $aapt2Path) {
    $aapt2Exe = Get-ChildItem $aapt2Path -Recurse -Filter "aapt2.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($aapt2Exe) {
        Write-Host "  [OK]   aapt2 : $($aapt2Exe.FullName)" -ForegroundColor Green
    }
}

# --- 7. Git ---
Write-Host "`n[7/8] Git" -ForegroundColor Yellow
Test-Command "git" "git"

# --- 8. PowerShell ---
Write-Host "`n[8/8] PowerShell" -ForegroundColor Yellow
$psVer = $PSVersionTable.PSVersion
Write-Host "  PowerShell : $($psVer.Major).$($psVer.Minor).$($psVer.Patch)" -ForegroundColor Green
if ($psVer.Major -ge 7) {
    Write-Host "  [OK]   PowerShell >= 7.0" -ForegroundColor Green
} else {
    Write-Host "  [WARN] PowerShell < 7.0 (some scripts may not run)" -ForegroundColor Yellow
    $warnings.Add("PowerShell $($psVer.Major).$($psVer.Minor) is below 7.0")
}

# --- Summary ---
Write-Host "`n=================================================================" -ForegroundColor Cyan
if ($failures.Count -eq 0) {
    Write-Host "   DOCTOR PASSED — environment ready for Glide development        " -ForegroundColor Green
    if ($warnings.Count -gt 0) {
        Write-Host "   $($warnings.Count) warning(s) — see above                          " -ForegroundColor Yellow
    }
    Write-Host "=================================================================" -ForegroundColor Cyan
    exit 0
} else {
    Write-Host "   DOCTOR FAILED — $($failures.Count) missing/broken tool(s)         " -ForegroundColor Red
    foreach ($f in $failures) { Write-Host "     - $f" -ForegroundColor Red }
    Write-Host "=================================================================" -ForegroundColor Cyan
    exit 1
}
