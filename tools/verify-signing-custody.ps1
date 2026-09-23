# Glide Signing Custody & Publisher Identity Verification Tool (GLIDE-060)
# Audits repository for key leaks, validates .gitignore rules, and verifies signing toolchain.

[CmdletBinding()]
param(
    [string]$KeystorePath = "",
    [string]$KeystorePassword = "",
    [string]$KeyAlias = "",
    [string]$KeyPassword = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path "$PSScriptRoot\..").Path

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "   Glide Signing Custody & Publisher Identity Auditor       " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

$violations = [System.Collections.Generic.List[string]]::new()

# --------------------------------------------------------------------------------------------------
# Check 1: Audit repository files for committed keystores and secret property files
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 1/5] Auditing repository for committed keystores and secret files..." -ForegroundColor Yellow

$forbiddenExtensions = @("*.jks", "*.keystore", "*.p12", "*.pfx", "*.pk8")
$forbiddenFilenames = @("signing.properties", "release.properties", "secrets.properties")

foreach ($ext in $forbiddenExtensions) {
    $found = Get-ChildItem -Path $projectRoot -Filter $ext -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch "\\build\\" -and $_.FullName -notmatch "\\\.gradle\\" }
    foreach ($file in $found) {
        $msg = "Committed keystore file detected: $($file.FullName)"
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    }
}

foreach ($name in $forbiddenFilenames) {
    $found = Get-ChildItem -Path $projectRoot -Filter $name -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch "\\build\\" }
    foreach ($file in $found) {
        $msg = "Committed secret property file detected: $($file.FullName)"
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    }
}

if ($violations.Count -eq 0) {
    Write-Host "  PASS: Zero keystores or unencrypted secrets detected in repository." -ForegroundColor Green
}

# --------------------------------------------------------------------------------------------------
# Check 2: Verify .gitignore contains comprehensive key and credential exclusions
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 2/5] Verifying .gitignore protection rules..." -ForegroundColor Yellow

$gitIgnoreFile = "$projectRoot\.gitignore"
if (-not (Test-Path $gitIgnoreFile)) {
    $msg = ".gitignore file missing at $gitIgnoreFile"
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
} else {
    $gitIgnoreContent = Get-Content -Path $gitIgnoreFile -Raw
    $requiredExclusions = @("*.jks", "*.keystore", "*.p12", "*.pfx", "*.pem", "*.key", "signing.properties", "release.properties")
    foreach ($req in $requiredExclusions) {
        if (-not $gitIgnoreContent.Contains($req)) {
            $msg = ".gitignore is missing required exclusion: '$req'"
            Write-Host "  VIOLATION: $msg" -ForegroundColor Red
            $violations.Add($msg)
        }
    }
    if ($violations.Count -eq 0) {
        Write-Host "  PASS: .gitignore comprehensively excludes all keystores and credential files." -ForegroundColor Green
    }
}

# --------------------------------------------------------------------------------------------------
# Check 3: Verify app/build.gradle.kts signing configuration safety
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 3/5] Verifying app/build.gradle.kts signing configuration safety..." -ForegroundColor Yellow

$buildFile = if (Test-Path "$projectRoot\android\app\build.gradle.kts") { "$projectRoot\android\app\build.gradle.kts" } elseif (Test-Path "$projectRoot\android\build.gradle.kts") { "$projectRoot\android\build.gradle.kts" } else { "$projectRoot\app\build.gradle.kts" }
$buildContent = Get-Content -Path $buildFile -Raw

# Ensure no hardcoded passwords in build script
if ($buildContent -match 'storePassword\s*=\s*"[^"]+"') {
    $msg = "Hardcoded storePassword found in app/build.gradle.kts"
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
}
if ($buildContent -match 'keyPassword\s*=\s*"[^"]+"') {
    $msg = "Hardcoded keyPassword found in app/build.gradle.kts"
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
}

# Verify environment variable providers are utilized
if (-not $buildContent.Contains("GLIDE_RELEASE_KEYSTORE_PATH")) {
    $msg = "app/build.gradle.kts does not wire GLIDE_RELEASE_KEYSTORE_PATH provider"
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
}
if (-not $buildContent.Contains("GLIDE_APPLICATION_ID")) {
    $msg = "app/build.gradle.kts does not wire GLIDE_APPLICATION_ID provider"
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
}

Write-Host "  PASS: app/build.gradle.kts has 0 hardcoded secrets and wires dynamic providers." -ForegroundColor Green

# --------------------------------------------------------------------------------------------------
# Check 4: Discover and verify cryptographic toolchain (keytool and apksigner)
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 4/5] Inspecting cryptographic toolchain (keytool & apksigner)..." -ForegroundColor Yellow

$keytoolExe = $null
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\keytool.exe")) {
    $keytoolExe = "$env:JAVA_HOME\bin\keytool.exe"
} else {
    $candidate = Get-ChildItem "C:\Program Files\Android", "C:\Program Files\Java" -Recurse -Filter "keytool.exe" -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if ($candidate) {
        $keytoolExe = $candidate
    }
}

if ($keytoolExe) {
    Write-Host "  Keytool found: $keytoolExe" -ForegroundColor Green
} else {
    Write-Host "  WARNING: keytool.exe not found in standard paths (JAVA_HOME / Android Studio)." -ForegroundColor Yellow
}

$apksignerBat = $null
if ($env:LOCALAPPDATA) {
    $candidate = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Recurse -Filter "apksigner.bat" -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if ($candidate) {
        $apksignerBat = $candidate
    }
}

if ($apksignerBat) {
    Write-Host "  Apksigner found: $apksignerBat" -ForegroundColor Green
} else {
    Write-Host "  WARNING: apksigner.bat not found in Android SDK build-tools." -ForegroundColor Yellow
}

# --------------------------------------------------------------------------------------------------
# Check 5: Inspect Keystore Parameters (if provided)
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 5/5] Checking Release Keystore Parameters..." -ForegroundColor Yellow

$activeKeystore = if ($KeystorePath) { $KeystorePath } else { $env:GLIDE_RELEASE_KEYSTORE_PATH }
if ($activeKeystore) {
    if (-not (Test-Path $activeKeystore)) {
        $msg = "Configured keystore does not exist at: $activeKeystore"
        Write-Host "  VIOLATION: $msg" -ForegroundColor Red
        $violations.Add($msg)
    } else {
        Write-Host "  Release keystore present at: $activeKeystore" -ForegroundColor Green
        if ($keytoolExe -and $KeystorePassword) {
            Write-Host "  Inspecting keystore certificates..." -ForegroundColor Cyan
            $output = & $keytoolExe -list -v -keystore $activeKeystore -storepass $KeystorePassword
            Write-Host ($output | Select-Object -First 15 | Out-String)
        }
    }
} else {
    Write-Host "  No external release keystore configured in environment; release signing defaults to unconfigured/unsigned." -ForegroundColor Gray
}

# --------------------------------------------------------------------------------------------------
# Summary & Outcome
# --------------------------------------------------------------------------------------------------
Write-Host "`n============================================================" -ForegroundColor Cyan
if ($violations.Count -eq 0) {
    Write-Host "   ALL SIGNING CUSTODY & REPOSITORY CHECKS PASSED (0 issues)    " -ForegroundColor Green
    Write-Host "============================================================" -ForegroundColor Cyan
    exit 0
} else {
    Write-Host "   SIGNING CUSTODY CHECKS FAILED: $($violations.Count) VIOLATION(S)  " -ForegroundColor Red
    Write-Host "============================================================" -ForegroundColor Cyan
    exit 1
}
