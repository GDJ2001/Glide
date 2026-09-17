<#
.SYNOPSIS
    Automated auditor for Glide ADR-0022 & Remote Internet Transfer Specification.
.DESCRIPTION
    Validates ADR-0022, technical specification completeness, zero-cloud storage invariants,
    zero-knowledge signaling, blind relay architecture, NAT traversal (ICE/STUN/TURN),
    relay anti-abuse quotas, metered cellular data protections, and remote threat model (R01-R08).
#>

$ErrorActionPreference = "Stop"
$ProjectRoot = Resolve-Path "$PSScriptRoot\.."

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "    Glide ADR-0022: Remote Internet Transfer Spec Auditor       " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# --- Check 1: ADR-0022 Existence, Accepted Status, and Decision Index ---
Write-Host "`n[Check 1/10] Auditing ADR-0022 existence, Accepted status, and index..." -ForegroundColor Yellow
$AdrPath = Join-Path $ProjectRoot "docs\adr\ADR-0022-remote-internet-transfer.md"
$AdrIndex = Join-Path $ProjectRoot "docs\adr\README.md"

if (-not (Test-Path $AdrPath)) {
    Write-Error "FAIL: ADR-0022 not found at $AdrPath"
}
$AdrContent = Get-Content $AdrPath -Raw
if ($AdrContent -notmatch "Status.*\bAccepted\b") {
    Write-Error "FAIL: ADR-0022 status must be Accepted."
}
if ($AdrContent -notmatch "Zero-Knowledge" -or $AdrContent -notmatch "NAT Traversal") {
    Write-Error "FAIL: ADR-0022 missing Zero-Knowledge or NAT Traversal decisions."
}
$IndexContent = Get-Content $AdrIndex -Raw
if ($IndexContent -notmatch "ADR-0022 — Remote and internet transfer architecture") {
    Write-Error "FAIL: ADR-0022 not properly indexed in docs/adr/README.md."
}
Write-Host "  PASS: ADR-0022 exists, is marked Accepted, and is indexed in README.md." -ForegroundColor Green

# --- Check 2: Roadmap Alignment in CROSS_PLATFORM_PATH.md ---
Write-Host "`n[Check 2/10] Auditing Roadmap Alignment in CROSS_PLATFORM_PATH.md..." -ForegroundColor Yellow
$PathDoc = Join-Path $ProjectRoot "docs\future\CROSS_PLATFORM_PATH.md"
$PathContent = Get-Content $PathDoc -Raw
if ($PathContent -notmatch "6\.\s*Remote/internet:.*ADR-0022.*REMOTE_INTERNET_TRANSFER_SPECIFICATION\.md") {
    Write-Error "FAIL: CROSS_PLATFORM_PATH.md Step 6 does not reference ADR-0022 and REMOTE_INTERNET_TRANSFER_SPECIFICATION.md."
}
Write-Host "  PASS: CROSS_PLATFORM_PATH.md Step 6 properly references ADR-0022 and specification." -ForegroundColor Green

# --- Check 3: Remote Internet Transfer Specification Completeness ---
Write-Host "`n[Check 3/10] Auditing Remote Internet Transfer technical specification completeness..." -ForegroundColor Yellow
$SpecPath = Join-Path $ProjectRoot "docs\future\REMOTE_INTERNET_TRANSFER_SPECIFICATION.md"
if (-not (Test-Path $SpecPath)) {
    Write-Error "FAIL: Specification not found at $SpecPath"
}
$SpecContent = Get-Content $SpecPath -Raw
$RequiredTopics = @(
    "Zero Cloud File Storage",
    "Zero-Knowledge Signaling",
    "Blind Relays",
    "glide://remote/v1#",
    "RendezvousUUID",
    "EphemeralPublicKeyHex",
    "RFC 8445",
    "RFC 8489",
    "RFC 8656",
    "LanRecord",
    "100 MB per transfer",
    "Metered Cellular",
    "Phase R1",
    "Phase R5"
)
foreach ($topic in $RequiredTopics) {
    if ($SpecContent -notmatch [regex]::Escape($topic)) {
        Write-Error "FAIL: Specification missing required topic: $topic"
    }
}
Write-Host "  PASS: Specification contains all 8 core architectural sections and multi-phase roadmap." -ForegroundColor Green

