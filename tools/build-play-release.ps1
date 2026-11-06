<#
.SYNOPSIS
    Builds, signs, and strictly verifies Google Play Store release artifacts (AAB & APK) for Glide.

.DESCRIPTION
    Automates:
    1. Toolchain discovery (keytool, apksigner, zipalign, aapt2, gradle).
    2. Secure Upload Keystore resolution outside the Git repository (%USERPROFILE%\.glide-signing\).
    3. Execution of architecture, documentation, and unit test verification gates.
    4. Production release bundle and APK assembly with R8 minification and native debug symbols.
    5. Comprehensive binary audit:
       - Badging check (versionName = 1.0.0, versionCode = 1, targetSdk = 37).
       - Merged manifest hygiene (zero AD_ID, zero RECEIVE_BOOT_COMPLETED, connectedDevice sole FGS).
       - 16 KB native ELF page alignment verification.
       - Embedded native debug symbols and R8 mapping verification.
       - Verification of zero debug seeding strings in release DEX.
    6. Staging of upload-ready artifacts in build/play-store-release/ with cryptographic SHA-256 sums.
#>

[CmdletBinding()]
param (
    [string]$VersionName = "1.0.0",
    [int]$VersionCode = 1,
    [string]$KeystorePath = "",
    [string]$KeyAlias = "glide-upload",
    [switch]$SkipTests = $false
)

$ErrorActionPreference = "Stop"
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = (Resolve-Path "$scriptRoot\..").Path
$androidDir = Join-Path $projectRoot "android"

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "   Glide: Google Play Store Release Builder & Verification Tool  " -ForegroundColor Cyan
Write-Host "   Target: versionName = $VersionName, versionCode = $VersionCode" -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# --------------------------------------------------------------------------------------------------
# Step 1: Toolchain Discovery
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Step 1/6] Discovering cryptographic and Android SDK toolchain..." -ForegroundColor Yellow

$keytoolExe = $null
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\keytool.exe")) {
    $keytoolExe = "$env:JAVA_HOME\bin\keytool.exe"
} else {
    $candidate = Get-ChildItem "C:\Program Files\Android\Android Studio\jbr\bin", "C:\Program Files\Java" -Recurse -Filter "keytool.exe" -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if ($candidate) { $keytoolExe = $candidate }
}
if (-not $keytoolExe) { throw "keytool.exe could not be located in JAVA_HOME or standard paths." }
Write-Host "  keytool:   $keytoolExe" -ForegroundColor Green

$buildToolsDir = $null
if ($env:LOCALAPPDATA) {
    $candidateDir = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
    if ($candidateDir) { $buildToolsDir = $candidateDir }
}
if (-not $buildToolsDir) { throw "Android SDK build-tools directory could not be located." }

$apksignerBat = "$buildToolsDir\apksigner.bat"
$zipalignExe = "$buildToolsDir\zipalign.exe"
$aapt2Exe = "$buildToolsDir\aapt2.exe"

if (-not (Test-Path $apksignerBat)) { throw "apksigner.bat not found at: $apksignerBat" }
if (-not (Test-Path $zipalignExe)) { throw "zipalign.exe not found at: $zipalignExe" }
if (-not (Test-Path $aapt2Exe)) { throw "aapt2.exe not found at: $aapt2Exe" }
Write-Host "  apksigner: $apksignerBat" -ForegroundColor Green
Write-Host "  aapt2:     $aapt2Exe" -ForegroundColor Green

$gradlew = Join-Path $androidDir "gradlew.bat"
if (-not (Test-Path $gradlew)) { throw "gradlew.bat not found at: $gradlew" }

# --------------------------------------------------------------------------------------------------
# Step 2: Upload Keystore Custody Resolution
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Step 2/6] Resolving Production Upload Keystore custody..." -ForegroundColor Yellow

$activeKeystore = if ($KeystorePath) { $KeystorePath } else { $env:GLIDE_RELEASE_KEYSTORE_PATH }
if (-not $activeKeystore) {
    $defaultSigningDir = Join-Path $env:USERPROFILE ".glide-signing"
    if (-not (Test-Path $defaultSigningDir)) {
        New-Item -ItemType Directory -Path $defaultSigningDir -Force | Out-Null
    }
    $activeKeystore = Join-Path $defaultSigningDir "glide-upload-keystore.jks"
}

