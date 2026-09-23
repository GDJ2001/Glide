<#
.SYNOPSIS
    Audits and validates the production release candidate approval gate for Glide.

.DESCRIPTION
    GLIDE-064: Production Release Candidate Approval Auditor.
    Validates:
    1. Exact cryptographic identity of frozen Release Candidate artifacts (AAB, APK, mapping.txt).
    2. Definition of Done quality gates reconciliation and milestone evidence completeness (M1-M11).
    3. Store readiness checklist completion in GLIDE_RELEASE_PLAN.md (12/12 items checked).
    4. Formal Production RC Approval record in PRODUCTION_RC_APPROVAL.md.
    5. Incident response halt triggers and forward-fix rollback runbook documentation.
#>

[CmdletBinding()]
param ()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$violations = [System.Collections.Generic.List[string]]::new()

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "   Glide GLIDE-064: Production Release Candidate Approval Auditor" -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# Frozen Release Candidate Hash Manifest
$EXPECTED_AAB_HASH = "728d919b76b444f72795f63422eaf5bf312de50b80215c64a804baf1b13d1d52"
$EXPECTED_APK_HASH = "cf771dbaf068e7656b2c848300c7679d3e7309a1fc081a2edbc9da56eba6f21c"
$EXPECTED_MAP_HASH = "9f108d84e77df3700b4944abd15cb2bd6fe439bf6cae01c7ea14c80d4aa9d6e4"

# --------------------------------------------------------------------------------------------------
# Check 1: Release Candidate Artifact Identity & Frozen Checksum Audit
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 1/5] Auditing frozen Release Candidate cryptographic identity..." -ForegroundColor Yellow

$androidPrefix = if (Test-Path "$projectRoot\android") { "$projectRoot\android" } else { "$projectRoot\app" }
$apkPath = "$androidPrefix\build\outputs\apk\release\app-release.apk"
$aabPath = "$androidPrefix\build\outputs\bundle\release\app-release.aab"
$mappingPath = "$androidPrefix\build\outputs\mapping\release\mapping.txt"
$checksumPath = "$androidPrefix\build\outputs\release-artifacts\SHA256SUMS.txt"

if (-not (Test-Path $aabPath)) {
    $msg = "Production App Bundle (app-release.aab) not found on disk."
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
    Write-Host "  PASS: SHA256SUMS.txt manifest present." -ForegroundColor Green
    $checksumContent = Get-Content -Path $checksumPath -Raw
    if ($checksumContent -notmatch $EXPECTED_AAB_HASH -or
        $checksumContent -notmatch $EXPECTED_APK_HASH -or
        $checksumContent -notmatch $EXPECTED_MAP_HASH) {
        $msg = "SHA256SUMS.txt does not contain all expected frozen digests!"
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    } else {
        Write-Host "  PASS: SHA256SUMS.txt contains all frozen cryptographic digests." -ForegroundColor Green
    }
}

# --------------------------------------------------------------------------------------------------
# Check 2: Definition of Done Quality Gate Evidence Completeness
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 2/5] Auditing Definition of Done quality gate evidence files..." -ForegroundColor Yellow

$docDir = if (Test-Path "$projectRoot\doc") { "doc" } else { "docs" }
$requiredEvidenceFiles = @(
    "$docDir/testing/evidence/GLIDE-043-premium-ux-gate-report.md",
    "$docDir/testing/evidence/GLIDE-047-adaptive-accessibility-gate-report.md",
    "$docDir/testing/evidence/GLIDE-050-security-review-gate-report.md",
    "$docDir/testing/evidence/GLIDE-054-performance-gate-report.md",
    "$docDir/testing/evidence/GLIDE-055-architecture-static-analysis-report.md",
    "$docDir/testing/evidence/GLIDE-061-store-assets-legal-listing-report.md",
    "$docDir/testing/evidence/GLIDE-062-signed-rc-artifacts-report.md",
    "$docDir/testing/evidence/GLIDE-063-closed-testing-report.md"
)

foreach ($relPath in $requiredEvidenceFiles) {
    $fullPath = Join-Path $projectRoot $relPath
    if (-not (Test-Path $fullPath)) {
        $msg = "Required quality gate evidence file missing: $relPath"
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    } else {
        Write-Host "  PASS: Evidence verified -> $relPath" -ForegroundColor Green
    }
}

# --------------------------------------------------------------------------------------------------
# Check 3: Store Readiness Checklist Completion Audit
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 3/5] Auditing Store readiness checklist in GLIDE_RELEASE_PLAN.md..." -ForegroundColor Yellow