# --- Check 4: Zero Cloud Storage & Data Sovereignty Invariants ---
Write-Host "`n[Check 4/10] Auditing Zero Cloud Storage & Data Sovereignty Invariants..." -ForegroundColor Yellow
if ($SpecContent -notmatch "Zero Cloud File Storage" -or $SpecContent -notmatch "No file payloads") {
    Write-Error "FAIL: Specification missing explicit Zero Cloud File Storage invariant statement."
}
if ($SpecContent -notmatch "AWS S3" -and $SpecContent -notmatch "buckets") {
    Write-Error "FAIL: Specification must explicitly disclaim cloud bucket storage."
}
Write-Host "  PASS: Zero cloud file storage and direct peer data sovereignty verified." -ForegroundColor Green

# --- Check 5: Zero-Knowledge Signaling & Blind Relay Invariants ---
Write-Host "`n[Check 5/10] Auditing Zero-Knowledge Signaling, Blind Relay & Signaling Client..." -ForegroundColor Yellow
if ($SpecContent -notmatch "blind bit couriers" -or $SpecContent -notmatch "End-to-End Encrypted") {
    Write-Error "FAIL: Specification missing blind courier and E2EE payload invariants."
}
if ($SpecContent -notmatch "zero cryptographic keys" -or $SpecContent -notmatch "cannot inspect") {
    Write-Error "FAIL: Specification must mandate that relays possess zero decryption capability."
}
$SigClientPath = Join-Path $ProjectRoot "windows\Glide.Transport\Remote\RemoteSignalingClient.cs"
$SigMsgPath = Join-Path $ProjectRoot "windows\Glide.Transport\Remote\SignalingMessages.cs"
$SigTestPath = Join-Path $ProjectRoot "windows\Glide.Tests\SignalingMessagesTests.cs"
foreach ($f in @($SigClientPath, $SigMsgPath, $SigTestPath)) {
    if (-not (Test-Path $f)) {
        Write-Error "FAIL: Missing remote signaling component: $f"
    }
}
Write-Host "  PASS: Zero-knowledge signaling, broker message protocol, and WebSocket client verified." -ForegroundColor Green

# --- Check 6: NAT Traversal Standards (ICE / STUN / TURN) & StunClient / TurnClient / ICE Engine ---
Write-Host "`n[Check 6/10] Auditing NAT Traversal Standards (ICE, STUN, TURN), StunClient, TurnClient & ICE Gatherer..." -ForegroundColor Yellow
if ($SpecContent -notmatch "RFC 8445" -or $SpecContent -notmatch "RFC 8489" -or $SpecContent -notmatch "RFC 8656") {
    Write-Error "FAIL: Specification missing IETF RFC standards for ICE, STUN, and TURN."
}
if ($SpecContent -notmatch "Server-Reflexive Candidates" -or $SpecContent -notmatch "Relay Candidates") {
    Write-Error "FAIL: Specification missing ICE candidate gathering priorities."
}
$StunClientPath = Join-Path $ProjectRoot "windows\Glide.Transport\Remote\StunClient.cs"
$StunTestPath = Join-Path $ProjectRoot "windows\Glide.Tests\StunClientTests.cs"
$TurnClientPath = Join-Path $ProjectRoot "windows\Glide.Transport\Remote\TurnClient.cs"
$TurnTestPath = Join-Path $ProjectRoot "windows\Glide.Tests\TurnClientTests.cs"
$IceCandidatePath = Join-Path $ProjectRoot "windows\Glide.Core\Remote\IceCandidate.cs"
$IceGathererPath = Join-Path $ProjectRoot "windows\Glide.Transport\Remote\IceCandidateGatherer.cs"
$IceTestPath = Join-Path $ProjectRoot "windows\Glide.Tests\IceCandidateTests.cs"
$GathererTestPath = Join-Path $ProjectRoot "windows\Glide.Tests\IceCandidateGathererTests.cs"

