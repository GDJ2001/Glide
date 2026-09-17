<#
.SYNOPSIS
    Automated auditor for Glide ADR-0020 & Apple Platforms (iOS, iPadOS, macOS) Interoperability Specification.
.DESCRIPTION
    Validates ADR-0020, technical specification completeness, zero-cloud offline invariants,
    Apple Network.framework transport mapping, Bonjour discovery contracts, pairing UX,
    iOS/macOS lifecycle rules, quarantine staging, App Store guidelines, and privacy entitlements.
#>

$ErrorActionPreference = "Stop"
$ProjectRoot = Resolve-Path "$PSScriptRoot\.."

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "    Glide ADR-0020: Apple Platforms Specification Auditor       " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# --- Check 1: ADR-0020 Existence, Accepted Status, and Decision Index ---
Write-Host "`n[Check 1/10] Auditing ADR-0020 existence, Accepted status, and index..." -ForegroundColor Yellow
$AdrPath = Join-Path $ProjectRoot "docs\adr\ADR-0020-apple-platforms-architecture.md"
$AdrIndex = Join-Path $ProjectRoot "docs\adr\README.md"

if (-not (Test-Path $AdrPath)) {
    Write-Error "FAIL: ADR-0020 not found at $AdrPath"
}
$AdrContent = Get-Content $AdrPath -Raw
if ($AdrContent -notmatch "Status.*\bAccepted\b") {
    Write-Error "FAIL: ADR-0020 status must be Accepted."
}
if ($AdrContent -notmatch "Network\.framework" -or $AdrContent -notmatch "TLS 1\.3") {
    Write-Error "FAIL: ADR-0020 missing Network.framework and TLS 1.3 decisions."
}
$IndexContent = Get-Content $AdrIndex -Raw
if ($IndexContent -notmatch "ADR-0020 — Apple platforms") {
    Write-Error "FAIL: ADR-0020 not properly indexed in docs/adr/README.md."
}
Write-Host "  PASS: ADR-0020 exists, is marked Accepted, and is indexed in README.md." -ForegroundColor Green

# --- Check 2: Roadmap Alignment in CROSS_PLATFORM_PATH.md ---
Write-Host "`n[Check 2/10] Auditing Roadmap Alignment in CROSS_PLATFORM_PATH.md..." -ForegroundColor Yellow
$PathDoc = Join-Path $ProjectRoot "docs\future\CROSS_PLATFORM_PATH.md"
$PathContent = Get-Content $PathDoc -Raw
if ($PathContent -notmatch "4\.\s*iOS/iPadOS and macOS.*ADR-0020.*APPLE_PLATFORMS_SPECIFICATION\.md") {
    Write-Error "FAIL: CROSS_PLATFORM_PATH.md Step 4 does not reference ADR-0020 and APPLE_PLATFORMS_SPECIFICATION.md."
}
Write-Host "  PASS: CROSS_PLATFORM_PATH.md Step 4 properly references ADR-0020 and specification." -ForegroundColor Green

# --- Check 3: Apple Platforms Specification Completeness & GlideCoreKit SPM ---
Write-Host "`n[Check 3/10] Auditing Apple Platforms technical specification & GlideCoreKit package..." -ForegroundColor Yellow
$SpecPath = Join-Path $ProjectRoot "docs\future\APPLE_PLATFORMS_SPECIFICATION.md"
if (-not (Test-Path $SpecPath)) {
    Write-Error "FAIL: Specification not found at $SpecPath"
}
$SpecContent = Get-Content $SpecPath -Raw
$RequiredTopics = @(
    "Zero Cloud / 100% Offline",
    "GlideCoreKit",
    "Network.framework",
    "NWListener",
    "NWConnection",
    "sec_protocol_options_set_verify_block",
    "_glide._tcp",
    "Short Authentication String (SAS)",
    "HKDF-Extract",
    "CIQRCodeGenerator",
    "AVCaptureSession",
    "beginBackgroundTask",
    "LSUIElement",
    "Quarantine Staging Store",
    "CryptoKit.SHA256",
    "NSLocalNetworkUsageDescription",
    "com.apple.security.app-sandbox",
    "Phase A1",
    "Phase A6"
)
foreach ($topic in $RequiredTopics) {
    if ($SpecContent -notmatch [regex]::Escape($topic)) {
        Write-Error "FAIL: Specification missing required topic: $topic"
    }
}
$PkgPath = Join-Path $ProjectRoot "apple\GlideCoreKit\Package.swift"
if (-not (Test-Path $PkgPath)) {
    Write-Error "FAIL: GlideCoreKit Package.swift not found at $PkgPath"
}
Write-Host "  PASS: Specification completeness and GlideCoreKit SPM package verified." -ForegroundColor Green

