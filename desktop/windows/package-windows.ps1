<#
.SYNOPSIS
Builds the signed-ready Glide Windows installer from a maintained NSIS definition.

.DESCRIPTION
Publishes the self-contained WPF application, creates a portable archive, and invokes NSIS.
Code signing is intentionally a separate release step: its certificate and credentials must never
be stored in this repository.
#>

[CmdletBinding()]
param(
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Release",
    [string]$Version = "1.0.0",
    [string]$Runtime = "win-x64",
    [string]$NsisCompiler = "",
    [string]$UpdateFeedUrl = "",
    [string]$UpdatePublicKey = "",
    [switch]$SkipInstaller
)

$ErrorActionPreference = "Stop"
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..")).Path
$project = Join-Path $scriptRoot "Glide.Windows.UI\Glide.Windows.UI.csproj"
$icon = Join-Path $scriptRoot "Glide.Windows.UI\glide.ico"
$installerScript = Join-Path $scriptRoot "installer\Glide.nsi"
$outputRoot = Join-Path $repoRoot "build\distributions\windows"
$stageDirectory = Join-Path $outputRoot "stage-$Runtime"
$portableArchive = Join-Path $outputRoot "glide-windows-$Runtime.zip"
$installerOutput = Join-Path $outputRoot "Glide-Setup-$Version-$Runtime.exe"

New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
if (Test-Path $stageDirectory) {
    Remove-Item -LiteralPath $stageDirectory -Recurse -Force
}

Write-Host "Publishing Glide $Version for $Runtime..." -ForegroundColor Cyan
dotnet publish $project `
    --configuration $Configuration `
    --runtime $Runtime `
    --self-contained true `
    /p:PublishSingleFile=true `
    /p:IncludeNativeLibrariesForSelfExtract=true `
    /p:DebugType=None `
    /p:DebugSymbols=false `
    /p:Version=$Version `
    /p:GlideUpdateFeedUrl=$UpdateFeedUrl `
    /p:GlideUpdatePublicKey=$UpdatePublicKey `
    --output $stageDirectory
if ($LASTEXITCODE -ne 0) {
    throw "Windows publish failed."
}

if (-not (Test-Path -LiteralPath $icon)) {
    throw "The installer icon is missing: $icon"
}
Copy-Item -LiteralPath $icon -Destination (Join-Path $stageDirectory "glide.ico") -Force

Set-Content -LiteralPath (Join-Path $stageDirectory "glide.cmd") -Encoding ASCII -Value "@echo off`r`n`"%~dp0Glide.exe`" %*"

if (Test-Path $portableArchive) {
    Remove-Item -LiteralPath $portableArchive -Force
}
Compress-Archive -Path (Join-Path $stageDirectory "*") -DestinationPath $portableArchive -CompressionLevel Optimal

if ($SkipInstaller) {
    Write-Host "Portable archive created: $portableArchive" -ForegroundColor Green
    exit 0
}

if ([string]::IsNullOrWhiteSpace($NsisCompiler)) {
    $candidates = @(
        (Get-Command makensis.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
        "${env:ProgramFiles(x86)}\NSIS\makensis.exe",
        "$env:ProgramFiles\NSIS\makensis.exe"
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    $NsisCompiler = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}

if ([string]::IsNullOrWhiteSpace($NsisCompiler) -or -not (Test-Path -LiteralPath $NsisCompiler)) {
    throw "NSIS 3.12+ is required to create the installer. Install it, or pass -NsisCompiler with the makensis.exe path."
}

if (-not (Test-Path -LiteralPath $installerScript)) {
    throw "Installer definition is missing: $installerScript"
}

Write-Host "Building NSIS installer..." -ForegroundColor Cyan
& $NsisCompiler "/DAPP_VERSION=$Version" "/DSTAGE_DIR=$stageDirectory" "/DOUTPUT_FILE=$installerOutput" $installerScript
if ($LASTEXITCODE -ne 0) {
    throw "NSIS installer build failed."
}

$portableHash = (Get-FileHash -Algorithm SHA256 -Path $portableArchive).Hash.ToLowerInvariant()
$installerHash = (Get-FileHash -Algorithm SHA256 -Path $installerOutput).Hash.ToLowerInvariant()
$shaFile = Join-Path $outputRoot "SHA256SUMS-$Runtime.txt"
@(
    "$portableHash  $(Split-Path -Leaf $portableArchive)",
    "$installerHash  $(Split-Path -Leaf $installerOutput)"
) | Set-Content -Path $shaFile -Encoding utf8

$commit = (git -C $repoRoot rev-parse HEAD).Trim()
$dotnetVersion = (dotnet --version).Trim()
$nsisVersionOutput = (& $NsisCompiler -VERSION 2>&1)
$nsisVersion = if ($LASTEXITCODE -eq 0) { $nsisVersionOutput.Trim() } else { "NSIS 3.12+" }

$manifestFile = Join-Path $outputRoot "BUILD-MANIFEST.txt"
@"
source_commit=$commit
build_utc=$((Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'))
dotnet_sdk=$dotnetVersion
nsis_version=$nsisVersion
package_version=$Version
runtime=$Runtime
installer_artifact=$(Split-Path -Leaf $installerOutput)
portable_artifact=$(Split-Path -Leaf $portableArchive)
signing_status=unsigned_development
installability=development_installable_unsigned
"@ | Set-Content -Path $manifestFile -Encoding utf8

Write-Host "Portable archive: $portableArchive" -ForegroundColor Green
Write-Host "Installer: $installerOutput" -ForegroundColor Green
Write-Host "Manifest: $manifestFile" -ForegroundColor Green
Write-Host "Sign the installer in the protected release pipeline before publishing it." -ForegroundColor Yellow
