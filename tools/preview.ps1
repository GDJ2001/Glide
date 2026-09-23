<#
.SYNOPSIS
    Glide Preview Script — HARD-035.
.DESCRIPTION
    Launches the development preview environment:
    - Android: Installs debug APK on connected emulator/device and launches MainActivity
    - Windows: Runs Glide.Windows.UI (WPF) or Glide.App (CLI) in the foreground

.PARAMETER Target
    Preview target: Android, WindowsUI, WindowsCLI. Default: Android.

.PARAMETER Configuration
    Configuration: Debug or Release. Default: Debug.

.EXAMPLE
    pwsh -File preview.ps1
    pwsh -File preview.ps1 -Target WindowsUI
    preview.cmd
#>

[CmdletBinding()]
param(
    [ValidateSet("Android", "WindowsUI", "WindowsCLI")]
    [string]$Target = "Android",
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Debug"
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path "$PSScriptRoot\..").Path

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "   Glide Preview — $Target ($Configuration)                            " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# --- Android Preview ---
if ($Target -eq "Android") {
    Write-Host "`n[Android] Installing and launching on connected device..." -ForegroundColor Yellow
    $adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    if (-not (Test-Path $adbPath)) {
        Write-Error "adb not found at $adbPath"
    }

    $devices = & $adbPath devices | Out-String
    if ($devices -match "(\S+)\s+device\b") {
        $deviceId = $Matches[1]
        Write-Host "  Device: $deviceId" -ForegroundColor Green

        $apkPath = if ($Configuration -eq "Release") {
            "$projectRoot\android\build\outputs\apk\release\android-release.apk"
        } else {
            "$projectRoot\android\build\outputs\apk\debug\android-debug.apk"
        }
        if (-not (Test-Path $apkPath)) {
            $fallback = if ($Configuration -eq "Release") {
                "$projectRoot\android\build\outputs\apk\release\app-release.apk"
            } else {
                "$projectRoot\android\build\outputs\apk\debug\app-debug.apk"
            }
            if (Test-Path $fallback) { $apkPath = $fallback }
        }
        if (-not (Test-Path $apkPath)) {
            Write-Host "  APK not found. Building first..." -ForegroundColor Yellow
            & "$projectRoot\tools\build.ps1" -Configuration $Configuration -Target Android
        }

        Write-Host "  Installing $apkPath ..." -ForegroundColor Cyan
        & $adbPath -s $deviceId install -r $apkPath 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0) { Write-Error "Install failed" }

        Write-Host "  Launching dev.glide.app..." -ForegroundColor Cyan
        & $adbPath -s $deviceId shell am start -n "dev.glide.app.debug/dev.glide.app.MainActivity" 2>&1 | Out-String
        Write-Host "  [OK]   Android app launched." -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] No connected device found." -ForegroundColor Red
        exit 1
    }
}

# --- Windows UI Preview ---
if ($Target -eq "WindowsUI") {
    Write-Host "`n[Windows UI] Launching Glide.Windows.UI..." -ForegroundColor Yellow
    $csproj = "$projectRoot\windows\Glide.Windows.UI\Glide.Windows.UI.csproj"
    Write-Host "  Project: $csproj" -ForegroundColor Gray
    Write-Host "  Starting WPF application..." -ForegroundColor Cyan
    & dotnet run --project $csproj -c $Configuration
}

# --- Windows CLI Preview ---
if ($Target -eq "WindowsCLI") {
    Write-Host "`n[Windows CLI] Launching Glide.App..." -ForegroundColor Yellow
    $csproj = "$projectRoot\windows\Glide.App\Glide.App.csproj"
    Write-Host "  Project: $csproj" -ForegroundColor Gray
    & dotnet run --project $csproj -c $Configuration
}

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "   Preview session ended                                           " -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Cyan