# --- Check 4: Zero-Cloud & 100% Offline Invariants ---
Write-Host "`n[Check 4/10] Auditing Zero-Cloud & 100% Offline Invariants..." -ForegroundColor Yellow
if ($SpecContent -notmatch "Zero Cloud" -or $SpecContent -notmatch "100% Offline") {
    Write-Error "FAIL: Specification missing explicit Zero Cloud / 100% Offline invariant statement."
}
if ($SpecContent -notmatch "APNs" -or $SpecContent -notmatch "iCloud") {
    Write-Error "FAIL: Specification must explicitly disclaim APNs and iCloud reliance."
}
Write-Host "  PASS: Zero-cloud, 100% offline, and zero-APNs/iCloud invariants verified." -ForegroundColor Green

# --- Check 5: Apple Network.framework Wire Mapping, LanRecord Codec & SPKI Pinning ---
Write-Host "`n[Check 5/10] Auditing Network.framework Wire Mapping, LanRecord Codec & SPKI Pinning..." -ForegroundColor Yellow
if ($SpecContent -notmatch "NWListener" -or $SpecContent -notmatch "42424") {
    Write-Error "FAIL: Specification missing NWListener port 42424 configuration."
}
if ($SpecContent -notmatch "LanRecord" -or $SpecContent -notmatch "Protocol V1") {
    Write-Error "FAIL: Specification must require LanRecord framing and Protocol V1 compatibility."
}
if ($SpecContent -notmatch "TLSv13") {
    Write-Error "FAIL: Specification must mandate TLS 1.3 protocol version."
}
$LanRecordPath = Join-Path $ProjectRoot "apple\GlideCoreKit\Sources\GlideCoreKit\Models\LanRecord.swift"
$LanRecordTestPath = Join-Path $ProjectRoot "apple\GlideCoreKit\Tests\GlideCoreKitTests\LanRecordTests.swift"
$SpkiPath = Join-Path $ProjectRoot "apple\GlideCoreKit\Sources\GlideCoreKit\Transport\SpkiPinningValidator.swift"
$SpkiTestPath = Join-Path $ProjectRoot "apple\GlideCoreKit\Tests\GlideCoreKitTests\SpkiPinningValidatorTests.swift"
$ConfigPath = Join-Path $ProjectRoot "apple\GlideCoreKit\Sources\GlideCoreKit\Transport\NetworkTransportConfig.swift"
$ConfigTestPath = Join-Path $ProjectRoot "apple\GlideCoreKit\Tests\GlideCoreKitTests\NetworkTransportConfigTests.swift"

foreach ($f in @($LanRecordPath, $LanRecordTestPath, $SpkiPath, $SpkiTestPath, $ConfigPath, $ConfigTestPath)) {
    if (-not (Test-Path $f)) {
        Write-Error "FAIL: Missing Network.framework transport component: $f"
    }
}
$LanContent = Get-Content $LanRecordPath -Raw
if ($LanContent -notmatch "LanRecordCodec" -or $LanContent -notmatch "headerSize = 5" -or $LanContent -notmatch "maxRecordPayloadSize") {
    Write-Error "FAIL: LanRecord.swift missing LanRecordCodec or framing bounds."
}
$SpkiContent = Get-Content $SpkiPath -Raw
if ($SpkiContent -notmatch "computeSpkiSha256Hex" -or $SpkiContent -notmatch "verify") {
    Write-Error "FAIL: SpkiPinningValidator.swift missing computeSpkiSha256Hex or verify methods."
}
$ConfigContent = Get-Content $ConfigPath -Raw
if ($ConfigContent -notmatch "defaultPort.*42424" -or $ConfigContent -notmatch "minTlsVersion.*TLSv1\.3") {
    Write-Error "FAIL: NetworkTransportConfig.swift missing default port or TLS 1.3 requirement."
}
Write-Host "  PASS: Network.framework, port 42424, TLS 1.3, SPKI pinning, and LanRecord framing verified." -ForegroundColor Green

