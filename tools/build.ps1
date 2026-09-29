<#
.SYNOPSIS
    Glide Build Script — HARD-035.
.DESCRIPTION
    Builds the Glide project across Android (Gradle) and Windows (.NET) targets.
    Supports Debug and Release configurations.

.PARAMETER Configuration
    Build configuration: Debug or Release. Default: Debug.

.PARAMETER Target
    Build target: All, Android, Windows. Default: All.

.EXAMPLE
    pwsh -File build.ps1
    pwsh -File build.ps1 -Configuration Release -Target Android
    build.cmd
#>

[CmdletBinding()]
param(
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Debug",
    [ValidateSet("All", "Android", "Windows", "Desktop")]
    [string]$Target = "All"
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path "$PSScriptRoot\..").Path

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "   Glide Build ($Configuration) — Target: $Target                      " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

$buildErrors = [System.Collections.Generic.List[string]]::new()

# --- Android Build ---
if ($Target -eq "All" -or $Target -eq "Android") {
    Write-Host "`n[Android] Building via Gradle..." -ForegroundColor Yellow
    $gradleTask = if ($Configuration -eq "Release") { ":android:app:assembleRelease" } else { ":android:app:assembleDebug" }
    $gradleArgs = @($gradleTask, "--no-daemon")
    if ($Configuration -eq "Release") {
        $gradleArgs += "--console=plain"
    }

    Write-Host "  Running: gradlew.bat $($gradleArgs -join ' ')" -ForegroundColor Gray
    $pinfo = New-Object System.Diagnostics.ProcessStartInfo
    $pinfo.FileName = "$projectRoot\android\gradlew.bat"
    $pinfo.Arguments = $gradleArgs -join " "
    $pinfo.WorkingDirectory = "$projectRoot\android"
    $pinfo.UseShellExecute = $false
    $pinfo.RedirectStandardOutput = $true
    $pinfo.RedirectStandardError = $true

    $process = [System.Diagnostics.Process]::Start($pinfo)
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    if ($process.ExitCode -ne 0) {
        Write-Host $stdout
        Write-Host $stderr -ForegroundColor Red
        $buildErrors.Add("Android Gradle build failed (exit code $($process.ExitCode))")
    } else {
        Write-Host "  [OK]   Android $Configuration build succeeded." -ForegroundColor Green
        $apkPath = if ($Configuration -eq "Release") {
            "$projectRoot\android\app\build\outputs\apk\release\app-release.apk"
        } else {
            "$projectRoot\android\app\build\outputs\apk\debug\app-debug.apk"
        }
        if (-not (Test-Path $apkPath)) {
            $fallback = if ($Configuration -eq "Release") {
                "$projectRoot\android\app\build\outputs\apk\release\android-release.apk"
            } else {
                "$projectRoot\android\app\build\outputs\apk\debug\android-debug.apk"
            }
            if (Test-Path $fallback) { $apkPath = $fallback }
        }
        if (Test-Path $apkPath) {
            $sizeMB = [math]::Round((Get-Item $apkPath).Length / 1MB, 2)
            Write-Host "  APK: $apkPath ($sizeMB MB)" -ForegroundColor Green
        }
    }
}

# --- Desktop (Windows / Linux) Build ---
if ($Target -eq "All" -or $Target -eq "Windows" -or $Target -eq "Desktop") {
    Write-Host "`n[Desktop] Building via dotnet..." -ForegroundColor Yellow
    $dotnetArgs = @("build", "$projectRoot\desktop\Glide.slnx", "-c", $Configuration, "--nologo")
    Write-Host "  Running: dotnet $($dotnetArgs -join ' ')" -ForegroundColor Gray
    $pinfo = New-Object System.Diagnostics.ProcessStartInfo
    $pinfo.FileName = "dotnet"
    $pinfo.Arguments = $dotnetArgs -join " "
    $pinfo.WorkingDirectory = $projectRoot
    $pinfo.UseShellExecute = $false
    $pinfo.RedirectStandardOutput = $true
    $pinfo.RedirectStandardError = $true

    $process = [System.Diagnostics.Process]::Start($pinfo)
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    if ($process.ExitCode -ne 0) {
        Write-Host $stdout
        Write-Host $stderr -ForegroundColor Red
        $buildErrors.Add("Desktop dotnet build failed (exit code $($process.ExitCode))")
    } else {
        Write-Host "  [OK]   Desktop $Configuration build succeeded." -ForegroundColor Green
    }
}

# --- Summary ---
Write-Host "`n=================================================================" -ForegroundColor Cyan
if ($buildErrors.Count -eq 0) {
    Write-Host "   BUILD PASSED ($Configuration)                                      " -ForegroundColor Green
    Write-Host "=================================================================" -ForegroundColor Cyan
    exit 0
} else {
    Write-Host "   BUILD FAILED ($Configuration) — $($buildErrors.Count) error(s)       " -ForegroundColor Red
    foreach ($e in $buildErrors) { Write-Host "     - $e" -ForegroundColor Red }
    Write-Host "=================================================================" -ForegroundColor Cyan
    exit 1
}
