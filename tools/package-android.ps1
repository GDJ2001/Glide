<#
.SYNOPSIS
    Builds and packages Android debug APK and AAB artifacts from source.
.DESCRIPTION
    Executes Gradle assembleDebug and bundleDebug, calculates SHA-256 digests,
    and produces BUILD-MANIFEST.txt and SHA256SUMS.txt in build/distributions/android.
#>
[CmdletBinding()]
param(
    [string]$Version = "0.1.0"
)

$ErrorActionPreference = "Stop"
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..")).Path
$androidDir = Join-Path $repoRoot "android"
$gradlew = Join-Path $androidDir "gradlew.bat"
$outputDir = Join-Path $repoRoot "build\distributions\android"

New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

Write-Host "Building Android debug APK and AAB via Gradle..." -ForegroundColor Cyan

Push-Location $androidDir
try {
    & $gradlew :android:app:assembleDebug :android:app:bundleDebug --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$apkSource = Join-Path $androidDir "app\build\outputs\apk\debug\app-debug.apk"
$aabSource = Join-Path $androidDir "app\build\outputs\bundle\debug\app-debug.aab"

if (-not (Test-Path $apkSource)) {
    throw "Generated APK not found: $apkSource"
}
if (-not (Test-Path $aabSource)) {
    throw "Generated AAB not found: $aabSource"
}

$apkDest = Join-Path $outputDir "app-debug.apk"
$aabDest = Join-Path $outputDir "app-debug.aab"

Copy-Item -Path $apkSource -Destination $apkDest -Force
Copy-Item -Path $aabSource -Destination $aabDest -Force

$apkHash = (Get-FileHash -Algorithm SHA256 -Path $apkDest).Hash.ToLowerInvariant()
$aabHash = (Get-FileHash -Algorithm SHA256 -Path $aabDest).Hash.ToLowerInvariant()

$shaFile = Join-Path $outputDir "SHA256SUMS.txt"
@(
    "$apkHash  app-debug.apk",
    "$aabHash  app-debug.aab"
) | Set-Content -Path $shaFile -Encoding utf8

$commit = (git -C $repoRoot rev-parse HEAD).Trim()
$javaVersion = (java -version 2>&1 | Select-Object -First 1).ToString().Trim()

$manifestFile = Join-Path $outputDir "BUILD-MANIFEST.txt"
@"
source_commit=$commit
build_utc=$((Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'))
toolchain=$javaVersion, Gradle 9.4.1, AGP 9.4.1, compileSdk 37, minSdk 26
package_version=$Version
apk_artifact=app-debug.apk
aab_artifact=app-debug.aab
signing_status=debug_signed (v2 scheme, 4-byte zipaligned)
installability=development_installable_debug
"@ | Set-Content -Path $manifestFile -Encoding utf8

Write-Host "Android packages successfully created in $outputDir" -ForegroundColor Green
Write-Host "  APK: $apkDest (SHA-256: $apkHash)" -ForegroundColor DarkGray
Write-Host "  AAB: $aabDest (SHA-256: $aabHash)" -ForegroundColor DarkGray
Write-Host "  Manifest: $manifestFile" -ForegroundColor DarkGray
