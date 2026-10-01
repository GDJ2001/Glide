# Glide Architecture & Static-Analysis Enforcement Script (GLIDE-055)
# Enforces import boundaries, pure JVM core isolation, UI main-thread I/O prevention, and dependency catalog usage.

[CmdletBinding()]
param(
    [switch]$FailFast = $false
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path "$PSScriptRoot\..").Path

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "   Glide Architecture & Static-Analysis Enforcement Suite   " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

$violations = [System.Collections.Generic.List[string]]::new()

function Report-Violation([string]$rule, [string]$file, [int]$lineNum, [string]$detail) {
    $relative = Resolve-Path -Relative -Path $file
    $msg = "[$rule] $relative : line $lineNum -> $detail"
    Write-Host "  VIOLATION: $msg" -ForegroundColor Red
    $violations.Add($msg)
    if ($FailFast) {
        Write-Error "Architecture check failed (FailFast enabled)."
        exit 1
    }
}

# --------------------------------------------------------------------------------------------------
# Rule 1: UI / Navigation Layer Architectural Boundaries
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Check 1/7] Scanning UI / Navigation layer for forbidden transport/Nearby/socket imports..." -ForegroundColor Yellow

$uiPaths = @(
    "$projectRoot/android/app/src/main/kotlin/dev/glide/app/ui",
    "$projectRoot/android/app/src/main/kotlin/dev/glide/app/navigation",
    "$projectRoot/android/designsystem/src/main/kotlin/dev/glide/designsystem"
)

$forbiddenUiImports = @(
    "com.google.android.gms.nearby",
    "dev.glide.transport.nearby",
    "android.bluetooth",
    "android.net.wifi",
    "java.net.Socket",
    "java.net.ServerSocket",
    "androidx.room."
)

foreach ($dir in $uiPaths) {
    if (Test-Path $dir) {
        Get-ChildItem -Path $dir -Filter "*.kt" -Recurse | ForEach-Object {
            $lines = Get-Content -Path $_.FullName
            for ($i = 0; $i -lt $lines.Count; $i++) {
                $trimmed = $lines[$i].Trim()
                if ($trimmed.StartsWith("import ")) {
                    $imported = $trimmed.Substring(7).Trim()
                    foreach ($forbidden in $forbiddenUiImports) {
                        if ($imported.StartsWith($forbidden)) {
                            Report-Violation "RULE_1_UI_NEARBY_IMPORT" $_.FullName ($i + 1) "Forbidden import '$imported'"
                        }
                    }
                }
            }
        }
    }
}

# --------------------------------------------------------------------------------------------------
# Rule 2: :transfer-core Pure Kotlin JVM Isolation
# --------------------------------------------------------------------------------------------------
Write-Host "[Check 2/7] Verifying :transfer-core pure Kotlin JVM isolation (zero Android dependencies)..." -ForegroundColor Yellow

$coreDirs = @(
    "$projectRoot/android/core/src/main/kotlin"
)
$forbiddenCoreImports = @(
    "android.",
    "androidx.",
    "com.google.android.gms."
)

foreach ($coreDir in $coreDirs) {
    if (Test-Path $coreDir) {
        Get-ChildItem -Path $coreDir -Filter "*.kt" -Recurse | ForEach-Object {
            $lines = Get-Content -Path $_.FullName
            for ($i = 0; $i -lt $lines.Count; $i++) {
                $trimmed = $lines[$i].Trim()
                if ($trimmed.StartsWith("import ")) {
                    $imported = $trimmed.Substring(7).Trim()
                    foreach ($forbidden in $forbiddenCoreImports) {
                        if ($imported.StartsWith($forbidden)) {
                            Report-Violation "RULE_2_CORE_ANDROID_ISOLATION" $_.FullName ($i + 1) "Core must not import Android API '$imported'"
                        }
                    }
                }
            }
        }
    }
}

# --------------------------------------------------------------------------------------------------
# Rule 3: Transport Layer Boundaries
# --------------------------------------------------------------------------------------------------
Write-Host "[Check 3/7] Verifying Transport adapter does not import UI, ViewModel, or Data layers..." -ForegroundColor Yellow

