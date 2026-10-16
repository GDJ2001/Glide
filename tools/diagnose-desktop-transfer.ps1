#Requires -Version 7.0
<#
.SYNOPSIS
Samples local Ethernet and Glide CPU use during a physical desktop transfer.
.DESCRIPTION
Run on each Windows PC before starting a Glide transfer. The CSV contains only
local performance counters and peer IPs; it does not read files or invitations.
#>
[CmdletBinding()]
param(
    [string]$AdapterName = 'Ethernet',
    [ValidateRange(2, 3600)][int]$DurationSeconds = 180,
    [string]$OutputPath = (Join-Path -Path ([Environment]::GetFolderPath('UserProfile')) -ChildPath ('Downloads\Glide-transfer-rate-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.csv'))
)

$ErrorActionPreference = 'Stop'
$adapter = Get-NetAdapter -Name $AdapterName -ErrorAction Stop
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

$previousNetwork = Get-NetAdapterStatistics -Name $AdapterName
$previousCpu = $null
$previousProcessId = $null
$previousReadBytes = $null
$previousWriteBytes = $null
$previousTime = [System.Diagnostics.Stopwatch]::GetTimestamp()
$fixedVolumes = @(Get-CimInstance Win32_LogicalDisk -Filter 'DriveType = 3' |
    Select-Object -ExpandProperty DeviceID)
$diskCounterPaths = @($fixedVolumes | ForEach-Object {
    "\LogicalDisk($_)\Disk Read Bytes/sec"
    "\LogicalDisk($_)\Disk Write Bytes/sec"
})

for ($sample = 1; $sample -le $DurationSeconds; $sample++) {
    # Get-Counter supplies the sample interval. Fall back to a timed sample if
    # disk counters are unavailable on this installation of Windows.
    $diskSamples = $null
    try {
        if ($diskCounterPaths.Count -gt 0) {
            $diskSamples = (Get-Counter -Counter $diskCounterPaths -MaxSamples 1 -ErrorAction Stop).CounterSamples
        }
    } catch { $diskSamples = $null }
    if ($null -eq $diskSamples) { Start-Sleep -Seconds 1 }
    $now = [System.Diagnostics.Stopwatch]::GetTimestamp()
    $elapsed = [System.Diagnostics.Stopwatch]::GetElapsedTime($previousTime, $now).TotalSeconds
    $network = Get-NetAdapterStatistics -Name $AdapterName
    $glideProcess = Get-Process -Name Glide -ErrorAction SilentlyContinue | Select-Object -First 1
    $processId = if ($null -ne $glideProcess) { $glideProcess.Id } else { $null }
    $cpuSeconds = if ($null -ne $glideProcess) { $glideProcess.CPU } else { $null }
    $cpuCorePercent = if ($null -ne $cpuSeconds -and $processId -eq $previousProcessId -and
        $null -ne $previousCpu) {
        [math]::Round(($cpuSeconds - $previousCpu) / $elapsed * 100, 1)
    } else { $null }
    $processIo = if ($null -ne $processId) {
        Get-CimInstance Win32_Process -Filter "ProcessId = $processId" -ErrorAction SilentlyContinue
    } else { $null }
    $readBytes = if ($null -ne $processIo) { [long]$processIo.ReadTransferCount } else { $null }
    $writeBytes = if ($null -ne $processIo) { [long]$processIo.WriteTransferCount } else { $null }
    $readMiBPerSecond = if ($null -ne $readBytes -and $processId -eq $previousProcessId -and
        $null -ne $previousReadBytes) {
        [math]::Round(($readBytes - $previousReadBytes) / $elapsed / 1MB, 2)
    } else { $null }
    $writeMiBPerSecond = if ($null -ne $writeBytes -and $processId -eq $previousProcessId -and
        $null -ne $previousWriteBytes) {
        [math]::Round(($writeBytes - $previousWriteBytes) / $elapsed / 1MB, 2)
    } else { $null }
    $peer = if ($null -ne $processId) {
        (Get-NetTCPConnection -OwningProcess $processId -State Established -ErrorAction SilentlyContinue |
            Where-Object RemoteAddress -ne '127.0.0.1' |
            Select-Object -First 1 -ExpandProperty RemoteAddress)
    } else { $null }
    $volumeReadRates = @()
    $volumeWriteRates = @()
    foreach ($counter in @($diskSamples)) {
        if ($counter.Path -match 'logicaldisk\(([^)]+)\)\\disk (read|write) bytes/sec$') {
            $entry = '{0}={1}' -f $Matches[1], [math]::Round($counter.CookedValue / 1MB, 2)
            if ($Matches[2] -eq 'read') { $volumeReadRates += $entry }
            else { $volumeWriteRates += $entry }
        }
    }

    [pscustomobject]@{
        UtcTime = (Get-Date).ToUniversalTime().ToString('o')
        Adapter = $AdapterName
        LinkSpeed = (Get-NetAdapter -Name $AdapterName).LinkSpeed
        GlideProcessId = $processId
        PeerAddress = $peer
        SentMiBPerSecond = [math]::Round(($network.SentBytes - $previousNetwork.SentBytes) / $elapsed / 1MB, 2)
        ReceivedMiBPerSecond = [math]::Round(($network.ReceivedBytes - $previousNetwork.ReceivedBytes) / $elapsed / 1MB, 2)
        GlideCpuCorePercent = $cpuCorePercent
        GlideReadIoMiBPerSecond = $readMiBPerSecond
        GlideWrittenIoMiBPerSecond = $writeMiBPerSecond
        GlideWorkingSetMiB = if ($null -ne $glideProcess) { [math]::Round($glideProcess.WorkingSet64 / 1MB, 1) } else { $null }
        DiskReadMiBPerSecondByVolume = $volumeReadRates -join ';'
        DiskWriteMiBPerSecondByVolume = $volumeWriteRates -join ';'
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
