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
    [switch]$SkipInstaller,
    [switch]$SkipPublish
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

if (-not $SkipPublish) {
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
} else {
    Write-Host "Reusing pre-staged binaries from '$stageDirectory' (-SkipPublish specified)..." -ForegroundColor Yellow
    if (-not (Test-Path -LiteralPath $stageDirectory) -or -not (Test-Path -LiteralPath (Join-Path $stageDirectory "Glide.exe"))) {
        throw "Cannot skip publish: staged binaries not found at '$stageDirectory'."
    }
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
$msiInstaller = Join-Path $outputRoot "Glide-Setup-$Version-$Runtime.msi"

$shaFile = Join-Path $outputRoot "SHA256SUMS-$Runtime.txt"
$shaEntries = @(
    "$installerHash  $(Split-Path -Leaf $installerOutput)",
    "$portableHash  $(Split-Path -Leaf $portableArchive)"
)
if (Test-Path -LiteralPath $msiInstaller) {
    $msiHash = (Get-FileHash -Algorithm SHA256 -Path $msiInstaller).Hash.ToLowerInvariant()
    $shaEntries += "$msiHash  $(Split-Path -Leaf $msiInstaller)"
}
$shaEntries | Sort-Object | Set-Content -Path $shaFile -Encoding utf8

$commit = (git -C $repoRoot rev-parse HEAD).Trim()
$dotnetVersion = (dotnet --version).Trim()
$nsisVersionOutput = (& $NsisCompiler -VERSION 2>&1)
$nsisVersion = if ($LASTEXITCODE -eq 0) { $nsisVersionOutput.Trim() } else { "NSIS 3.12+" }

$manifestFile = Join-Path $outputRoot "BUILD-MANIFEST.txt"
$manifestLines = [System.Collections.Generic.List[string]]::new()
$manifestLines.Add("source_commit=$commit")
$manifestLines.Add("build_utc=$((Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'))")
$manifestLines.Add("dotnet_sdk=$dotnetVersion")
$manifestLines.Add("nsis_version=$nsisVersion")
$manifestLines.Add("package_version=$Version")
$manifestLines.Add("runtime=$Runtime")
$manifestLines.Add("installer_artifact=$(Split-Path -Leaf $installerOutput)")
if (Test-Path -LiteralPath $msiInstaller) {
    $manifestLines.Add("msi_artifact=$(Split-Path -Leaf $msiInstaller)")
}
$manifestLines.Add("portable_artifact=$(Split-Path -Leaf $portableArchive)")
$manifestLines.Add("signing_status=unsigned_development")
$manifestLines.Add("installability=development_installable_unsigned")
$manifestLines | Set-Content -Path $manifestFile -Encoding utf8

Write-Host "Portable archive: $portableArchive" -ForegroundColor Green
Write-Host "Installer: $installerOutput" -ForegroundColor Green
Write-Host "Manifest: $manifestFile" -ForegroundColor Green
Write-Host "Sign the installer in the protected release pipeline before publishing it." -ForegroundColor Yellow