$transportDirs = @(
    "$projectRoot/android/transport-nearby/src/main/kotlin",
    "$projectRoot/android/transport-lan/src/main/kotlin"
)
$forbiddenTransportImports = @(
    "dev.glide.app.ui.",
    "dev.glide.app.navigation.",
    "dev.glide.data.",
    "androidx.lifecycle.ViewModel",
    "androidx.compose."
)

foreach ($transportDir in $transportDirs) {
    if (Test-Path $transportDir) {
        Get-ChildItem -Path $transportDir -Filter "*.kt" -Recurse | ForEach-Object {
            $lines = Get-Content -Path $_.FullName
            for ($i = 0; $i -lt $lines.Count; $i++) {
                $trimmed = $lines[$i].Trim()
                if ($trimmed.StartsWith("import ")) {
                    $imported = $trimmed.Substring(7).Trim()
                    foreach ($forbidden in $forbiddenTransportImports) {
                        if ($imported.StartsWith($forbidden)) {
                            Report-Violation "RULE_3_TRANSPORT_LEAKAGE" $_.FullName ($i + 1) "Transport adapter imports UI/Data '$imported'"
                        }
                    }
                }
            }
        }
    }
}

# --------------------------------------------------------------------------------------------------
# Rule 4: Data Layer Boundaries
# --------------------------------------------------------------------------------------------------
Write-Host "[Check 4/7] Verifying Data layer does not import UI or Transport implementations..." -ForegroundColor Yellow

$dataDirs = @(
    "$projectRoot/android/data/src/main/kotlin"
)
$forbiddenDataImports = @(
    "dev.glide.app.ui.",
    "dev.glide.app.navigation.",
    "dev.glide.transport.nearby.",
    "com.google.android.gms.nearby.",
    "androidx.compose."
)

foreach ($dataDir in $dataDirs) {
    if (Test-Path $dataDir) {
        Get-ChildItem -Path $dataDir -Filter "*.kt" -Recurse | ForEach-Object {
            $lines = Get-Content -Path $_.FullName
            for ($i = 0; $i -lt $lines.Count; $i++) {
                $trimmed = $lines[$i].Trim()
                if ($trimmed.StartsWith("import ")) {
                    $imported = $trimmed.Substring(7).Trim()
                    foreach ($forbidden in $forbiddenDataImports) {
                        if ($imported.StartsWith($forbidden)) {
                            Report-Violation "RULE_4_DATA_LEAKAGE" $_.FullName ($i + 1) "Data layer imports UI/Transport '$imported'"
                        }
                    }
                }
            }
        }
    }
}

# --------------------------------------------------------------------------------------------------
# Rule 5: Main-Thread I/O Pattern Prevention in UI
# --------------------------------------------------------------------------------------------------
Write-Host "[Check 5/7] Scanning UI code for blocking main-thread file I/O operations..." -ForegroundColor Yellow

$uiDirs = @(
    "$projectRoot/android/app/src/main/kotlin/dev/glide/app/ui"
)
$blockingIoRegex = [regex]'\b(File|file)\s*\([^)]*\)\.(readBytes|readText|readLines|writeBytes|writeText|appendText)\(|\b(FileInputStream|FileOutputStream|RandomAccessFile|FileReader|FileWriter)\(|\bFiles\.(read|write|copy|move|delete)'

foreach ($uiDir in $uiDirs) {
    if (Test-Path $uiDir) {
        Get-ChildItem -Path $uiDir -Filter "*.kt" -Recurse | ForEach-Object {
            $lines = Get-Content -Path $_.FullName
            for ($i = 0; $i -lt $lines.Count; $i++) {
                $trimmed = $lines[$i].Trim()
                if (-not $trimmed.StartsWith("//") -and -not $trimmed.StartsWith("*") -and -not $trimmed.StartsWith("/*")) {
                    if ($blockingIoRegex.IsMatch($trimmed)) {
                        Report-Violation "RULE_5_UI_MAIN_THREAD_IO" $_.FullName ($i + 1) "Blocking I/O in UI layer: '$trimmed'"
                    }
                }
            }
        }
    }
}