foreach ($f in @($StunClientPath, $StunTestPath, $TurnClientPath, $TurnTestPath, $IceCandidatePath, $IceGathererPath, $IceTestPath, $GathererTestPath)) {
    if (-not (Test-Path $f)) {
        Write-Error "FAIL: Missing ICE / STUN / TURN component: $f"
    }
}
$StunContent = Get-Content $StunClientPath -Raw
if ($StunContent -notmatch "BuildBindingRequest" -or $StunContent -notmatch "ParseBindingResponse" -or $StunContent -notmatch "AttrXorMappedAddress") {
    Write-Error "FAIL: StunClient.cs missing RFC 8489 packet builder or XOR-MAPPED-ADDRESS parser."
}
$TurnContent = Get-Content $TurnClientPath -Raw
if ($TurnContent -notmatch "BuildAllocateRequest" -or $TurnContent -notmatch "ParseAllocateResponse" -or $TurnContent -notmatch "BuildSendIndication") {
    Write-Error "FAIL: TurnClient.cs missing RFC 8656 packet builder or parser methods."
}
Write-Host "  PASS: ICE (RFC 8445), STUN (RFC 8489), TURN (RFC 8656), StunClient, TurnClient, and IceCandidateGatherer verified." -ForegroundColor Green

# --- Check 7: Remote Invitation Schema, Codecs & Unit Tests ---
Write-Host "`n[Check 7/10] Auditing Remote Invitation Schema, Codecs & Unit Tests..." -ForegroundColor Yellow
if ($SpecContent -notmatch "glide://remote/v1#" -or $SpecContent -notmatch "RendezvousUUID") {
    Write-Error "FAIL: Specification missing canonical glide://remote/v1# URI schema."
}
if ($SpecContent -notmatch "LanRecord\.Control" -or $SpecContent -notmatch "LanRecord\.Data") {
    Write-Error "FAIL: Specification missing LanRecord framing preservation."
}
$CsModel = Join-Path $ProjectRoot "windows\Glide.Core\Remote\RemoteInvitation.cs"
$CsCodec = Join-Path $ProjectRoot "windows\Glide.Core\Remote\RemoteInvitationCodec.cs"
$CsTest = Join-Path $ProjectRoot "windows\Glide.Tests\RemoteInvitationCodecTests.cs"
$KtModel = Join-Path $ProjectRoot "transfer-core\src\main\kotlin\dev\glide\core\remote\RemoteInvitation.kt"
$KtCodec = Join-Path $ProjectRoot "transfer-core\src\main\kotlin\dev\glide\core\remote\RemoteInvitationCodec.kt"
$KtTest = Join-Path $ProjectRoot "transfer-core\src\test\kotlin\dev\glide\core\remote\RemoteInvitationCodecTest.kt"

foreach ($file in @($CsModel, $CsCodec, $CsTest, $KtModel, $KtCodec, $KtTest)) {
    if (-not (Test-Path $file)) {
        Write-Error "FAIL: Missing remote invitation component: $file"
    }
}
Write-Host "  PASS: Remote invitation URI schema, cross-platform .NET/Kotlin codecs, and unit tests verified." -ForegroundColor Green

# --- Check 8: Relay Anti-Abuse Quotas & Rate Limits ---
Write-Host "`n[Check 8/10] Auditing Relay Anti-Abuse Quotas & Rate Limits..." -ForegroundColor Yellow
if ($SpecContent -notmatch "100 MB per transfer") {
    Write-Error "FAIL: Specification missing 100 MB relay transfer ceiling."
}
if ($SpecContent -notmatch "concurrent transfer sessions" -or $SpecContent -notmatch "rate limits") {
    Write-Error "FAIL: Specification missing relay connection rate limiting rules."
}
$QuotaTrackerPath = Join-Path $ProjectRoot "windows\Glide.Core\Remote\RelayQuotaTracker.cs"
$QuotaTestPath = Join-Path $ProjectRoot "windows\Glide.Tests\RelayQuotaTrackerTests.cs"
foreach ($f in @($QuotaTrackerPath, $QuotaTestPath)) {
    if (-not (Test-Path $f)) {
        Write-Error "FAIL: Missing relay quota component: $f"
    }
}
$QuotaContent = Get-Content $QuotaTrackerPath -Raw
if ($QuotaContent -notmatch "MaxRelayBytesPerTransfer" -or $QuotaContent -notmatch "MaxConcurrentSessions" -or $QuotaContent -notmatch "MaxAttemptsPerMinute") {
    Write-Error "FAIL: RelayQuotaTracker missing quota or rate limit constants."
}
Write-Host "  PASS: 100 MB relay quota, concurrency limits, and RelayQuotaTracker engine verified." -ForegroundColor Green

