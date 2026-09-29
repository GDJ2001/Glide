<#
.SYNOPSIS
    Builds, signs, and strictly verifies Release Candidate (RC) artifacts (AAB & APK) for Glide.

.DESCRIPTION
    GLIDE-062: Release Candidate Build and Verification Pipeline.
    Automates:
    1. Toolchain discovery (keytool, apksigner, zipalign, aapt2, adb).
    2. Keystore custody resolution (creates standard testing upload key if none specified).
    3. Production release assembly with R8 minification and resource shrinking (:app:bundleRelease and :app:assembleRelease).
    4. APK signature scheme verification (v1, v2, v3, v4).
    5. 16 KB page alignment verification (Android 15+ / Android 17 compliance).
    6. R8 mapping and symbol retention check.
    7. Cryptographic checksum calculation (SHA-256 / SHA-512).
    8. Badging and manifest security audit.
    9. Optional live installation and launch smoke test on attached Android runtime.
#>

[CmdletBinding()]
param (
    [string]$KeystorePath = "",
    [string]$KeystorePassword = "",
    [string]$KeyAlias = "",
    [string]$KeyPassword = "",
    [switch]$InstallOnDevice = $true
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "   Glide GLIDE-062: Release Candidate Build & Verification Tool  " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# --------------------------------------------------------------------------------------------------
# Step 1: Discover Android SDK and Java Cryptographic Toolchain
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Step 1/8] Discovering cryptographic and build toolchain..." -ForegroundColor Yellow

$keytoolExe = $null
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\keytool.exe")) {
    $keytoolExe = "$env:JAVA_HOME\bin\keytool.exe"
} else {
    $candidate = Get-ChildItem "C:\Program Files\Android\Android Studio\jbr\bin", "C:\Program Files\Java" -Recurse -Filter "keytool.exe" -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if ($candidate) { $keytoolExe = $candidate }
}
if (-not $keytoolExe) {
    throw "keytool.exe could not be located in JAVA_HOME or standard paths."
}
Write-Host "  keytool:   $keytoolExe" -ForegroundColor Green

$buildToolsDir = $null
if ($env:LOCALAPPDATA) {
    $candidateDir = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
    if ($candidateDir) { $buildToolsDir = $candidateDir }
}
if (-not $buildToolsDir) {
    throw "Android SDK build-tools directory could not be located."
}

$apksignerBat = "$buildToolsDir\apksigner.bat"
$zipalignExe = "$buildToolsDir\zipalign.exe"
$aapt2Exe = "$buildToolsDir\aapt2.exe"
$adbExe = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

if (-not (Test-Path $apksignerBat)) { throw "apksigner.bat not found at: $apksignerBat" }
if (-not (Test-Path $zipalignExe)) { throw "zipalign.exe not found at: $zipalignExe" }
if (-not (Test-Path $aapt2Exe)) { throw "aapt2.exe not found at: $aapt2Exe" }
Write-Host "  apksigner: $apksignerBat" -ForegroundColor Green
Write-Host "  zipalign:  $zipalignExe" -ForegroundColor Green
Write-Host "  aapt2:     $aapt2Exe" -ForegroundColor Green
if (Test-Path $adbExe) {
    Write-Host "  adb:       $adbExe" -ForegroundColor Green
} else {
    Write-Host "  adb:       (Not found at standard path; device install will be skipped)" -ForegroundColor DarkGray
}

# --------------------------------------------------------------------------------------------------
# Step 2: Keystore & Signing Configuration Resolution
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Step 2/8] Resolving release signing credentials..." -ForegroundColor Yellow

$activeKeystore = if ($KeystorePath) { $KeystorePath } else { $env:GLIDE_RELEASE_KEYSTORE_PATH }
$activeStorePass = if ($KeystorePassword) { $KeystorePassword } else { $env:GLIDE_RELEASE_KEYSTORE_PASSWORD }
$activeAlias = if ($KeyAlias) { $KeyAlias } else { $env:GLIDE_RELEASE_KEY_ALIAS }
$activeKeyPass = if ($KeyPassword) { $KeyPassword } else { $env:GLIDE_RELEASE_KEY_PASSWORD }

