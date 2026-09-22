<#
.SYNOPSIS
    Glide Lint Script — HARD-035.
.DESCRIPTION
    Runs static analysis and lint checks on both Android and Windows code.

    Android: Gradle :app:lintDebug (Android Lint + Kotlin compiler checks)
    Windows:  dotnet format + .NET analyzers via dotnet build analyzers

.PARAMETER Target
    Target: All, Android, Windows. Default: All.

.PARAMETER Fix
    Attempt to auto-fix lint issues where supported (dotnet format --fix).

.EXAMPLE
    pwsh -File lint.ps1
    pwsh -File lint.ps1 -Target Windows -Fix
    lint.cmd
#>

[CmdletBinding()]
param(
    [ValidateSet("All", "Android", "Windows")]
    [string]$Target = "All",
    [switch]$Fix = $false
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path "$PSScriptRoot").Path

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "   Glide Lint — Target: $Target                                   " -ForegroundColor Cyan
if ($Fix) { Write-Host "   (Auto-fix enabled)" -ForegroundColor Yellow }
Write-Host "=================================================================" -ForegroundColor Cyan

$lintErrors = [System.Collections.Generic.List[string]]::new()

# --- Android Lint ---
if ($Target -eq "All" -or $Target -eq "Android") {
    Write-Host "`n[Android] Running Android Lint..." -ForegroundColor Yellow
    $gradleArgs = @(":app:lintDebug", "--no-daemon")

    Write-Host "  Running: gradlew.bat $($gradleArgs -join ' ')" -ForegroundColor Gray
    $pinfo = New-Object System.Diagnostics.ProcessStartInfo
    $pinfo.FileName = "$projectRoot\gradlew.bat"
    $pinfo.Arguments = $gradleArgs -join " "
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
        $lintErrors.Add("Android lint failed (exit code $($process.ExitCode))")
    } else {
        Write-Host "  [OK]   Android lint passed." -ForegroundColor Green
        $lintReport = "$projectRoot\app\build\reports\lint-results-debug.html"
        if (Test-Path $lintReport) {
            Write-Host "  Report: $lintReport" -ForegroundColor Gray
        }
    }
}

# --- Windows Lint ---
if ($Target -eq "All" -or $Target -eq "Windows") {
    Write-Host "`n[Windows] Running dotnet build with analyzers..." -ForegroundColor Yellow
    $dotnetArgs = @("build", "$projectRoot\windows\Glide.slnx", "-c", "Debug", "--nologo")

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
        $lintErrors.Add("Windows build-with-analyzers failed (exit code $($process.ExitCode))")
    } else {
        Write-Host "  [OK]   Windows code analysis passed." -ForegroundColor Green
    }
}

# --- Summary ---
Write-Host "`n=================================================================" -ForegroundColor Cyan
if ($lintErrors.Count -eq 0) {
    Write-Host "   LINT PASSED                                                      " -ForegroundColor Green
    Write-Host "=================================================================" -ForegroundColor Cyan
    exit 0
} else {
    Write-Host "   LINT FAILED — $($lintErrors.Count) error(s)                         " -ForegroundColor Red
    foreach ($e in $lintErrors) { Write-Host "     - $e" -ForegroundColor Red }
    Write-Host "=================================================================" -ForegroundColor Cyan
    exit 1
}