# --- Check 6: Bonjour Discovery Specification & BonjourService ---
Write-Host "`n[Check 6/10] Auditing Bonjour Discovery Specification & BonjourService..." -ForegroundColor Yellow
if ($SpecContent -notmatch "_glide\._tcp" -or $SpecContent -notmatch "local\.") {
    Write-Error "FAIL: Specification missing _glide._tcp Bonjour service type in local. domain."
}
if ($SpecContent -notmatch "spki" -or $SpecContent -notmatch "presence") {
    Write-Error "FAIL: Specification missing TXT record schema attributes (spki, presence)."
}
$BonjourPath = Join-Path $ProjectRoot "apple\GlideCoreKit\Sources\GlideCoreKit\Transport\BonjourService.swift"
$BonjourTestPath = Join-Path $ProjectRoot "apple\GlideCoreKit\Tests\GlideCoreKitTests\BonjourServiceTests.swift"
foreach ($f in @($BonjourPath, $BonjourTestPath)) {
    if (-not (Test-Path $f)) {
        Write-Error "FAIL: Missing Bonjour service component: $f"
    }
}
$BonjourContent = Get-Content $BonjourPath -Raw
if ($BonjourContent -notmatch "buildTxtRecord" -or $BonjourContent -notmatch "parseTxtRecord" -or $BonjourContent -notmatch "BonjourPeer") {
    Write-Error "FAIL: BonjourService.swift missing buildTxtRecord, parseTxtRecord, or BonjourPeer."
}
Write-Host "  PASS: Bonjour service type (_glide._tcp), TXT record schema, and BonjourService verified." -ForegroundColor Green

# --- Check 7: Authentication & Pairing Parity (SAS & Invitation Codecs) ---
Write-Host "`n[Check 7/10] Auditing Authentication & Pairing Parity (SAS & Invitation Codecs)..." -ForegroundColor Yellow
if ($SpecContent -notmatch "HKDF-Extract" -or $SpecContent -notmatch "GLIDE-SAS-V1") {
    Write-Error "FAIL: Specification missing HKDF-SHA256 SAS calculation contract."
}
if ($SpecContent -notmatch "CIQRCodeGenerator" -or $SpecContent -notmatch "AVCaptureMetadataOutput") {
    Write-Error "FAIL: Specification missing CoreImage QR rendering and AVFoundation QR capture."
}
$SasPath = Join-Path $ProjectRoot "apple\GlideCoreKit\Sources\GlideCoreKit\Security\SasCalculator.swift"
$SasTestPath = Join-Path $ProjectRoot "apple\GlideCoreKit\Tests\GlideCoreKitTests\SasCalculatorTests.swift"
$InvPath = Join-Path $ProjectRoot "apple\GlideCoreKit\Sources\GlideCoreKit\Security\InvitationCodec.swift"
$InvTestPath = Join-Path $ProjectRoot "apple\GlideCoreKit\Tests\GlideCoreKitTests\InvitationCodecTests.swift"
foreach ($f in @($SasPath, $SasTestPath, $InvPath, $InvTestPath)) {
    if (-not (Test-Path $f)) {
        Write-Error "FAIL: Missing security component: $f"
    }
}
$SasContent = Get-Content $SasPath -Raw
if ($SasContent -notmatch "extractDecimal6" -or $SasContent -notmatch "calculateSas") {
    Write-Error "FAIL: SasCalculator.swift missing extractDecimal6 or calculateSas methods."
}
$InvContent = Get-Content $InvPath -Raw
if ($InvContent -notmatch "glide://pair/v1#" -or $InvContent -notmatch "maxUriChars" -or $InvContent -notmatch "maxJsonBytes") {
    Write-Error "FAIL: InvitationCodec.swift missing URI prefix or bounds."
}
Write-Host "  PASS: 6-digit numeric SAS, out-of-band QR pairing, and Swift codecs verified." -ForegroundColor Green