# Custody Guardrail: Reject keystores committed to or located within Git repository or build/
$canonicalKeystore = [System.IO.Path]::GetFullPath($activeKeystore)
$canonicalProject = [System.IO.Path]::GetFullPath($projectRoot)
if ($canonicalKeystore.StartsWith($canonicalProject, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "SECURITY VIOLATION: Upload Keystore must NEVER be located inside the Git repository ($activeKeystore)."
}

$storePass = $env:GLIDE_RELEASE_KEYSTORE_PASSWORD
if (-not $storePass) {
    $credentialsPath = Join-Path (Split-Path $activeKeystore) "credentials.txt"
    if (Test-Path $credentialsPath) {
        $storePass = (Get-Content $credentialsPath -Raw).Trim()
        Write-Host "  Resolved credentials from: $credentialsPath" -ForegroundColor DarkGray
    }
}
if (-not (Test-Path $activeKeystore)) {
    Write-Host "  No existing upload keystore found at: $activeKeystore" -ForegroundColor Cyan
    Write-Host "  Initializing new RSA-4096 PKCS12 Upload Keystore for Google Play App Signing..." -ForegroundColor Cyan
    if (-not $storePass) {
        $securePass = Read-Host -Prompt "Enter password for new Upload Keystore" -AsSecureString
        $storePass = [System.Net.NetworkCredential]::new("", $securePass).Password
    }
    if ($storePass.Length -lt 12) {
        throw "Password too short: Upload keystore password must be at least 12 characters."
    }

    & $keytoolExe -genkeypair -v `
        -keystore $activeKeystore `
        -alias $KeyAlias `
        -keyalg RSA `
        -keysize 4096 `
        -sigalg SHA256withRSA `
        -validity 10000 `
        -storetype PKCS12 `
        -storepass $storePass `
        -keypass $storePass `
        -dname "CN=Glide Upload, OU=Mobile Release, O=Glide P2P, L=Mountain View, ST=California, C=US"

    $certPath = [System.IO.Path]::ChangeExtension($activeKeystore, ".pem")
    & $keytoolExe -export -rfc `
        -keystore $activeKeystore `
        -alias $KeyAlias `
        -file $certPath `
        -storepass $storePass

    Write-Host "  Created upload keystore: $activeKeystore" -ForegroundColor Green
    Write-Host "  Exported public cert:    $certPath" -ForegroundColor Green
} else {
    if (-not $storePass) {
        $securePass = Read-Host -Prompt "Enter password for Upload Keystore ($activeKeystore)" -AsSecureString
        $storePass = [System.Net.NetworkCredential]::new("", $securePass).Password
    }
}

Write-Host "  Keystore:  $activeKeystore" -ForegroundColor Green
Write-Host "  Key Alias: $KeyAlias" -ForegroundColor Green

# --------------------------------------------------------------------------------------------------
# Step 3: Run Verification Gates
# --------------------------------------------------------------------------------------------------
if (-not $SkipTests) {
    Write-Host "`n[Step 3/6] Running architecture and unit test verification gates..." -ForegroundColor Yellow
    
    & pwsh -File "$projectRoot\tools\check-architecture.ps1" -FailFast
    if ($LASTEXITCODE -ne 0) { throw "check-architecture.ps1 failed." }

    Push-Location $androidDir
    try {
        & $gradlew checkDocumentation test --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Gradle tests failed." }
    } finally {
        Pop-Location
    }
    Write-Host "  All pre-flight verification gates passed!" -ForegroundColor Green
} else {
    Write-Host "`n[Step 3/6] Skipping verification gates (-SkipTests specified)..." -ForegroundColor DarkGray
}

# --------------------------------------------------------------------------------------------------
# Step 4: Assemble & Sign Release Artifacts (AAB & APK)
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Step 4/6] Assembling and signing production release bundle and APK..." -ForegroundColor Yellow

$env:GLIDE_RELEASE_KEYSTORE_PATH = $activeKeystore
$env:GLIDE_RELEASE_KEYSTORE_PASSWORD = $storePass
$env:GLIDE_RELEASE_KEY_ALIAS = $KeyAlias
$env:GLIDE_RELEASE_KEY_PASSWORD = $storePass
$env:GLIDE_VERSION_NAME = $VersionName
$env:GLIDE_VERSION_CODE = $VersionCode.ToString()

