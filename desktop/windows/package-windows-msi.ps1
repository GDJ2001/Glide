<#
.SYNOPSIS
Builds the WiX v5 MSI package for the Glide Windows desktop companion.

.DESCRIPTION
Compiles the WiX v5 definition into a per-machine Windows Installer (.msi) package,
validates internal consistency (ICE rules), computes SHA-256 checksums, and updates
build manifests.

Suitable for Microsoft Store Win32 direct ingestion and enterprise deployment.
Maintained alongside desktop/windows/package-windows.ps1 (NSIS).
#>

[CmdletBinding()]
param(
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Release",
    [string]$Version = "1.0.0",
    [string]$Runtime = "win-x64",
    [string]$StageDirectory = "",
    [string]$OutputDirectory = "",
    [string]$WixCompiler = "",
    [switch]$SkipValidation
)

$ErrorActionPreference = "Stop"
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..")).Path
. (Join-Path $repoRoot 'tools\resolve-dotnet.ps1')
$null = Enable-GlideDotnetSdk
$project = Join-Path $scriptRoot "Glide.Windows.UI\Glide.Windows.UI.csproj"
$icon = Join-Path $scriptRoot "Glide.Windows.UI\glide.ico"
$installerScript = Join-Path $scriptRoot "installer\Glide.wxs"

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repoRoot "build\distributions\windows"
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$OutputDirectory = (Resolve-Path $OutputDirectory).Path

if ([string]::IsNullOrWhiteSpace($StageDirectory)) {
    $StageDirectory = Join-Path $OutputDirectory "stage-$Runtime"
}

$msiOutput = Join-Path $OutputDirectory "Glide-Setup-$Version-$Runtime.msi"

# 1. Resolve WiX v5 CLI compiler
if ([string]::IsNullOrWhiteSpace($WixCompiler)) {
    $candidates = @(
        (Get-Command wix.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
        (Join-Path $env:USERPROFILE ".dotnet\tools\wix.exe"),
        "${env:ProgramFiles}\WiX Toolset v5\bin\wix.exe"
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    $WixCompiler = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}

if ([string]::IsNullOrWhiteSpace($WixCompiler) -or -not (Test-Path -LiteralPath $WixCompiler)) {
    throw "WiX Toolset v5 (wix.exe) is required to create the MSI installer. Install it via 'dotnet tool install -g wix', or pass -WixCompiler."
}

# 2. Ensure Staged Binaries Exist
if (-not (Test-Path -LiteralPath $StageDirectory) -or -not (Test-Path -LiteralPath (Join-Path $StageDirectory "Glide.exe"))) {
    Write-Host "Pre-staged binaries not found at '$StageDirectory'. Attempting publish..." -ForegroundColor Yellow
    New-Item -ItemType Directory -Force -Path $StageDirectory | Out-Null
    dotnet publish $project `
        --configuration $Configuration `
        --runtime $Runtime `
        --self-contained true `
        /p:PublishSingleFile=true `
        /p:IncludeNativeLibrariesForSelfExtract=true `
        /p:DebugType=None `
        /p:DebugSymbols=false `
        /p:Version=$Version `
        --output $StageDirectory
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to publish Glide Windows payload to '$StageDirectory'."
    }
}
$StageDirectory = (Resolve-Path $StageDirectory).Path

# Ensure required assets exist in staging
$stagedIcon = Join-Path $StageDirectory "glide.ico"
if (-not (Test-Path -LiteralPath $stagedIcon) -and (Test-Path -LiteralPath $icon)) {
    Copy-Item -LiteralPath $icon -Destination $stagedIcon -Force
}

$stagedCmd = Join-Path $StageDirectory "glide.cmd"
if (-not (Test-Path -LiteralPath $stagedCmd)) {
    Set-Content -LiteralPath $stagedCmd -Encoding ASCII -Value "@echo off`r`n`"%~dp0Glide.exe`" %*"
}

if (-not (Test-Path -LiteralPath $installerScript)) {
    throw "WiX installer definition is missing: $installerScript"
}

# 3. Build WiX v5 MSI
Write-Host "Building WiX v5 MSI package: $msiOutput..." -ForegroundColor Cyan
& $WixCompiler build -arch x64 `
    -d "Version=$Version" `
    -d "StageDir=$StageDirectory" `
    -o "$msiOutput" `
    "$installerScript"

if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $msiOutput)) {
    throw "WiX MSI build failed."
}

Write-Host "MSI package created successfully: $msiOutput" -ForegroundColor Green

