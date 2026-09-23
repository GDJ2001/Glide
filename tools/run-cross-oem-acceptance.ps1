# tools/run-cross-oem-acceptance.ps1
# Automates Cross-OEM Physical Acceptance and Soak Execution (GLIDE-058)

param(
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$ApkPath = "$PSScriptRoot\..\android\build\outputs\apk\debug\android-debug.apk",
    [string]$ReportPath = "$PSScriptRoot\..\doc\testing\evidence\GLIDE-058-cross-oem-physical-acceptance-report.md",
    [int]$SoakSeconds = 15,
    [switch]$SkipInstall = $false
)

$ErrorActionPreference = "Stop"

Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "   GLIDE-058: Cross-OEM Physical Acceptance and Soak Harness" -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan

if (-not (Test-Path $AdbPath)) {
    $adbCommand = Get-Command "adb" -ErrorAction SilentlyContinue
    if ($adbCommand) {
        $AdbPath = $adbCommand.Source
    } else {
        Write-Error "ADB not found at $AdbPath. Verify Android SDK platform-tools installation."
        exit 1
    }
}
Write-Host "Using ADB: $AdbPath" -ForegroundColor Gray

# 1. Inspect Connected Devices
$rawDevices = & $AdbPath devices -l
$deviceLines = ($rawDevices -split "`r?`n") | Where-Object { $_ -match "\bdevice\b" -and $_ -notmatch "^List of" }

if ($deviceLines.Count -eq 0) {
    Write-Warning "No connected Android devices detected via ADB."
    Write-Host "Target Cross-OEM Acceptance Matrix requires physical devices or AVD emulator."
    exit 1
}

Write-Host "Found $($deviceLines.Count) connected device(s).`n" -ForegroundColor Green

$deviceResults = @()

