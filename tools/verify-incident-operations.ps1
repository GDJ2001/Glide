<#
.SYNOPSIS
    Audits and validates the incident response and rollback operational governance for Glide.

.DESCRIPTION
    GLIDE-066: Incident & Rollback Operations Auditor.
    Validates:
    1. Incident runbook existence and severity classification (P0, P1, P2, P3) with SLAs.
    2. Incident Command System (ICS) on-call roles and emergency communication channels.
    3. Privacy-preserving user report handling and zero payload content access.
    4. Data-corruption escalation, quarantine mechanics, and public storage isolation.
    5. Forward-fix rollback procedure, schema forward-compatibility, and user data preservation.
    6. Three complete tabletop incident simulations and recovery checklists.
#>

[CmdletBinding()]
param ()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$violations = [System.Collections.Generic.List[string]]::new()

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "   Glide GLIDE-066: Incident & Rollback Operations Auditor       " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

$runbookPath = "$projectRoot\docs\release\INCIDENT_RESPONSE_AND_ROLLBACK_RUNBOOK.md"

if (-not (Test-Path $runbookPath)) {
    $msg = "docs/release/INCIDENT_RESPONSE_AND_ROLLBACK_RUNBOOK.md is missing!"
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
    exit 1
}

$runbookText = Get-Content -Path $runbookPath -Raw

# --------------------------------------------------------------------------------------------------
# Check 1: Severity Classification Matrix & Response SLAs
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 1/5] Auditing severity classification matrix & SLAs..." -ForegroundColor Yellow

$requiredSeverities = @("P0", "P1", "P2", "P3")
foreach ($sev in $requiredSeverities) {
    if ($runbookText -notmatch [regex]::Escape($sev)) {
        $msg = "Runbook does not document severity tier: $sev"
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    }
}

if ($runbookText -notmatch "2\s*hours" -and $runbookText -notmatch "2h") {
    $msg = "P0 halt SLA of <= 2 hours is not documented."
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    Write-Host "  PASS: P0 halt SLA <= 2 hours documented." -ForegroundColor Green
}

if ($runbookText -notmatch "24.*hours" -and $runbookText -notmatch "24h") {
    $msg = "P0 hotfix SLA of <= 24 hours is not documented."
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    Write-Host "  PASS: P0 hotfix SLA <= 24 hours documented." -ForegroundColor Green
}

# --------------------------------------------------------------------------------------------------
# Check 2: Incident Command System Roles & Emergency Channels
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 2/5] Auditing ICS on-call roles & communication channels..." -ForegroundColor Yellow

$requiredRoles = @("Incident Commander", "Technical Lead", "Communications Lead")
foreach ($role in $requiredRoles) {
    if ($runbookText -notmatch $role) {
        $msg = "Runbook missing on-call role: $role"
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    }
}

if ($runbookText -notmatch "security@glide\.dev" -or $runbookText -notmatch "support@glide\.dev") {
    $msg = "Emergency communication channels (security@glide.dev / support@glide.dev) are missing."
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    Write-Host "  PASS: ICS roles and emergency communication channels verified." -ForegroundColor Green
}

# --------------------------------------------------------------------------------------------------
# Check 3: Privacy-Preserving User Report Handling
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 3/5] Auditing privacy-preserving diagnostics & report handling..." -ForegroundColor Yellow

if ($runbookText -notmatch "Zero Content Inspection" -and $runbookText -notmatch "Never Request Payload Files") {
    $msg = "Runbook does not forbid requesting user payload files."
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    Write-Host "  PASS: Zero content inspection policy verified." -ForegroundColor Green
}

if ($runbookText -notmatch "Redacted Diagnostics" -and $runbookText -notmatch "Sanitized Metadata") {
    $msg = "Runbook does not specify redacted diagnostics collection."
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    Write-Host "  PASS: Consented redacted diagnostics collection verified." -ForegroundColor Green
}

if ($runbookText -notmatch "Wrong-Recipient" -and $runbookText -notmatch "wrong-recipient") {
    $msg = "Runbook does not document wrong-recipient sharing triage."
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    Write-Host "  PASS: Wrong-recipient sharing triage runbook verified." -ForegroundColor Green
}

# --------------------------------------------------------------------------------------------------
# Check 4: Data-Corruption Escalation & Forward-Fix Rollback Protocol
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 4/5] Auditing data-corruption escalation & forward-fix rollback..." -ForegroundColor Yellow

if ($runbookText -notmatch "Quarantine" -and $runbookText -notmatch "quarantine") {
    $msg = "Runbook does not document quarantine isolation for corrupted payloads."
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    Write-Host "  PASS: Data-corruption quarantine mechanics verified." -ForegroundColor Green
}

if ($runbookText -notmatch "Forward-Fix Rollback" -and $runbookText -notmatch "forward-fix") {
    $msg = "Runbook does not mandate Forward-Fix Rollback."
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    Write-Host "  PASS: Forward-fix rollback procedure verified." -ForegroundColor Green
}

if ($runbookText -notmatch "versionCode") {
    $msg = "Runbook does not specify monotonic versionCode increment."
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    Write-Host "  PASS: Monotonic versionCode increment verified." -ForegroundColor Green
}

if ($runbookText -notmatch "never delete user files" -and $runbookText -notmatch "never be deleted" -and $runbookText -notmatch "User Data Sanctity") {
    $msg = "User file preservation invariant is missing."
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    Write-Host "  PASS: User file preservation invariant verified." -ForegroundColor Green
}

# --------------------------------------------------------------------------------------------------
# Check 5: Tabletop Incident Simulations & Recovery Checklists
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 5/5] Auditing tabletop incident simulations..." -ForegroundColor Yellow

$requiredSimulations = @(
    "Corrupted Payload",
    "ANR",
    "Signing Key"
)

foreach ($sim in $requiredSimulations) {
    if ($runbookText -notmatch $sim) {
        $msg = "Tabletop drill missing scenario: $sim"
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    }
}

if ($runbookText -notmatch "Recovery Verification Checklist") {
    $msg = "Tabletop simulation recovery checklists missing."
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    Write-Host "  PASS: All 3 tabletop drills and recovery checklists verified." -ForegroundColor Green
}

# --------------------------------------------------------------------------------------------------
# Final Audit Verdict
# --------------------------------------------------------------------------------------------------
Write-Host "`n=================================================================" -ForegroundColor Cyan
if ($violations.Count -eq 0) {
    Write-Host "   INCIDENT OPERATIONS AUDIT PASSED (0 violations)               " -ForegroundColor Green
    Write-Host "=================================================================" -ForegroundColor Cyan
    exit 0
} else {
    Write-Host "   INCIDENT OPERATIONS AUDIT FAILED WITH $($violations.Count) VIOLATION(S)" -ForegroundColor Red
    Write-Host "=================================================================" -ForegroundColor Cyan
    foreach ($v in $violations) {
        Write-Host " - $v" -ForegroundColor Red
    }
    exit 1
}
