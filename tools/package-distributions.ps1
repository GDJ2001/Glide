<#
.SYNOPSIS
    Consolidates, packages, and cryptographically digests release distributions for all supported platforms.
.DESCRIPTION
    HARD-041 Multi-Platform Release Distribution Packaging Pipeline.
    Manages:
    1. Android Release Candidate artifacts (AAB, APK, v4 sig, R8 mapping).
    2. Windows desktop standalone package (zip).
    3. Linux headless daemon and service distribution (tar.gz).
    4. Zero-CDN embedded Web transfer client distribution (zip).
    5. Unified SHA-256 and SHA-512 cryptographic digest catalogs.
#>

param(
    [switch]$SkipBuild = $false
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path "$ScriptDir\.."
$DistDir = Join-Path $RepoRoot "build\distributions"

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "   Glide HARD-041: Multi-Platform Release Packaging Pipeline    " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# Ensure distribution directories
$AndroidDist = Join-Path $DistDir "android"
$WindowsDist = Join-Path $DistDir "windows"
$LinuxDist = Join-Path $DistDir "linux"
$WebDist = Join-Path $DistDir "web"

foreach ($dir in @($AndroidDist, $WindowsDist, $LinuxDist, $WebDist)) {
    if (-not (Test-Path $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
}

# 1. Android Artifacts
Write-Host "`n[1/5] Collecting Android Release Candidate artifacts..." -ForegroundColor Yellow
$AndroidOutputs = Join-Path $RepoRoot "android\app\build\outputs"
$ApkSrc = Join-Path $AndroidOutputs "apk\release\app-release.apk"
$AabSrc = Join-Path $AndroidOutputs "bundle\release\app-release.aab"
$MapSrc = Join-Path $AndroidOutputs "mapping\release\mapping.txt"
$IdsigSrc = Join-Path $AndroidOutputs "apk\release\app-release.apk.idsig"

if (-not (Test-Path $ApkSrc) -or -not (Test-Path $AabSrc)) {
    if (-not $SkipBuild) {
        Write-Host "  Android release artifacts missing. Running tools/build-and-verify-rc.ps1..." -ForegroundColor Cyan
        & (Join-Path $RepoRoot "tools\build-and-verify-rc.ps1") -InstallOnDevice:$false
    } else {
        throw "Android release artifacts not found in $AndroidOutputs"
    }
}

Copy-Item $ApkSrc (Join-Path $AndroidDist "app-release.apk") -Force
Copy-Item $AabSrc (Join-Path $AndroidDist "app-release.aab") -Force
if (Test-Path $MapSrc) { Copy-Item $MapSrc (Join-Path $AndroidDist "mapping.txt") -Force }
if (Test-Path $IdsigSrc) { Copy-Item $IdsigSrc (Join-Path $AndroidDist "app-release.apk.idsig") -Force }
Write-Host "  Android artifacts staged to $AndroidDist" -ForegroundColor Green

# 2. Windows Package
Write-Host "`n[2/5] Packaging Windows x64 distribution..." -ForegroundColor Yellow
$WinZip = Join-Path $WindowsDist "glide-windows-win-x64.zip"
if (-not (Test-Path $WinZip) -or -not $SkipBuild) {
    & (Join-Path $RepoRoot "desktop\windows\package-windows.ps1")
}
# Remove staging directory
Remove-Item -Recurse -Force (Join-Path $WindowsDist "stage-win-x64") -ErrorAction SilentlyContinue
Write-Host "  Windows package ready: $WinZip" -ForegroundColor Green

# 3. Linux Package
Write-Host "`n[3/5] Packaging Linux x64 distribution..." -ForegroundColor Yellow
$LinuxTar = Join-Path $LinuxDist "glide-linux-linux-x64.tar.gz"
if (-not (Test-Path $LinuxTar) -or -not $SkipBuild) {
    & (Join-Path $RepoRoot "desktop\linux\build-linux-packages.ps1")
}
# Remove staging directory
Remove-Item -Recurse -Force (Join-Path $LinuxDist "stage-linux-x64") -ErrorAction SilentlyContinue
Write-Host "  Linux package ready: $LinuxTar" -ForegroundColor Green

# 4. Web Client Package
Write-Host "`n[4/5] Packaging Web Client distribution..." -ForegroundColor Yellow
$WebZip = Join-Path $WebDist "glide-web-client.zip"
$WebStage = Join-Path $WebDist "web-stage"
if (Test-Path $WebStage) { Remove-Item -Recurse -Force $WebStage }
New-Item -ItemType Directory -Path $WebStage -Force | Out-Null
Copy-Item (Join-Path $RepoRoot "desktop\src\Glide.Transport\WebRtc\Assets\*") $WebStage
if (Test-Path $WebZip) { Remove-Item -Force $WebZip }
Compress-Archive -Path (Join-Path $WebStage "*") -DestinationPath $WebZip -CompressionLevel Optimal
Remove-Item -Recurse -Force $WebStage
Write-Host "  Web client package ready: $WebZip" -ForegroundColor Green

# 5. Cryptographic Digest Calculation & Catalog
Write-Host "`n[5/5] Generating cryptographic digests (SHA-256 / SHA-512)..." -ForegroundColor Yellow

$allArtifacts = Get-ChildItem -Path $DistDir -Recurse -File |
    Where-Object { $_.Extension -in @(".apk", ".aab", ".zip", ".tar.gz", ".gz", ".txt") } |
    Sort-Object FullName

$sha256Lines = [System.Collections.Generic.List[string]]::new()
$sha512Lines = [System.Collections.Generic.List[string]]::new()
$summaryItems = [System.Collections.Generic.List[PSObject]]::new()

foreach ($file in $allArtifacts) {
    $relPath = [System.IO.Path]::GetRelativePath($DistDir, $file.FullName).Replace("\", "/")
    # Skip any previously existing checksum manifest files
    if ($relPath -match "SHA(256|512)SUMS\.txt$") { continue }

    $h256 = (Get-FileHash -Path $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $h512 = (Get-FileHash -Path $file.FullName -Algorithm SHA512).Hash.ToLowerInvariant()

    $sha256Lines.Add("$h256  $relPath")
    $sha512Lines.Add("$h512  $relPath")

    $summaryItems.Add([PSCustomObject]@{
        Platform = ($relPath -split "/")[0]
        Artifact = [System.IO.Path]::GetFileName($relPath)
        SizeBytes = $file.Length
        SizeMB = [math]::Round($file.Length / 1MB, 2)
        SHA256 = $h256
    })
}

$sha256Manifest = Join-Path $DistDir "SHA256SUMS.txt"
$sha512Manifest = Join-Path $DistDir "SHA512SUMS.txt"
Set-Content -Path $sha256Manifest -Value ($sha256Lines -join "`n") -NoNewline
Set-Content -Path $sha512Manifest -Value ($sha512Lines -join "`n") -NoNewline

# Also write to doc/release/
$docReleaseDir = Join-Path $RepoRoot "doc\release"
if (-not (Test-Path $docReleaseDir)) {
    New-Item -ItemType Directory -Path $docReleaseDir -Force | Out-Null
}
Set-Content -Path (Join-Path $docReleaseDir "RELEASE_ARTIFACTS_SHA256.txt") -Value ($sha256Lines -join "`n") -NoNewline
Set-Content -Path (Join-Path $docReleaseDir "RELEASE_ARTIFACTS_SHA512.txt") -Value ($sha512Lines -join "`n") -NoNewline

Write-Host "`nRelease Distribution Summary:" -ForegroundColor Cyan
$summaryItems | Format-Table -AutoSize Platform, Artifact, SizeMB, SHA256

Write-Host "`nManifests written to:" -ForegroundColor Green
Write-Host "  - $sha256Manifest" -ForegroundColor Green
Write-Host "  - $sha512Manifest" -ForegroundColor Green
Write-Host "  - $(Join-Path $docReleaseDir 'RELEASE_ARTIFACTS_SHA256.txt')" -ForegroundColor Green

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "   ALL PLATFORM RELEASE PACKAGES SUCCESSFULLY ASSEMBLED!         " -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Cyan
