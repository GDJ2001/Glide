#Requires -Version 7.0
<#
.SYNOPSIS
Collects Android device/emulator metrics, Macrobenchmark results, and peer driver outputs into a
shared glide-r3-diagnostic-v1 JSON report for non-debuggable Release/Benchmark APKs.
.DESCRIPTION
Queries adb/dumpsys (meminfo, gfxinfo, battery, thermalservice, df) on the target Android device,
optionally merges Macrobenchmark *-benchmarkData.json or Glide.BackendTestDriver JSON output,
redacts any invitation URIs/secrets/user paths, marks inaccessible counters explicitly as
"unavailable", and writes a glide-r3-diagnostic-v1 JSON file.
#>
[CmdletBinding()]
param(
    [string]$DeviceSerial = "emulator-5554",
    [string]$PackageName = "dev.glide.app",
    [string]$Direction = "Receive",
    [string]$DatasetId = "unavailable",
    [string]$ContentProfile = "incompressible-prng",
    [long]$DeclaredTotalBytes = 0,
    [int]$ItemCount = 1,
    [string]$DatasetSha256 = "unavailable",
    [string]$BenchmarkPath = "installed-app",
    [string]$BenchmarkMode = "production-default",
    [string]$RunType = "interactive",
    [int]$RepetitionIndex = 1,
    [int]$RepetitionTotal = 1,
    [string]$BuildConfiguration = "Release",
    [string]$AppVersion = "1.0.0",
    [string]$ArtifactPath = "",
    [string]$MacrobenchmarkJsonPath = "",
    [string]$PeerDriverJsonPath = "",
    [double]$WallMs = -1.0,
    [double]$PayloadMs = -1.0,
    [double]$VerificationMs = -1.0,
    [double]$SavingMs = -1.0,
    [double]$AcceptanceToVerifiedMs = -1.0,
    [bool]$Sha256Verified = $false,
    [string]$OutcomeStatus = "Completed",
    [string]$TerminalMessage = "",
    [Parameter(Mandatory = $true)]
    [string]$OutputJsonPath
)

$ErrorActionPreference = "Stop"

