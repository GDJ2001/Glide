<#
.SYNOPSIS
    Builds, packages, and validates the Glide Windows MSIX & .msixupload container for Microsoft Store.
.DESCRIPTION
    Publishes Glide.Windows.UI unbundled on .NET 10 (with external NetSparkle auto-update disabled),
    stages visual assets, templates AppxManifest.xml, compiles resources.pri via MakePri.exe,
    packs the MSIX package via MakeAppx.exe, bundles PDB symbols into .appxsym, and creates .msixupload.
#>

[CmdletBinding()]
param(
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Release",
    [ValidatePattern('^\d+\.\d+\.\d+\.0$')]
    [string]$Version = "1.0.0.0",
    [ValidateSet("win-x64")]
    [string]$Runtime = "win-x64",
    [string]$DisplayName = "Glide",
    [string]$PackageName = "Glide.GlideApp",
    [string]$PublisherId = "CN=00000000-0000-0000-0000-000000000000",
    [string]$PublisherDisplayName = "Glide",
    [string]$OutputDirectory = "",
    [string]$CertificatePath = "",
    [string]$CertificatePassword = "",
    [switch]$SignForLocalTest,
    [switch]$SkipWack,
    [string]$SdkBuildToolsVersion = "10.0.26100.9457"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..")).Path
. (Join-Path $repoRoot 'tools\resolve-dotnet.ps1')
$null = Enable-GlideDotnetSdk

$project = Join-Path $scriptRoot "Glide.Windows.UI\Glide.Windows.UI.csproj"
$msixTemplateDir = Join-Path $scriptRoot "msix"
$assetGenerator = Join-Path $scriptRoot "generate-msix-assets.ps1"

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repoRoot "build\distributions\windows\msix"
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$OutputDirectory = (Resolve-Path $OutputDirectory).Path

$stageDir = Join-Path $OutputDirectory "stage-$Runtime"
$symDir = Join-Path $OutputDirectory "symbols-$Runtime"
$msixOutput = Join-Path $OutputDirectory "Glide-$Version-$Runtime.msix"
$appxsymOutput = Join-Path $OutputDirectory "Glide-$Version-$Runtime.appxsym"
$msixUploadOutput = Join-Path $OutputDirectory "Glide-$Version-$Runtime.msixupload"
$priConfig = Join-Path $OutputDirectory "priconfig.xml"

function Resolve-SdkTool {
    param(
        [Parameter(Mandatory = $true)][string]$ToolName,
        [string]$BootstrapRoot
    )

    $windowsKitsBin = "${env:ProgramFiles(x86)}\Windows Kits\10\bin"
    $nugetPackages = Join-Path $env:USERPROFILE ".nuget\packages\microsoft.windows.sdk.buildtools"
    $searchRoots = @(
        (Join-Path $windowsKitsBin "*\x64"),
        (Join-Path $nugetPackages "*\bin\*\x64"),
        (Join-Path $BootstrapRoot "*\bin\*\x64")
    )

    foreach ($pattern in $searchRoots) {
        $match = Get-ChildItem -Path (Join-Path $pattern $ToolName) -ErrorAction SilentlyContinue |
            Sort-Object FullName |
            Select-Object -Last 1
        if ($match) {
            return $match.FullName
        }
    }

    return $null
}

# 1. Resolve or bootstrap Windows SDK BuildTools (makeappx.exe, makepri.exe, signtool.exe)
$bootstrapRoot = Join-Path $repoRoot "build\tools\windows-sdk-buildtools"
$makeAppx = Resolve-SdkTool -ToolName "makeappx.exe" -BootstrapRoot $bootstrapRoot
$makePri = Resolve-SdkTool -ToolName "makepri.exe" -BootstrapRoot $bootstrapRoot
$signTool = Resolve-SdkTool -ToolName "signtool.exe" -BootstrapRoot $bootstrapRoot

if (-not $makeAppx -or -not $makePri -or -not $signTool) {
    $pkgExtractDir = Join-Path $bootstrapRoot $SdkBuildToolsVersion
    $nupkgPath = Join-Path $bootstrapRoot "microsoft.windows.sdk.buildtools.$SdkBuildToolsVersion.nupkg"
    $nupkgUrl = "https://api.nuget.org/v3-flatcontainer/microsoft.windows.sdk.buildtools/$SdkBuildToolsVersion/microsoft.windows.sdk.buildtools.$SdkBuildToolsVersion.nupkg"

    New-Item -ItemType Directory -Force -Path $bootstrapRoot | Out-Null
    if (-not (Test-Path -LiteralPath $pkgExtractDir)) {
        Write-Host "Downloading Microsoft.Windows.SDK.BuildTools $SdkBuildToolsVersion from NuGet..." -ForegroundColor Cyan
        Invoke-WebRequest -Uri $nupkgUrl -OutFile $nupkgPath -UseBasicParsing
        [System.IO.Compression.ZipFile]::ExtractToDirectory($nupkgPath, $pkgExtractDir)
        Remove-Item -LiteralPath $nupkgPath -Force -ErrorAction SilentlyContinue
    }

    $makeAppx = Resolve-SdkTool -ToolName "makeappx.exe" -BootstrapRoot $bootstrapRoot
    $makePri = Resolve-SdkTool -ToolName "makepri.exe" -BootstrapRoot $bootstrapRoot
    $signTool = Resolve-SdkTool -ToolName "signtool.exe" -BootstrapRoot $bootstrapRoot
}

if (-not $makeAppx -or -not $makePri -or -not $signTool) {
    throw "Required Windows SDK tools (makeappx.exe, makepri.exe, signtool.exe) could not be resolved."
}

Write-Host "Using MakeAppx: $makeAppx" -ForegroundColor Cyan
Write-Host "Using MakePri:  $makePri" -ForegroundColor Cyan
Write-Host "Using SignTool: $signTool" -ForegroundColor Cyan

# 2. Clean staging & symbol directories
foreach ($dir in @($stageDir, $symDir)) {
    if (Test-Path -LiteralPath $dir) {
        Remove-Item -LiteralPath $dir -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}

# 3. Publish unbundled self-contained application (Store Policy 10.2.8: empty update feed)
Write-Host "`nPublishing Glide.Windows.UI ($Configuration, $Runtime, v$Version)..." -ForegroundColor Cyan
dotnet publish $project `
    --configuration $Configuration `
    --runtime $Runtime `
    --self-contained true `
    /p:PublishSingleFile=false `
    /p:DebugType=portable `
    /p:DebugSymbols=true `
    /p:Version=$Version `
    /p:GlideUpdateFeedUrl="" `
    /p:GlideUpdatePublicKey="" `
    --output $stageDir
if ($LASTEXITCODE -ne 0) {
    throw "dotnet publish failed."
}

# 4. Sanitize staging directory (remove non-Windows native binaries, extract PDBs)
foreach ($nonWinDir in @("runtimes\linux-x64", "runtimes\linux-arm64")) {
    $fullNonWin = Join-Path $stageDir $nonWinDir
    if (Test-Path -LiteralPath $fullNonWin) {
        Remove-Item -LiteralPath $fullNonWin -Recurse -Force
    }
}
foreach ($nonWinFile in @("libglide_spake2.so")) {
    $fullFile = Join-Path $stageDir $nonWinFile
    if (Test-Path -LiteralPath $fullFile) {
        Remove-Item -LiteralPath $fullFile -Force
    }
}

$exePath = Join-Path $stageDir "Glide.exe"
$winSpakeRoot = Join-Path $stageDir "glide_spake2.dll"
$winSpakeRid = Join-Path $stageDir "runtimes\win-x64\native\glide_spake2.dll"
if (-not (Test-Path -LiteralPath $exePath)) {
    throw "Published Glide.exe missing from '$stageDir'."
}
if (-not (Test-Path -LiteralPath $winSpakeRid) -and -not (Test-Path -LiteralPath $winSpakeRoot)) {
    throw "Required native library glide_spake2.dll missing from '$stageDir'."
}

$pdbFiles = Get-ChildItem -Path $stageDir -Filter "*.pdb" -Recurse -File
foreach ($pdb in $pdbFiles) {
    $relPdb = $pdb.FullName.Substring($stageDir.Length).TrimStart('\', '/')
    $destPdb = Join-Path $symDir $relPdb
    $destParent = Split-Path -Parent $destPdb
    if (-not (Test-Path -LiteralPath $destParent)) {
        New-Item -ItemType Directory -Force -Path $destParent | Out-Null
    }
    Move-Item -LiteralPath $pdb.FullName -Destination $destPdb -Force
}
Write-Host "Extracted $($pdbFiles.Count) PDB symbol file(s) out of MSIX stage." -ForegroundColor DarkGray

# 5. Stage visual assets and generate AppxManifest.xml
$assetsSource = Join-Path $msixTemplateDir "Assets"
if (-not (Test-Path -LiteralPath (Join-Path $assetsSource "Square150x150Logo.scale-100.png"))) {
    Write-Host "Generating MSIX visual assets..." -ForegroundColor Cyan
    & $assetGenerator -OutputDirectory $assetsSource
}
$assetsTarget = Join-Path $stageDir "Assets"
Copy-Item -Path $assetsSource -Destination $assetsTarget -Recurse -Force

$manifestTemplate = Join-Path $msixTemplateDir "AppxManifest.xml"
$manifestTarget = Join-Path $stageDir "AppxManifest.xml"
if (-not (Test-Path -LiteralPath $manifestTemplate)) {
    throw "MSIX manifest template missing: $manifestTemplate"
}

$manifestContent = Get-Content -Path $manifestTemplate -Raw -Encoding utf8
$manifestContent = $manifestContent.Replace("{{DISPLAY_NAME}}", $DisplayName)
$manifestContent = $manifestContent.Replace("{{PACKAGE_NAME}}", $PackageName)
$manifestContent = $manifestContent.Replace("{{PUBLISHER_ID}}", $PublisherId)
$manifestContent = $manifestContent.Replace("{{PUBLISHER_DISPLAY_NAME}}", $PublisherDisplayName)
$manifestContent = $manifestContent.Replace("{{VERSION}}", $Version)
$manifestContent = $manifestContent.Replace("{{PROCESSOR_ARCHITECTURE}}", "x64")

$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($manifestTarget, $manifestContent, $utf8NoBom)

# 6. Compile resources.pri via MakePri.exe
Write-Host "`nGenerating resources.pri via MakePri..." -ForegroundColor Cyan
if (Test-Path -LiteralPath $priConfig) { Remove-Item -LiteralPath $priConfig -Force }
$priOut = Join-Path $stageDir "resources.pri"
if (Test-Path -LiteralPath $priOut) { Remove-Item -LiteralPath $priOut -Force }

& $makePri createconfig /cf $priConfig /dq en-US /pv 10.0.0 /o
if ($LASTEXITCODE -ne 0) { throw "makepri createconfig failed." }

& $makePri new /pr $stageDir /cf $priConfig /mn $manifestTarget /of $priOut /o
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $priOut) -or ((Get-Item $priOut).Length -le 0)) {
    throw "makepri new failed to produce resources.pri."
}

# 7. Pack MSIX container via MakeAppx.exe
Write-Host "`nPacking MSIX container: $msixOutput..." -ForegroundColor Cyan
if (Test-Path -LiteralPath $msixOutput) { Remove-Item -LiteralPath $msixOutput -Force }
& $makeAppx pack /v /h SHA256 /d $stageDir /p $msixOutput /o
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $msixOutput)) {
    throw "MakeAppx pack failed."
}

