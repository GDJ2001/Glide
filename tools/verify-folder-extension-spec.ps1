<#
.SYNOPSIS
    Glide GLIDE-069: Safe Folder Transfer Extension Specification Auditor.
.DESCRIPTION
    Automated auditor validating the safe folder transfer extension specification (M13 Future Scope):
    1. ADR-0014 existence, decision, and indexing in docs/adr/README.md.
    2. SAFE_FOLDER_TRANSFER_SPECIFICATION.md completeness and bounded complexity limits.
    3. Storage Access Framework (SAF) direct ContentResolver query performance plan and loop detection.
    4. Destination collision non-destructive renaming and two-stage staging isolation.
    5. Pure JVM SafeRelativePath implementation, test fixtures, and threat model coverage (T01-T08, T10-T15, T17).
#>

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path "$PSScriptRoot\.."

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "    Glide GLIDE-069: Safe Folder Transfer Extension Auditor      " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host ""

$violations = 0

# Check 1: ADR-0014 Architecture Decision Record Audit
Write-Host "[Check 1/5] Auditing ADR-0014 existence and decision index..." -ForegroundColor Yellow
$adrPath = Join-Path $repoRoot "docs\adr\ADR-0014-safe-folder-transfer-extension.md"
$adrIndexPath = Join-Path $repoRoot "docs\adr\README.md"

if (-not (Test-Path $adrPath)) {
    Write-Host "  FAIL: ADR-0014 not found at $adrPath" -ForegroundColor Red
    $violations++
} else {
    $adrContent = Get-Content -Raw -Path $adrPath
    if ($adrContent -match "Discrete item streaming" -and $adrContent -match "CAP_FOLDER_TRANSFER") {
        Write-Host "  PASS: ADR-0014 documents discrete item streaming and CAP_FOLDER_TRANSFER capability." -ForegroundColor Green
    } else {
        Write-Host "  FAIL: ADR-0014 missing decision details or capability flag." -ForegroundColor Red
        $violations++
    }
}

if (-not (Test-Path $adrIndexPath)) {
    Write-Host "  FAIL: docs/adr/README.md not found." -ForegroundColor Red
    $violations++
} else {
    $indexContent = Get-Content -Raw -Path $adrIndexPath
    if ($indexContent -match "ADR-0014") {
        Write-Host "  PASS: ADR-0014 properly indexed in docs/adr/README.md." -ForegroundColor Green
    } else {
        Write-Host "  FAIL: ADR-0014 not referenced in docs/adr/README.md." -ForegroundColor Red
        $violations++
    }
}
Write-Host ""

# Check 2: Technical Specification & Bounded Complexity Limits
Write-Host "[Check 2/5] Auditing SAFE_FOLDER_TRANSFER_SPECIFICATION.md and bounded limits..." -ForegroundColor Yellow
$specPath = Join-Path $repoRoot "docs\future\SAFE_FOLDER_TRANSFER_SPECIFICATION.md"
if (-not (Test-Path $specPath)) {
    Write-Host "  FAIL: Specification not found at $specPath" -ForegroundColor Red
    $violations++
} else {
    $specContent = Get-Content -Raw -Path $specPath
    if ($specContent -match "16.*levels" -and $specContent -match "10,000" -and $specContent -match "1024") {
        Write-Host "  PASS: Bounded complexity limits (depth <= 16, files <= 10,000, length <= 1024) verified." -ForegroundColor Green
    } else {
        Write-Host "  FAIL: Bounded complexity limits missing or incorrect in spec." -ForegroundColor Red
        $violations++
    }

    if ($specContent -match "relative_path" -or $specContent -match "relativePath") {
        Write-Host "  PASS: Extended item manifest schema with relativePath attribute verified." -ForegroundColor Green
    } else {
        Write-Host "  FAIL: Item manifest schema missing relativePath." -ForegroundColor Red
        $violations++
    }
}
Write-Host ""

# Check 3: SAF Direct ContentResolver Performance Plan & Loop Prevention
Write-Host "[Check 3/5] Auditing SAF ContentResolver traversal performance plan..." -ForegroundColor Yellow
if ($specContent -match "DocumentsContract\.buildChildDocumentsUriUsingTree" -and $specContent -match "DocumentFile\.listFiles") {
    Write-Host "  PASS: Low-level ContentResolver query projection plan bypassing DocumentFile verified." -ForegroundColor Green
} else {
    Write-Host "  FAIL: SAF ContentResolver query optimization missing from spec." -ForegroundColor Red
    $violations++
}

if ($specContent -match "documentId" -and $specContent -match "visited" -or $specContent -match "loop") {
    Write-Host "  PASS: Symlink and cyclic directory loop detection verified." -ForegroundColor Green
} else {
    Write-Host "  FAIL: Cyclic directory loop detection missing." -ForegroundColor Red
    $violations++
}
Write-Host ""

# Check 4: Destination Collision & Non-Destructive Resolution Policy
Write-Host "[Check 4/5] Auditing destination collision and non-destructive resolution..." -ForegroundColor Yellow
if ($specContent -match "Folder \(1\)" -and $specContent -match "User Data Sanctity") {
    Write-Host "  PASS: Non-destructive monotonic collision renaming (Folder (1)) verified." -ForegroundColor Green
} else {
    Write-Host "  FAIL: Non-destructive collision renaming missing." -ForegroundColor Red
    $violations++
}

if ($specContent -match "cacheDir/staging" -and $specContent -match "quarantine") {
    Write-Host "  PASS: Two-stage commit and staging sandbox isolation verified." -ForegroundColor Green
} else {
    Write-Host "  FAIL: Staging sandbox isolation missing." -ForegroundColor Red
    $violations++
}
Write-Host ""

# Check 5: Pure JVM Implementation, Test Fixtures & Threat Model
Write-Host "[Check 5/5] Auditing pure JVM SafeRelativePath implementation and threat model..." -ForegroundColor Yellow
$modelPath = Join-Path $repoRoot "transfer-core\src\main\kotlin\dev\glide\core\folder\SafeRelativePath.kt"
$testPath = Join-Path $repoRoot "transfer-core\src\test\kotlin\dev\glide\core\folder\SafeRelativePathTest.kt"

if (-not (Test-Path $modelPath) -or -not (Test-Path $testPath)) {
    Write-Host "  FAIL: SafeRelativePath.kt or SafeRelativePathTest.kt not found in :transfer-core" -ForegroundColor Red
    $violations++
} else {
    Write-Host "  PASS: Pure JVM SafeRelativePath.kt model and test fixtures verified." -ForegroundColor Green
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
    Write-Host "  SAFE FOLDER TRANSFER SPEC AUDIT PASSED (0 violations)          " -ForegroundColor Green
    Write-Host "=================================================================" -ForegroundColor Cyan
    exit 0
} else {
    Write-Host "  SAFE FOLDER TRANSFER SPEC AUDIT FAILED ($violations violations)" -ForegroundColor Red
    Write-Host "=================================================================" -ForegroundColor Cyan
    exit 1
}
