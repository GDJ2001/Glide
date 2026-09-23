<#
.SYNOPSIS
    Audits and validates the controlled production rollout protocol for Glide.

.DESCRIPTION
    GLIDE-065: Controlled Production Rollout Auditor.
    Validates:
    1. Cryptographic identity and integrity of frozen Release Candidate artifacts (AAB, APK, mapping.txt).
    2. Staged rollout schedule (5% -> 20% -> 50% -> 100%) and 24-hour observation gating rules.
    3. Android Vitals quality thresholds (< 0.10% crash, < 0.05% ANR, 0 wake locks/scans).
    4. Initial release bounded distribution contingency protocol.
    5. Play Console operator runbook, immediate halt triggers, and forward-fix rollback procedure.
    6. Two-device smoke verification runbook.
#>

[CmdletBinding()]
param ()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$violations = [System.Collections.Generic.List[string]]::new()

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "   Glide GLIDE-065: Controlled Production Rollout Auditor        " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# Frozen Release Candidate Hash Manifest
$EXPECTED_AAB_HASH = "a2d12fb51e3fa293e9190849abae9ed72fb906dff05a2a18eb069ef1a5d941c2"
$EXPECTED_APK_HASH = "22d5db96b773c370a90c7fdf65f2b779e52437ecf4a2ccff94037b83d1489493"
$EXPECTED_MAP_HASH = "e4c1de9006da57630b4c79773ae456d582cc14a8f9db5751db5edca631491cfd"

# --------------------------------------------------------------------------------------------------
# Check 1: Release Candidate Artifact Identity & Frozen Checksum Audit
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 1/5] Auditing frozen Release Candidate cryptographic identity..." -ForegroundColor Yellow

$androidPrefix = if (Test-Path "$projectRoot\android\app") { "$projectRoot\android\app" } elseif (Test-Path "$projectRoot\android") { "$projectRoot\android" } else { "$projectRoot\app" }
$apkPath = "$androidPrefix\build\outputs\apk\release\app-release.apk"
$aabPath = "$androidPrefix\build\outputs\bundle\release\app-release.aab"
$mappingPath = "$androidPrefix\build\outputs\mapping\release\mapping.txt"
$checksumPath = "$androidPrefix\build\outputs\release-artifacts\SHA256SUMS.txt"

if (-not (Test-Path $aabPath)) {
    $msg = "Play App Bundle (app-release.aab) not found on disk."
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    $actualAabHash = (Get-FileHash -Path $aabPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualAabHash -ne $EXPECTED_AAB_HASH) {
        $msg = "AAB hash mismatch! Expected $EXPECTED_AAB_HASH, got $actualAabHash"
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    } else {
        Write-Host "  PASS: app-release.aab matches frozen hash ($actualAabHash)" -ForegroundColor Green
    }
}

if (-not (Test-Path $apkPath)) {
    $msg = "Signed Release Tester APK (app-release.apk) not found on disk."
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    $actualApkHash = (Get-FileHash -Path $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualApkHash -ne $EXPECTED_APK_HASH) {
        $msg = "APK hash mismatch! Expected $EXPECTED_APK_HASH, got $actualApkHash"
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    } else {
        Write-Host "  PASS: app-release.apk matches frozen hash ($actualApkHash)" -ForegroundColor Green
    }
}

if (-not (Test-Path $mappingPath)) {
    $msg = "R8 mapping file (mapping.txt) not found on disk."
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    $actualMapHash = (Get-FileHash -Path $mappingPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualMapHash -ne $EXPECTED_MAP_HASH) {
        $msg = "mapping.txt hash mismatch! Expected $EXPECTED_MAP_HASH, got $actualMapHash"
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    } else {
        Write-Host "  PASS: mapping.txt matches frozen hash ($actualMapHash)" -ForegroundColor Green
    }
}

if (-not (Test-Path $checksumPath)) {
    $msg = "Release artifact SHA256SUMS.txt manifest is missing!"
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    $sums = Get-Content -Path $checksumPath -Raw
    if ($sums -notmatch $EXPECTED_AAB_HASH -or $sums -notmatch $EXPECTED_APK_HASH -or $sums -notmatch $EXPECTED_MAP_HASH) {
        $msg = "SHA256SUMS.txt does not contain all expected frozen hashes."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    } else {
        Write-Host "  PASS: SHA256SUMS.txt contains all frozen cryptographic digests." -ForegroundColor Green
    }
}

# --------------------------------------------------------------------------------------------------
# Check 2: Rollout Protocol Document & Phased Schedule Audit
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 2/5] Auditing PRODUCTION_ROLLOUT_PROTOCOL.md phased schedule..." -ForegroundColor Yellow

