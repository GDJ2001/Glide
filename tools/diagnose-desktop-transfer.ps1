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
$previousTime = [System.Diagnostics.Stopwatch]::GetTimestamp()

for ($sample = 1; $sample -le $DurationSeconds; $sample++) {
    Start-Sleep -Seconds 1
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
    $peer = if ($null -ne $processId) {
        (Get-NetTCPConnection -OwningProcess $processId -State Established -ErrorAction SilentlyContinue |
            Where-Object RemoteAddress -ne '127.0.0.1' |
            Select-Object -First 1 -ExpandProperty RemoteAddress)
    } else { $null }

    [pscustomobject]@{
        UtcTime = (Get-Date).ToUniversalTime().ToString('o')
        Adapter = $AdapterName
        LinkSpeed = (Get-NetAdapter -Name $AdapterName).LinkSpeed
        GlideProcessId = $processId
        PeerAddress = $peer
        SentMiBPerSecond = [math]::Round(($network.SentBytes - $previousNetwork.SentBytes) / $elapsed / 1MB, 2)
        ReceivedMiBPerSecond = [math]::Round(($network.ReceivedBytes - $previousNetwork.ReceivedBytes) / $elapsed / 1MB, 2)
        GlideCpuCorePercent = $cpuCorePercent
        OutboundPacketErrors = $network.OutboundPacketErrors
        ReceivedPacketErrors = $network.ReceivedPacketErrors
    } | Export-Csv -LiteralPath $OutputPath -NoTypeInformation -Append

    $previousNetwork = $network
    $previousCpu = $cpuSeconds
    $previousProcessId = $processId
    $previousTime = $now
}

Write-Host "Transfer rate samples: $OutputPath"