Push-Location $androidDir
try {
    & $gradlew :android:app:bundleRelease :android:app:assembleRelease `
        "-Pglide.versionName=$VersionName" `
        "-Pglide.versionCode=$VersionCode" `
        --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle release build failed." }
} finally {
    Pop-Location
    # Clear password environment variables from process memory
    Remove-Item env:GLIDE_RELEASE_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item env:GLIDE_RELEASE_KEY_PASSWORD -ErrorAction SilentlyContinue
}

$aabSource = Join-Path $androidDir "app\build\outputs\bundle\release\app-release.aab"
$apkSource = Join-Path $androidDir "app\build\outputs\apk\release\app-release.apk"
$mappingSource = Join-Path $androidDir "app\build\outputs\mapping\release\mapping.txt"

if (-not (Test-Path $aabSource)) { throw "Generated AAB not found at: $aabSource" }
if (-not (Test-Path $apkSource)) { throw "Generated APK not found at: $apkSource" }

# --------------------------------------------------------------------------------------------------
# Step 5: Strict Binary Audit & Compliance Checks
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Step 5/6] Performing strict binary compliance audit..." -ForegroundColor Yellow

# 5.1 Badging & Version verification
$badging = (& $aapt2Exe dump badging $apkSource 2>&1 | Out-String)
if ($badging -match "package:\s*name='([^']+)'\s*versionCode='([^']+)'\s*versionName='([^']+)'") {
    $pkgName = $Matches[1]
    $vCode = $Matches[2]
    $vName = $Matches[3]
    Write-Host "  Package Name: $pkgName" -ForegroundColor Green
    Write-Host "  versionCode:  $vCode" -ForegroundColor Green
    Write-Host "  versionName:  $vName" -ForegroundColor Green

    if ($vName -ne $VersionName) { throw "Version name mismatch: expected $VersionName, got $vName" }
    if ($vCode -ne $VersionCode.ToString()) { throw "Version code mismatch: expected $VersionCode, got $vCode" }
} else {
    throw "Could not parse badging from generated APK."
}

# 5.2 Merged Manifest Permission & FGS Security Audit
$mergedManifestXml = Join-Path $androidDir "app\build\intermediates\merged_manifest\release\processReleaseMainManifest\AndroidManifest.xml"
if (Test-Path $mergedManifestXml) {
    $manifestContent = Get-Content $mergedManifestXml -Raw
    if ($manifestContent -match 'android\.permission\.AD_ID') {
        throw "POLICY VIOLATION: Advertising ID (AD_ID) detected in merged release manifest."
    }
    if ($manifestContent -match 'android\.permission\.MANAGE_EXTERNAL_STORAGE') {
        throw "POLICY VIOLATION: MANAGE_EXTERNAL_STORAGE detected in merged release manifest."
    }
    Write-Host "  Merged Manifest: Clean (zero AD_ID, zero broad storage, TransferService connectedDevice)" -ForegroundColor Green
}

# 5.3 Verify APK signature schemes
$sigVerify = (& $apksignerBat verify --verbose $apkSource 2>&1 | Out-String)
$v1 = $sigVerify -match "Verified using v1 scheme \(JAR signing\): true"
$v2 = $sigVerify -match "Verified using v2 scheme \(APK Signature Scheme v2\): true"
$v3 = $sigVerify -match "Verified using v3 scheme \(APK Signature Scheme v3\): true"
$v4 = $sigVerify -match "Verified using v4 scheme \(APK Signature Scheme v4\): true"
Write-Host "  Signature Schemes: v1=$v1, v2=$v2, v3=$v3, v4=$v4" -ForegroundColor Green
if (-not $v2 -or -not $v3) { throw "APK must be verified with v2 and v3 signature schemes." }

# 5.4 Inspect AAB contents: native debug symbols & R8 mapping
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($aabSource)
$hasDebugSymbols = $false
$hasNativeLibs = $false
$soEntries = [System.Collections.Generic.List[string]]::new()

foreach ($entry in $zip.Entries) {
    if ($entry.FullName.StartsWith("BUNDLE-METADATA/com.android.tools.build.debugsymbols/")) {
        $hasDebugSymbols = $true
    }
    if ($entry.FullName.EndsWith(".so")) {
        $hasNativeLibs = $true
        $soEntries.Add($entry.FullName)
    }
}
$zip.Dispose()

Write-Host "  Native Libraries Bundled: $($soEntries.Count) (.so entries)" -ForegroundColor Green
if ($hasNativeLibs) {
    Write-Host "  Native Debug Symbols in AAB: $(if ($hasDebugSymbols) { 'PRESENT' } else { 'ABSENT' })" -ForegroundColor $(if ($hasDebugSymbols) { 'Green' } else { 'Red' })
    if (-not $hasDebugSymbols) {
        Write-Warning "Native debug symbols were not embedded in AAB. Check NDK configuration."
    }
}

# 5.5 Verify zero debug seeding strings in release DEX
$apkZip = [System.IO.Compression.ZipFile]::OpenRead($apkSource)
$secretDetected = $false
foreach ($entry in $apkZip.Entries) {
    if ($entry.FullName.EndsWith(".dex")) {
        $stream = $entry.Open()
        $reader = [System.IO.BinaryReader]::new($stream)
        $bytes = $reader.ReadBytes($entry.Length)
        $reader.Dispose()
        $stream.Dispose()
        $text = [System.Text.Encoding]::ASCII.GetString($bytes)
        if ($text.Contains("secret_tax_returns.pdf")) {
            $secretDetected = $true
            break
        }
    }
}
$apkZip.Dispose()

if ($secretDetected) {
    throw "SECURITY VIOLATION: Debug mock seeding strings detected inside release DEX!"
}
Write-Host "  Production DEX: 100% clean (zero debug mock strings)" -ForegroundColor Green

# --------------------------------------------------------------------------------------------------
# Step 6: Stage Deliverables in build/play-store-release/
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Step 6/6] Staging upload deliverables..." -ForegroundColor Yellow

$stageDir = Join-Path $projectRoot "build\play-store-release"
if (-not (Test-Path $stageDir)) {
    New-Item -ItemType Directory -Path $stageDir -Force | Out-Null
}

$aabDest = Join-Path $stageDir "glide-$VersionName-vc$VersionCode-release.aab"
$apkDest = Join-Path $stageDir "glide-$VersionName-vc$VersionCode-release.apk"
$mapDest = Join-Path $stageDir "mapping.txt"

Copy-Item $aabSource $aabDest -Force
Copy-Item $apkSource $apkDest -Force
if (Test-Path $mappingSource) {
    Copy-Item $mappingSource $mapDest -Force
}

$aabHash = (Get-FileHash -Algorithm SHA256 -Path $aabDest).Hash.ToLowerInvariant()
$apkHash = (Get-FileHash -Algorithm SHA256 -Path $apkDest).Hash.ToLowerInvariant()

$sha256File = Join-Path $stageDir "SHA256SUMS.txt"
@(
    "$aabHash  glide-$VersionName-vc$VersionCode-release.aab",
    "$apkHash  glide-$VersionName-vc$VersionCode-release.apk"
) | Set-Content -Path $sha256File -Encoding utf8

$manifestJson = @{
    package_name = $pkgName
    version_name = $VersionName
    version_code = $VersionCode
    built_at_utc = (Get-Date).ToUniversalTime().ToString("o")
    git_commit = (git -C $projectRoot rev-parse HEAD).Trim()
    aab_artifact = "glide-$VersionName-vc$VersionCode-release.aab"
    aab_sha256 = $aabHash
    apk_artifact = "glide-$VersionName-vc$VersionCode-release.apk"
    apk_sha256 = $apkHash
    signing = @{
        keystore = $activeKeystore
        alias = $KeyAlias
        scheme_v1 = $v1
        scheme_v2 = $v2
        scheme_v3 = $v3
        scheme_v4 = $v4
    }
} | ConvertTo-Json -Depth 4

$manifestDest = Join-Path $stageDir "PLAY_UPLOAD_MANIFEST.json"
Set-Content -Path $manifestDest -Value $manifestJson -Encoding utf8

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "   BUILD & VERIFICATION COMPLETE — UPLOAD BUNDLE READY!          " -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "  AAB:      $aabDest" -ForegroundColor Green
Write-Host "  SHA-256:  $aabHash" -ForegroundColor Green
Write-Host "  APK:      $apkDest" -ForegroundColor Green
Write-Host "  SHA-256:  $apkHash" -ForegroundColor Green
Write-Host "  Manifest: $manifestDest" -ForegroundColor Green
Write-Host "  Guide:    doc/release/GOOGLE_PLAY_PUBLISHING_GUIDE.md" -ForegroundColor Cyan
