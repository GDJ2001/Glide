# HARD-017 deterministic native interoperability corpus generator.
# Generates >32 files so every success run must cross the desktop offer-page boundary.
# Generated payloads belong under build/ and are never evidence by themselves.

[CmdletBinding()]
param(
    [string]$OutputDir = "",
    [int]$SmallFileCount = 32,
    [int]$LargeMiB = 100,
    [switch]$Force
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $OutputDir) {
    $OutputDir = Join-Path $RepoRoot "build\hard-017\corpus"
}

if ($SmallFileCount -lt 32) {
    throw "HARD-017 requires at least 32 small files so the full corpus exceeds one 32-item offer page."
}
if ($LargeMiB -lt 100) {
    throw "HARD-017 large payload must be at least 100 MiB."
}

if (Test-Path $OutputDir) {
    if (-not $Force) {
        throw "Corpus directory already exists: $OutputDir. Use -Force to regenerate."
    }
    Remove-Item -Recurse -Force $OutputDir
}
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

function Write-PatternFile {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][long]$SizeBytes,
        [Parameter(Mandatory=$true)][int]$Seed
    )

    $bufferSize = 64 * 1024
    $buffer = [byte[]]::new($bufferSize)
    for ($i = 0; $i -lt $buffer.Length; $i++) {
        $buffer[$i] = [byte](($i + $Seed) % 251)
    }

    $stream = [System.IO.File]::Open(
        $Path,
        [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::None)
    try {
        $remaining = $SizeBytes
        while ($remaining -gt 0) {
            $count = [int][Math]::Min([long]$buffer.Length, $remaining)
            $stream.Write($buffer, 0, $count)
            $remaining -= $count
        }
        $stream.Flush($true)
    }
    finally {
        $stream.Dispose()
    }
}

$emptyPath = Join-Path $OutputDir "00-empty.bin"
[System.IO.File]::WriteAllBytes($emptyPath, [byte[]]::new(0))

for ($n = 1; $n -le $SmallFileCount; $n++) {
    $path = Join-Path $OutputDir ("{0:D2}-small-{1:D2}.bin" -f $n, $n)
    $size = 1024 + ($n * 37)
    Write-PatternFile -Path $path -SizeBytes $size -Seed $n
}

$mediumPath = Join-Path $OutputDir "90-medium-10mib.bin"
Write-PatternFile -Path $mediumPath -SizeBytes (10L * 1024 * 1024) -Seed 90

$largePath = Join-Path $OutputDir ("99-large-{0}mib.bin" -f $LargeMiB)
Write-PatternFile -Path $largePath -SizeBytes ([long]$LargeMiB * 1024 * 1024) -Seed 99

$items = @()
foreach ($file in (Get-ChildItem -File $OutputDir | Sort-Object Name)) {
    if ($file.Name -eq "corpus-manifest.json") { continue }
    $items += [pscustomobject]@{
        name = $file.Name
        relativePath = $file.Name
        sizeBytes = [long]$file.Length
        sha256 = (Get-FileHash $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

$itemCount = $items.Count
$totalBytes = [long](($items | Measure-Object -Property sizeBytes -Sum).Sum)
$pageCount = [int][Math]::Ceiling($itemCount / 32.0)

if ($itemCount -le 32 -or $pageCount -lt 2) {
    throw "Generated corpus does not force offer pagination."
}
if (-not ($items | Where-Object { $_.sizeBytes -eq 0 })) {
    throw "Generated corpus is missing the zero-byte file."
}
if ((($items | Measure-Object -Property sizeBytes -Maximum).Maximum) -lt 100L * 1024 * 1024) {
    throw "Generated corpus is missing the required >=100 MiB large file."
}

$manifest = [pscustomobject]@{
    schema = "glide-hard017-corpus-v1"
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    itemCount = $itemCount
    totalBytes = $totalBytes
    offerPageSize = 32
    offerPageCount = $pageCount
    requirements = [pscustomobject]@{
        includesZeroByte = $true
        includesLargeAtLeast100MiB = $true
        forcesMultiPageOffer = $true
        deterministicPattern = $true
    }
    items = $items
}

$manifestPath = Join-Path $OutputDir "corpus-manifest.json"
$manifest | ConvertTo-Json -Depth 8 | Set-Content -Path $manifestPath -Encoding utf8

Write-Host "HARD-017 corpus generated: $OutputDir"
Write-Host "Items: $itemCount"
Write-Host "Offer pages: $pageCount"
Write-Host "Total bytes: $totalBytes"
Write-Host "Manifest: $manifestPath"
