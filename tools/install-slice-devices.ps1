# Script to inspect and install Glide on physical Android devices for GLIDE-017
param(
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$ApkPath = "$PSScriptRoot\..\android\app\build\outputs\apk\debug\app-debug.apk",
    [string]$FixturePath = "$PSScriptRoot\..\build\fixtures\glide-test-10mb.bin"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $AdbPath)) {
    # Check if adb is on PATH
    $adbCommand = Get-Command "adb" -ErrorAction SilentlyContinue
    if ($adbCommand) {
        $AdbPath = $adbCommand.Source
    } else {
        Write-Error "ADB not found at $AdbPath. Please verify Android SDK platform-tools installation."
        exit 1
    }
}

Write-Host "Using ADB: $AdbPath"

# Check connected devices
$rawDevices = & $AdbPath devices -l
Write-Host "`n$rawDevices`n"

$deviceLines = ($rawDevices -split "`r?`n") | Where-Object { $_ -match "\bdevice\b" -and $_ -notmatch "^List of" }

if ($deviceLines.Count -eq 0) {
    Write-Warning "No Android devices connected via ADB."
    Write-Host "To execute GLIDE-017:"
    Write-Host "  1. Connect two physical Android devices with USB debugging or Wireless Debugging enabled."
    Write-Host "  2. Run this script again: .\tools\install-slice-devices.ps1`n"
    exit 0
}

Write-Host "Found $($deviceLines.Count) device(s) connected.`n"

foreach ($line in $deviceLines) {
    $serial = ($line -split "\s+")[0]
    $model = "Unknown"
    $version = "Unknown"
    $gms = "Unknown"

    try {
        $model = (& $AdbPath -s $serial shell getprop ro.product.model).Trim()
        $version = (& $AdbPath -s $serial shell getprop ro.build.version.release).Trim()
        $sdk = (& $AdbPath -s $serial shell getprop ro.build.version.sdk).Trim()
        $gms = (& $AdbPath -s $serial shell dumpsys package com.google.android.gms 2>$null | Select-String "versionName" | Select-Object -First 1)
        if ($gms) { $gms = ($gms -split "=")[1].Trim() }
    } catch {}

    Write-Host "Device: $serial ($model - Android $version, SDK $sdk, GMS $gms)"

    if (Test-Path $ApkPath) {
        Write-Host "  Installing $ApkPath ..."
        & $AdbPath -s $serial install -r -d $ApkPath
    } else {
        Write-Warning "  APK not found at $ApkPath. Please run: .\gradlew.bat :android:app:assembleDebug"
    }

    if (Test-Path $FixturePath) {
        Write-Host "  Pushing test fixture to /sdcard/Download/ ..."
        & $AdbPath -s $serial push $FixturePath /sdcard/Download/glide-test-10mb.bin
    }
    Write-Host ""
}

Write-Host "=== Device Preparation Complete ==="
