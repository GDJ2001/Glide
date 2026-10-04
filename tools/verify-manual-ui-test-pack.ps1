# Verify the synthetic master pack, or files received through Glide's UI.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$PackRoot,
    [string]$ReceivedDir = '',
    [ValidateSet('Quick', 'FolderTree', 'Batch35', 'Progress10', 'Progress100', 'Bulk')]
    [string]$Set = 'Quick'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$pack = (Resolve-Path -LiteralPath $PackRoot).Path
$manifestPath = Join-Path $pack 'MANIFEST.json'
if (-not (Test-Path -LiteralPath $manifestPath)) {
    throw "MANIFEST.json is missing in $pack"
}
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.schema -ne 'glide-manual-ui-test-pack-v1') {
    throw 'Unknown test-pack manifest schema.'
}

$selected = @($manifest.files | Where-Object {
    $path = [string]$_.relativePath
    switch ($Set) {
        'Quick' { $path.StartsWith('Quick/') }
        'FolderTree' { $path.StartsWith('FolderTree/') }
        'Batch35' { $path.StartsWith('Batch35/') }
        'Progress10' { $path -eq 'Progress/10MiB.bin' }
        'Progress100' { $path -eq 'Progress/100MiB.bin' }
        'Bulk' { $path.StartsWith('Batch35/') -or $path.StartsWith('FolderTree/') -or $path -eq 'Progress/100MiB.bin' }
    }
})
if ($selected.Count -eq 0) { throw "No files selected for set $Set." }

$errors = [System.Collections.Generic.List[string]]::new()
if (-not $ReceivedDir) {
    foreach ($entry in $manifest.files) {
        $path = Join-Path $pack ([string]$entry.relativePath).Replace('/', [System.IO.Path]::DirectorySeparatorChar)
        if (-not (Test-Path -LiteralPath $path)) {
            $errors.Add("Missing source file: $($entry.relativePath)")
            continue
        }
        $item = Get-Item -LiteralPath $path
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($item.Length -ne [long]$entry.sizeBytes -or $hash -ne [string]$entry.sha256) {
            $errors.Add("Source mismatch: $($entry.relativePath)")
        }
    }
    $scope = "master pack ($($manifest.files.Count) files)"
}
else {
    $received = (Resolve-Path -LiteralPath $ReceivedDir).Path
    $actualFiles = @(Get-ChildItem -LiteralPath $received -Recurse -File)
    if ($actualFiles.Count -ne $selected.Count) {
        $errors.Add("File count differs: expected $($selected.Count), found $($actualFiles.Count)")
    }

    $actualHashes = @{}
    foreach ($file in $actualFiles) {
        $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        if (-not $actualHashes.ContainsKey($hash)) { $actualHashes[$hash] = 0 }
        $actualHashes[$hash]++
    }
    foreach ($entry in $selected) {
        $hash = [string]$entry.sha256
        if (-not $actualHashes.ContainsKey($hash) -or $actualHashes[$hash] -lt 1) {
            $errors.Add("Missing received bytes for: $($entry.relativePath)")
        }
        else { $actualHashes[$hash]-- }
    }
    foreach ($hash in $actualHashes.Keys) {
        if ($actualHashes[$hash] -gt 0) { $errors.Add("Unexpected received SHA-256: $hash") }
    }
    $scope = "$Set receive set ($($selected.Count) files)"
}

if ($errors.Count -gt 0) {
    $errors | ForEach-Object { Write-Error $_ }
    exit 1
}
Write-Host "PASS: SHA-256 and file count match for $scope."
