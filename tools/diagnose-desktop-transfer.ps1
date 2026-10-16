#Requires -Version 7.0
<#
.SYNOPSIS
Samples local Ethernet, TCP retransmissions, disk throughput/latency, and Glide CPU/GC/allocation metrics during a physical desktop transfer.
.DESCRIPTION
Run on each Windows PC before starting a Glide transfer. The CSV contains only
local performance counters, diagnostic metadata, and peer IPs; it never reads or records
file contents, pairing secrets, tokens, PINs, or invitation links.
Rates use decimal megabytes per second (1 MB = 1,000,000 bytes).
Missing system counters or unavailable tools (such as dotnet-counters) are explicitly reported as 'unavailable'.
#>
[CmdletBinding()]
param(
    [string]$AdapterName = 'Ethernet',
    [ValidateRange(2, 3600)][int]$DurationSeconds = 180,
    [string]$AttemptId = ([Guid]::NewGuid().ToString('N')),
    [string]$BuildCommit = '',
    [string]$DiagnosticLogPath = $env:GLIDE_DIAGNOSTIC_LOG_PATH,
    [string]$OutputPath = (Join-Path -Path ([Environment]::GetFolderPath('UserProfile')) -ChildPath ('Downloads\Glide-transfer-rate-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.csv'))
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($BuildCommit)) {
    if (-not [string]::IsNullOrWhiteSpace($env:GLIDE_BUILD_COMMIT)) {
        $BuildCommit = $env:GLIDE_BUILD_COMMIT
    } else {
        try {
            $gitOut = & git rev-parse --short=12 HEAD 2>$null
            $BuildCommit = if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($gitOut)) { $gitOut.Trim() } else { 'unavailable' }
        } catch {
            $BuildCommit = 'unavailable'
        }
    }
}

$adapter = Get-NetAdapter -Name $AdapterName -ErrorAction SilentlyContinue
if ($null -eq $adapter) {
    $adapter = Get-NetAdapter | Where-Object { $_.Status -eq 'Up' } | Select-Object -First 1
    if ($null -ne $adapter) {
        $AdapterName = $adapter.Name
    } else {
        throw "Adapter '$AdapterName' was not found and no active network adapter is up."
    }
}
if ($adapter.Status -ne 'Up') {
    throw "Adapter '$AdapterName' is not up."
}

$outputDirectory = Split-Path -Parent $OutputPath
if (-not (Test-Path -LiteralPath $outputDirectory)) {
    throw "Output directory does not exist: $outputDirectory"
}
if (Test-Path -LiteralPath $OutputPath) {
    throw "Output file already exists: $OutputPath"
}

$dotnetCountersCmd = Get-Command 'dotnet-counters' -ErrorAction SilentlyContinue
if ($null -eq $dotnetCountersCmd) {
    $userToolPath = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.dotnet\tools\dotnet-counters.exe'
    if (Test-Path -LiteralPath $userToolPath) {
        $dotnetCountersCmd = Get-Item -LiteralPath $userToolPath
    }
}
$dotnetCountersStatus = if ($null -ne $dotnetCountersCmd) { 'available' } else { 'unavailable' }

$previousNetwork = Get-NetAdapterStatistics -Name $AdapterName
$previousCpu = $null
$previousProcessId = $null
$previousReadBytes = $null
$previousWriteBytes = $null
$previousTime = [System.Diagnostics.Stopwatch]::GetTimestamp()

$fixedVolumes = @(Get-CimInstance Win32_LogicalDisk -Filter 'DriveType = 3' -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty DeviceID)
$counterPaths = @()
foreach ($vol in $fixedVolumes) {
    $counterPaths += "\LogicalDisk($vol)\Disk Read Bytes/sec"
    $counterPaths += "\LogicalDisk($vol)\Disk Write Bytes/sec"
    $counterPaths += "\LogicalDisk($vol)\Avg. Disk sec/Read"
    $counterPaths += "\LogicalDisk($vol)\Avg. Disk sec/Write"
}
$counterPaths += '\TCPv4\Segments Retransmitted/sec'
$counterPaths += '\TCPv6\Segments Retransmitted/sec'

