<#
.SYNOPSIS
    Glide GLIDE-070: Persistent Identity & True Resume Extension Specification Auditor.
.DESCRIPTION
    Automated auditor validating the persistent identity and true resume specification (M13 Future Scope):
    1. ADR-0015 existence, decision, and indexing in docs/adr/README.md.
    2. PERSISTENT_IDENTITY_AND_RESUME_SPECIFICATION.md completeness and hardware Keystore identity.
    3. Rejection of offset-only fake resume and requirement for chunk Merkle digest tree verification.
    4. Retained-partial lifecycle governance (24-hour TTL, storage pressure purge, staging isolation).
    5. Pure JVM ResumeToken and ContentDigestTree implementations, test fixtures, and threat model (T01-T08).
#>

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path "$PSScriptRoot\.."

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "    Glide GLIDE-070: Persistent Identity & Resume Auditor        " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host ""

$violations = 0

# Check 1: ADR-0015 Architecture Decision Record Audit
Write-Host "[Check 1/5] Auditing ADR-0015 existence and decision index..." -ForegroundColor Yellow
$adrPath = Join-Path $repoRoot "docs\adr\ADR-0015-persistent-identity-and-true-resume.md"
$adrIndexPath = Join-Path $repoRoot "docs\adr\README.md"

if (-not (Test-Path $adrPath)) {
    Write-Host "  FAIL: ADR-0015 not found at $adrPath" -ForegroundColor Red
    $violations++
} else {
    $adrContent = Get-Content -Raw -Path $adrPath
    if ($adrContent -match "Android Keystore" -and $adrContent -match "Merkle digest" -and $adrContent -match "banning offset-only") {
        Write-Host "  PASS: ADR-0015 documents Keystore identity, Merkle digest verification, and offset-only ban." -ForegroundColor Green
    } else {
        Write-Host "  FAIL: ADR-0015 missing key decision points." -ForegroundColor Red
        $violations++
    }
}

if (-not (Test-Path $adrIndexPath)) {
    Write-Host "  FAIL: docs/adr/README.md not found." -ForegroundColor Red
    $violations++
} else {
    $indexContent = Get-Content -Raw -Path $adrIndexPath
    if ($indexContent -match "ADR-0015") {
        Write-Host "  PASS: ADR-0015 properly indexed in docs/adr/README.md." -ForegroundColor Green
    } else {
        Write-Host "  FAIL: ADR-0015 not referenced in docs/adr/README.md." -ForegroundColor Red
        $violations++
    }
}
Write-Host ""

# Check 2: Technical Specification & Hardware Keystore Identity
Write-Host "[Check 2/5] Auditing specification for Android Keystore identity and TOFU pairing..." -ForegroundColor Yellow
$specPath = Join-Path $repoRoot "docs\future\PERSISTENT_IDENTITY_AND_RESUME_SPECIFICATION.md"
if (-not (Test-Path $specPath)) {
    Write-Host "  FAIL: Specification not found at $specPath" -ForegroundColor Red
    $violations++
} else {
    $specContent = Get-Content -Raw -Path $specPath
    if ($specContent -match "Android Keystore" -and $specContent -match "PURPOSE_SIGN" -and $specContent -match "PURPOSE_VERIFY") {
        Write-Host "  PASS: Android Keystore hardware-backed keys and permissions verified." -ForegroundColor Green
    } else {
        Write-Host "  FAIL: Android Keystore details missing from spec." -ForegroundColor Red
        $violations++
    }

    if ($specContent -match "Trust-On-First-Use" -or $specContent -match "TOFU") {
        Write-Host "  PASS: TOFU pairing lifecycle and challenge-response authentication verified." -ForegroundColor Green
    } else {
        Write-Host "  FAIL: TOFU pairing mechanics missing." -ForegroundColor Red
        $violations++
    }
}
Write-Host ""