# 8. Optional certificate signing (if a real certificate path is provided)
$signingStatus = "unsigned_store_ready"
if (-not [string]::IsNullOrWhiteSpace($CertificatePath)) {
    if (-not (Test-Path -LiteralPath $CertificatePath)) {
        throw "Certificate not found: $CertificatePath"
    }
    Write-Host "`nSigning MSIX with provided certificate..." -ForegroundColor Cyan
    $signArgs = @("sign", "/fd", "SHA256", "/f", $CertificatePath)
    if (-not [string]::IsNullOrEmpty($CertificatePassword)) {
        $signArgs += @("/p", $CertificatePassword)
    }
    $signArgs += @("/tr", "http://timestamp.digicert.com", "/td", "SHA256", $msixOutput)
    & $signTool @signArgs
    if ($LASTEXITCODE -ne 0) { throw "SignTool failed." }
    $signingStatus = "signed_provided_certificate"
}

# 9. Build .appxsym and .msixupload archives via System.IO.Compression.ZipFile
Write-Host "`nCreating .appxsym and .msixupload Store packages..." -ForegroundColor Cyan
foreach ($pkg in @($appxsymOutput, $msixUploadOutput)) {
    if (Test-Path -LiteralPath $pkg) { Remove-Item -LiteralPath $pkg -Force }
}