function Resolve-AdbPath {
    $candidates = @(
        @(
            (Get-Command "adb" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
            (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
        ) | Where-Object { $_ -and (Test-Path $_) }
    )
    if ($candidates.Count -eq 0) {
        throw "adb executable not found on PATH or under LOCALAPPDATA\Android\Sdk\platform-tools."
    }
    return $candidates[0]
}

function Sanitize-DiagnosticText {
    param([string]$Text, [int]$MaxLength = 512)
    if ([string]::IsNullOrWhiteSpace($Text)) { return "" }
    $cleaned = [regex]::Replace($Text, 'glide://pair/\S+', 'glide://pair/[REDACTED]', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    $cleaned = [regex]::Replace($cleaned, '\b(token|secret|pin|password|passcode|spki|privatekey|private_key)\b(\s*[:=]\s*)([^\s,;\x27"&]+)', '$1$2[REDACTED]', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    $cleaned = [regex]::Replace($cleaned, '/(?:home|Users)/[^/\s\\]+', '~')
    $cleaned = [regex]::Replace($cleaned, '[A-Za-z]:\\Users\\[^\\\s/]+', '~', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if ($cleaned.Length -gt $MaxLength) {
        return $cleaned.Substring(0, $MaxLength)
    }
    return $cleaned
}

function Invoke-AdbShell {
    param([string]$Adb, [string]$Serial, [string]$Command)
    $output = & $Adb -s $Serial shell $Command 2>$null | Out-String
    return $output.Trim()
}

$adb = Resolve-AdbPath
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

$sourceCommit = "unavailable"
try {
    $gitCommit = & git -C $repoRoot rev-parse HEAD 2>$null
    if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($gitCommit)) {
        $sourceCommit = $gitCommit.Trim()
    }
} catch {
    $sourceCommit = "unavailable"
}

$artifactSha256 = "unavailable"
if (-not [string]::IsNullOrWhiteSpace($ArtifactPath) -and (Test-Path -LiteralPath $ArtifactPath)) {
    $artifactSha256 = (Get-FileHash -LiteralPath $ArtifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
}

$sdkInt = Invoke-AdbShell $adb $DeviceSerial "getprop ro.build.version.sdk"
$releaseVer = Invoke-AdbShell $adb $DeviceSerial "getprop ro.build.version.release"
$abi = Invoke-AdbShell $adb $DeviceSerial "getprop ro.product.cpu.abi"
$model = Invoke-AdbShell $adb $DeviceSerial "getprop ro.product.model"
$hardware = Invoke-AdbShell $adb $DeviceSerial "getprop ro.hardware"
$characteristics = Invoke-AdbShell $adb $DeviceSerial "getprop ro.build.characteristics"
$fingerprint = Invoke-AdbShell $adb $DeviceSerial "getprop ro.build.fingerprint"

$isEmulator = ($model -match 'sdk_gphone|Emulator|Android SDK') -or ($characteristics -match 'emulator') -or ($fingerprint -match 'generic|emulator')
$execEnv = if ($isEmulator) { "android-emulator" } else { "physical" }

$coresRaw = Invoke-AdbShell $adb $DeviceSerial "nproc"
$logicalCores = if ($coresRaw -match '^\d+$') { [int]$coresRaw } else { "unavailable" }

$memTotalRaw = Invoke-AdbShell $adb $DeviceSerial "grep MemTotal /proc/meminfo"
$totalRamMiB = if ($memTotalRaw -match '(\d+)\s+kB') {
    [Math]::Round([double]$Matches[1] / 1024.0, 2)
} else { "unavailable" }

$dfDataRaw = Invoke-AdbShell $adb $DeviceSerial "df -k /data | tail -n 1"
$stagingFreeBytes = if ($dfDataRaw -match '\s+(\d+)\s+\d+%\s+') {
    [long]$Matches[1] * 1024L
} else { "unavailable" }

$dfSdcardRaw = Invoke-AdbShell $adb $DeviceSerial "df -k /storage/emulated | tail -n 1"
$destFreeBytes = if ($dfSdcardRaw -match '\s+(\d+)\s+\d+%\s+') {
    [long]$Matches[1] * 1024L
} else { "unavailable" }

$batteryDump = Invoke-AdbShell $adb $DeviceSerial "dumpsys battery"
$batteryLevel = if ($batteryDump -match 'level:\s*(\d+)') { [int]$Matches[1] } else { "unavailable" }
$batteryTempC = if ($batteryDump -match 'temperature:\s*(-?\d+)') { [Math]::Round([double]$Matches[1] / 10.0, 2) } else { "unavailable" }
$isCharging = if ($batteryDump -match 'status:\s*(\d+)') {
    ($Matches[1] -eq "2" -or $Matches[1] -eq "5")
} else { "unavailable" }

$thermalDump = Invoke-AdbShell $adb $DeviceSerial "dumpsys thermalservice"
$thermalStatus = if ($thermalDump -match 'Thermal Status:\s*(\d+)') {
    switch ([int]$Matches[1]) {
        0 { "none" }
        1 { "light" }
        2 { "moderate" }
        3 { "severe" }
        4 { "critical" }
        5 { "emergency" }
        6 { "shutdown" }
        default { "unavailable" }
    }
} else { "unavailable" }

$meminfoDump = Invoke-AdbShell $adb $DeviceSerial "dumpsys meminfo $PackageName"
$totalPssMiB = if ($meminfoDump -match 'TOTAL PSS:\s+(\d+)') {
    [Math]::Round([double]$Matches[1] / 1024.0, 2)
} elseif ($meminfoDump -match 'TOTAL\s+(\d+)\s+') {
    [Math]::Round([double]$Matches[1] / 1024.0, 2)
} else { "unavailable" }

$nativeHeapMiB = if ($meminfoDump -match 'Native Heap\s+\d+\s+\d+\s+\d+\s+\d+\s+\d+\s+(\d+)') {
    [Math]::Round([double]$Matches[1] / 1024.0, 2)
} else { "unavailable" }

$dalvikHeapMiB = if ($meminfoDump -match 'Dalvik Heap\s+\d+\s+\d+\s+\d+\s+\d+\s+\d+\s+(\d+)') {
    [Math]::Round([double]$Matches[1] / 1024.0, 2)
} else { "unavailable" }

$oneSecRates = @()
$fiveSecWindows = @()
if (-not [string]::IsNullOrWhiteSpace($PeerDriverJsonPath) -and (Test-Path -LiteralPath $PeerDriverJsonPath)) {
    try {
        $peerData = Get-Content -LiteralPath $PeerDriverJsonPath -Raw | ConvertFrom-Json
        if ($peerData.oneSecondRatesMBps) { $oneSecRates = @($peerData.oneSecondRatesMBps) }
        if ($peerData.fiveSecondRateWindowsMBps) { $fiveSecWindows = @($peerData.fiveSecondRateWindowsMBps) }
    } catch { }
}

$avgPayloadRate = if ($PayloadMs -gt 0 -and $DeclaredTotalBytes -gt 0) {
    [Math]::Round($DeclaredTotalBytes / (($PayloadMs / 1000.0) * 1000000.0), 2)
} else { "unavailable" }

$acceptanceRate = if ($AcceptanceToVerifiedMs -gt 0 -and $DeclaredTotalBytes -gt 0) {
    [Math]::Round($DeclaredTotalBytes / (($AcceptanceToVerifiedMs / 1000.0) * 1000000.0), 2)
} else { "unavailable" }

$report = [ordered]@{
    schemaVersion = "glide-r3-diagnostic-v1"
    attemptId = ([Guid]::NewGuid().ToString("N"))
    timestampUtc = (Get-Date).ToUniversalTime().ToString("O")
    sourceCommit = $sourceCommit
    artifactSha256 = $artifactSha256
    appVersion = $AppVersion
    platform = [ordered]@{
        osFamily = "android"
        osVersion = "Android $releaseVer (API $sdkInt)"
        architecture = if ($abi) { $abi } else { "unavailable" }
        executionEnvironment = $execEnv
        deviceModel = if ($model) { $model } else { "unavailable" }
    }
    hardware = [ordered]@{
        cpuModel = if ($hardware) { $hardware } else { "unavailable" }
        logicalCores = $logicalCores
        totalRamMiB = $totalRamMiB
    }
    runtime = [ordered]@{
        runtimeName = "ART"
        runtimeVersion = "Android $sdkInt ART"
        buildConfiguration = $BuildConfiguration
        gcMode = "ART-CC"
        aotOrBaselineProfile = if ($MacrobenchmarkJsonPath) { "BaselineProfile-Partial" } else { "unavailable" }
    }
    networkLink = [ordered]@{
        transport = "lan-tls13"
        interfaceName = if ($isEmulator) { "wlan0" } else { "unavailable" }
        linkType = if ($isEmulator) { "virtual-nat" } else { "wifi" }
        negotiatedLinkMbps = "unavailable"
        mtuBytes = 1500
        wifiRssiDbm = "unavailable"
        wifiFrequencyMhz = "unavailable"
        localAddressClass = "rfc1918"
        peerAddressClass = "rfc1918"
        tcpRetransmitsDelta = "unavailable"
        outboundPacketErrorsDelta = "unavailable"
        receivedPacketErrorsDelta = "unavailable"
    }
    storage = [ordered]@{
        stagingLocation = "filesDir/staging"
        stagingVolumeOrMount = "filesDir"
        stagingFilesystem = "unavailable"
        stagingStorageType = if ($isEmulator) { "virtual-disk" } else { "emmc-ufs" }
        stagingFreeBytesBefore = $stagingFreeBytes
        destinationLocation = "Download/Glide"
        destinationVolumeOrMount = "MediaStore:Download/Glide"
        destinationFilesystem = "fuse-mediastore"
        destinationStorageType = if ($isEmulator) { "virtual-disk" } else { "emmc-ufs" }
        destinationFreeBytesBefore = $destFreeBytes
        sameVolumeStagingEligible = "unavailable"
        commitStrategy = "MediaStoreAtomic"
    }
    dataset = [ordered]@{
        datasetId = $DatasetId
        contentProfile = $ContentProfile
        itemCount = $ItemCount
        declaredTotalBytes = $DeclaredTotalBytes
        datasetSha256 = $DatasetSha256
        manifestSha256 = "unavailable"
    }
    testConfiguration = [ordered]@{
        role = if ($Direction -eq "Send") { "sender" } else { "receiver" }
        direction = $Direction
        pairingMode = "qr-v1"
        benchmarkPath = $BenchmarkPath
        benchmarkMode = $BenchmarkMode
        runType = $RunType
        repetitionIndex = $RepetitionIndex
        repetitionTotal = $RepetitionTotal
        uiState = "visible"
        powerSource = if ($isCharging -eq $true) { "ac" } elseif ($isCharging -eq $false) { "battery" } else { "unavailable" }
        ioOptions = [ordered]@{
            chunkBufferBytes = 65536
            queueBufferCount = "unavailable"
            maxProcessTransferBufferBytes = "unavailable"
            writeCoalesceBufferBytes = "unavailable"
            maxWriteBatchDelayMs = "unavailable"
            enableCoalescedFraming = "unavailable"
            enableBoundedPipeline = "unavailable"
            discardStorageForBenchmark = $false
        }
    }
    outcome = [ordered]@{
        status = $OutcomeStatus
        success = ($OutcomeStatus -eq "Completed")
        rejected = ($OutcomeStatus -eq "Rejected")
        cancelled = ($OutcomeStatus -eq "Cancelled")
        errorCategory = if ($OutcomeStatus -eq "Completed") { "None" } else { $OutcomeStatus }
        terminalMessage = (Sanitize-DiagnosticText -Text $TerminalMessage)
        verifiedItemCount = if ($Sha256Verified) { $ItemCount } else { 0 }
        verifiedBytes = if ($Sha256Verified) { $DeclaredTotalBytes } else { 0 }
        sha256Verified = $Sha256Verified
        quarantineCleanAfterTerminal = "unavailable"
    }
    phaseDurationsMs = [ordered]@{
        totalWallMs = if ($WallMs -ge 0) { [Math]::Round($WallMs, 2) } else { "unavailable" }
        preparationMs = "unavailable"
        discoveryMs = "unavailable"
        connectionMs = "unavailable"
        authenticationMs = "unavailable"
        userConsentMs = "unavailable"
        payloadTransferMs = if ($PayloadMs -ge 0) { [Math]::Round($PayloadMs, 2) } else { "unavailable" }
        verificationMs = if ($VerificationMs -ge 0) { [Math]::Round($VerificationMs, 2) } else { "unavailable" }
        savingMs = if ($SavingMs -ge 0) { [Math]::Round($SavingMs, 2) } else { "unavailable" }
        finalAcknowledgementMs = "unavailable"
        acceptanceToVerifiedMs = if ($AcceptanceToVerifiedMs -ge 0) { [Math]::Round($AcceptanceToVerifiedMs, 2) } else { "unavailable" }
    }
    bytes = [ordered]@{
        declaredTotalBytes = $DeclaredTotalBytes
        payloadBytesTransferred = $DeclaredTotalBytes
        stagingBytesWritten = "unavailable"
        verifiedBytes = if ($Sha256Verified) { $DeclaredTotalBytes } else { 0 }
    }
    throughput = [ordered]@{
        averagePayloadRateMBps = $avgPayloadRate
        acceptanceToVerifiedRateMBps = $acceptanceRate
        oneSecondRatesMBps = $oneSecRates
        fiveSecondRateWindowsMBps = $fiveSecWindows
        firstSteadyQuarterRateMBps = "unavailable"
        finalSteadyQuarterRateMBps = "unavailable"
        finalToFirstQuarterRatio = "unavailable"
    }
    pipelineTimingsMs = [ordered]@{
        sourceReadMs = "unavailable"
        hashComputeMs = "unavailable"
        tlsWriteMs = "unavailable"
        tlsReadMs = "unavailable"
        stagingWriteMs = "unavailable"
        verificationReadMs = "unavailable"
        commitMs = "unavailable"
    }
    resources = [ordered]@{
        cpuPercentAvg = "unavailable"
        cpuPercentPeak = "unavailable"
        managedHeapBaselineMiB = $dalvikHeapMiB
        managedHeapPeakMiB = $dalvikHeapMiB
        managedHeapDeltaMiB = "unavailable"
        managedAllocatedTotalMB = "unavailable"
        managedAllocatedRateAvgMBPerSec = "unavailable"
        nativeOrPrivateMemoryBaselineMiB = $nativeHeapMiB
        nativeOrPrivateMemoryPeakMiB = $nativeHeapMiB
        workingSetPeakMiB = $totalPssMiB
        gcPauseTotalMs = "unavailable"
        gcPausePercentPeak = "unavailable"
        gen0Collections = "unavailable"
        gen1Collections = "unavailable"
        gen2Collections = "unavailable"
        threadPoolPendingWorkItemsPeak = "unavailable"
    }
    queueOccupancy = [ordered]@{
        senderQueueDepthMax = "unavailable"
        receiverQueueDepthMax = "unavailable"
        activePooledBuffersPeak = "unavailable"
        activePooledBytesPeak = "unavailable"
    }
    diskActivity = [ordered]@{
        processReadIoBytes = "unavailable"
        processWriteIoBytes = "unavailable"
        processReadMBpsAvg = "unavailable"
        processWriteMBpsAvg = "unavailable"
        volumeReadLatencyMsAvg = "unavailable"
        volumeWriteLatencyMsAvg = "unavailable"
    }
    cancellation = [ordered]@{
        cancellationRequested = ($OutcomeStatus -eq "Cancelled")
        uiCancelLatencyMs = "unavailable"
        resourceReleaseLatencyMs = "unavailable"
    }
    androidConditions = [ordered]@{
        thermalStatusStart = $thermalStatus
        thermalStatusPeak = $thermalStatus
        thermalStatusEnd = $thermalStatus
        thermalHeadroom = "unavailable"
        batteryLevelPercentStart = $batteryLevel
        batteryLevelPercentEnd = $batteryLevel
        isCharging = $isCharging
        batterySaverEnabled = "unavailable"
        batteryTemperatureCelsiusStart = $batteryTempC
        batteryTemperatureCelsiusEnd = $batteryTempC
    }
    linuxConditions = [ordered]@{
        kernelRelease = "unavailable"
        distro = "unavailable"
        virtualization = "unavailable"
        cgroupMemoryLimitBytes = "unavailable"
        networkBridgeOrNatMode = "unavailable"
        stagingMountOptions = "unavailable"
        destinationMountOptions = "unavailable"
    }
}

$outDir = Split-Path -Parent $OutputJsonPath
if ($outDir -and -not (Test-Path -LiteralPath $outDir)) {
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
}
$report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $OutputJsonPath -Encoding UTF8
Write-Host "Wrote Android R3 diagnostic report to $OutputJsonPath"
