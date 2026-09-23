<#
.SYNOPSIS
    Glide Test Script — HARD-035.
.DESCRIPTION
    Runs the full test suite across Android (Gradle/JUnit) and Windows (.NET/xUnit).
    Supports filtering by test category and generating TRX/JUnit XML reports.

.PARAMETER Target
    Test target: All, Android, Windows. Default: All.

.PARAMETER Configuration
    Configuration: Debug or Release. Default: Debug.

.PARAMETER Filter
    Optional test filter expression (e.g., "FullyQualifiedName~MyTests").

.EXAMPLE
    pwsh -File test.ps1
    pwsh -File test.ps1 -Target Android -Filter "FullyQualifiedName~Transfer"
    test.cmd
#>

[CmdletBinding()]
param(
    [ValidateSet("All", "Android", "Windows")]
    [string]$Target = "All",
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Debug",
    [string]$Filter = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path "$PSScriptRoot\..").Path

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "   Glide Test Run ($Configuration) — Target: $Target                 " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

$testErrors = [System.Collections.Generic.List[string]]::new()

# --- Android Test ---
if ($Target -eq "All" -or $Target -eq "Android") {
    Write-Host "`n[Android] Running unit tests via Gradle..." -ForegroundColor Yellow
    $gradleTask = "test${Configuration}UnitTest"
    $gradleArgs = @($gradleTask, "--no-daemon")
    if ($Filter) {
        $gradleArgs += "--tests", $Filter
    }

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
        $testErrors.Add("Android unit tests failed (exit code $($process.ExitCode))")
    } else {
        Write-Host "  [OK]   Android unit tests passed." -ForegroundColor Green
        $reportDir = "$projectRoot\android\build\reports\tests\test${Configuration}UnitTest"
        if (-not (Test-Path $reportDir)) {
            $fallback = "$projectRoot\app\build\reports\tests\test${Configuration}UnitTest"
            if (Test-Path $fallback) { $reportDir = $fallback }
        }
        if (Test-Path $reportDir) {
            Write-Host "  Report: $reportDir" -ForegroundColor Gray
        }
    }
}

# --- Windows Test ---
if ($Target -eq "All" -or $Target -eq "Windows") {
    Write-Host "`n[Windows] Running tests via dotnet test..." -ForegroundColor Yellow
    $dotnetArgs = @("test", "$projectRoot\windows\Glide.Tests\Glide.Tests.csproj", "-c", $Configuration, "--nologo", "--logger", "console;verbosity=detailed")
    if ($Filter) {
        $dotnetArgs += "--filter", $Filter
    }

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
        $testErrors.Add("Windows tests failed (exit code $($process.ExitCode))")
    } else {
        Write-Host "  [OK]   Windows tests passed." -ForegroundColor Green
    }
}

# --- Summary ---
Write-Host "`n=================================================================" -ForegroundColor Cyan
if ($testErrors.Count -eq 0) {
    Write-Host "   TEST PASSED                                                       " -ForegroundColor Green
    Write-Host "=================================================================" -ForegroundColor Cyan
    exit 0
} else {
    Write-Host "   TEST FAILED — $($testErrors.Count) error(s)                          " -ForegroundColor Red
    foreach ($e in $testErrors) { Write-Host "     - $e" -ForegroundColor Red }
    Write-Host "=================================================================" -ForegroundColor Cyan
    exit 1
}
