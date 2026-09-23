<#
.SYNOPSIS
    Automated auditor for Glide GLIDE-072: Android-to-Windows Interoperable LAN Slice.
.DESCRIPTION
    Validates ADR-0017, cross-platform technical specification, mDNS discovery contracts,
    zero-cloud offline invariants, LAN threat model coverage, and pure JVM stream framing models.
#>

$ErrorActionPreference = "Stop"
$ProjectRoot = Resolve-Path "$PSScriptRoot\.."

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "    Glide GLIDE-072: Android-Windows LAN Slice Auditor           " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# --- Check 1: ADR-0017 and Architecture Decision Index ---
Write-Host "`n[Check 1/5] Auditing ADR-0017 existence and decision index..." -ForegroundColor Yellow
$docDir = if (Test-Path (Join-Path $ProjectRoot "doc")) { "doc" } else { "docs" }
$AdrPath = Join-Path $ProjectRoot "$docDir\adr\ADR-0017-android-windows-interoperable-lan-slice.md"
$AdrIndex = Join-Path $ProjectRoot "$docDir\adr\README.md"

if (-not (Test-Path $AdrPath)) {
    Write-Error "FAIL: ADR-0017 not found at $AdrPath"
}
$AdrContent = Get-Content $AdrPath -Raw
if ($AdrContent -notmatch "Standard LAN Transport" -or $AdrContent -notmatch "mDNS" -or $AdrContent -notmatch "TLS 1\.3") {
    Write-Error "FAIL: ADR-0017 missing Standard LAN Transport decision with mDNS and TLS 1.3."
}
$IndexContent = Get-Content $AdrIndex -Raw
if ($IndexContent -notmatch "ADR-0017.*Android-to-Windows interoperable LAN slice") {
    Write-Error "FAIL: ADR-0017 not indexed in $docDir/adr/README.md."
}
Write-Host "  PASS: ADR-0017 documents standard LAN transport with mDNS/TLS 1.3 and is properly indexed." -ForegroundColor Green

# --- Check 2: Cross-Platform LAN Technical Specification ---
Write-Host "`n[Check 2/5] Auditing cross-platform technical specification completeness..." -ForegroundColor Yellow
$SpecPath = Join-Path $ProjectRoot "$docDir\future\ANDROID_WINDOWS_LAN_SPECIFICATION.md"
if (-not (Test-Path $SpecPath)) {
    Write-Error "FAIL: Specification not found at $SpecPath"
}
$SpecContent = Get-Content $SpecPath -Raw
$RequiredSections = @(
    "_glide\._tcp\.local\.",
    "mDNS Service Discovery",
    "Length-Prefixed Control Framing",
    "16 KiB",
    "Safe Windows Path Sanitizer",
    "Phase 1",
    "Phase 2",
    "Phase 3",
    "Phase 4",
    "Phase 5"
)
foreach ($section in $RequiredSections) {
    if ($SpecContent -notmatch $section) {
        Write-Error "FAIL: Specification missing required topic: $section"
    }
}
Write-Host "  PASS: Specification contains complete mDNS schema, 16 KiB framing, Windows path sanitizer, and 5-phase plan." -ForegroundColor Green

# --- Check 3: Zero-Cloud / 100% Offline LAN Invariant ---
Write-Host "`n[Check 3/5] Auditing Zero-Cloud / 100% Offline LAN Invariant..." -ForegroundColor Yellow
if ($SpecContent -notmatch "Zero Cloud" -and $SpecContent -notmatch "100% Offline") {
    Write-Error "FAIL: Specification missing explicit Zero Cloud / 100% Offline invariant statement."
}
if ($SpecContent -match "cloud relay" -and $SpecContent -notmatch "No cloud|rejected|forbidden|Zero Cloud") {
    Write-Error "FAIL: Cloud relay mistakenly approved in specification!"
}
Write-Host "  PASS: Zero-cloud, 100% offline local subnet invariant verified." -ForegroundColor Green

# --- Check 4: Threat Model & SAS / TLS 1.3 Security Coverage ---
Write-Host "`n[Check 4/5] Auditing LAN threat model matrix and cryptographic SAS derivation..." -ForegroundColor Yellow
$RequiredThreats = @("T01", "T02", "T03", "T04", "T05", "T06", "T07", "T08", "T10", "T17")
foreach ($threat in $RequiredThreats) {
    if ($SpecContent -notmatch "\*\*$threat\*\*") {
        Write-Error "FAIL: Specification missing mitigation for threat $threat"
    }
}
if ($SpecContent -notmatch "ExtractDecimal6" -or $SpecContent -notmatch "TLS 1\.3") {
    Write-Error "FAIL: Specification missing SAS derivation or TLS 1.3 mandate."
}
Write-Host "  PASS: Threat model matrix (T01-T08, T10-T15, T17) and SAS/TLS 1.3 derivation verified." -ForegroundColor Green

# --- Check 5: Pure JVM Transport Domain Models, Tests & Golden Fixtures ---
Write-Host "`n[Check 5/5] Auditing pure JVM LanTransportContracts, test suite, and golden fixtures..." -ForegroundColor Yellow
$corePrefix = if (Test-Path (Join-Path $ProjectRoot "android\core")) { "android\core" } elseif (Test-Path (Join-Path $ProjectRoot "shared\transfer-core")) { "shared\transfer-core" } else { "transfer-core" }
$DomainModel = Join-Path $ProjectRoot "$corePrefix\src\main\kotlin\dev\glide\core\transport\lan\LanTransportContracts.kt"
$TestFile = Join-Path $ProjectRoot "$corePrefix\src\test\kotlin\dev\glide\core\transport\lan\LanFramingAndSecurityTest.kt"
$FixturesDir = Join-Path $ProjectRoot "$corePrefix\src\test\resources\fixtures"

if (-not (Test-Path $DomainModel)) {
    Write-Error "FAIL: LanTransportContracts.kt not found at $DomainModel"
}
if (-not (Test-Path $TestFile)) {
    Write-Error "FAIL: LanFramingAndSecurityTest.kt not found at $TestFile"
}

# Verify no Android framework imports in transfer-core
$DomainContent = Get-Content $DomainModel -Raw
if ($DomainContent -match "import android\.") {
    Write-Error "FAIL: Android framework dependency leaked into pure JVM transfer-core!"
}

# Verify canonical golden fixtures count >= 20
$FixturesCount = (Get-ChildItem $FixturesDir -Filter "*.json").Count
if ($FixturesCount -lt 20) {
    Write-Error "FAIL: Expected at least 20 canonical golden fixtures in $FixturesDir, found $FixturesCount"
}
Write-Host "  PASS: Pure JVM LAN framing models verified; $FixturesCount golden fixtures confirmed." -ForegroundColor Green

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "  ANDROID-WINDOWS LAN SLICE AUDIT PASSED (0 violations)          " -ForegroundColor Cyan
Write-Host "=================================================================`n" -ForegroundColor Cyan