# --- Check 8: iOS Lifecycle & macOS Companion Invariants ---
Write-Host "`n[Check 8/10] Auditing iOS Lifecycle & macOS Companion Invariants..." -ForegroundColor Yellow
if ($SpecContent -notmatch "beginBackgroundTask" -or $SpecContent -notmatch "isIdleTimerDisabled") {
    Write-Error "FAIL: Specification missing iOS background task extension and idle timer rules."
}
if ($SpecContent -notmatch "SYSTEM_CANCELLED" -or $SpecContent -notmatch "LSUIElement") {
    Write-Error "FAIL: Specification missing orderly cancellation and macOS LSUIElement menu bar mode."
}
$LifecyclePath = Join-Path $ProjectRoot "apple\GlideCoreKit\Sources\GlideCoreKit\Lifecycle\IosLifecycleCoordinator.swift"
$LifecycleTestPath = Join-Path $ProjectRoot "apple\GlideCoreKit\Tests\GlideCoreKitTests\IosLifecycleCoordinatorTests.swift"
$ThemePath = Join-Path $ProjectRoot "apple\GlideCoreKit\Sources\GlideCoreKit\UI\GlideTheme.swift"
$ThemeTestPath = Join-Path $ProjectRoot "apple\GlideCoreKit\Tests\GlideCoreKitTests\GlideThemeTests.swift"
$ShareCoordPath = Join-Path $ProjectRoot "apple\GlideCoreKit\Sources\GlideCoreKit\UI\ShareExtensionCoordinator.swift"
$ShareCoordTestPath = Join-Path $ProjectRoot "apple\GlideCoreKit\Tests\GlideCoreKitTests\ShareExtensionCoordinatorTests.swift"

foreach ($f in @($LifecyclePath, $LifecycleTestPath, $ThemePath, $ThemeTestPath, $ShareCoordPath, $ShareCoordTestPath)) {
    if (-not (Test-Path $f)) {
        Write-Error "FAIL: Missing iOS lifecycle or UI component: $f"
    }
}
$LifecycleContent = Get-Content $LifecyclePath -Raw
if ($LifecycleContent -notmatch "IosLifecycleCoordinator" -or $LifecycleContent -notmatch "beginBackgroundTask" -or $LifecycleContent -notmatch "isIdleTimerDisabled") {
    Write-Error "FAIL: IosLifecycleCoordinator missing beginBackgroundTask or isIdleTimerDisabled."
}
$ThemeContent = Get-Content $ThemePath -Raw
if ($ThemeContent -notmatch "GlideTheme" -or $ThemeContent -notmatch "brandBlueHex") {
    Write-Error "FAIL: GlideTheme missing brand color definitions."
}
$ShareContent = Get-Content $ShareCoordPath -Raw
if ($ShareContent -notmatch "ShareExtensionCoordinator" -or $ShareContent -notmatch "prepareBatch") {
    Write-Error "FAIL: ShareExtensionCoordinator missing prepareBatch method."
}
Write-Host "  PASS: iOS background task limits, idle timer management, theme tokens, and Share Sheet coordinator verified." -ForegroundColor Green