$releasePlanPath = "$projectRoot\$docDir\release\GLIDE_RELEASE_PLAN.md"
if (-not (Test-Path $releasePlanPath)) {
    $msg = "$docDir/release/GLIDE_RELEASE_PLAN.md is missing!"
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    $planContent = Get-Content -Path $releasePlanPath
    $inChecklist = $false
    $checkedCount = 0
    $uncheckedCount = 0

    foreach ($line in $planContent) {
        if ($line -match "^##\s+Store readiness checklist") {
            $inChecklist = $true
            continue
        }
        if ($inChecklist -and $line -match "^##\s+") {
            $inChecklist = $false
            break
        }
        if ($inChecklist) {
            if ($line -match "^\s*-\s*\[x\]") {
                $checkedCount++
            } elseif ($line -match "^\s*-\s*\[\s\]") {
                $uncheckedCount++
            }
        }
    }

    if ($checkedCount -ne 12) {
        $msg = "Store readiness checklist has $checkedCount checked items (expected 12)."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    } elseif ($uncheckedCount -gt 0) {
        $msg = "Store readiness checklist has $uncheckedCount unchecked items."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    } else {
        Write-Host "  PASS: Store readiness checklist is 100% complete ($checkedCount/12 items checked)." -ForegroundColor Green
    }
}

# --------------------------------------------------------------------------------------------------
# Check 4: Formal Production RC Approval Record Audit
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 4/5] Auditing formal approval record in PRODUCTION_RC_APPROVAL.md..." -ForegroundColor Yellow

$approvalDocPath = "$projectRoot\$docDir\release\PRODUCTION_RC_APPROVAL.md"
if (-not (Test-Path $approvalDocPath)) {
    $msg = "$docDir/release/PRODUCTION_RC_APPROVAL.md is missing!"
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    $approvalText = Get-Content -Path $approvalDocPath -Raw

    if ($approvalText -notmatch "Status:[\*\s]+APPROVED") {
        $msg = "Approval record does not contain 'Status: APPROVED'."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    }
    if ($approvalText -notmatch $EXPECTED_AAB_HASH) {
        $msg = "Approval record does not match frozen AAB hash."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    }
    if ($approvalText -notmatch $EXPECTED_APK_HASH) {
        $msg = "Approval record does not match frozen APK hash."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    }
    if ($approvalText -notmatch "Quality Gates Reconciliation Matrix") {
        $msg = "Approval record missing Quality Gates Reconciliation Matrix."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    }
    Write-Host "  PASS: Formal production release approval record verified." -ForegroundColor Green
}

# --------------------------------------------------------------------------------------------------
# Check 5: Incident Response Halt Triggers & Rollback Protocol Audit
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 5/5] Auditing incident response triggers & rollback protocol..." -ForegroundColor Yellow

if (Test-Path $approvalDocPath) {
    $approvalText = Get-Content -Path $approvalDocPath -Raw

    $requiredTriggers = @(
        "Data Corruption",
        "Consent Bypass",
        "Unverified Exposure",
        "Vitals Regression",
        "Critical Vulnerability"
    )

    foreach ($trigger in $requiredTriggers) {
        if ($approvalText -notmatch $trigger) {
            $msg = "Incident response halt trigger missing: $trigger"
            Write-Host "  VIOLATION: $msg" -ForegroundColor Red
            $violations.Add($msg)
        }
    }

    if ($approvalText -notmatch "Forward-Fix Rollback") {
        $msg = "Forward-Fix Rollback policy is not documented in approval record."
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    }

    Write-Host "  PASS: Incident response triggers and forward-fix rollback runbook validated." -ForegroundColor Green
}

# --------------------------------------------------------------------------------------------------
# Final Audit Verdict
# --------------------------------------------------------------------------------------------------
Write-Host "`n=================================================================" -ForegroundColor Cyan
if ($violations.Count -eq 0) {
    Write-Host "   PRODUCTION RELEASE GATE APPROVED (0 violations)               " -ForegroundColor Green
    Write-Host "=================================================================" -ForegroundColor Cyan
    exit 0
} else {
    Write-Host "   PRODUCTION RELEASE GATE FAILED WITH $($violations.Count) VIOLATION(S) " -ForegroundColor Red
    Write-Host "=================================================================" -ForegroundColor Cyan
    foreach ($v in $violations) {
        Write-Host " - $v" -ForegroundColor Red
    }
    exit 1
}
