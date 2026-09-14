#Requires -Version 5.1
<#
.SYNOPSIS
Captures physical emulator screenshots for GLIDE-037 Home and Discovery surfaces.
#>
param(
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$OutputDir = "d:\GITHub\Glide\docs\testing\evidence\screenshots",
    [string]$ArtifactsDir = "C:\Users\GDJ\.gemini\antigravity\brain\ac444b3f-013a-405b-8007-34183b37abfd"
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $AdbPath)) {
    throw "adb.exe not found at $AdbPath"
}

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

$scenarios = @(
    @{ Name = "glide_emulator_home_polished"; Extra = "" },
    @{ Name = "glide_emulator_home_active_transfer"; Extra = "--es debug_state home_active_transfer" },
    @{ Name = "glide_emulator_discovery_searching"; Extra = "--es debug_state discovery_searching" },
    @{ Name = "glide_emulator_discovery_peers"; Extra = "--es debug_state discovery_peers" },
    @{ Name = "glide_emulator_discovery_blocked"; Extra = "--es debug_state discovery_blocked" },
    @{ Name = "glide_emulator_discovery_timeout"; Extra = "--es debug_state discovery_timeout" },
    @{ Name = "glide_emulator_receive_readiness"; Extra = "--es debug_state receive_readiness" }
)

foreach ($s in $scenarios) {
    $name = $s.Name
    $extra = $s.Extra

    Write-Host "Capturing $name..." -ForegroundColor Cyan
    & $AdbPath shell am force-stop dev.glide.app.debug
    Start-Sleep -Milliseconds 600

    if ($extra -ne "") {
        $cmd = "am start -n dev.glide.app.debug/dev.glide.app.MainActivity $extra"
        & $AdbPath shell $cmd
    } else {
        & $AdbPath shell am start -n dev.glide.app.debug/dev.glide.app.MainActivity
    }

    Start-Sleep -Seconds 5

    $remotePath = "/sdcard/$name.png"
    $destPath = Join-Path $OutputDir "$name.png"
    & $AdbPath shell screencap -p $remotePath
    & $AdbPath pull $remotePath $destPath
    & $AdbPath shell rm -f $remotePath

    if (Test-Path $ArtifactsDir) {
        $artPath = Join-Path $ArtifactsDir "$name.png"
        Copy-Item -Path $destPath -Destination $artPath -Force
    }

    $fileSize = (Get-Item $destPath).Length
    Write-Host "  -> Saved $name.png ($fileSize bytes)" -ForegroundColor Green
}

Write-Host "`nAll 6 emulator screenshots successfully captured for GLIDE-037." -ForegroundColor Green