# --------------------------------------------------------------------------------------------------
# Rule 6: Room Database allowMainThreadQueries Prevention
# --------------------------------------------------------------------------------------------------
Write-Host "[Check 6/7] Verifying zero allowMainThreadQueries() in Room database builders..." -ForegroundColor Yellow

$scanPaths = @(
    "$projectRoot/android/app/src/main/kotlin",
    "$projectRoot/android/data/src/main/kotlin"
)

foreach ($dir in $scanPaths) {
    if (Test-Path $dir) {
        Get-ChildItem -Path $dir -Filter "*.kt" -Recurse | ForEach-Object {
            $lines = Get-Content -Path $_.FullName
            for ($i = 0; $i -lt $lines.Count; $i++) {
                if ($lines[$i].Contains("allowMainThreadQueries()")) {
                    Report-Violation "RULE_6_ROOM_MAIN_THREAD_QUERIES" $_.FullName ($i + 1) "allowMainThreadQueries() is forbidden in Room builders"
                }
            }
        }
    }
}

# --------------------------------------------------------------------------------------------------
# Rule 7: Version Catalog Integrity & Unreviewed Dependency Check
# --------------------------------------------------------------------------------------------------
Write-Host "[Check 7/7] Verifying dependency catalog usage and zero unreviewed dependencies..." -ForegroundColor Yellow

$buildFiles = @(
    "$projectRoot/build.gradle.kts",
    "$projectRoot/android/app/build.gradle.kts",
    "$projectRoot/android/core/build.gradle.kts",
    "$projectRoot/android/data/build.gradle.kts",
    "$projectRoot/android/designsystem/build.gradle.kts",
    "$projectRoot/android/transport-lan/build.gradle.kts",
    "$projectRoot/android/transport-nearby/build.gradle.kts",
    "$projectRoot/android/benchmark/build.gradle.kts"
)

$forbiddenDepKeywords = @(
    "play-services-ads", "admob", "facebook-ads", "unity-ads", "applovin", "ironsource", "mopub",
    "firebase-analytics", "firebase-crashlytics", "mixpanel", "amplitude", "segment", "flurry", "google-analytics",
    "play-services-auth", "firebase-auth", "facebook-login", "auth0"
)

$directStringCoordRegex = [regex]'(implementation|api|ksp|debugImplementation|testImplementation|androidTestImplementation)\s*\(\s*["''][^"'']+:[^"'']+:[^"'']+["'']\s*\)'

foreach ($file in $buildFiles) {
    if (Test-Path $file) {
        $lines = Get-Content -Path $file
        for ($i = 0; $i -lt $lines.Count; $i++) {
            $trimmed = $lines[$i].Trim()
            if (-not $trimmed.StartsWith("//")) {
                $lower = $trimmed.ToLower()
                foreach ($banned in $forbiddenDepKeywords) {
                    if ($lower.Contains($banned)) {
                        Report-Violation "RULE_7_BANNED_DEPENDENCY" $file ($i + 1) "Forbidden dependency '$banned'"
                    }
                }
                if ($directStringCoordRegex.IsMatch($trimmed)) {
                    Report-Violation "RULE_7_UNREVIEWED_DEPENDENCY" $file ($i + 1) "Uncataloged direct string coordinate: '$trimmed' (must use version catalog libs.*)"
                }
            }
        }
    }
}

# --------------------------------------------------------------------------------------------------
# Summary & Outcome
# --------------------------------------------------------------------------------------------------
Write-Host "`n============================================================" -ForegroundColor Cyan
if ($violations.Count -eq 0) {
    Write-Host "   ALL ARCHITECTURE & STATIC-ANALYSIS CHECKS PASSED (0 issues)  " -ForegroundColor Green
    Write-Host "============================================================" -ForegroundColor Cyan
    exit 0
} else {
    Write-Host "   ARCHITECTURE CHECKS FAILED: $($violations.Count) VIOLATION(S) DETECTED " -ForegroundColor Red
    Write-Host "============================================================" -ForegroundColor Cyan
    exit 1
}