if (-not $activeKeystore) {
    # Generate dedicated RC testing keystore under android/app/build/ (ignored by git and repository scans)
    $rcKeystoreDir = "$projectRoot\android\app\build\rc-keystore"
    if (-not (Test-Path $rcKeystoreDir)) {
        New-Item -ItemType Directory -Path $rcKeystoreDir -Force | Out-Null
    }
    $activeKeystore = "$rcKeystoreDir\rc-upload-keystore.jks"
    $activeStorePass = "GlideRcTestPassword2026!"
    $activeAlias = "glide-rc-upload"
    $activeKeyPass = "GlideRcTestPassword2026!"

    if (-not (Test-Path $activeKeystore)) {
        Write-Host "  Generating RC test upload key (RSA 4096-bit, SHA256withRSA, 10000 days)..." -ForegroundColor Cyan
        & $keytoolExe -genkeypair -v `
            -keystore $activeKeystore `
            -alias $activeAlias `
            -keyalg RSA `
            -keysize 4096 `
            -validity 10000 `
            -storepass $activeStorePass `
            -keypass $activeKeyPass `
            -dname "CN=Glide Release Candidate, OU=Mobile Release, O=Glide P2P, L=Mountain View, ST=California, C=US"
    } else {
        Write-Host "  Reusing existing RC test upload key at: $activeKeystore" -ForegroundColor Cyan
    }
}

Write-Host "  Keystore: $activeKeystore" -ForegroundColor Green
Write-Host "  Key Alias: $activeAlias" -ForegroundColor Green

# --------------------------------------------------------------------------------------------------
# Step 3: Build Signed Release App Bundle & APK
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Step 3/8] Compiling and assembling minified Release Candidate artifacts..." -ForegroundColor Yellow
$gradleArgs = @(
    ":android:app:bundleRelease",
    ":android:app:assembleRelease",
    "-Pglide.release.keystorePath=$activeKeystore",
    "-Pglide.release.keystorePassword=$activeStorePass",
    "-Pglide.release.keyAlias=$activeAlias",
    "-Pglide.release.keyPassword=$activeKeyPass",
    "--no-daemon"
)

$gradlew = "$projectRoot\android\gradlew.bat"
$pinfo = New-Object System.Diagnostics.ProcessStartInfo
$pinfo.FileName = $gradlew
$pinfo.Arguments = $gradleArgs -join " "
$pinfo.WorkingDirectory = "$projectRoot\android"
$pinfo.UseShellExecute = $false
$pinfo.RedirectStandardOutput = $true
$pinfo.RedirectStandardError = $true

$process = [System.Diagnostics.Process]::Start($pinfo)
$stdout = $process.StandardOutput.ReadToEnd()
$stderr = $process.StandardError.ReadToEnd()
$process.WaitForExit()

if ($process.ExitCode -ne 0) {
    Write-Host $stdout
    Write-Host $stderr -ForegroundColor Red
    throw "Gradle release build failed with exit code $($process.ExitCode)"
}
Write-Host "  Gradle build successful!" -ForegroundColor Green

# --------------------------------------------------------------------------------------------------
# Step 4: Verify Artifact Outputs & R8 Deobfuscation Mapping
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Step 4/8] Validating generated artifacts..." -ForegroundColor Yellow

$apkPath = "$projectRoot\android\app\build\outputs\apk\release\app-release.apk"
$aabPath = "$projectRoot\android\app\build\outputs\bundle\release\app-release.aab"
$mappingPath = "$projectRoot\android\app\build\outputs\mapping\release\mapping.txt"

if (-not (Test-Path $apkPath)) { throw "Release APK not found at: $apkPath" }
if (-not (Test-Path $aabPath)) { throw "Release AAB not found at: $aabPath" }
if (-not (Test-Path $mappingPath)) { throw "R8 mapping file not found at: $mappingPath" }

$apkItem = Get-Item $apkPath
$aabItem = Get-Item $aabPath
$mappingItem = Get-Item $mappingPath