for ($sample = 1; $sample -le $DurationSeconds; $sample++) {
    $counterSamples = $null
    try {
        if ($counterPaths.Count -gt 0) {
            $counterSamples = (Get-Counter -Counter $counterPaths -MaxSamples 1 -ErrorAction SilentlyContinue).CounterSamples
        }
    } catch {
        $counterSamples = $null
    }
    if ($null -eq $counterSamples) {
        Start-Sleep -Seconds 1
    }

    $now = [System.Diagnostics.Stopwatch]::GetTimestamp()
    $elapsed = [Math]::Max(0.001, [System.Diagnostics.Stopwatch]::GetElapsedTime($previousTime, $now).TotalSeconds)
    $network = Get-NetAdapterStatistics -Name $AdapterName
    $glideProcess = Get-Process -Name 'Glide', 'Glide.BackendTestDriver' -ErrorAction SilentlyContinue | Select-Object -First 1
    $processId = if ($null -ne $glideProcess) { $glideProcess.Id } else { $null }
    $cpuSeconds = if ($null -ne $glideProcess) { $glideProcess.CPU } else { $null }
    $cpuCorePercent = if ($null -ne $cpuSeconds -and $processId -eq $previousProcessId -and $null -ne $previousCpu) {
        [math]::Round(($cpuSeconds - $previousCpu) / $elapsed * 100, 1)
    } else { 'unavailable' }

    $processIo = if ($null -ne $processId) {
        Get-CimInstance Win32_Process -Filter "ProcessId = $processId" -ErrorAction SilentlyContinue
    } else { $null }
    $readBytes = if ($null -ne $processIo) { [long]$processIo.ReadTransferCount } else { $null }
    $writeBytes = if ($null -ne $processIo) { [long]$processIo.WriteTransferCount } else { $null }
    $readMBPerSecond = if ($null -ne $readBytes -and $processId -eq $previousProcessId -and $null -ne $previousReadBytes) {
        [math]::Round(($readBytes - $previousReadBytes) / $elapsed / 1000000.0, 2)
    } else { 'unavailable' }
    $writeMBPerSecond = if ($null -ne $writeBytes -and $processId -eq $previousProcessId -and $null -ne $previousWriteBytes) {
        [math]::Round(($writeBytes - $previousWriteBytes) / $elapsed / 1000000.0, 2)
    } else { 'unavailable' }

    $peer = if ($null -ne $processId) {
        (Get-NetTCPConnection -OwningProcess $processId -State Established -ErrorAction SilentlyContinue |
            Where-Object RemoteAddress -ne '127.0.0.1' |
            Select-Object -First 1 -ExpandProperty RemoteAddress)
    } else { $null }

    $volumeReadMBps = @()
    $volumeWriteMBps = @()
    $volumeReadLatencyMs = @()
    $volumeWriteLatencyMs = @()
    $tcpRetransmitsPerSec = 'unavailable'
    $tcpRetransmitSum = 0.0
    $hasTcpCounter = $false

    foreach ($counter in @($counterSamples)) {
        if ($null -eq $counter) { continue }
        if ($counter.Path -match 'logicaldisk\(([^)]+)\)\\disk (read|write) bytes/sec$') {
            $entry = '{0}={1}' -f $Matches[1], [math]::Round($counter.CookedValue / 1000000.0, 2)
            if ($Matches[2] -eq 'read') { $volumeReadMBps += $entry }
            else { $volumeWriteMBps += $entry }
        } elseif ($counter.Path -match 'logicaldisk\(([^)]+)\)\\avg\. disk sec/(read|write)$') {
            $entry = '{0}={1}' -f $Matches[1], [math]::Round($counter.CookedValue * 1000.0, 3)
            if ($Matches[2] -eq 'read') { $volumeReadLatencyMs += $entry }
            else { $volumeWriteLatencyMs += $entry }
        } elseif ($counter.Path -match 'tcpv[46]\\segments retransmitted/sec$') {
            $tcpRetransmitSum += $counter.CookedValue
            $hasTcpCounter = $true
        }
    }
    if ($hasTcpCounter) {
        $tcpRetransmitsPerSec = [math]::Round($tcpRetransmitSum, 2)
    }

    $inProcPhase = 'unavailable'
    $inProcAllocMBps = 'unavailable'
    $inProcGcPauseMs = 'unavailable'
    $inProcHeapMiB = 'unavailable'
    $inProcQueueDepth = 'unavailable'
    if (-not [string]::IsNullOrWhiteSpace($DiagnosticLogPath) -and (Test-Path -LiteralPath $DiagnosticLogPath)) {
        try {
            $lastLine = Get-Content -LiteralPath $DiagnosticLogPath -Tail 1 -ErrorAction SilentlyContinue
            if (-not [string]::IsNullOrWhiteSpace($lastLine)) {
                $parsed = $lastLine | ConvertFrom-Json -ErrorAction Stop
                $inProcPhase = $parsed.phase
                $inProcAllocMBps = $parsed.managedAllocatedMBPerSec
                $inProcGcPauseMs = $parsed.gcPauseMs
                $inProcHeapMiB = $parsed.managedHeapMiB
                $inProcQueueDepth = "send=$($parsed.senderQueueDepth);recv=$($parsed.receiverQueueDepth)"
            }
        } catch { }
    }

    $sentDeltaBytes = [Math]::Max(0L, ([long]$network.SentBytes - [long]$previousNetwork.SentBytes))
    $recvDeltaBytes = [Math]::Max(0L, ([long]$network.ReceivedBytes - [long]$previousNetwork.ReceivedBytes))

    [pscustomobject]@{
        UtcTime = (Get-Date).ToUniversalTime().ToString('o')
        AttemptId = $AttemptId
        BuildCommit = $BuildCommit
        Adapter = $AdapterName
        LinkSpeed = (Get-NetAdapter -Name $AdapterName).LinkSpeed
        GlideProcessId = if ($null -ne $processId) { $processId } else { 'unavailable' }
        PeerAddress = if (-not [string]::IsNullOrWhiteSpace($peer)) { $peer } else { 'unavailable' }
        SentMBPerSecond = [math]::Round($sentDeltaBytes / $elapsed / 1000000.0, 2)
        ReceivedMBPerSecond = [math]::Round($recvDeltaBytes / $elapsed / 1000000.0, 2)
        SentMiBPerSecond = [math]::Round($sentDeltaBytes / $elapsed / 1MB, 2)
        ReceivedMiBPerSecond = [math]::Round($recvDeltaBytes / $elapsed / 1MB, 2)
        GlideCpuCorePercent = $cpuCorePercent
        GlideReadIoMBPerSecond = $readMBPerSecond
        GlideWrittenIoMBPerSecond = $writeMBPerSecond
        GlideWorkingSetMiB = if ($null -ne $glideProcess) { [math]::Round($glideProcess.WorkingSet64 / 1MB, 1) } else { 'unavailable' }
        DotnetCountersTool = $dotnetCountersStatus
        GlidePhase = $inProcPhase
        ManagedAllocMBPerSecond = $inProcAllocMBps
        ManagedHeapMiB = $inProcHeapMiB
        GcPauseMs = $inProcGcPauseMs
        QueueOccupancy = $inProcQueueDepth
        DiskReadMBPerSecondByVolume = if ($volumeReadMBps.Count -gt 0) { $volumeReadMBps -join ';' } else { 'unavailable' }
        DiskWriteMBPerSecondByVolume = if ($volumeWriteMBps.Count -gt 0) { $volumeWriteMBps -join ';' } else { 'unavailable' }
        DiskReadLatencyMsByVolume = if ($volumeReadLatencyMs.Count -gt 0) { $volumeReadLatencyMs -join ';' } else { 'unavailable' }
        DiskWriteLatencyMsByVolume = if ($volumeWriteLatencyMs.Count -gt 0) { $volumeWriteLatencyMs -join ';' } else { 'unavailable' }
        TcpSegmentsRetransmittedPerSec = $tcpRetransmitsPerSec
        OutboundPacketErrors = $network.OutboundPacketErrors
        ReceivedPacketErrors = $network.ReceivedPacketErrors
    } | Export-Csv -LiteralPath $OutputPath -NoTypeInformation -Append

    $previousNetwork = $network
    $previousCpu = $cpuSeconds
    $previousReadBytes = $readBytes
    $previousWriteBytes = $writeBytes
    $previousProcessId = $processId
    $previousTime = $now
}

Write-Host "Transfer rate samples: $OutputPath"
