# Assemble a relocatable development candidate for hands-on UI tests.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$LinuxPackageDirectory,
    [Parameter(Mandatory)][string]$SourceCommit,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) { throw 'Choose a new output directory; existing handoffs are preserved.' }
$sources = @{
    Linux = (Resolve-Path -LiteralPath $LinuxPackageDirectory).Path
    Windows = Join-Path $repo 'build/distributions/windows'
    Android = Join-Path $repo 'build/distributions/android'
}
$files = @{
    Linux = @('glide_1.0.0_amd64.deb', 'glide-1.0.0-1.x86_64.rpm', 'glide-1.0.0-linux-x64.tar.gz', 'BUILD-MANIFEST.txt', 'SHA256SUMS-linux-x64.txt')
    Windows = @('Glide-Setup-1.0.0-win-x64.exe', 'glide-windows-win-x64.zip', 'BUILD-MANIFEST.txt', 'SHA256SUMS-win-x64.txt')
    Android = @('app-debug.apk', 'app-debug.aab', 'BUILD-MANIFEST.txt', 'SHA256SUMS.txt')
}
foreach ($platform in $sources.Keys) {
    $manifest = Get-Content -LiteralPath (Join-Path $sources[$platform] 'BUILD-MANIFEST.txt') -Raw
    if ($manifest -notmatch "(?m)^source_commit=$([regex]::Escape($SourceCommit))\r?$" ) {
        throw "$platform does not identify the requested source commit."
    }
    foreach ($name in $files[$platform]) {
        if (-not (Test-Path -LiteralPath (Join-Path $sources[$platform] $name) -PathType Leaf)) {
            throw "Missing $platform package: $name"
        }
    }
    foreach ($catalog in Get-ChildItem -LiteralPath $sources[$platform] -Filter 'SHA256SUMS*.txt') {
        foreach ($line in Get-Content -LiteralPath $catalog.FullName) {
            if (-not $line.Trim()) { continue }
            if ($line -notmatch '^([a-fA-F0-9]{64})\s+\*?([^/\\]+)$') { throw "Invalid checksum entry in $platform" }
            $expected = $Matches[1]; $name = $Matches[2]
            if ((Get-FileHash -LiteralPath (Join-Path $sources[$platform] $name)).Hash -ne $expected) {
                throw "Package hash mismatch: $platform/$name"
            }
        }
    }
}
New-Item -ItemType Directory -Path $output | Out-Null
foreach ($platform in $sources.Keys) {
    $destination = Join-Path $output "Installers/$platform"
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    foreach ($name in $files[$platform]) {
        Copy-Item -LiteralPath (Join-Path $sources[$platform] $name) -Destination $destination
    }
}
& (Join-Path $PSScriptRoot 'new-manual-ui-test-pack.ps1') -OutputRoot (Join-Path $output 'TestData')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'verify-manual-ui-test-pack.ps1') -Destination $output
Copy-Item -LiteralPath (Join-Path $repo 'doc/testing/MANUAL_UI_PHYSICAL_TRIAD_RUNBOOK.md') -Destination $output
Copy-Item -LiteralPath (Join-Path $repo 'doc/testing/MANUAL_UI_TRIAD_RUN_SHEET.csv') -Destination $output
@"
# Glide manual UI handoff

Candidate source: $SourceCommit. Development packages: unsigned Windows/Linux;
Android uses the local debug signing key. These packages have build evidence;
the physical UI, accessibility, and installation lifecycle gates remain open.

Copy this entire folder to any location on the other PC. No original drive letter
or repository checkout is required. PowerShell 7 is needed only for verification.

1. Run: pwsh -NoProfile -File ./Verify-Copy.ps1
2. Windows: open Installers/Windows/Glide-Setup-1.0.0-win-x64.exe.
   Updating the copied build folder alone does not update the installed app.
   Uninstall through Windows Installed apps when running the lifecycle case.
3. Ubuntu 26 VM: copy Installers/Linux/glide_1.0.0_amd64.deb into Downloads.
   In a terminal in that directory: sudo apt install ./glide_1.0.0_amd64.deb
   Open Glide from the Applications menu. Uninstall: sudo apt remove glide.
   Preserve personal receive folders and app state during ordinary upgrades.
4. Android: copy Installers/Android/app-debug.apk to the phone and open it.
   The AAB is a build artifact, not a file to tap-install. If an existing APK uses
   a different signing key, stop and record the installer error before removing data.
5. Keep the extracted TestData/Glide-Manual-UI-Test-Pack-v1 master untouched.
   Verify it with: pwsh -File ./verify-manual-ui-test-pack.ps1 -PackRoot ./TestData/Glide-Manual-UI-Test-Pack-v1
6. Follow MANUAL_UI_PHYSICAL_TRIAD_RUNBOOK.md and fill the CSV. The VM currently
   uses NAT; configure Bridged Adapter for cross-PC/phone LAN testing as described
   in the runbook. Keep that network setup separate from app installation.
7. For a received Quick set: pwsh -File ./verify-manual-ui-test-pack.ps1 -PackRoot ./TestData/Glide-Manual-UI-Test-Pack-v1 -ReceivedDir <case-folder> -Set Quick

Use Glide's visible UI and explicit consent for scored transfers. Save sanitized
screenshots, endpoint versions, and hash results in a new folder for every case.
No Apple artifacts are included in this Android/Windows/Linux testing handoff.
"@ | Set-Content -LiteralPath (Join-Path $output 'START-HERE.md') -Encoding utf8
@'
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$manifest = Get-Content -LiteralPath (Join-Path $root 'HANDOFF.json') -Raw | ConvertFrom-Json
foreach ($entry in $manifest.files) {
    $path = [IO.Path]::GetFullPath((Join-Path $root $entry.path))
    if (-not $path.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe manifest path' }
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing: $($entry.path)" }
    if ((Get-FileHash -LiteralPath $path).Hash -ne $entry.sha256) { throw "Hash mismatch: $($entry.path)" }
}
Write-Host "Verified $($manifest.files.Count) handoff files from $($manifest.sourceCommit)."
'@ | Set-Content -LiteralPath (Join-Path $output 'Verify-Copy.ps1') -Encoding utf8
$entries = @(Get-ChildItem -LiteralPath $output -Recurse -File | ForEach-Object {
    [pscustomobject]@{ path = [IO.Path]::GetRelativePath($output, $_.FullName); sha256 = (Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant() }
})
[pscustomobject]@{ sourceCommit = $SourceCommit; generatedUtc = (Get-Date).ToUniversalTime().ToString('o'); files = $entries } |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'HANDOFF.json') -Encoding utf8
& (Join-Path $output 'Verify-Copy.ps1')
Write-Host "Copy this folder: $output"