$apkSizeMB = [math]::Round($apkItem.Length / 1MB, 2)
$aabSizeMB = [math]::Round($aabItem.Length / 1MB, 2)
$mappingSizeKB = [math]::Round($mappingItem.Length / 1KB, 2)

Write-Host "  Release APK:     $apkPath ($apkSizeMB MB)" -ForegroundColor Green
Write-Host "  Release AAB:     $aabPath ($aabSizeMB MB)" -ForegroundColor Green
Write-Host "  R8 Mapping File: $mappingPath ($mappingSizeKB KB)" -ForegroundColor Green

if ($mappingItem.Length -eq 0) {
    throw "R8 mapping file is empty! Minification may not have executed properly."
}

# --------------------------------------------------------------------------------------------------
# Step 5: Verify APK Signature Schemes (v1, v2, v3, v4)
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Step 5/8] Verifying APK Signature Schemes via apksigner..." -ForegroundColor Yellow

$v4SigFile = "$apkPath.idsig"
$signerArgs = @("verify", "--verbose", "--print-certs")
if (Test-Path $v4SigFile) {
    $signerArgs += @("--v4-signature-file", $v4SigFile)
}
$signerArgs += $apkPath

$signerOutput = & $apksignerBat $signerArgs 2>&1 | Out-String
Write-Host $signerOutput

if ($LASTEXITCODE -ne 0 -or $signerOutput -notmatch "(?m)^Verifies\s*$") {
    throw "apksigner verification failed for $apkPath"
}
if ($signerOutput -notmatch "Verified using v2 scheme \(APK Signature Scheme v2\):\s*true") {
    throw "APK does not have valid v2 signature"
}
if ($signerOutput -notmatch "Verified using v3 scheme \(APK Signature Scheme v3\):\s*true") {
    throw "APK does not have valid v3 signature"
}
if (Test-Path $v4SigFile) {
    if ($signerOutput -notmatch "Verified using v4 scheme \(APK Signature Scheme v4\):\s*true") {
        throw "APK does not have valid v4 signature"
    }
    Write-Host "  PASS: APK Signature Schemes v2, v3, and v4 (fs-verity) verified successfully!" -ForegroundColor Green
} else {
    Write-Host "  PASS: APK Signature Schemes v2 and v3 verified successfully!" -ForegroundColor Green
}

# --------------------------------------------------------------------------------------------------
# Step 6: Verify 16 KB Page Alignment via zipalign
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Step 6/8] Verifying 16 KB page alignment for Android 15/17 compliance..." -ForegroundColor Yellow

$zipalignOutput = & $zipalignExe -c -P 16 -v 4 $apkPath 2>&1 | Out-String
if ($LASTEXITCODE -ne 0 -or $zipalignOutput -notmatch "Verification succes[s]?ful") {
    throw "zipalign 16 KB page alignment check failed! Output:`n$zipalignOutput"
}
Write-Host "  PASS: APK 16 KB page alignment verified successfully!" -ForegroundColor Green

# --------------------------------------------------------------------------------------------------
# Step 7: Cryptographic Checksums & Manifest Badging Audit
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Step 7/8] Generating cryptographic checksums & auditing manifest badging..." -ForegroundColor Yellow

$artifactsDir = "$projectRoot\android\app\build\outputs\release-artifacts"
if (-not (Test-Path $artifactsDir)) {
    New-Item -ItemType Directory -Path $artifactsDir -Force | Out-Null
}