[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $symDir,
    $appxsymOutput,
    [System.IO.Compression.CompressionLevel]::Optimal,
    $false)

$uploadZip = [System.IO.Compression.ZipFile]::Open($msixUploadOutput, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $uploadZip, $msixOutput, (Split-Path -Leaf $msixOutput), [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $uploadZip, $appxsymOutput, (Split-Path -Leaf $appxsymOutput), [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally {
    $uploadZip.Dispose()
}

# Optional self-signed local sideload test (after .msixupload creation so self-signed cert never pollutes Store upload)
if ($SignForLocalTest -and [string]::IsNullOrWhiteSpace($CertificatePath)) {
    Write-Host "`nCreating ephemeral local-test signature on .msix (leaving .msixupload clean for Store)..." -ForegroundColor Yellow
    $testCert = New-SelfSignedCertificate -Type CodeSigningCert -Subject $PublisherId -CertStoreLocation "Cert:\CurrentUser\My" -HashAlgorithm SHA256
    & $signTool sign /fd SHA256 /sha1 $testCert.Thumbprint /s "My" $msixOutput
    if ($LASTEXITCODE -ne 0) { throw "SignTool local test signing failed." }
    $signingStatus = "local_test_self_signed_msix"
}

# 10. Optional WACK check (only if installed locally)
$appCert = "${env:ProgramFiles(x86)}\Windows Kits\10\App Certification Kit\appcert.exe"
if (-not $SkipWack -and (Test-Path -LiteralPath $appCert)) {
    Write-Host "`nExecuting Windows App Certification Kit (WACK)..." -ForegroundColor Cyan
    $wackReport = Join-Path $OutputDirectory "WACK-Report-$Runtime.xml"
    & $appCert test -appxpackagepath $msixOutput -reportoutputpath $wackReport
} elseif (-not $SkipWack) {
    Write-Host "Note: Local WACK (appcert.exe) is not installed; Microsoft Partner Center runs automated WACK validation upon .msixupload upload." -ForegroundColor DarkGray
}

# 11. Emit SHA256SUMS and BUILD-MANIFEST
$shaFile = Join-Path $OutputDirectory "SHA256SUMS-msix-$Runtime.txt"
$shaLines = @(
    $msixOutput,
    $appxsymOutput,
    $msixUploadOutput
) | ForEach-Object {
    $h = (Get-FileHash -Algorithm SHA256 -Path $_).Hash.ToLowerInvariant()
    "$h  $(Split-Path -Leaf $_)"
} | Sort-Object
$shaLines | Set-Content -Path $shaFile -Encoding utf8

$commit = (git -C $repoRoot rev-parse HEAD 2>$null).Trim()
$dotnetVer = (dotnet --version).Trim()
$manifestOut = Join-Path $OutputDirectory "BUILD-MANIFEST.txt"
@(
    "source_commit=$commit"
    "build_utc=$((Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'))"
    "dotnet_sdk=$dotnetVer"
    "sdk_build_tools=$SdkBuildToolsVersion"
    "package_version=$Version"
    "display_name=$DisplayName"
    "package_name=$PackageName"
    "publisher_id=$PublisherId"
    "publisher_display_name=$PublisherDisplayName"
    "runtime=$Runtime"
    "msix_artifact=$(Split-Path -Leaf $msixOutput)"
    "appxsym_artifact=$(Split-Path -Leaf $appxsymOutput)"
    "msixupload_artifact=$(Split-Path -Leaf $msixUploadOutput)"
    "signing_status=$signingStatus"
) | Set-Content -Path $manifestOut -Encoding utf8

Write-Host "`nMSIX Packaging Complete:" -ForegroundColor Green
Write-Host "  MSIX:        $msixOutput" -ForegroundColor Green
Write-Host "  Symbols:     $appxsymOutput" -ForegroundColor Green
Write-Host "  Store Upload:$msixUploadOutput" -ForegroundColor Green
Write-Host "  Checksums:   $shaFile" -ForegroundColor Green
Write-Host "  Manifest:    $manifestOut" -ForegroundColor Green
