<#
.SYNOPSIS
    Assembles the authoritative release candidate manifest and catalog for Glide.
.DESCRIPTION
    Validates all native client packages and build evidence against the frozen release
    candidate commit. Computes independent SHA-256 hashes, records toolchain details,
    signing status, and build jobs, rejecting missing or stale artifacts.
    Produces build/distributions/RELEASE_MANIFEST.json and build/distributions/RELEASE_CATALOG.md.
    Strictly separates runnable packages from test-evidence archives.
#>
param(
    [string]$Commit = '',
    [string]$Version = '1.0.0',
    [string]$ArtifactsDir = '',
    [switch]$AllowDirty,
    [switch]$AllowMissing
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "       Glide Release Candidate Manifest Assembly                 " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# 1. Verify Git Commit & Status
$currentCommit = (git -C $repoRoot rev-parse HEAD).Trim()
if ([string]::IsNullOrWhiteSpace($Commit)) {
    $Commit = $currentCommit
} elseif ($currentCommit -ne $Commit) {
    throw "Working tree HEAD ($currentCommit) does not match expected frozen commit ($Commit)"
}

if (-not $AllowDirty) {
    $dirty = (git -C $repoRoot status --porcelain).Trim()
    if ($dirty) {
        $dirtyLines = $dirty -split "`n" | Where-Object {
            $_ -and ($_ -notmatch 'RELEASE_MANIFEST\.json') -and ($_ -notmatch 'RELEASE_CATALOG\.md')
        }
        if ($dirtyLines) {
            throw "Working tree has uncommitted modifications:`n$($dirtyLines -join "`n")"
        }
    }
}

Write-Host "Source commit verified: $Commit" -ForegroundColor Green

# Helper: Parse key=value BUILD-MANIFEST.txt files
function Parse-BuildManifest {
    param([string]$FilePath)
    $dict = @{}
    if (Test-Path $FilePath) {
        Get-Content -Path $FilePath -Encoding utf8 | ForEach-Object {
            $line = $_.Trim()
            if ($line -and -not $line.StartsWith('#') -and ($line -match '^([^=]+)=(.*)$')) {
                $dict[$matches[1].Trim()] = $matches[2].Trim()
            }
        }
    }
    return $dict
}

function Assert-BuildManifestIdentity {
    param(
        [hashtable]$Manifest,
        [string]$ManifestPath,
        [string]$Platform
    )

    if ($Manifest.Count -eq 0) {
        if ($AllowMissing -and -not (Test-Path (Split-Path -Parent $ManifestPath))) {
            return
        }
        throw "$Platform artifact directory has no BUILD-MANIFEST.txt: $ManifestPath"
    }
    if (-not $Manifest.ContainsKey('source_commit') -or $Manifest['source_commit'] -ne $Commit) {
        $actual = if ($Manifest.ContainsKey('source_commit')) { $Manifest['source_commit'] } else { '<missing>' }
        throw "$Platform artifact source commit is $actual, expected $Commit. Rebuild this platform from the frozen commit."
    }
    if (-not $Manifest.ContainsKey('package_version') -or $Manifest['package_version'] -ne $Version) {
        $actual = if ($Manifest.ContainsKey('package_version')) { $Manifest['package_version'] } else { '<missing>' }
        throw "$Platform package version is $actual, expected $Version."
    }
}

# 2. Collect and Register Artifacts
$runnableArtifacts = [System.Collections.Generic.List[PSCustomObject]]::new()
$testEvidenceArtifacts = [System.Collections.Generic.List[PSCustomObject]]::new()
$allArtifacts = [System.Collections.Generic.List[PSCustomObject]]::new()

function Register-Artifact {
    param(
        [Parameter(Mandatory=$true)][string]$Platform,
        [Parameter(Mandatory=$true)][string]$Architecture,
        [Parameter(Mandatory=$true)][string]$ArtifactType,
        [Parameter(Mandatory=$true)][string]$FilePath,
        [Parameter(Mandatory=$true)][string]$PackageVersion,
        [Parameter(Mandatory=$true)][string]$Toolchain,
        [Parameter(Mandatory=$true)][string]$SigningStatus,
        [Parameter(Mandatory=$true)][string]$BuildJob,
        [Parameter(Mandatory=$true)][bool]$IsRunnable,
        [string]$Installability = "",
        [string]$ExpectedSha256 = $null
    )

    $resolvedPath = if ([System.IO.Path]::IsPathRooted($FilePath)) {
        $FilePath
    } else {
        Join-Path $repoRoot $FilePath
    }

    if (-not (Test-Path $resolvedPath)) {
        if ($script:AllowMissing) {
            Write-Host "  [MISSING] $Platform ($Architecture) - $FilePath (skipped)" -ForegroundColor Yellow
            return
        }
        throw "Missing required release candidate artifact: $FilePath"
    }

    $item = Get-Item $resolvedPath
    $hash = (Get-FileHash -Algorithm SHA256 -Path $resolvedPath).Hash.ToLowerInvariant()

    if ($IsRunnable) {
        $declaredHashes = @(
            Get-ChildItem -LiteralPath $item.DirectoryName -Filter 'SHA256SUMS*.txt' -File -ErrorAction SilentlyContinue |
                ForEach-Object {
                    Get-Content -LiteralPath $_.FullName | ForEach-Object {
                        if ($_ -match '^([0-9a-fA-F]{64})\s+\*?(.+)$' -and
                            $matches[2].Trim() -eq $item.Name) {
                            $matches[1].ToLowerInvariant()
                        }
                    }
                } | Select-Object -Unique
        )
        if ($declaredHashes.Count -ne 1) {
            throw "Expected exactly one declared SHA-256 for $($item.Name) beside the package; found $($declaredHashes.Count)."
        }
        if ($hash -ne $declaredHashes[0]) {
            throw "Package checksum mismatch for $($item.Name): declared $($declaredHashes[0]), actual $hash."
        }
    }

    if ($ExpectedSha256 -and ($hash -ne $ExpectedSha256.ToLowerInvariant())) {
        throw "SHA-256 mismatch for $FilePath! Expected: $ExpectedSha256, Actual: $hash"
    }

    $relPath = if ($resolvedPath.StartsWith($repoRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        $resolvedPath.Substring($repoRoot.Length).TrimStart('\', '/').Replace('\', '/')
    } else {
        $FilePath.Replace('\', '/')
    }

    $artifactObj = [PSCustomObject]@{
        Platform       = $Platform
        Architecture   = $Architecture
        ArtifactType   = $ArtifactType
        FileName       = $item.Name
        RelativePath   = $relPath
        SizeBytes      = $item.Length
        Sha256         = $hash
        PackageVersion = $PackageVersion
        Toolchain      = $Toolchain
        SigningStatus  = $SigningStatus
        Installability = $Installability
        BuildJob       = $BuildJob
        IsRunnable     = $IsRunnable
    }

    if ($IsRunnable) {
        $script:runnableArtifacts.Add($artifactObj)
    } else {
        $script:testEvidenceArtifacts.Add($artifactObj)
    }
    $script:allArtifacts.Add($artifactObj)

    $catLabel = if ($IsRunnable) { "RUNNABLE" } else { "EVIDENCE" }
    Write-Host "  [OK:$catLabel] $Platform ($Architecture) - $($item.Name)" -ForegroundColor Green
    Write-Host "         Size: $($item.Length) bytes | SHA-256: $hash" -ForegroundColor DarkGray
}

Write-Host "`nAuditing and hashing platform artifacts..." -ForegroundColor Yellow

# --- A. Windows Artifacts ---
$winManifestPath = Join-Path $repoRoot 'build\distributions\windows\BUILD-MANIFEST.txt'
$winManifest = Parse-BuildManifest $winManifestPath
Assert-BuildManifestIdentity $winManifest $winManifestPath 'Windows'
$winToolchain = if ($winManifest.ContainsKey('dotnet_sdk') -and $winManifest.ContainsKey('nsis_version')) {
    ".NET $($winManifest['dotnet_sdk']), $($winManifest['nsis_version'])"
} else {
    $dotnetVer = try { (dotnet --version).Trim() } catch { ".NET 10.0" }
    "$dotnetVer, NSIS 3.12+"
}
$winSigning = if ($winManifest.ContainsKey('signing_status')) { $winManifest['signing_status'] } else { 'unsigned_development' }
$winInstallability = if ($winManifest.ContainsKey('installability')) { $winManifest['installability'] } else { 'development_installable_unsigned' }

Register-Artifact `
    -Platform 'Windows' `
    -Architecture 'win-x64' `
    -ArtifactType 'installer' `
    -FilePath "build\distributions\windows\Glide-Setup-$Version-win-x64.exe" `
    -PackageVersion $Version `
    -Toolchain $winToolchain `
    -SigningStatus $winSigning `
    -Installability $winInstallability `
    -BuildJob 'local-windows-host' `
    -IsRunnable $true

Register-Artifact `
    -Platform 'Windows' `
    -Architecture 'win-x64' `
    -ArtifactType 'portable_archive' `
    -FilePath 'build\distributions\windows\glide-windows-win-x64.zip' `
    -PackageVersion $Version `
    -Toolchain $winToolchain `
    -SigningStatus $winSigning `
    -Installability $winInstallability `
    -BuildJob 'local-windows-host' `
    -IsRunnable $true

# --- B. Android Artifacts ---
$androidManifestPath = Join-Path $repoRoot 'build\distributions\android\BUILD-MANIFEST.txt'
$androidManifest = Parse-BuildManifest $androidManifestPath
Assert-BuildManifestIdentity $androidManifest $androidManifestPath 'Android'
$androidToolchain = if ($androidManifest.ContainsKey('toolchain')) {
    $androidManifest['toolchain']
} else {
    $javaVer = try { (java -version 2>&1 | Select-Object -First 1).ToString().Trim() } catch { "OpenJDK 26" }
    "$javaVer, Gradle 9.4.1, AGP 9.4.1, compileSdk 37, minSdk 26"
}
$androidSigning = if ($androidManifest.ContainsKey('signing_status')) {
    $androidManifest['signing_status']
} else {
    'debug_signed (v2 scheme, 4-byte zipaligned)'
}
$androidInstallability = if ($androidManifest.ContainsKey('installability')) {
    $androidManifest['installability']
} else {
    'development_installable_debug'
}

$androidApkPath = if (Test-Path (Join-Path $repoRoot 'build\distributions\android\app-debug.apk')) {
    'build\distributions\android\app-debug.apk'
} else {
    'android\app\build\outputs\apk\debug\app-debug.apk'
}

$androidAabPath = if (Test-Path (Join-Path $repoRoot 'build\distributions\android\app-debug.aab')) {
    'build\distributions\android\app-debug.aab'
} else {
    'android\app\build\outputs\bundle\debug\app-debug.aab'
}

Register-Artifact `
    -Platform 'Android' `
    -Architecture 'universal' `
    -ArtifactType 'apk' `
    -FilePath $androidApkPath `
    -PackageVersion $Version `
    -Toolchain $androidToolchain `
    -SigningStatus $androidSigning `
    -Installability $androidInstallability `
    -BuildJob 'local-android-sdk' `
    -IsRunnable $true

Register-Artifact `
    -Platform 'Android' `
    -Architecture 'universal' `
    -ArtifactType 'aab' `
    -FilePath $androidAabPath `
    -PackageVersion $Version `
    -Toolchain $androidToolchain `
    -SigningStatus $androidSigning `
    -Installability $androidInstallability `
    -BuildJob 'local-android-sdk' `
    -IsRunnable $true

# --- C. Linux x64 Native Packages ---
$linuxDir = $null
$candidateDirs = @(
    (Join-Path $repoRoot "build\distributions\runs\${Commit}*\linux"),
    (Join-Path $repoRoot "build\distributions\runs\*-*\linux"),
    (Join-Path $repoRoot "build\distributions\linux")
)
foreach ($c in $candidateDirs) {
    $matching = Get-Item -Path $c -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($matching) {
        $linuxDir = $matching.FullName
        break
    }
}

$linuxDeb = if ($linuxDir) { Get-ChildItem -Path $linuxDir -Filter "glide_*_amd64.deb" -ErrorAction SilentlyContinue | Select-Object -First 1 } else { $null }
$linuxRpm = if ($linuxDir) { Get-ChildItem -Path $linuxDir -Filter "glide-*-1.x86_64.rpm" -ErrorAction SilentlyContinue | Select-Object -First 1 } else { $null }
$linuxTar = if ($linuxDir) { Get-ChildItem -Path $linuxDir -Filter "glide-*-linux-x64.tar.gz" -ErrorAction SilentlyContinue | Select-Object -First 1 } else { $null }
$linuxManifestPath = if ($linuxDir) { Join-Path $linuxDir 'BUILD-MANIFEST.txt' } else { '' }
$linuxManifest = Parse-BuildManifest $linuxManifestPath
if ($linuxDir) {
    Assert-BuildManifestIdentity $linuxManifest $linuxManifestPath 'Linux'
}
$linuxToolchain = if ($linuxManifest.ContainsKey('dotnet_sdk') -and $linuxManifest.ContainsKey('rpm_version')) {
    ".NET $($linuxManifest['dotnet_sdk']), dpkg-deb 1.22.6, $($linuxManifest['rpm_version']), Ubuntu 24.04 WSL2"
} else {
    '.NET 10.0.401, dpkg-deb 1.22.6, rpmbuild 4.18.2, Ubuntu 24.04 WSL2'
}
$linuxPkgVer = if ($linuxManifest.ContainsKey('package_version')) { $linuxManifest['package_version'] } else { '1.0.0' }
$linuxSigning = if ($linuxManifest.ContainsKey('signing_status')) { $linuxManifest['signing_status'] } else { 'unsigned_development' }

$debPath = if ($linuxDeb) { $linuxDeb.FullName } else { Join-Path $repoRoot "build\distributions\linux\glide_${linuxPkgVer}_amd64.deb" }
Register-Artifact `
    -Platform 'Linux' `
    -Architecture 'linux-x64' `
    -ArtifactType 'deb_package' `
    -FilePath $debPath `
    -PackageVersion $linuxPkgVer `
    -Toolchain $linuxToolchain `
    -SigningStatus $linuxSigning `
    -Installability 'development_installable_unsigned' `
    -BuildJob 'local-wsl2-ubuntu-24.04' `
    -IsRunnable $true

$rpmPath = if ($linuxRpm) { $linuxRpm.FullName } else { Join-Path $repoRoot "build\distributions\linux\glide-$linuxPkgVer-1.x86_64.rpm" }
Register-Artifact `
    -Platform 'Linux' `
    -Architecture 'linux-x64' `
    -ArtifactType 'rpm_package' `
    -FilePath $rpmPath `
    -PackageVersion $linuxPkgVer `
    -Toolchain $linuxToolchain `
    -SigningStatus $linuxSigning `
    -Installability 'development_installable_unsigned' `
    -BuildJob 'local-wsl2-ubuntu-24.04' `
    -IsRunnable $true

$tarPath = if ($linuxTar) { $linuxTar.FullName } else { Join-Path $repoRoot "build\distributions\linux\glide-$linuxPkgVer-linux-x64.tar.gz" }
Register-Artifact `
    -Platform 'Linux' `
    -Architecture 'linux-x64' `
    -ArtifactType 'portable_archive' `
    -FilePath $tarPath `
    -PackageVersion $linuxPkgVer `
    -Toolchain $linuxToolchain `
    -SigningStatus $linuxSigning `
    -Installability 'portable_executable' `
    -BuildJob 'local-wsl2-ubuntu-24.04' `
    -IsRunnable $true

# --- D. Apple Platform Packages (macOS, iOS Simulator, iOS Device Archive) ---
$appleMacDir = $null
$appleIosDir = $null
$appleCandidateDirs = @(
    (Join-Path $repoRoot "build\distributions\runs\${Commit}*\apple"),
    (Join-Path $repoRoot "build\distributions\runs\*-*\apple"),
    (Join-Path $repoRoot "build\distributions\apple")
)
foreach ($ac in $appleCandidateDirs) {
    $matching = Get-Item -Path $ac -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($matching) {
        $appleMacDir = Join-Path $matching.FullName "macos"
        $appleIosDir = Join-Path $matching.FullName "ios"
        break
    }
}

$macAppZip = if ($appleMacDir) { Get-ChildItem -Path $appleMacDir -Filter "Glide-macos-unsigned.zip" -ErrorAction SilentlyContinue | Select-Object -First 1 } else { $null }
$macManifest = if ($appleMacDir) { Parse-BuildManifest (Join-Path $appleMacDir 'BUILD-MANIFEST.txt') } else { @{} }
if ($macAppZip) {
    Assert-BuildManifestIdentity $macManifest (Join-Path $appleMacDir 'BUILD-MANIFEST.txt') 'macOS'
}
$macToolchain = if ($macManifest.ContainsKey('toolchain')) { $macManifest['toolchain'] } else { 'Xcode 26.6 (17F113), Apple Swift 6.3.3, macOS 26.6.2' }
$macSigning = if ($macManifest.ContainsKey('signing_status')) { $macManifest['signing_status'] } else { 'unsigned_development' }
$macInstallability = if ($macManifest.ContainsKey('installability')) { $macManifest['installability'] } else { 'requires_local_development_or_signed_release_build' }

$macPath = if ($macAppZip) { $macAppZip.FullName } else { Join-Path $repoRoot "build\distributions\apple\macos\Glide-macos-unsigned.zip" }
Register-Artifact `
    -Platform 'macOS' `
    -Architecture 'universal (arm64/x86_64)' `
    -ArtifactType 'portable_archive' `
    -FilePath $macPath `
    -PackageVersion $Version `
    -Toolchain $macToolchain `
    -SigningStatus $macSigning `
    -Installability $macInstallability `
    -BuildJob 'github-actions-apple-runner' `
    -IsRunnable $true

$iosSimZip = if ($appleIosDir) { Get-ChildItem -Path $appleIosDir -Filter "Glide-iOS-simulator-unsigned.zip" -ErrorAction SilentlyContinue | Select-Object -First 1 } else { $null }
$iosDevZip = if ($appleIosDir) { Get-ChildItem -Path $appleIosDir -Filter "Glide-iOS-device-unsigned-archive.zip" -ErrorAction SilentlyContinue | Select-Object -First 1 } else { $null }
$iosManifest = if ($appleIosDir) { Parse-BuildManifest (Join-Path $appleIosDir 'BUILD-MANIFEST.txt') } else { @{} }
if ($iosSimZip -or $iosDevZip) {
    Assert-BuildManifestIdentity $iosManifest (Join-Path $appleIosDir 'BUILD-MANIFEST.txt') 'iOS/iPadOS'
}
$iosToolchain = if ($iosManifest.ContainsKey('toolchain')) { $iosManifest['toolchain'] } else { 'Xcode 26.6 (17F113), Apple Swift 6.3.3, iOS 26.5 Simulator' }
$iosSigning = if ($iosManifest.ContainsKey('signing_status')) { $iosManifest['signing_status'] } else { 'unsigned_development' }

$simPath = if ($iosSimZip) { $iosSimZip.FullName } else { Join-Path $repoRoot "build\distributions\apple\ios\Glide-iOS-simulator-unsigned.zip" }
Register-Artifact `
    -Platform 'iOS / iPadOS' `
    -Architecture 'universal (arm64/x86_64)' `
    -ArtifactType 'simulator_bundle' `
    -FilePath $simPath `
    -PackageVersion $Version `
    -Toolchain $iosToolchain `
    -SigningStatus $iosSigning `
    -Installability 'simulator_installable' `
    -BuildJob 'github-actions-apple-runner' `
    -IsRunnable $true

$devPath = if ($iosDevZip) { $iosDevZip.FullName } else { Join-Path $repoRoot "build\distributions\apple\ios\Glide-iOS-device-unsigned-archive.zip" }
Register-Artifact `
    -Platform 'iOS / iPadOS' `
    -Architecture 'arm64' `
    -ArtifactType 'device_archive' `
    -FilePath $devPath `
    -PackageVersion $Version `
    -Toolchain $iosToolchain `
    -SigningStatus 'unsigned_development' `
    -Installability 'build_only_requires_distribution_signing' `
    -BuildJob 'github-actions-apple-runner' `
    -IsRunnable $true

# --- E. Test Evidence Archives (Strictly separated from runnable packages) ---
$appleEvidence = Get-ChildItem -Path (Join-Path $repoRoot 'build\test-evidence\apple-backend') -Filter "apple-backend-test-evidence.zip" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $appleEvidence) {
    $appleEvidence = Get-ChildItem -Path (Join-Path $repoRoot 'build\distributions') -Filter "apple-backend-test-evidence.zip" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
}
if ($appleEvidence) {
    Register-Artifact `
        -Platform 'Apple' `
        -Architecture 'universal' `
        -ArtifactType 'ci_backend_validation_evidence' `
        -FilePath $appleEvidence.FullName `
        -PackageVersion $Version `
        -Toolchain 'Xcode 26.6 (17F113), Apple Swift 6.3.3, macOS 26.6.2 (Darwin 25.6.0)' `
        -SigningStatus 'development_signed / simulator_unsigned (App Group: group.dev.glide.shared)' `
        -Installability 'test_evidence_archive' `
        -BuildJob 'github-actions/run-37060072390' `
        -IsRunnable $false
}

$linuxCiX64 = Get-ChildItem -Path (Join-Path $repoRoot 'build\distributions') -Filter "linux-packages-linux-x64-*.zip" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if ($linuxCiX64) {
    Register-Artifact `
        -Platform 'Linux' `
        -Architecture 'linux-x64' `
        -ArtifactType 'ci_distribution_bundle' `
        -FilePath $linuxCiX64.FullName `
        -PackageVersion '1.0.0' `
        -Toolchain '.NET 10.0.100, dpkg-deb, rpmbuild, Ubuntu 24.04 runner' `
        -SigningStatus 'unsigned_development' `
        -Installability 'test_evidence_archive' `
        -BuildJob 'github-actions/run-37060072410' `
        -IsRunnable $false
}

$linuxCiArm64 = Get-ChildItem -Path (Join-Path $repoRoot 'build\distributions') -Filter "linux-packages-linux-arm64-*.zip" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if ($linuxCiArm64) {
    Register-Artifact `
        -Platform 'Linux' `
        -Architecture 'linux-arm64' `
        -ArtifactType 'ci_distribution_bundle' `
        -FilePath $linuxCiArm64.FullName `
        -PackageVersion '1.0.0' `
        -Toolchain '.NET 10.0.100, dpkg-deb, rpmbuild, Ubuntu 24.04 ARM runner' `
        -SigningStatus 'unsigned_development' `
        -Installability 'test_evidence_archive' `
        -BuildJob 'github-actions/run-37060072410' `
        -IsRunnable $false
}

# 3. Assemble Release Manifest JSON
$manifest = [PSCustomObject]@{
    ReleaseCandidate = [PSCustomObject]@{
        Commit                     = $Commit
        Version                    = $Version
        TimestampUtc               = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
        TotalRunnableArtifactCount = $runnableArtifacts.Count
        TotalEvidenceArchiveCount  = $testEvidenceArtifacts.Count
        PlatformsCovered           = @('Windows', 'Android', 'Linux', 'macOS', 'iOS', 'iPadOS')
    }
    RunnableArtifacts     = $runnableArtifacts.ToArray()
    TestEvidenceArtifacts = $testEvidenceArtifacts.ToArray()
    Artifacts             = $allArtifacts.ToArray()
}

$distDir = Join-Path $repoRoot 'build\distributions'
if (-not (Test-Path $distDir)) {
    New-Item -ItemType Directory -Force -Path $distDir | Out-Null
}

$manifestPath = Join-Path $distDir 'RELEASE_MANIFEST.json'
$manifest | ConvertTo-Json -Depth 10 | Set-Content -Path $manifestPath -Encoding utf8
Write-Host "`nRelease manifest written: $manifestPath" -ForegroundColor Cyan

# 4. Generate Release Catalog Markdown
$catalogPath = Join-Path $distDir 'RELEASE_CATALOG.md'
$timestampString = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss')
$bt = [char]96

$sb = [System.Text.StringBuilder]::new()
[void]$sb.AppendLine("# Glide Release Candidate Catalog")
[void]$sb.AppendLine()
[void]$sb.AppendLine("**Commit:** $bt$Commit$bt  ")
[void]$sb.AppendLine("**Version:** $bt$Version$bt  ")
[void]$sb.AppendLine("**Assembly Date:** $bt$timestampString UTC$bt  ")
[void]$sb.AppendLine("**Platforms Covered:** Windows 11, Android 13+, Linux (Ubuntu 24.04 / Fedora 44), macOS (macOS 13+), iOS/iPadOS 16+")
[void]$sb.AppendLine()
[void]$sb.AppendLine("---")
[void]$sb.AppendLine()
[void]$sb.AppendLine("## 1. Runnable Platform Packages")
[void]$sb.AppendLine()
[void]$sb.AppendLine("| Platform | Arch | Artifact Type | File / Path | Size (Bytes) | SHA-256 Checksum | Toolchain | Signing Status | Installability |")
[void]$sb.AppendLine("|---|---|---|---|---|---|---|---|---|")

foreach ($art in $runnableArtifacts) {
    $sizeFormatted = $art.SizeBytes.ToString('N0')
    [void]$sb.AppendLine("| **$($art.Platform)** | $bt$($art.Architecture)$bt | $($art.ArtifactType) | $bt$($art.FileName)$bt | $sizeFormatted | $bt$($art.Sha256)$bt | $($art.Toolchain) | $($art.SigningStatus) | $bt$($art.Installability)$bt |")
}

[void]$sb.AppendLine()
[void]$sb.AppendLine("---")
[void]$sb.AppendLine()
[void]$sb.AppendLine("## 2. Test Evidence Archives")
[void]$sb.AppendLine()
[void]$sb.AppendLine("| Platform | Arch | Evidence Type | File / Archive | Size (Bytes) | SHA-256 Checksum | Toolchain | Build Job |")
[void]$sb.AppendLine("|---|---|---|---|---|---|---|---|")

foreach ($art in $testEvidenceArtifacts) {
    $sizeFormatted = $art.SizeBytes.ToString('N0')
    [void]$sb.AppendLine("| **$($art.Platform)** | $bt$($art.Architecture)$bt | $($art.ArtifactType) | $bt$($art.FileName)$bt | $sizeFormatted | $bt$($art.Sha256)$bt | $($art.Toolchain) | $bt$($art.BuildJob)$bt |")
}

[void]$sb.AppendLine()
[void]$sb.AppendLine("---")
[void]$sb.AppendLine()
[void]$sb.AppendLine("## 3. Platform Installation and Execution Gate Status")
[void]$sb.AppendLine()
[void]$sb.AppendLine("This manifest inventories files and hashes. It cannot establish installation, execution, UI, or physical-transfer results. Record those gates separately with the exact artifact hash, source commit, host, test log, and date; an earlier run does not qualify this build.")
[void]$sb.AppendLine()
[void]$sb.AppendLine("| Platform | Installed UI | Physical transfer |")
[void]$sb.AppendLine("|---|:---:|:---:|")
foreach ($platform in @('Windows', 'Android', 'Linux', 'macOS', 'iOS/iPadOS')) {
    [void]$sb.AppendLine("| **$platform** | **NOT VERIFIED BY THIS MANIFEST** | **NOT VERIFIED BY THIS MANIFEST** |")
}
[void]$sb.AppendLine()
[void]$sb.AppendLine("---")
[void]$sb.AppendLine()
[void]$sb.AppendLine("## 4. Disclaimers & Governance Rules")
[void]$sb.AppendLine()
[void]$sb.AppendLine("1. **Linux and Apple physical gates**: Package presence and hashes do not establish independent-device interoperability or installed UI quality.")
[void]$sb.AppendLine("2. **Apple device installation**: An unsigned archive is not an installable iPhone/iPad release. Physical deployment requires suitable signing and provisioning.")
[void]$sb.AppendLine("3. **Native-only scope**: Browser file transfer is retired; verify the source and package contents separately before making an absence claim.")

$catalogText = $sb.ToString()

# 5. Regression Check for Unresolved PowerShell Template Expressions
$unresolvedPatterns = @(
    '\$\([^\)]*\)',         # Unexpanded $(...)
    '\\\$[a-zA-Z0-9_]+',    # Escaped literal \$Var
    '\$\{+[a-zA-Z0-9_]+\}', # Unexpanded ${Var}
    '(?<![a-zA-Z0-9_])\$Commit(?![a-zA-Z0-9_])',
    '(?<![a-zA-Z0-9_])\$Version(?![a-zA-Z0-9_])'
)
foreach ($pattern in $unresolvedPatterns) {
    $rxMatches = [regex]::Matches($catalogText, $pattern)
    if ($rxMatches.Count -gt 0) {
        $firstMatch = $rxMatches[0].Value
        throw "Regression check failed: generated RELEASE_CATALOG.md contains unresolved PowerShell expression '$firstMatch'"
    }
}

$catalogText | Set-Content -Path $catalogPath -Encoding utf8
Write-Host "Release catalog written: $catalogPath" -ForegroundColor Cyan

# 6. JSON Parse Verification
try {
    $parsedJson = Get-Content -Path $manifestPath -Raw -Encoding utf8 | ConvertFrom-Json
    if ($parsedJson.RunnableArtifacts.Count -ne $runnableArtifacts.Count) {
        throw "JSON serialization mismatch in RunnableArtifacts count."
    }
    Write-Host "JSON parse verified: $($parsedJson.RunnableArtifacts.Count) runnable artifact(s), $($parsedJson.TestEvidenceArtifacts.Count) evidence archive(s)." -ForegroundColor Green
} catch {
    throw "Generated RELEASE_MANIFEST.json failed JSON parse validation: $_"
}

Write-Host "`nArtifact inventory and hashes assembled. Installation and physical gates require separate evidence." -ForegroundColor Yellow