# 4. Internal Consistency Validation (ICE)
if (-not $SkipValidation) {
    Write-Host "Validating MSI database consistency (wix msi validate)..." -ForegroundColor Cyan
    & $WixCompiler msi validate "$msiOutput"
    if ($LASTEXITCODE -ne 0) {
        throw "MSI validation failed ICE checks."
    }
    Write-Host "MSI validation passed with zero ICE errors." -ForegroundColor Green
}

# 5. Hashing & Checksums
$msiHash = (Get-FileHash -Algorithm SHA256 -Path $msiOutput).Hash.ToLowerInvariant()
$shaFile = Join-Path $OutputDirectory "SHA256SUMS-$Runtime.txt"

$hashes = [System.Collections.Generic.Dictionary[string, string]]::new([System.StringComparer]::OrdinalIgnoreCase)
if (Test-Path -LiteralPath $shaFile) {
    Get-Content $shaFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -match '^([a-fA-F0-9]{64})\s+(.+)$') {
            $hashes[$Matches[2].Trim()] = $Matches[1].ToLowerInvariant()
        }
    }
}

# Also audit sibling artifacts if present
$exeInstaller = Join-Path $OutputDirectory "Glide-Setup-$Version-$Runtime.exe"
if (Test-Path -LiteralPath $exeInstaller) {
    $hashes[(Split-Path -Leaf $exeInstaller)] = (Get-FileHash -Algorithm SHA256 -Path $exeInstaller).Hash.ToLowerInvariant()
}
$zipArchive = Join-Path $OutputDirectory "glide-windows-$Runtime.zip"
if (Test-Path -LiteralPath $zipArchive) {
    $hashes[(Split-Path -Leaf $zipArchive)] = (Get-FileHash -Algorithm SHA256 -Path $zipArchive).Hash.ToLowerInvariant()
}
$hashes[(Split-Path -Leaf $msiOutput)] = $msiHash

$sortedLines = $hashes.Keys | Sort-Object | ForEach-Object { "$($hashes[$_])  $_" }
$sortedLines | Set-Content -Path $shaFile -Encoding utf8

# 6. Update Build Manifest
$manifestFile = Join-Path $OutputDirectory "BUILD-MANIFEST.txt"
$wixVersionOutput = (& $WixCompiler --version 2>&1)
$wixVersion = if ($LASTEXITCODE -eq 0) { $wixVersionOutput.Trim() } else { "WiX v5.0.2" }

$manifestEntries = [ordered]@{}
if (Test-Path -LiteralPath $manifestFile) {
    Get-Content $manifestFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -match '^([^=]+)=(.*)$') {
            $manifestEntries[$Matches[1].Trim()] = $Matches[2].Trim()
        }
    }
}

# Ensure core manifest values
if (-not $manifestEntries.Contains("source_commit")) {
    $manifestEntries["source_commit"] = (git -C $repoRoot rev-parse HEAD 2>$null).Trim()
}
if (-not $manifestEntries.Contains("package_version")) {
    $manifestEntries["package_version"] = $Version
}
if (-not $manifestEntries.Contains("runtime")) {
    $manifestEntries["runtime"] = $Runtime
}
$manifestEntries["wix_version"] = $wixVersion
$manifestEntries["msi_artifact"] = Split-Path -Leaf $msiOutput
if (Test-Path -LiteralPath $exeInstaller) {
    $manifestEntries["installer_artifact"] = Split-Path -Leaf $exeInstaller
}
if (Test-Path -LiteralPath $zipArchive) {
    $manifestEntries["portable_artifact"] = Split-Path -Leaf $zipArchive
}
if (-not $manifestEntries.Contains("signing_status")) {
    $manifestEntries["signing_status"] = "unsigned_development"
}
if (-not $manifestEntries.Contains("installability")) {
    $manifestEntries["installability"] = "development_installable_unsigned"
}

$manifestContent = $manifestEntries.Keys | ForEach-Object { "$_=$($manifestEntries[$_])" }
$manifestContent | Set-Content -Path $manifestFile -Encoding utf8

Write-Host "`nWindows MSI Packaging Complete:" -ForegroundColor Green
Write-Host "  MSI Package: $msiOutput" -ForegroundColor Green
Write-Host "  SHA-256:     $msiHash" -ForegroundColor DarkGray
Write-Host "  Checksums:   $shaFile" -ForegroundColor Green
Write-Host "  Manifest:    $manifestFile" -ForegroundColor Green