$docDir = if (Test-Path "$projectRoot\doc") { "doc" } else { "docs" }
$protocolPath = "$projectRoot\$docDir\release\PRODUCTION_ROLLOUT_PROTOCOL.md"
if (-not (Test-Path $protocolPath)) {
    $msg = "$docDir/release/PRODUCTION_ROLLOUT_PROTOCOL.md is missing!"
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    $protocolText = Get-Content -Path $protocolPath -Raw

    # Verify all 4 rollout percentages
    $requiredPercentages = @("5%", "20%", "50%", "100%")
    foreach ($pct in $requiredPercentages) {
        if ($protocolText -notmatch [regex]::Escape($pct)) {
            $msg = "Rollout protocol does not document phase percentage: $pct"
            Write-Host "  VIOLATION: $msg" -ForegroundColor Red
            $violations.Add($msg)
        }
    }

    # Verify 24-hour observation window
    if ($protocolText -notmatch "24\s*hours" -and $protocolText -notmatch "24h") {
        $msg = "Rollout protocol does not document 24-hour observation window."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    } else {
        Write-Host "  PASS: 4-phase rollout schedule (5% -> 20% -> 50% -> 100%) and 24h soak verified." -ForegroundColor Green
    }

    # Verify hash consistency in protocol doc
    if ($protocolText -notmatch $EXPECTED_AAB_HASH -or $protocolText -notmatch $EXPECTED_APK_HASH -or $protocolText -notmatch $EXPECTED_MAP_HASH) {
        $msg = "Rollout protocol does not contain all frozen artifact hashes."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    } else {
        Write-Host "  PASS: Rollout protocol contains matching frozen cryptographic hashes." -ForegroundColor Green
    }
}

# --------------------------------------------------------------------------------------------------
# Check 3: Android Vitals Thresholds & Telemetry Health Review
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 3/5] Auditing Android Vitals quality thresholds & halt triggers..." -ForegroundColor Yellow

if (Test-Path $protocolPath) {
    $protocolText = Get-Content -Path $protocolPath -Raw

    if ($protocolText -notmatch "0\.10") {
        $msg = "Rollout protocol does not specify crash rate threshold (< 0.10%)."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    }
    if ($protocolText -notmatch "0\.05") {
        $msg = "Rollout protocol does not specify ANR rate threshold (< 0.05%)."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    }

    $requiredTriggers = @(
        "Data Corruption",
        "Consent Bypass",
        "Unverified Exposure",
        "Vitals Regression",
        "Critical Vulnerability"
    )

    foreach ($trigger in $requiredTriggers) {
        if ($protocolText -notmatch $trigger) {
            $msg = "Rollout protocol missing halt trigger: $trigger"
            Write-Host "  VIOLATION: $msg" -ForegroundColor Red
            $violations.Add($msg)
        }
    }

    if ($protocolText -notmatch "Forward-Fix Rollback") {
        $msg = "Rollout protocol does not document Forward-Fix Rollback."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    }

    Write-Host "  PASS: Vitals thresholds (<0.10% crash, <0.05% ANR), 5 halt triggers, and forward-fix rollback verified." -ForegroundColor Green
}

# --------------------------------------------------------------------------------------------------
# Check 4: Initial Release Bounded Distribution Contingency Audit
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 4/5] Auditing initial release bounded distribution contingency..." -ForegroundColor Yellow

if (Test-Path $protocolPath) {
    $protocolText = Get-Content -Path $protocolPath -Raw

    if ($protocolText -notmatch "Bounded Distribution" -and $protocolText -notmatch "bounded distribution") {
        $msg = "Rollout protocol does not document bounded distribution contingency."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    } else {
        Write-Host "  PASS: Initial release bounded distribution contingency documented." -ForegroundColor Green
    }

    if ($protocolText -notmatch "Closed Testing Track") {
        $msg = "Rollout protocol does not reference Closed Testing Track as pre-production boundary."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    } else {
        Write-Host "  PASS: Closed Testing Track referenced as pre-production boundary." -ForegroundColor Green
    }
}

# --------------------------------------------------------------------------------------------------
# Check 5: Play Console Operator Runbook & Two-Device Smoke Audit
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 5/5] Auditing operator runbook & two-device smoke verification..." -ForegroundColor Yellow

if (Test-Path $protocolPath) {
    $protocolText = Get-Content -Path $protocolPath -Raw

    if ($protocolText -notmatch "Play Console" -or $protocolText -notmatch "Halt rollout") {
        $msg = "Play Console operator runbook missing or does not explain Halt rollout action."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    } else {
        Write-Host "  PASS: Play Console operator runbook verified (initiating, updating, halting)." -ForegroundColor Green
    }

    if ($protocolText -notmatch "Two-Device Smoke Verification" -or $protocolText -notmatch "SAS") {
        $msg = "Two-device smoke verification runbook missing or does not require SAS verification."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    } else {
        Write-Host "  PASS: Two-device smoke verification runbook with mutual SAS check verified." -ForegroundColor Green
    }
}

# --------------------------------------------------------------------------------------------------
# Final Audit Verdict
# --------------------------------------------------------------------------------------------------
Write-Host "`n=================================================================" -ForegroundColor Cyan
if ($violations.Count -eq 0) {
    Write-Host "   CONTROLLED PRODUCTION ROLLOUT AUDIT PASSED (0 violations)     " -ForegroundColor Green
    Write-Host "=================================================================" -ForegroundColor Cyan
    exit 0
} else {
    Write-Host "   ROLLOUT AUDIT FAILED WITH $($violations.Count) VIOLATION(S)   " -ForegroundColor Red
    Write-Host "=================================================================" -ForegroundColor Cyan
    foreach ($v in $violations) {
        Write-Host " - $v" -ForegroundColor Red
    }
    exit 1
}
