# Generate synthetic, deterministic files for hands-on Glide UI transfer testing.
# Outputs under build/ are disposable. The script never deletes an existing pack.
[CmdletBinding()]
param(
    [string]$OutputRoot = "",
    [switch]$IncludeOneGiB
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $OutputRoot) {
    $OutputRoot = Join-Path $repoRoot 'build\test-packs\glide-manual-ui-v1'
}
$OutputRoot = [System.IO.Path]::GetFullPath($OutputRoot)
if (Test-Path -LiteralPath $OutputRoot) {
    throw "Pack already exists at $OutputRoot. Choose a different -OutputRoot; existing test data is preserved."
}

$payloadRoot = Join-Path $OutputRoot 'Glide-Manual-UI-Test-Pack-v1'
New-Item -ItemType Directory -Path $payloadRoot -Force | Out-Null

function Write-Utf8File {
    param([string]$RelativePath, [string]$Content)
    $path = Join-Path $payloadRoot $RelativePath
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    [System.IO.File]::WriteAllText($path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function Write-PatternFile {
    param([string]$RelativePath, [long]$Length, [int]$Seed)
    $path = Join-Path $payloadRoot $RelativePath
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    $buffer = [byte[]]::new(65536)
    for ($i = 0; $i -lt $buffer.Length; $i++) {
        $buffer[$i] = [byte](($i + $Seed) % 251)
    }
    $stream = [System.IO.File]::Open($path, [System.IO.FileMode]::CreateNew)
    try {
        $remaining = $Length
        while ($remaining -gt 0) {
            $count = [int][Math]::Min($remaining, [long]$buffer.Length)
            $stream.Write($buffer, 0, $count)
            $remaining -= $count
        }
        $stream.Flush($true)
    }
    finally {
        $stream.Dispose()
    }
}

Write-Utf8File 'Quick/01-read-me.txt' "Glide synthetic test file. No private data.\n"
[System.IO.File]::WriteAllBytes((Join-Path $payloadRoot 'Quick/02-zero-byte.bin'), [byte[]]::new(0))
Write-Utf8File 'Quick/03-සිංහල-தமிழ்.txt' "Unicode filename and content: සිංහල / தமிழ் / café.\n"
Write-Utf8File 'Quick/04-spaces and (punctuation).txt' "A filename with spaces and safe punctuation.\n"
Write-Utf8File 'FolderTree/read-me.txt' "Select the whole FolderTree folder in Glide's Add folder control.\n"
Write-Utf8File 'FolderTree/notes/2026/summary.txt' "Nested folder test.\n"
Write-Utf8File 'FolderTree/one/shared-name.txt' "First same-named file.\n"
Write-Utf8File 'FolderTree/two/shared-name.txt' "Second same-named file with different bytes.\n"

for ($n = 1; $n -le 35; $n++) {
    $relative = 'Batch35/{0:D2}-item.bin' -f $n
    Write-PatternFile $relative (1024 + 37 * $n) $n
}
Write-PatternFile 'Progress/10MiB.bin' (10L * 1024 * 1024) 90
Write-PatternFile 'Progress/100MiB.bin' (100L * 1024 * 1024) 99
if ($IncludeOneGiB) {
    Write-PatternFile 'Progress/1GiB-optional.bin' (1L * 1024 * 1024 * 1024) 101
}

$files = @(Get-ChildItem -LiteralPath $payloadRoot -File -Recurse | Sort-Object FullName)
$entries = foreach ($file in $files) {
    $relative = [System.IO.Path]::GetRelativePath($payloadRoot, $file.FullName).Replace('\', '/')
    [pscustomobject]@{
        relativePath = $relative
        sizeBytes = [long]$file.Length
        sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
$commit = (git -C $repoRoot rev-parse HEAD).Trim()
$manifest = [pscustomobject]@{
    schema = 'glide-manual-ui-test-pack-v1'
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    sourceCommitAtGeneration = $commit
    fileCount = $entries.Count
    totalPayloadBytes = [long](($entries | Measure-Object -Property sizeBytes -Sum).Sum)
    optionalOneGiB = [bool]$IncludeOneGiB
    files = @($entries)
}
$manifestPath = Join-Path $payloadRoot 'MANIFEST.json'
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding utf8

$sumLines = foreach ($entry in $entries) { "{0}  {1}" -f $entry.sha256, $entry.relativePath }
[System.IO.File]::WriteAllLines((Join-Path $payloadRoot 'SHA256SUMS.txt'), [string[]]$sumLines, [System.Text.UTF8Encoding]::new($false))

Write-Utf8File 'START-HERE.txt' @'
Glide manual UI test pack v1. Synthetic files only.
Do not send MANIFEST.json, SHA256SUMS.txt, or this START-HERE.txt as test payloads.

Quick: repeated smoke tests (4 files, including zero-byte and Unicode names).
FolderTree: select the folder with Add folder; watch for safe handling of duplicate leaf names.
Batch35: select all 35 files to cross the 32-item offer-page boundary.
Progress: 10MiB for cancellation and visible progress; 100MiB for larger transfers.

Keep an untouched master copy on each machine. Create separate received-file folders for each run.
Use the companion MANUAL_UI_PHYSICAL_TRIAD_RUNBOOK.md in doc/testing for the sequence.
From this directory on Ubuntu, run: sha256sum -c SHA256SUMS.txt
'@

$zipPath = Join-Path $OutputRoot 'Glide-Manual-UI-Test-Pack-v1.zip'
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $payloadRoot,
    $zipPath,
    [System.IO.Compression.CompressionLevel]::Optimal,
    $true)
$zipHash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText(
    (Join-Path $OutputRoot 'Glide-Manual-UI-Test-Pack-v1.zip.sha256'),
    "$zipHash  Glide-Manual-UI-Test-Pack-v1.zip`n",
    [System.Text.UTF8Encoding]::new($false))

Write-Host "Pack: $zipPath"
Write-Host "ZIP SHA-256: $zipHash"
Write-Host "Payload files: $($entries.Count); bytes: $($manifest.totalPayloadBytes)"
Write-Host "Manifest: $manifestPath"
