<#
.SYNOPSIS
    Glide Verify Script — HARD-035.
.DESCRIPTION
    Runs the full verification pipeline: build, test, lint, architecture enforcement,
    and Windows companion conformance. This is the "CI gate" script.

    Components:
    - Architecture enforcement (tools/check-architecture.ps1)
    - Windows companion verification (tools/verify-windows-companion.ps1)
    - Build + Test + Lint

.PARAMETER SkipTests
    Skip running test suites.

.PARAMETER SkipLint
    Skip lint checks.

.PARAMETER SkipArchitecture
    Skip architecture/static-analysis enforcement.

.EXAMPLE
    pwsh -File verify.ps1
    pwsh -File verify.ps1 -SkipTests
    verify.cmd
#>

[CmdletBinding()]
param(
    [switch]$SkipTests = $false,
    [switch]$SkipLint = $false,
    [switch]$SkipArchitecture = $false
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path "$PSScriptRoot\..").Path

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "   Glide Verify — Full CI Gate                                    " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

$verifyErrors = [System.Collections.Generic.List[string]]::new()

# --- 1. Architecture & Static Analysis ---
if (-not $SkipArchitecture) {
    Write-Host "`n[1/4] Architecture & Static Analysis..." -ForegroundColor Yellow
    & "$projectRoot\tools\check-architecture.ps1" -FailFast
    if ($LASTEXITCODE -ne 0) {
        $verifyErrors.Add("Architecture enforcement failed")
    } else {
        Write-Host "  [OK]   Architecture checks passed." -ForegroundColor Green
    }
}

# --- 2. Windows Companion Verification ---
Write-Host "`n[2/4] Windows Companion Verification..." -ForegroundColor Yellow
& "$projectRoot\tools\verify-windows-companion.ps1"
if ($LASTEXITCODE -ne 0) {
    $verifyErrors.Add("Windows companion verification failed")
} else {
    Write-Host "  [OK]   Windows companion verification passed." -ForegroundColor Green
}

# --- 3. Build ---
Write-Host "`n[3/4] Build..." -ForegroundColor Yellow
& "$projectRoot\tools\build.ps1" -Configuration Debug -Target All
if ($LASTEXITCODE -ne 0) {
    $verifyErrors.Add("Build failed")
} else {
    Write-Host "  [OK]   Build passed." -ForegroundColor Green
}

# --- 4. Lint ---
if (-not $SkipLint) {
    Write-Host "`n[4/4] Lint..." -ForegroundColor Yellow
    & "$projectRoot\tools\lint.ps1" -Target All
    if ($LASTEXITCODE -ne 0) {
        $verifyErrors.Add("Lint failed")
    } else {
        Write-Host "  [OK]   Lint passed." -ForegroundColor Green
    }
} else {
    Write-Host "`n[4/4] Lint (SKIPPED)" -ForegroundColor Yellow
}

# --- Summary ---
Write-Host "`n=================================================================" -ForegroundColor Cyan
if ($verifyErrors.Count -eq 0) {
    Write-Host "   VERIFY PASSED — All CI gates green                             " -ForegroundColor Green
    Write-Host "=================================================================" -ForegroundColor Cyan
    exit 0
} else {
    Write-Host "   VERIFY FAILED — $($verifyErrors.Count) gate(s) failed            " -ForegroundColor Red
    foreach ($e in $verifyErrors) { Write-Host "     - $e" -ForegroundColor Red }
    Write-Host "=================================================================" -ForegroundColor Cyan
    exit 1
}
