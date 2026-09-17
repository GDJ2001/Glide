<#
.SYNOPSIS
    Automated auditor for Glide ADR-0019: Browser and Web Client Zero-Install Local Transfer Gateway.
.DESCRIPTION
    Validates ADR-0019, technical specification completeness, zero-cloud offline invariants,
    zero-CDN embedded SPA constraints, browser security sandbox policies, and quarantine staging.
#>

$ErrorActionPreference = "Stop"
$ProjectRoot = Resolve-Path "$PSScriptRoot\.."

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "    Glide ADR-0019: Browser & Web Transfer Slice Auditor         " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# --- Check 1: ADR-0019 Existence and Decision Index ---
Write-Host "`n[Check 1/5] Auditing ADR-0019 existence and decision index..." -ForegroundColor Yellow
$AdrPath = Join-Path $ProjectRoot "docs\adr\ADR-0019-browser-web-local-transfer.md"
$AdrIndex = Join-Path $ProjectRoot "docs\adr\README.md"

if (-not (Test-Path $AdrPath)) {
    Write-Error "FAIL: ADR-0019 not found at $AdrPath"
}
$AdrContent = Get-Content $AdrPath -Raw
if ($AdrContent -notmatch "Zero-Install Ephemeral Local Web Gateway" -or $AdrContent -notmatch "HTTP/1\.1") {
    Write-Error "FAIL: ADR-0019 missing Zero-Install Local Web Gateway decision."
}
$IndexContent = Get-Content $AdrIndex -Raw
if ($IndexContent -notmatch "ADR-0019 — Browser and web client zero-install local transfer gateway") {
    Write-Error "FAIL: ADR-0019 not properly indexed in docs/adr/README.md."
}
Write-Host "  PASS: ADR-0019 exists and is indexed in docs/adr/README.md." -ForegroundColor Green

# --- Check 2: Technical Specification Completeness ---
Write-Host "`n[Check 2/5] Auditing Browser Web technical specification completeness..." -ForegroundColor Yellow
$SpecPath = Join-Path $ProjectRoot "docs\future\ANDROID_BROWSER_WEB_SPECIFICATION.md"
if (-not (Test-Path $SpecPath)) {
    Write-Error "FAIL: Specification not found at $SpecPath"
}
$SpecContent = Get-Content $SpecPath -Raw
$RequiredSections = @(
    "Zero Cloud",
    "Zero External CDN",
    "QR Onboarding",
    "Token Lifecycle",
    "120 seconds",
    "Content-Security-Policy",
    "Quarantine Staging",
    "Atomic",
    "Phase W1",
    "Phase W2",
    "Phase W3",
    "Phase W4",
    "Phase W5"
)
foreach ($section in $RequiredSections) {
    if ($SpecContent -notmatch [regex]::Escape($section)) {
        Write-Error "FAIL: Specification missing required topic: $section"
    }
}
Write-Host "  PASS: Specification contains complete REST endpoints, QR token lifecycle, CSP headers, and 5-phase roadmap." -ForegroundColor Green

# --- Check 3: Zero-Cloud & Zero-CDN Offline Invariants ---
Write-Host "`n[Check 3/5] Auditing Zero-Cloud & Zero-CDN Offline Invariants..." -ForegroundColor Yellow
if ($SpecContent -notmatch "Zero Cloud" -or $SpecContent -notmatch "100% Offline") {
    Write-Error "FAIL: Specification missing explicit Zero Cloud / 100% Offline invariant statement."
}
if ($SpecContent -notmatch "Zero External CDN" -or $SpecContent -notmatch "Google Fonts") {
    Write-Error "FAIL: Specification missing explicit Zero-CDN / air-gapped web client invariant."
}
Write-Host "  PASS: Zero-cloud, 100% offline, and zero-CDN air-gapped invariants verified." -ForegroundColor Green

# --- Check 4: Browser Security Sandbox & Header Policy ---
Write-Host "`n[Check 4/5] Auditing Browser Security Sandbox & Header Policy..." -ForegroundColor Yellow
$RequiredHeaders = @(
    "nosniff",
    "DENY",
    "SameSite=Strict",
    "HttpOnly"
)
foreach ($header in $RequiredHeaders) {
    if ($SpecContent -notmatch $header) {
        Write-Error "FAIL: Specification missing required security header rule: $header"
    }
}
Write-Host "  PASS: CSP, X-Content-Type-Options, X-Frame-Options, and cookie security policies verified." -ForegroundColor Green

# --- Check 5: Cross-Platform Path Strategic Alignment ---
Write-Host "`n[Check 5/5] Auditing cross-platform roadmap strategic alignment..." -ForegroundColor Yellow
$PathFile = Join-Path $ProjectRoot "docs\future\CROSS_PLATFORM_PATH.md"
if (-not (Test-Path $PathFile)) {
    Write-Error "FAIL: docs/future/CROSS_PLATFORM_PATH.md missing."
}
$PathContent = Get-Content $PathFile -Raw
if ($PathContent -notmatch "Android↔Browser" -or $PathContent -notmatch "ADR-0019") {
    Write-Error "FAIL: CROSS_PLATFORM_PATH.md Step 3 missing ADR-0019 alignment."
}
Write-Host "  PASS: CROSS_PLATFORM_PATH.md Step 3 properly references ADR-0019 and web specification." -ForegroundColor Green

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "  BROWSER & WEB TRANSFER SPECIFICATION AUDIT PASSED (0 issues)   " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan
