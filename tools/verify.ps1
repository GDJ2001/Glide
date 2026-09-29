<#
.SYNOPSIS
    Glide Verify Script — HARD-035.
.DESCRIPTION
    Runs local development checks: documentation, architecture, Windows companion
    conformance, build, tests, and lint. Hosted CI and physical device checks are separate.

    Components:
    - Documentation check (android/build.gradle.kts)
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
Write-Host "   Glide Verify — Local Development Gates                        " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

$verifyErrors = [System.Collections.Generic.List[string]]::new()

# --- 1. Documentation ---
Write-Host "`n[1/6] Documentation..." -ForegroundColor Yellow
Push-Location (Join-Path $projectRoot "android")
try {
    & .\gradlew.bat checkDocumentation --console=plain
} finally {
    Pop-Location
}
if ($LASTEXITCODE -ne 0) {
    $verifyErrors.Add("Documentation check failed")
} else {
    Write-Host "  [OK]   Documentation check passed." -ForegroundColor Green
}

# --- 2. Architecture & Static Analysis ---
if (-not $SkipArchitecture) {
    Write-Host "`n[2/6] Architecture & Static Analysis..." -ForegroundColor Yellow
    & "$projectRoot\tools\check-architecture.ps1" -FailFast
    if ($LASTEXITCODE -ne 0) {
        $verifyErrors.Add("Architecture enforcement failed")
    } else {
        Write-Host "  [OK]   Architecture checks passed." -ForegroundColor Green
    }
}

# --- 3. Windows Companion Verification ---
Write-Host "`n[3/6] Windows Companion Verification..." -ForegroundColor Yellow
& "$projectRoot\tools\verify-windows-companion.ps1"
if ($LASTEXITCODE -ne 0) {
    $verifyErrors.Add("Windows companion verification failed")
} else {
    Write-Host "  [OK]   Windows companion verification passed." -ForegroundColor Green
}

# --- 4. Build ---
Write-Host "`n[4/6] Build..." -ForegroundColor Yellow
& "$projectRoot\tools\build.ps1" -Configuration Debug -Target All
if ($LASTEXITCODE -ne 0) {
    $verifyErrors.Add("Build failed")
} else {
    Write-Host "  [OK]   Build passed." -ForegroundColor Green
}

# --- 5. Tests ---
if (-not $SkipTests) {
    Write-Host "`n[5/6] Tests..." -ForegroundColor Yellow
    & "$projectRoot\tools\test.ps1" -Target All
    if ($LASTEXITCODE -ne 0) {
        $verifyErrors.Add("Tests failed")
    } else {
        Write-Host "  [OK]   Tests passed." -ForegroundColor Green
    }
} else {
    Write-Host "`n[5/6] Tests (SKIPPED)" -ForegroundColor Yellow
}

# --- 6. Lint ---
if (-not $SkipLint) {
    Write-Host "`n[6/6] Lint..." -ForegroundColor Yellow
    & "$projectRoot\tools\lint.ps1" -Target All
    if ($LASTEXITCODE -ne 0) {
        $verifyErrors.Add("Lint failed")
    } else {
        Write-Host "  [OK]   Lint passed." -ForegroundColor Green
    }
} else {
    Write-Host "`n[6/6] Lint (SKIPPED)" -ForegroundColor Yellow
}

# --- Summary ---
Write-Host "`n=================================================================" -ForegroundColor Cyan
if ($verifyErrors.Count -eq 0) {
    Write-Host "   LOCAL VERIFICATION PASSED                                     " -ForegroundColor Green
    Write-Host "=================================================================" -ForegroundColor Cyan
    exit 0
} else {
    Write-Host "   VERIFY FAILED — $($verifyErrors.Count) gate(s) failed            " -ForegroundColor Red
    foreach ($e in $verifyErrors) { Write-Host "     - $e" -ForegroundColor Red }
    Write-Host "=================================================================" -ForegroundColor Cyan
    exit 1
}