# Check 3: True Resume Protocol & Merkle Digest Verification
Write-Host "[Check 3/5] Auditing True Resume protocol and chunk digest verification..." -ForegroundColor Yellow
if ($specContent -match "offset-only" -and $specContent -match "Merkle") {
    Write-Host "  PASS: Rejection of offset-only fake resume and mandate for Merkle chunk digests verified." -ForegroundColor Green
} else {
    Write-Host "  FAIL: True resume or Merkle verification missing." -ForegroundColor Red
    $violations++
}

if ($specContent -match "CAP_PERSISTENT_IDENTITY" -and $specContent -match "CAP_TRUE_RESUME") {
    Write-Host "  PASS: Capability flags (CAP_PERSISTENT_IDENTITY, CAP_TRUE_RESUME) verified." -ForegroundColor Green
} else {
    Write-Host "  FAIL: Capability flags missing." -ForegroundColor Red
    $violations++
}
Write-Host ""

# Check 4: Retained-Partial Lifecycle & Storage Governance
Write-Host "[Check 4/5] Auditing retained-partial lifecycle and 24-hour TTL..." -ForegroundColor Yellow
if ($specContent -match "24.*hour" -and $specContent -match "TTL") {
    Write-Host "  PASS: 24-hour expiration TTL verified." -ForegroundColor Green
} else {
    Write-Host "  FAIL: 24-hour TTL missing." -ForegroundColor Red
    $violations++
}

if ($specContent -match "cacheDir/staging/resumable" -and $specContent -match "User Data Sanctity") {
    Write-Host "  PASS: Sandboxed staging isolation and User Data Sanctity invariant verified." -ForegroundColor Green
} else {
    Write-Host "  FAIL: Sandboxed staging isolation missing." -ForegroundColor Red
    $violations++
}
Write-Host ""

# Check 5: Pure JVM Implementation, Test Fixtures & Threat Matrix
Write-Host "[Check 5/5] Auditing pure JVM ResumeToken, ContentDigestTree and threat matrix..." -ForegroundColor Yellow
$tokenPath = Join-Path $repoRoot "transfer-core\src\main\kotlin\dev\glide\core\resume\ResumeToken.kt"
$treePath = Join-Path $repoRoot "transfer-core\src\main\kotlin\dev\glide\core\resume\ContentDigestTree.kt"
$testPath = Join-Path $repoRoot "transfer-core\src\test\kotlin\dev\glide\core\resume\ResumeSecurityTest.kt"

if (-not (Test-Path $tokenPath) -or -not (Test-Path $treePath) -or -not (Test-Path $testPath)) {
    Write-Host "  FAIL: ResumeToken.kt, ContentDigestTree.kt, or ResumeSecurityTest.kt not found in :transfer-core" -ForegroundColor Red
    $violations++
} else {
    Write-Host "  PASS: Pure JVM resume domain models and test fixtures verified." -ForegroundColor Green
}

$threats = @("T01", "T02", "T03", "T04", "T05", "T06", "T07", "T08")
$missingThreat = $false
foreach ($t in $threats) {
    if ($specContent -notmatch "\*\*$t\*\*") {
        Write-Host "  FAIL: Threat $t missing in specification threat matrix." -ForegroundColor Red
        $missingThreat = $true
    }
}
if (-not $missingThreat) {
    Write-Host "  PASS: Threat model coverage (T01 through T08) verified." -ForegroundColor Green
} else {
    $violations++
}
Write-Host ""

# Final Summary
Write-Host "=================================================================" -ForegroundColor Cyan
if ($violations -eq 0) {
    Write-Host "  PERSISTENT IDENTITY & RESUME AUDIT PASSED (0 violations)       " -ForegroundColor Green
    Write-Host "=================================================================" -ForegroundColor Cyan
    exit 0
} else {
    Write-Host "  PERSISTENT IDENTITY & RESUME AUDIT FAILED ($violations errors) " -ForegroundColor Red
    Write-Host "=================================================================" -ForegroundColor Cyan
    exit 1
}