# --- Check 9: Quarantine Storage & Darwin Filesystem Sanitization ---
Write-Host "`n[Check 9/10] Auditing Quarantine Storage & Darwin Filesystem Sanitization..." -ForegroundColor Yellow
if ($SpecContent -notmatch "\.quarantine" -or $SpecContent -notmatch "CryptoKit\.SHA256") {
    Write-Error "FAIL: Specification missing streaming quarantine staging and CryptoKit SHA-256."
}
if ($SpecContent -notmatch "FileManager\.default\.moveItem" -or $SpecContent -notmatch "PHPhotoLibrary") {
    Write-Error "FAIL: Specification missing atomic commitment and PhotoLibrary export."
}
$SanitizerPath = Join-Path $ProjectRoot "apple\GlideCoreKit\Sources\GlideCoreKit\Storage\DarwinPathSanitizer.swift"
$SanitizerTestPath = Join-Path $ProjectRoot "apple\GlideCoreKit\Tests\GlideCoreKitTests\DarwinPathSanitizerTests.swift"
$QuarantinePath = Join-Path $ProjectRoot "apple\GlideCoreKit\Sources\GlideCoreKit\Storage\QuarantineStagingStore.swift"
$QuarantineTestPath = Join-Path $ProjectRoot "apple\GlideCoreKit\Tests\GlideCoreKitTests\QuarantineStagingStoreTests.swift"
$PhotoExpPath = Join-Path $ProjectRoot "apple\GlideCoreKit\Sources\GlideCoreKit\Storage\PhotoLibraryExporter.swift"
$PhotoExpTestPath = Join-Path $ProjectRoot "apple\GlideCoreKit\Tests\GlideCoreKitTests\PhotoLibraryExporterTests.swift"

foreach ($f in @($SanitizerPath, $SanitizerTestPath, $QuarantinePath, $QuarantineTestPath, $PhotoExpPath, $PhotoExpTestPath)) {
    if (-not (Test-Path $f)) {
        Write-Error "FAIL: Missing storage/quarantine component: $f"
    }
}
$SanitizerContent = Get-Content $SanitizerPath -Raw
if ($SanitizerContent -notmatch "sanitizeFilename" -or $SanitizerContent -notmatch "precomposedStringWithCanonicalMapping" -or $SanitizerContent -notmatch "truncateToMaxBytes") {
    Write-Error "FAIL: DarwinPathSanitizer missing sanitizeFilename or normalization methods."
}
$QuarantineContent = Get-Content $QuarantinePath -Raw
if ($QuarantineContent -notmatch "QuarantineStagingStore" -or $QuarantineContent -notmatch "writeChunk" -or $QuarantineContent -notmatch "finalizeAndCommit" -or $QuarantineContent -notmatch "purge") {
    Write-Error "FAIL: QuarantineStagingStore missing writeChunk, finalizeAndCommit, or purge."
}
$PhotoExpContent = Get-Content $PhotoExpPath -Raw
if ($PhotoExpContent -notmatch "PhotoLibraryExporter" -or $PhotoExpContent -notmatch "classify" -or $PhotoExpContent -notmatch "isMediaFile") {
    Write-Error "FAIL: PhotoLibraryExporter missing classify or isMediaFile."
}
Write-Host "  PASS: Quarantine staging, incremental hashing, atomic move, Darwin path sanitizer, and PhotoLibrary exporter verified." -ForegroundColor Green

# --- Check 10: App Permissions & Privacy Declarations ---
Write-Host "`n[Check 10/10] Auditing App Permissions & Privacy Declarations..." -ForegroundColor Yellow
$RequiredPlistKeys = @(
    "NSLocalNetworkUsageDescription",
    "NSBonjourServices",
    "NSCameraUsageDescription",
    "NSPhotoLibraryAddUsageDescription",
    "com.apple.security.app-sandbox",
    "com.apple.security.network.client",
    "com.apple.security.network.server"
)
foreach ($key in $RequiredPlistKeys) {
    if ($SpecContent -notmatch [regex]::Escape($key)) {
        Write-Error "FAIL: Specification missing required Info.plist key or entitlement: $key"
    }
}
Write-Host "  PASS: All required iOS Info.plist privacy keys and macOS sandbox entitlements verified." -ForegroundColor Green

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "  ALL 10 CHECKS PASSED: Apple Platforms Spec Conformance Certified  " -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Cyan