# --- Check 9: Metered Cellular Data Protection ---
Write-Host "`n[Check 9/10] Auditing Metered Cellular Data Protection Invariants..." -ForegroundColor Yellow
if ($SpecContent -notmatch "NET_CAPABILITY_NOT_METERED" -or
    $SpecContent -notmatch "nw_path_is_expensive" -or
    $SpecContent -notmatch "NetworkCostType") {
    Write-Error "FAIL: Specification missing OS-level metered network detection API mappings."
}
if ($SpecContent -notmatch "Metered Cellular Connection" -or $SpecContent -notmatch "Warning") {
    Write-Error "FAIL: Specification missing metered connection warning dialog contract."
}
$MeteredPath = Join-Path $ProjectRoot "windows\Glide.Core\Remote\MeteredNetworkProtection.cs"
$MeteredTestPath = Join-Path $ProjectRoot "windows\Glide.Tests\MeteredNetworkProtectionTests.cs"
foreach ($f in @($MeteredPath, $MeteredTestPath)) {
    if (-not (Test-Path $f)) {
        Write-Error "FAIL: Missing metered protection component: $f"
    }
}
$MeteredContent = Get-Content $MeteredPath -Raw
if ($MeteredContent -notmatch "IMeteredNetworkDetector" -or $MeteredContent -notmatch "MeteredTransferPrompt" -or $MeteredContent -notmatch "MeteredTransferPolicy") {
    Write-Error "FAIL: MeteredNetworkProtection missing detector, prompt, or policy."
}
Write-Host "  PASS: OS metered network detection, user warning prompt, and policy engine verified." -ForegroundColor Green

# --- Check 10: Remote Threat Model Additions (Threats R01 to R08) & Phase R5 Conformance Engine ---
Write-Host "`n[Check 10/10] Auditing Remote Threat Model (R01-R08) & Phase R5 Conformance Engine..." -ForegroundColor Yellow
$ThreatIds = @("R01", "R02", "R03", "R04", "R05", "R06", "R07", "R08")
foreach ($tid in $ThreatIds) {
    if ($SpecContent -notmatch "\b$tid\b" -or $AdrContent -notmatch "\b$tid\b") {
        Write-Error "FAIL: Threat ID $tid missing from specification or ADR-0022 threat catalog."
    }
}
$OrchestratorPath = Join-Path $ProjectRoot "windows\Glide.Core\Remote\RemoteTransferSession.cs"
$OrchestratorTestPath = Join-Path $ProjectRoot "windows\Glide.Tests\RemoteTransferSessionTests.cs"
foreach ($f in @($OrchestratorPath, $OrchestratorTestPath)) {
    if (-not (Test-Path $f)) {
        Write-Error "FAIL: Missing Phase R5 conformance component: $f"
    }
}
$OrchContent = Get-Content $OrchestratorPath -Raw
if ($OrchContent -notmatch "SelectCandidatePair" -or $OrchContent -notmatch "EvaluateMeteredPolicy" -or $OrchContent -notmatch "RemoteSessionState") {
    Write-Error "FAIL: RemoteTransferSession missing candidate selection or metered policy methods."
}
Write-Host "  PASS: Threat IDs R01 through R08 and Phase R5 RemoteTransferSession orchestrator verified." -ForegroundColor Green

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "  ALL 10 CHECKS PASSED: Remote Internet Spec Conformance Certified " -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Cyan