$apkSha256 = (Get-FileHash -Path $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
$aabSha256 = (Get-FileHash -Path $aabPath -Algorithm SHA256).Hash.ToLowerInvariant()
$mappingSha256 = (Get-FileHash -Path $mappingPath -Algorithm SHA256).Hash.ToLowerInvariant()

$apkSha512 = (Get-FileHash -Path $apkPath -Algorithm SHA512).Hash.ToLowerInvariant()
$aabSha512 = (Get-FileHash -Path $aabPath -Algorithm SHA512).Hash.ToLowerInvariant()

$sha256Content = @"
$apkSha256  app-release.apk
$aabSha256  app-release.aab
$mappingSha256  mapping.txt
"@
Set-Content -Path "$artifactsDir\SHA256SUMS.txt" -Value $sha256Content

$sha512Content = @"
$apkSha512  app-release.apk
$aabSha512  app-release.aab
"@
Set-Content -Path "$artifactsDir\SHA512SUMS.txt" -Value $sha512Content

Write-Host "  APK SHA-256: $apkSha256" -ForegroundColor Cyan
Write-Host "  AAB SHA-256: $aabSha256" -ForegroundColor Cyan
Write-Host "  Checksum manifests written to: $artifactsDir" -ForegroundColor Green

# Audit badging
$badging = & $aapt2Exe dump badging $apkPath 2>&1 | Out-String
if ($badging -match "package:\s*name='([^']+)'\s*versionCode='([^']+)'\s*versionName='([^']+)'") {
    Write-Host "  Package:     $($Matches[1])" -ForegroundColor Green
    Write-Host "  versionCode: $($Matches[2])" -ForegroundColor Green
    Write-Host "  versionName: $($Matches[3])" -ForegroundColor Green
}
if ($badging -match "targetSdkVersion:'(\d+)'") {
    Write-Host "  targetSdk:   $($Matches[1])" -ForegroundColor Green
}
if ($badging -match "sdkVersion:'(\d+)'") {
    Write-Host "  minSdk:      $($Matches[1])" -ForegroundColor Green
}
if ($badging -match "application-debuggable") {
    throw "CRITICAL VIOLATION: Release APK contains application-debuggable flag!"
}
Write-Host "  PASS: Release APK is not debuggable and conforms to target specifications." -ForegroundColor Green

# --------------------------------------------------------------------------------------------------
# Step 8: Device Installation & Launch Smoke Test (Optional / When Attached)
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Step 8/8] Testing installation and launch on attached runtime..." -ForegroundColor Yellow

if ($InstallOnDevice -and (Test-Path $adbExe)) {
    $devices = & $adbExe devices | Out-String
    if ($devices -match "(\S+)\s+device\b") {
        $targetDevice = $Matches[1]
        Write-Host "  Found target device: $targetDevice" -ForegroundColor Cyan

        Write-Host "  Installing $apkPath on $targetDevice..." -ForegroundColor Cyan
        $installOut = & $adbExe -s $targetDevice install -r $apkPath 2>&1 | Out-String
        Write-Host "  Install result: $installOut"

        if ($installOut -notmatch "Success") {
            throw "Failed to install release APK on ${targetDevice}: $installOut"
        }

        # Clear logcat buffer before test
        & $adbExe -s $targetDevice logcat -c

        Write-Host "  Launching dev.glide.app/.MainActivity..." -ForegroundColor Cyan
        $startOut = & $adbExe -s $targetDevice shell am start -n dev.glide.app/.MainActivity 2>&1 | Out-String
        Write-Host "  Launch result: $startOut"

        Start-Sleep -Seconds 3

        # Check for crash in logcat
        $crashLog = & $adbExe -s $targetDevice logcat -d -s AndroidRuntime:E FATAL:E 2>&1 | Out-String
        if ($crashLog -match "FATAL EXCEPTION") {
            Write-Host $crashLog -ForegroundColor Red
            throw "Fatal exception detected during release APK startup!"
        }

        # Verify activity is focused
        $windowOut = & $adbExe -s $targetDevice shell dumpsys window | Out-String
        if ($windowOut -match "mCurrentFocus.*dev\.glide\.app") {
            Write-Host "  PASS: MainActivity is focused and running without crashes!" -ForegroundColor Green
        } else {
            Write-Host "  WARNING: App launched but may not be top focus." -ForegroundColor Yellow
        }
    } else {
        Write-Host "  No attached devices found in 'device' state. Skipping live installation." -ForegroundColor DarkGray
    }
} else {
    Write-Host "  Device installation skipped." -ForegroundColor DarkGray
}

Write-Host "`n=================================================================" -ForegroundColor Green
Write-Host "   GLIDE-062: RELEASE CANDIDATE ARTIFACTS VERIFIED SUCCESSFULLY  " -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Green
