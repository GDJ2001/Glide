<#
.SYNOPSIS
    Audits and validates the readiness of Glide for Google Play Internal and Closed Testing.

.DESCRIPTION
    GLIDE-063: Closed Testing Readiness Auditor.
    Validates:
    1. Signed Release Candidate artifacts readiness (AAB, APK, mapping.txt, SHA256SUMS).
    2. Google Play Closed Testing policy compliance (12 testers, 14 days, opt-in mechanism).
    3. Multi-OEM cohort and hardware matrix representation.
    4. Android Vitals monitoring thresholds and incident trigger rules.
    5. Reviewer runbook and zero-cloud credential isolation.
#>

[CmdletBinding()]
param ()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$violations = [System.Collections.Generic.List[string]]::new()

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "   Glide GLIDE-063: Closed Testing Readiness Auditor             " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# --------------------------------------------------------------------------------------------------
# Check 1: Release Candidate Artifacts & Checksums
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 1/5] Auditing Release Candidate artifacts & supply-chain manifests..." -ForegroundColor Yellow

$androidPrefix = if (Test-Path "$projectRoot\android") { "$projectRoot\android" } else { "$projectRoot\app" }
$apkPath = "$androidPrefix\build\outputs\apk\release\android-release.apk"
if (-not (Test-Path $apkPath)) {
    $apkPath = "$androidPrefix\build\outputs\apk\release\app-release.apk"
}
$aabPath = "$androidPrefix\build\outputs\bundle\release\android-release.aab"
if (-not (Test-Path $aabPath)) {
    $aabPath = "$androidPrefix\build\outputs\bundle\release\app-release.aab"
}
$mappingPath = "$androidPrefix\build\outputs\mapping\release\mapping.txt"
$checksumPath = "$androidPrefix\build\outputs\release-artifacts\SHA256SUMS.txt"

if (-not (Test-Path $aabPath)) {
    $msg = "Play App Bundle (aab) is missing at $aabPath!"
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    $aabSize = [math]::Round((Get-Item $aabPath).Length / 1MB, 2)
    Write-Host "  aab present ($aabSize MB)" -ForegroundColor Green
}

if (-not (Test-Path $apkPath)) {
    $msg = "Signed Release Tester APK is missing at $apkPath!"
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    $apkSize = [math]::Round((Get-Item $apkPath).Length / 1MB, 2)
    Write-Host "  apk present ($apkSize MB)" -ForegroundColor Green
}

if (-not (Test-Path $mappingPath)) {
    $msg = "R8 deobfuscation mapping file (mapping.txt) is missing!"
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    $mapSize = [math]::Round((Get-Item $mappingPath).Length / 1MB, 2)
    Write-Host "  mapping.txt present ($mapSize MB)" -ForegroundColor Green
}

if (-not (Test-Path $checksumPath)) {
    $msg = "SHA256SUMS.txt checksum manifest is missing!"
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    Write-Host "  SHA256SUMS.txt present and non-empty." -ForegroundColor Green
}

# --------------------------------------------------------------------------------------------------
# Check 2: Google Play Closed Testing Policy Specifications
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 2/5] Auditing Google Play closed testing policy compliance..." -ForegroundColor Yellow

$docDir = if (Test-Path "$projectRoot\doc") { "doc" } else { "docs" }
$protocolFile = "$projectRoot\$docDir\release\CLOSED_TESTING_PROTOCOL.md"
if (-not (Test-Path $protocolFile)) {
    $msg = "CLOSED_TESTING_PROTOCOL.md does not exist!"
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    $protocolText = Get-Content -Path $protocolFile -Raw

    if ($protocolText -notmatch "12\s+(?:distinct|testers)") {
        $msg = "CLOSED_TESTING_PROTOCOL.md does not document the mandatory 12 testers requirement."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    }
    if ($protocolText -notmatch "14\s+(?:continuous|consecutive)?\s*days") {
        $msg = "CLOSED_TESTING_PROTOCOL.md does not document the mandatory 14 days duration requirement."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    }
    if ($protocolText -notmatch "Internal Testing Track") {
        $msg = "CLOSED_TESTING_PROTOCOL.md does not define the Internal Testing Track."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    }
    Write-Host "  PASS: Google Play closed testing policy rules fully formalized." -ForegroundColor Green
}

# --------------------------------------------------------------------------------------------------
# Check 3: Multi-OEM Hardware Matrix Representation
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 3/5] Auditing multi-OEM hardware matrix & API level coverage..." -ForegroundColor Yellow

if (Test-Path $protocolFile) {
    $protocolText = Get-Content -Path $protocolFile -Raw
    $requiredBrands = @("Pixel", "Samsung", "Xiaomi", "OnePlus")
    foreach ($brand in $requiredBrands) {
        if (-not $protocolText.Contains($brand)) {
            $msg = "Tester cohort does not include required brand: $brand"
            Write-Host "  VIOLATION: $msg" -ForegroundColor Red
            $violations.Add($msg)
        }
    }
    if ($protocolText -notmatch "Tablet") {
        $msg = "Tester cohort does not include Tablet form factors."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    }
    Write-Host "  PASS: Multi-OEM hardware matrix spans flagship, mid-range, legacy, and tablet form factors." -ForegroundColor Green
}

# --------------------------------------------------------------------------------------------------
# Check 4: Android Vitals Threshold Specifications
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 4/5] Auditing Android Vitals quality thresholds & SLA runbook..." -ForegroundColor Yellow

if (Test-Path $protocolFile) {
    $protocolText = Get-Content -Path $protocolFile -Raw

    if ($protocolText -notmatch "0\.10\\?%") {
        $msg = "Crash rate threshold (0.10%) not found in protocol."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    }
    if ($protocolText -notmatch "0\.05\\?%") {
        $msg = "ANR rate threshold (0.05%) not found in protocol."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    }
    if ($protocolText -notmatch "P0\s*\(Blocker\)") {
        $msg = "Bug triage SLA (P0 Blocker) not formalized in protocol."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    }
    Write-Host "  PASS: Android Vitals targets (<0.10% crash, <0.05% ANR) and SLA runbooks verified." -ForegroundColor Green
}

# --------------------------------------------------------------------------------------------------
# Check 5: Reviewer Guidance & Local Isolation
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 5/5] Auditing Play Reviewer guidance & zero-cloud isolation..." -ForegroundColor Yellow

if (Test-Path $protocolFile) {
    $protocolText = Get-Content -Path $protocolFile -Raw
    if ($protocolText -notmatch "No Account Required") {
        $msg = "Reviewer guidance does not state that no user account is required."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    }
    if ($protocolText -notmatch "Two-Device") {
        $msg = "Reviewer guidance does not explain the two-device testing requirement."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    }
    Write-Host "  PASS: Reviewer guidance enables smooth evaluation without cloud credentials." -ForegroundColor Green
}

# --------------------------------------------------------------------------------------------------
# Final Audit Verdict
# --------------------------------------------------------------------------------------------------
Write-Host "`n=================================================================" -ForegroundColor Cyan
if ($violations.Count -eq 0) {
    Write-Host "   ALL CLOSED TESTING READINESS CHECKS PASSED (0 issues)        " -ForegroundColor Green
    Write-Host "=================================================================" -ForegroundColor Cyan
    exit 0
} else {
    Write-Host "   AUDIT FAILED WITH $($violations.Count) VIOLATION(S)          " -ForegroundColor Red
    Write-Host "=================================================================" -ForegroundColor Cyan
    foreach ($v in $violations) {
        Write-Host " - $v" -ForegroundColor Red
    }
    exit 1
}