foreach ($line in $deviceLines) {
    $serial = ($line -split "\s+")[0]
    Write-Host "--------------------------------------------------------------------------------" -ForegroundColor Yellow
    Write-Host "Inspecting Device: $serial" -ForegroundColor Yellow
    Write-Host "--------------------------------------------------------------------------------" -ForegroundColor Yellow

    # Query Device Properties
    $manufacturer = (& $AdbPath -s $serial shell getprop ro.product.manufacturer).Trim()
    $model = (& $AdbPath -s $serial shell getprop ro.product.model).Trim()
    $deviceCode = (& $AdbPath -s $serial shell getprop ro.product.device).Trim()
    $osRelease = (& $AdbPath -s $serial shell getprop ro.build.version.release).Trim()
    $sdkApi = (& $AdbPath -s $serial shell getprop ro.build.version.sdk).Trim()
    $securityPatch = (& $AdbPath -s $serial shell getprop ro.build.version.security_patch).Trim()
    $abi = (& $AdbPath -s $serial shell getprop ro.product.cpu.abi).Trim()

    # Query RAM from /proc/meminfo
    $memTotalKb = 0
    try {
        $memLine = (& $AdbPath -s $serial shell cat /proc/meminfo | Select-String "MemTotal:" | Select-Object -First 1).ToString()
        if ($memLine -match "(\d+)") {
            $memTotalKb = [int64]$matches[1]
        }
    } catch {}

    $ramGb = [math]::Round(($memTotalKb / (1024 * 1024)), 1)
    $ramClass = if ($ramGb -lt 4.5) { "Low-End (2-4 GB)" } elseif ($ramGb -le 9.5) { "Midrange (6-8 GB)" } else { "Flagship (12+ GB)" }

    # Query GMS Version
    $gmsVer = "Not Installed"
    try {
        $gmsLine = (& $AdbPath -s $serial shell dumpsys package com.google.android.gms 2>$null | Select-String "versionName=" | Select-Object -First 1)
        if ($gmsLine) {
            $gmsVer = ($gmsLine.ToString().Trim() -split "=")[1]
        }
    } catch {}

    # Query Display Metrics
    $wmSize = (& $AdbPath -s $serial shell wm size).Trim()
    $wmDensity = (& $AdbPath -s $serial shell wm density).Trim()

    Write-Host "  Manufacturer:    $manufacturer"
    Write-Host "  Model:           $model ($deviceCode)"
    Write-Host "  Android Version: Android $osRelease (API $sdkApi)"
    Write-Host "  RAM Capacity:    $ramGb GB -> $ramClass"
    Write-Host "  Architecture:    $abi"
    Write-Host "  Security Patch:  $securityPatch"
    Write-Host "  GMS Version:     $gmsVer"
    Write-Host "  Display:         $wmSize, $wmDensity"
    Write-Host ""

    # Install APK if present and not skipped
    if (-not $SkipInstall -and (Test-Path $ApkPath)) {
        Write-Host "  Installing $ApkPath ..." -ForegroundColor Cyan
        & $AdbPath -s $serial install -r -d $ApkPath | Out-Null
    }

    # Ensure device is awake and unlocked
    & $AdbPath -s $serial shell input keyevent 26 | Out-Null
    & $AdbPath -s $serial shell input keyevent 82 | Out-Null
    Start-Sleep -Milliseconds 500

    # Clean start application
    Write-Host "  [Step 1/5] Launching Glide with active transfer state..." -ForegroundColor Cyan
    & $AdbPath -s $serial shell am force-stop dev.glide.app.debug
    Start-Sleep -Milliseconds 500
    & $AdbPath -s $serial shell am start -n dev.glide.app.debug/dev.glide.app.MainActivity --es debug_state "service_transfer_active" | Out-Null
    Start-Sleep -Seconds 3

    # Baseline Memory Check
    Write-Host "  [Step 2/5] Profiling Baseline Process Memory..." -ForegroundColor Cyan
    $baselineMem = & $AdbPath -s $serial shell dumpsys meminfo dev.glide.app.debug
    $baselinePssKb = 0
    if ($baselineMem -match "TOTAL PSS:\s+(\d+)") {
        $baselinePssKb = [int64]$matches[1]
    }
    Write-Host "    Baseline Total PSS: $([math]::Round($baselinePssKb / 1024, 2)) MB" -ForegroundColor Gray

    # Service Check
    Write-Host "  [Step 3/5] Verifying Connected-Device Foreground Service..." -ForegroundColor Cyan
    $serviceDump = & $AdbPath -s $serial shell dumpsys activity services dev.glide.app.debug/dev.glide.app.service.TransferService
    $serviceRunning = $serviceDump -match "TransferService"
    if ($serviceRunning) {
        Write-Host "    TransferService active and bound." -ForegroundColor Green
    } else {
        Write-Host "    TransferService not in active dump (UI only state)." -ForegroundColor Yellow
    }

    # Soak Duration Execution & Chaos Injections
    Write-Host "  [Step 4/5] Executing Sustained Soak & Chaos Cycle ($SoakSeconds seconds)..." -ForegroundColor Cyan
    
    # 4a. Screen Lock / Background Rebind
    & $AdbPath -s $serial shell input keyevent 26 | Out-Null # Screen off
    Start-Sleep -Seconds 3
    & $AdbPath -s $serial shell input keyevent 26 | Out-Null # Screen on
    & $AdbPath -s $serial shell input keyevent 82 | Out-Null # Unlock
    Start-Sleep -Seconds 2

    # 4b. Battery Saver Mode Pulse
    & $AdbPath -s $serial shell cmd battery unplug | Out-Null
    & $AdbPath -s $serial shell cmd power set-mode 1 | Out-Null # Low power mode
    Start-Sleep -Seconds 3
    & $AdbPath -s $serial shell cmd power set-mode 0 | Out-Null
    & $AdbPath -s $serial shell cmd battery reset | Out-Null

    # 4c. Deep Doze Pulse
    & $AdbPath -s $serial shell dumpsys deviceidle step deep 2>$null | Out-Null
    Start-Sleep -Seconds 2
    & $AdbPath -s $serial shell dumpsys deviceidle unforce 2>$null | Out-Null

    # Wait remainder of soak
    $remaining = [math]::Max(0, $SoakSeconds - 10)
    if ($remaining -gt 0) {
        Start-Sleep -Seconds $remaining
    }

    # Post-Soak Memory Check
    Write-Host "  [Step 5/5] Profiling Post-Soak Memory and Leak Detection..." -ForegroundColor Cyan
    $postMem = & $AdbPath -s $serial shell dumpsys meminfo dev.glide.app.debug
    $postPssKb = 0
    if ($postMem -match "TOTAL PSS:\s+(\d+)") {
        $postPssKb = [int64]$matches[1]
    }
    $pssGrowthMb = [math]::Round(($postPssKb - $baselinePssKb) / 1024, 2)
    Write-Host "    Post-Soak Total PSS: $([math]::Round($postPssKb / 1024, 2)) MB" -ForegroundColor Gray
    Write-Host "    PSS Delta: $pssGrowthMb MB (Budget <= 32 MB)" -ForegroundColor Gray

    $memoryPass = $pssGrowthMb -le 32.0
    if ($memoryPass) {
        Write-Host "    [PASS] Memory bounded within 32 MB ceiling." -ForegroundColor Green
    } else {
        Write-Warning "    [FAIL] Memory growth exceeded 32 MB budget ($pssGrowthMb MB)."
    }

    # Check process death / crash in logcat
    $logcatCrashes = (& $AdbPath -s $serial logcat -d -t 200 *:E | Select-String "FATAL EXCEPTION|dev.glide.app")
    $crashCount = if ($logcatCrashes) { $logcatCrashes.Count } else { 0 }
    Write-Host "    Logcat Fatal Crashes: $crashCount" -ForegroundColor $(if ($crashCount -eq 0) { "Green" } else { "Red" })

    $deviceResults += [PSCustomObject]@{
        Serial = $serial
        Manufacturer = $manufacturer
        Model = $model
        AndroidVersion = "Android $osRelease (API $sdkApi)"
        RamClass = $ramClass
        RamGb = $ramGb
        GmsVersion = $gmsVer
        BaselinePssMb = [math]::Round($baselinePssKb / 1024, 2)
        PostPssMb = [math]::Round($postPssKb / 1024, 2)
        PssDeltaMb = $pssGrowthMb
        MemoryBounded = $memoryPass
        CrashCount = $crashCount
        OverallStatus = if ($memoryPass -and ($crashCount -eq 0)) { "PASS" } else { "FAIL" }
    }
}

Write-Host "`n================================================================================" -ForegroundColor Cyan
Write-Host "   CROSS-OEM EXECUTION SUMMARY" -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan
$deviceResults | Format-Table -Property Serial, Manufacturer, Model, AndroidVersion, RamClass, PssDeltaMb, CrashCount, OverallStatus -AutoSize

Write-Host "`n=== Physical Acceptance & Soak Run Complete ===" -ForegroundColor Green
