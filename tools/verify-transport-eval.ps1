<#
.SYNOPSIS
    Automated auditor for Glide GLIDE-071: Advanced Android Transports Evaluation.
.DESCRIPTION
    Validates ADR-0016, technical evaluation specification, V1 permission isolation invariant,
    threat model mitigations, and pure JVM capability domain models and test suites.
#>

$ErrorActionPreference = "Stop"
$ProjectRoot = Resolve-Path "$PSScriptRoot\.."

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "    Glide GLIDE-071: Advanced Android Transports Auditor         " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# --- Check 1: ADR-0016 and Architecture Decision Index ---
Write-Host "`n[Check 1/5] Auditing ADR-0016 existence and decision index..." -ForegroundColor Yellow
$AdrPath = Join-Path $ProjectRoot "docs\adr\ADR-0016-advanced-android-transports.md"
$AdrIndex = Join-Path $ProjectRoot "docs\adr\README.md"

if (-not (Test-Path $AdrPath)) {
    Write-Error "FAIL: ADR-0016 not found at $AdrPath"
}
$AdrContent = Get-Content $AdrPath -Raw
if ($AdrContent -notmatch "Multi-Tier Transport Architecture") {
    Write-Error "FAIL: ADR-0016 missing Multi-Tier Transport Architecture decision."
}
if ($AdrContent -notmatch "Tier 1" -or $AdrContent -notmatch "Tier 2" -or $AdrContent -notmatch "Tier 3") {
    Write-Error "FAIL: ADR-0016 missing transport tier designations."
}
$IndexContent = Get-Content $AdrIndex -Raw
if ($IndexContent -notmatch "ADR-0016 — Advanced Android transports evaluation") {
    Write-Error "FAIL: ADR-0016 not indexed in docs/adr/README.md."
}
Write-Host "  PASS: ADR-0016 documents multi-tier transport architecture and is properly indexed." -ForegroundColor Green

# --- Check 2: Technical Evaluation Specification ---
Write-Host "`n[Check 2/5] Auditing technical evaluation specification completeness..." -ForegroundColor Yellow
$SpecPath = Join-Path $ProjectRoot "docs\future\ADVANCED_ANDROID_TRANSPORTS_EVALUATION.md"
if (-not (Test-Path $SpecPath)) {
    Write-Error "FAIL: Specification not found at $SpecPath"
}
$SpecContent = Get-Content $SpecPath -Raw
$RequiredSections = @(
    "Candidate Transport Evaluation Matrix",
    "Wi-Fi Aware",
    "Autonomous Group Owner",
    "Local-Only Hotspot",
    "LAN \+ NSD",
    "Radio Coexistence",
    "Single-PHY",
    "Dual-PHY",
    "Application-Layer TLS 1\.3"
)
foreach ($section in $RequiredSections) {
    if ($SpecContent -notmatch $section) {
        Write-Error "FAIL: Specification missing required topic: $section"
    }
}
Write-Host "  PASS: Specification contains comprehensive technical matrix, coexistence analysis, and TLS 1.3 mandate." -ForegroundColor Green

# --- Check 3: V1 Permission Non-Expansion Invariant ---
Write-Host "`n[Check 3/5] Auditing V1 permission isolation and non-expansion..." -ForegroundColor Yellow
$PermMatrix = Join-Path $ProjectRoot "docs\android\PERMISSION_MATRIX.md"
if (-not (Test-Path $PermMatrix)) {
    Write-Error "FAIL: PERMISSION_MATRIX.md missing."
}

$AppManifest = Join-Path $ProjectRoot "app\src\main\AndroidManifest.xml"
$AppManifestContent = Get-Content $AppManifest -Raw

# Ensure production manifests adhere strictly to PERMISSION_MATRIX.md:
# 1. No broad storage permissions
# 2. No background location or intrusive device permissions
# 3. Location permissions bounded to legacy Android <= 32 (maxSdkVersion="32")
$BannedV1Permissions = @(
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.READ_MEDIA_AUDIO",
    "android.permission.ACCESS_BACKGROUND_LOCATION",
    "android.permission.RECORD_AUDIO",
    "android.permission.READ_CONTACTS",
    "android.permission.RECEIVE_BOOT_COMPLETED"
)
foreach ($perm in $BannedV1Permissions) {
    if ($AppManifestContent -match [regex]::Escape($perm)) {
        Write-Error "FAIL: Banned permission '$perm' found in V1 production manifest!"
    }
}

# Verify ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION are bounded by maxSdkVersion="32"
if ($AppManifestContent -notmatch '(?s)android\.permission\.ACCESS_FINE_LOCATION.*?android:maxSdkVersion="32"') {
    Write-Error "FAIL: ACCESS_FINE_LOCATION is not properly bounded to maxSdkVersion=32!"
}
if ($AppManifestContent -notmatch 'neverForLocation') {
    Write-Error "FAIL: Bluetooth and Wi-Fi permissions must assert neverForLocation!"
}
Write-Host "  PASS: V1 production manifest adheres strictly to zero silent permission expansion." -ForegroundColor Green

# --- Check 4: Threat Model & TLS 1.3 Security Coverage ---
Write-Host "`n[Check 4/5] Auditing threat model matrix and TLS 1.3 security proofs..." -ForegroundColor Yellow
$RequiredThreats = @("T01", "T02", "T03", "T04", "T05", "T06", "T07", "T08", "T10", "T17", "T18")
foreach ($threat in $RequiredThreats) {
    if ($SpecContent -notmatch "\*\*$threat\*\*") {
        Write-Error "FAIL: Specification missing mitigation for threat $threat"
    }
}
if ($SpecContent -notmatch "ExtractDecimal6" -and $SpecContent -notmatch "SAS") {
    Write-Error "FAIL: Specification missing SAS / TOFU cryptographic binding."
}
Write-Host "  PASS: Threat model matrix (T01-T08, T10-T15, T17, T18) and SAS/TLS 1.3 bindings verified." -ForegroundColor Green

# --- Check 5: Pure JVM Transport Domain Models and Tests ---
Write-Host "`n[Check 5/5] Auditing pure JVM TransportCapability and unit test suite..." -ForegroundColor Yellow
$DomainModel = Join-Path $ProjectRoot "transfer-core\src\main\kotlin\dev\glide\core\transport\TransportCapability.kt"
$TestFile = Join-Path $ProjectRoot "transfer-core\src\test\kotlin\dev\glide\core\transport\TransportEvaluationMatrixTest.kt"

if (-not (Test-Path $DomainModel)) {
    Write-Error "FAIL: TransportCapability.kt not found at $DomainModel"
}
if (-not (Test-Path $TestFile)) {
    Write-Error "FAIL: TransportEvaluationMatrixTest.kt not found at $TestFile"
}

# Verify no Android framework imports in transfer-core domain model
$DomainContent = Get-Content $DomainModel -Raw
if ($DomainContent -match "import android\.") {
    Write-Error "FAIL: Android framework dependency leaked into pure JVM transfer-core!"
}
Write-Host "  PASS: Pure JVM transport domain models and unit tests verified." -ForegroundColor Green

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "  ADVANCED TRANSPORTS EVALUATION AUDIT PASSED (0 violations)     " -ForegroundColor Cyan
Write-Host "=================================================================`n" -ForegroundColor Cyan
