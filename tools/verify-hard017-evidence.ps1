# HARD-017 native interoperability evidence verifier.
# This script is intentionally fail-closed: missing physical directions, identities, hashes,
# logs, or truthful negative terminal states prevent certification.

[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [string]$EvidenceDir
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if (-not (Test-Path $EvidenceDir -PathType Container)) {
    throw "Evidence directory not found: $EvidenceDir"
}
$EvidenceDir = (Resolve-Path $EvidenceDir).Path

$errors = [System.Collections.Generic.List[string]]::new()

function Add-Failure([string]$Message) {
    $errors.Add($Message)
}

function Read-JsonFile([string]$Path) {
    if (-not (Test-Path $Path -PathType Leaf)) {
        Add-Failure "Missing JSON evidence: $Path"
        return $null
    }
    try {
        return (Get-Content $Path -Raw | ConvertFrom-Json)
    }
    catch {
        Add-Failure "Invalid JSON evidence $Path: $($_.Exception.Message)"
        return $null
    }
}

function Test-Sha256([object]$Value) {
    if ($null -eq $Value) { return $false }
    return ([string]$Value) -match "^[0-9a-f]{64}$"
}

function Require-Property($Object,[string]$Name,[string]$Context) {
    if ($null -eq $Object -or $null -eq $Object.PSObject.Properties[$Name]) {
        Add-Failure "$Context missing property '$Name'."
        return $null
    }
    $value = $Object.$Name
    if ($null -eq $value -or ([string]$value).Length -eq 0) {
        Add-Failure "$Context property '$Name' is empty."
    }
    return $value
}

$endpointDir = Join-Path $EvidenceDir "endpoints"
$endpoints = @{}
foreach ($platform in @("android","windows","linux")) {
    $path = Join-Path $endpointDir "$platform.json"
    $endpoint = Read-JsonFile $path
    if ($null -eq $endpoint) { continue }

    $endpointSchema = Require-Property $endpoint "schema" "$platform endpoint"
    if ($endpointSchema -and $endpointSchema -ne "glide-hard017-endpoint-v1") {
        Add-Failure "$platform endpoint has unsupported schema '$endpointSchema'."
    }
    $declaredPlatform = Require-Property $endpoint "platform" "$platform endpoint"
    if ($declaredPlatform -and $declaredPlatform -ne $platform) {
        Add-Failure "$platform endpoint declares platform '$declaredPlatform'."
    }

    $head = Require-Property $endpoint "repositoryHead" "$platform endpoint"
    if ($head -and $head -notmatch "^[0-9a-f]{40}$") {
        Add-Failure "$platform endpoint repositoryHead is not a full commit SHA."
    }

    $hash = Require-Property $endpoint "artifactSha256" "$platform endpoint"
    if ($hash -and -not (Test-Sha256 ([string]$hash))) {
        Add-Failure "$platform endpoint artifactSha256 is not canonical lowercase SHA-256."
    }

    if ($platform -eq "android") {
        foreach ($field in @("serial","manufacturer","model","androidRelease","sdk","buildFingerprint","packageId")) {
            [void](Require-Property $endpoint $field "android endpoint")
        }
        $deviceRule = Require-Property $endpoint "deviceEvidenceRule" "android endpoint"
        if ($deviceRule -and $deviceRule -notmatch "physical Android") {
            Add-Failure "Android endpoint does not record the physical-device evidence rule."
        }
    }
    else {
        foreach ($field in @("machineName","osDescription","osArchitecture","processArchitecture")) {
            [void](Require-Property $endpoint $field "$platform endpoint")
        }
    }

    $endpoints[$platform] = $endpoint
}

if ($endpoints.Count -eq 3) {
    $heads = @($endpoints.Values | ForEach-Object { $_.repositoryHead } | Select-Object -Unique)
    if ($heads.Count -ne 1) {
        Add-Failure "Android, Windows and Linux artifacts were not captured from the same repository HEAD."
    }
}

$corpusPath = Join-Path $EvidenceDir "corpus\corpus-manifest.json"
$corpus = Read-JsonFile $corpusPath
$corpusSha = ""
if ($null -ne $corpus) {
    $corpusSchema = Require-Property $corpus "schema" "corpus"
    if ($corpusSchema -and $corpusSchema -ne "glide-hard017-corpus-v1") {
        Add-Failure "Corpus has unsupported schema '$corpusSchema'."
    }
    if ([int]$corpus.itemCount -lt 35) {
        Add-Failure "Corpus must contain at least 35 items; found $($corpus.itemCount)."
    }
    if ([int]$corpus.offerPageCount -lt 2) {
        Add-Failure "Corpus does not force a multi-page offer."
    }

    $zero = @($corpus.items | Where-Object { [long]$_.sizeBytes -eq 0 })
    if ($zero.Count -lt 1) {
        Add-Failure "Corpus is missing a zero-byte item."
    }
    $large = @($corpus.items | Where-Object { [long]$_.sizeBytes -ge (100L * 1024 * 1024) })
    if ($large.Count -lt 1) {
        Add-Failure "Corpus is missing a >=100 MiB item."
    }
    foreach ($item in @($corpus.items)) {
        if (-not (Test-Sha256 ([string]$item.sha256))) {
            Add-Failure "Corpus item '$($item.name)' has invalid SHA-256."
        }
    }

    $corpusSha = (Get-FileHash $corpusPath -Algorithm SHA256).Hash.ToLowerInvariant()
}

$required = @(
    [pscustomobject]@{ id="android-to-windows-success"; kind="success"; sender="android"; receiver="windows"; pair="android-windows" },
    [pscustomobject]@{ id="windows-to-android-success"; kind="success"; sender="windows"; receiver="android"; pair="android-windows" },
    [pscustomobject]@{ id="android-to-linux-success"; kind="success"; sender="android"; receiver="linux"; pair="android-linux" },
    [pscustomobject]@{ id="linux-to-android-success"; kind="success"; sender="linux"; receiver="android"; pair="android-linux" },
    [pscustomobject]@{ id="windows-to-linux-success"; kind="success"; sender="windows"; receiver="linux"; pair="windows-linux" },
    [pscustomobject]@{ id="linux-to-windows-success"; kind="success"; sender="linux"; receiver="windows"; pair="windows-linux" },

    [pscustomobject]@{ id="android-windows-reject"; kind="reject"; pair="android-windows" },
    [pscustomobject]@{ id="android-windows-cancel"; kind="cancel"; pair="android-windows" },
    [pscustomobject]@{ id="android-windows-wrong-auth"; kind="wrong-auth"; pair="android-windows" },
    [pscustomobject]@{ id="android-windows-reconnect"; kind="reconnect"; pair="android-windows" },

    [pscustomobject]@{ id="android-linux-reject"; kind="reject"; pair="android-linux" },
    [pscustomobject]@{ id="android-linux-cancel"; kind="cancel"; pair="android-linux" },
    [pscustomobject]@{ id="android-linux-wrong-auth"; kind="wrong-auth"; pair="android-linux" },
    [pscustomobject]@{ id="android-linux-reconnect"; kind="reconnect"; pair="android-linux" },

    [pscustomobject]@{ id="windows-linux-reject"; kind="reject"; pair="windows-linux" },
    [pscustomobject]@{ id="windows-linux-cancel"; kind="cancel"; pair="windows-linux" },
    [pscustomobject]@{ id="windows-linux-wrong-auth"; kind="wrong-auth"; pair="windows-linux" },
    [pscustomobject]@{ id="windows-linux-reconnect"; kind="reconnect"; pair="windows-linux" }
)

function Expected-Pair([string]$Pair) {
    return @($Pair -split "-")
}

function Get-RunProperty($Run,[string]$Name,[string]$Context) {
    if ($null -eq $Run.PSObject.Properties[$Name]) {
        Add-Failure "$Context missing property '$Name'."
        return $null
    }
    return $Run.$Name
}

function Verify-LogPaths($Run,[string]$Context) {
    if ($null -eq $Run.PSObject.Properties["logs"]) {
        Add-Failure "$Context has no logs array."
        return
    }

    $logs = @($Run.logs)
    if ($logs.Count -lt 2) {
        Add-Failure "$Context must retain at least sender and receiver logs."
    }

    foreach ($relative in $logs) {
        if ([string]::IsNullOrWhiteSpace([string]$relative)) {
            Add-Failure "$Context contains an empty log path."
            continue
        }
        $full = Join-Path $EvidenceDir ([string]$relative)
        if (-not (Test-Path $full -PathType Leaf)) {
            Add-Failure "$Context log does not exist: $relative"
            continue
        }
        if ((Get-Item $full).Length -eq 0) {
            Add-Failure "$Context log is empty: $relative"
        }
    }
}

$runsDir = Join-Path $EvidenceDir "runs"
foreach ($case in $required) {
    $runPath = Join-Path $runsDir ($case.id + ".json")
    $run = Read-JsonFile $runPath
    if ($null -eq $run) { continue }

    $ctx = "run '$($case.id)'"
    $runSchema = Get-RunProperty $run "schema" $ctx
    if ($runSchema -and $runSchema -ne "glide-hard017-run-v1") {
        Add-Failure "$ctx has unsupported schema '$runSchema'."
    }
    $declaredId = Get-RunProperty $run "id" $ctx
    if ($declaredId -and $declaredId -ne $case.id) {
        Add-Failure "$ctx declares id '$declaredId'."
    }
    $declaredScenario = Get-RunProperty $run "scenario" $ctx
    if ($declaredScenario -and $declaredScenario -ne $case.kind) {
        Add-Failure "$ctx scenario '$declaredScenario' does not match '$($case.kind)'."
    }
    if ((Get-RunProperty $run "observedResult" $ctx) -ne "pass") {
        Add-Failure "$ctx is not recorded as observedResult=pass."
    }

    $sender = [string](Require-Property $run "senderPlatform" $ctx)
    $receiver = [string](Require-Property $run "receiverPlatform" $ctx)
    if ($sender -eq $receiver -or $sender -notin @("android","windows","linux") -or $receiver -notin @("android","windows","linux")) {
        Add-Failure "$ctx has invalid sender/receiver platform pair."
    }

    if ($case.kind -eq "success") {
        if ($sender -ne $case.sender -or $receiver -ne $case.receiver) {
            Add-Failure "$ctx must be $($case.sender) -> $($case.receiver)."
        }
    }
    else {
        $actualPair = @(@($sender,$receiver) | Sort-Object)
        $expectedPair = @(@(Expected-Pair $case.pair) | Sort-Object)
        if (($actualPair -join ",") -ne ($expectedPair -join ",")) {
            Add-Failure "$ctx must exercise platform pair $($case.pair), found $sender -> $receiver."
        }
    }

    if ($endpoints.ContainsKey($sender)) {
        if ((Get-RunProperty $run "senderArtifactSha256" $ctx) -ne $endpoints[$sender].artifactSha256) {
            Add-Failure "$ctx sender artifact hash does not match captured $sender endpoint."
        }
    }
    if ($endpoints.ContainsKey($receiver)) {
        if ((Get-RunProperty $run "receiverArtifactSha256" $ctx) -ne $endpoints[$receiver].artifactSha256) {
            Add-Failure "$ctx receiver artifact hash does not match captured $receiver endpoint."
        }
    }

    if ($endpoints.Count -eq 3) {
        $expectedHead = $endpoints["android"].repositoryHead
        if ((Get-RunProperty $run "repositoryHead" $ctx) -ne $expectedHead) {
            Add-Failure "$ctx repositoryHead does not match endpoint build identity."
        }
    }

    [void](Require-Property $run "startedAtUtc" $ctx)
    [void](Require-Property $run "endedAtUtc" $ctx)
    Verify-LogPaths $run $ctx

    if ($case.kind -eq "success") {
        if ((Get-RunProperty $run "corpusManifestSha256" $ctx) -ne $corpusSha) {
            Add-Failure "$ctx corpus manifest hash does not match the audited corpus."
        }
        if ([int](Get-RunProperty $run "itemCount" $ctx) -lt 35 -or [int](Get-RunProperty $run "offerPageCount" $ctx) -lt 2) {
            Add-Failure "$ctx did not prove a >32-item multi-page batch."
        }
        if ((Get-RunProperty $run "allItemHashesMatch" $ctx) -ne $true) {
            Add-Failure "$ctx does not prove byte-identical per-item hashes."
        }
        if ((Get-RunProperty $run "terminalBatchStatus" $ctx) -ne "success") {
            Add-Failure "$ctx terminal batch status is not success."
        }
    }

    if ($case.kind -eq "reject") {
        if ((Get-RunProperty $run "offerRejected" $ctx) -ne $true) {
            Add-Failure "$ctx does not prove explicit offer rejection."
        }
        if ((Get-RunProperty $run "terminalBatchStatus" $ctx) -eq "success") {
            Add-Failure "$ctx falsely reports successful batch status after rejection."
        }
        if ((Get-RunProperty $run "noUnverifiedCommit" $ctx) -ne $true) {
            Add-Failure "$ctx does not prove no unverified file was committed."
        }
    }

    if ($case.kind -eq "cancel") {
        if ((Get-RunProperty $run "cancelObserved" $ctx) -ne $true) {
            Add-Failure "$ctx does not prove active-transfer cancellation."
        }
        if ((Get-RunProperty $run "terminalBatchStatus" $ctx) -eq "success") {
            Add-Failure "$ctx falsely reports successful batch status after cancellation."
        }
        if ((Get-RunProperty $run "noUnverifiedCommit" $ctx) -ne $true) {
            Add-Failure "$ctx does not prove partial/unverified output was withheld."
        }
    }

    if ($case.kind -eq "wrong-auth") {
        if ((Get-RunProperty $run "authenticationRejected" $ctx) -ne $true) {
            Add-Failure "$ctx does not prove authentication rejection."
        }
        if ((Get-RunProperty $run "terminalBatchStatus" $ctx) -eq "success") {
            Add-Failure "$ctx falsely reports successful batch status after authentication failure."
        }
        if ([long](Get-RunProperty $run "payloadBytesAuthorized" $ctx) -ne 0) {
            Add-Failure "$ctx authorized payload bytes despite failed authentication."
        }
    }

    if ($case.kind -eq "reconnect") {
        if ((Get-RunProperty $run "freshInvitation" $ctx) -ne $true) {
            Add-Failure "$ctx did not use a fresh invitation after the prior terminal failure."
        }
        if ((Get-RunProperty $run "previousSessionTerminalNotSuccess" $ctx) -ne $true) {
            Add-Failure "$ctx does not bind reconnect proof to a previously non-successful session."
        }
        if ((Get-RunProperty $run "terminalBatchStatus" $ctx) -ne "success") {
            Add-Failure "$ctx reconnect did not reach truthful success."
        }
        if ((Get-RunProperty $run "allItemHashesMatch" $ctx) -ne $true) {
            Add-Failure "$ctx reconnect does not prove byte-identical output."
        }
    }
}

if ($errors.Count -gt 0) {
    Write-Host ""
    Write-Host "HARD-017 EVIDENCE AUDIT FAILED ($($errors.Count) issue(s))" -ForegroundColor Red
    foreach ($errorMessage in $errors) {
        Write-Host ("  - " + $errorMessage) -ForegroundColor Red
    }
    exit 1
}

Write-Host "HARD-017 EVIDENCE AUDIT PASSED" -ForegroundColor Green
Write-Host "  Physical endpoints: Android + Windows + Linux" -ForegroundColor Green
Write-Host "  Success directions: 6/6" -ForegroundColor Green
Write-Host "  Negative/reconnect cases: 12/12" -ForegroundColor Green
Write-Host "  Corpus: >32 items, multi-page, zero-byte, >=100 MiB, per-item SHA-256" -ForegroundColor Green
Write-Host "This audit validates retained evidence structure; reviewers must still inspect the captured logs for authenticity and expected protocol behavior." -ForegroundColor Yellow
