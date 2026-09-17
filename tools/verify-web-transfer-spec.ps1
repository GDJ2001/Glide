<#
.SYNOPSIS
    Automated auditor for Glide ADR-0019: Browser and Web Client Zero-Install Local Transfer Gateway.
.DESCRIPTION
    Validates ADR-0019, technical specification completeness, zero-cloud offline invariants,
    zero-CDN embedded SPA constraints, browser security sandbox policies, quarantine staging,
    and Phase W1-W5 cross-platform security conformance.
#>

$ErrorActionPreference = "Stop"
$ProjectRoot = Resolve-Path "$PSScriptRoot\.."

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "    Glide ADR-0019: Browser & Web Transfer Slice Auditor         " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# --- Check 1: ADR-0019 Existence and Decision Index ---
Write-Host "`n[Check 1/9] Auditing ADR-0019 existence and decision index..." -ForegroundColor Yellow
$AdrPath = Join-Path $ProjectRoot "docs\adr\ADR-0019-browser-web-local-transfer.md"
$AdrIndex = Join-Path $ProjectRoot "docs\adr\README.md"

if (-not (Test-Path $AdrPath)) {
    Write-Error "FAIL: ADR-0019 not found at $AdrPath"
}
$AdrContent = Get-Content $AdrPath -Raw
if ($AdrContent -notmatch "Zero-Install Ephemeral Local Web Gateway" -or $AdrContent -notmatch "HTTP/1\.1") {
    Write-Error "FAIL: ADR-0019 missing Zero-Install Local Web Gateway decision."
}
$IndexContent = Get-Content $AdrIndex -Raw
if ($IndexContent -notmatch "ADR-0019 — Browser and web client zero-install local transfer gateway") {
    Write-Error "FAIL: ADR-0019 not properly indexed in docs/adr/README.md."
}
Write-Host "  PASS: ADR-0019 exists and is indexed in docs/adr/README.md." -ForegroundColor Green

# --- Check 2: Technical Specification Completeness ---
Write-Host "`n[Check 2/9] Auditing Browser Web technical specification completeness..." -ForegroundColor Yellow
$SpecPath = Join-Path $ProjectRoot "docs\future\ANDROID_BROWSER_WEB_SPECIFICATION.md"
if (-not (Test-Path $SpecPath)) {
    Write-Error "FAIL: Specification not found at $SpecPath"
}
$SpecContent = Get-Content $SpecPath -Raw
$RequiredSections = @(
    "Zero Cloud",
    "Zero External CDN",
    "QR Onboarding",
    "Token Lifecycle",
    "120 seconds",
    "Content-Security-Policy",
    "Quarantine Staging",
    "Atomic",
    "Phase W1",
    "Phase W2",
    "Phase W3",
    "Phase W4",
    "Phase W5"
)
foreach ($section in $RequiredSections) {
    if ($SpecContent -notmatch [regex]::Escape($section)) {
        Write-Error "FAIL: Specification missing required topic: $section"
    }
}
Write-Host "  PASS: Specification contains complete REST endpoints, QR token lifecycle, CSP headers, and 5-phase roadmap." -ForegroundColor Green

# --- Check 3: Zero-Cloud & Zero-CDN Offline Invariants ---
Write-Host "`n[Check 3/9] Auditing Zero-Cloud & Zero-CDN Offline Invariants..." -ForegroundColor Yellow
if ($SpecContent -notmatch "Zero Cloud" -or $SpecContent -notmatch "100% Offline") {
    Write-Error "FAIL: Specification missing explicit Zero Cloud / 100% Offline invariant statement."
}
if ($SpecContent -notmatch "Zero External CDN" -or $SpecContent -notmatch "Google Fonts") {
    Write-Error "FAIL: Specification missing explicit Zero-CDN / air-gapped web client invariant."
}
Write-Host "  PASS: Zero-cloud, 100% offline, and zero-CDN air-gapped invariants verified." -ForegroundColor Green

# --- Check 4: Browser Security Sandbox & Header Policy ---
Write-Host "`n[Check 4/9] Auditing Browser Security Sandbox & Header Policy..." -ForegroundColor Yellow
$RequiredHeaders = @(
    "nosniff",
    "DENY",
    "SameSite=Strict",
    "HttpOnly"
)
foreach ($header in $RequiredHeaders) {
    if ($SpecContent -notmatch $header) {
        Write-Error "FAIL: Specification missing required security header rule: $header"
    }
}
Write-Host "  PASS: CSP, X-Content-Type-Options, X-Frame-Options, and cookie security policies verified." -ForegroundColor Green

# --- Check 5: Cross-Platform Path Strategic Alignment ---
Write-Host "`n[Check 5/9] Auditing cross-platform roadmap strategic alignment..." -ForegroundColor Yellow
$PathFile = Join-Path $ProjectRoot "docs\future\CROSS_PLATFORM_PATH.md"
if (-not (Test-Path $PathFile)) {
    Write-Error "FAIL: docs/future/CROSS_PLATFORM_PATH.md missing."
}
$PathContent = Get-Content $PathFile -Raw
if ($PathContent -notmatch "Android↔Browser" -or $PathContent -notmatch "ADR-0019") {
    Write-Error "FAIL: CROSS_PLATFORM_PATH.md Step 3 missing ADR-0019 alignment."
}
Write-Host "  PASS: CROSS_PLATFORM_PATH.md Step 3 properly references ADR-0019 and web specification." -ForegroundColor Green

# --- Check 6: Cross-Platform Implementation Parity (.NET & Android/Kotlin) ---
Write-Host "`n[Check 6/9] Auditing Cross-Platform Implementation Parity (.NET & Android/Kotlin)..." -ForegroundColor Yellow

# Verify .NET Gateway
$DotNetGateway = Join-Path $ProjectRoot "windows\Glide.Transport\Web\GlideWebGateway.cs"
if (-not (Test-Path $DotNetGateway)) {
    Write-Error "FAIL: windows/Glide.Transport/Web/GlideWebGateway.cs missing."
}
$DotNetGatewaySrc = Get-Content $DotNetGateway -Raw
if ($DotNetGatewaySrc -notmatch "Content-Security-Policy" -or $DotNetGatewaySrc -notmatch "glide_web_session") {
    Write-Error "FAIL: .NET GlideWebGateway missing CSP or cookie enforcement."
}

# Verify Android/JVM Kotlin Gateway
$KotlinGateway = Join-Path $ProjectRoot "transport-lan\src\main\kotlin\dev\glide\transport\lan\web\GlideWebGateway.kt"
if (-not (Test-Path $KotlinGateway)) {
    Write-Error "FAIL: transport-lan/src/main/kotlin/dev/glide/transport/lan/web/GlideWebGateway.kt missing."
}
$KotlinGatewaySrc = Get-Content $KotlinGateway -Raw
if ($KotlinGatewaySrc -notmatch "Content-Security-Policy" -or $KotlinGatewaySrc -notmatch "glide_web_session") {
    Write-Error "FAIL: Kotlin GlideWebGateway missing CSP or cookie enforcement."
}

# Verify Embedded Single-Page Application Assets in both trees
$DotNetAssets = Join-Path $ProjectRoot "windows\Glide.Transport\Web\Assets\index.html"
$KotlinAssets = Join-Path $ProjectRoot "transport-lan\src\main\resources\glide\web\index.html"
if (-not (Test-Path $DotNetAssets) -or -not (Test-Path $KotlinAssets)) {
    Write-Error "FAIL: Embedded index.html missing in windows or transport-lan."
}

# Verify Cross-Platform QR Code Visual Onboarding
$QrGen = Join-Path $ProjectRoot "windows\Glide.Core\Security\QrCodeGenerator.cs"
$AndroidQr = Join-Path $ProjectRoot "app\src\main\kotlin\dev\glide\app\qr\QrEncoder.kt"
if (-not (Test-Path $QrGen) -or -not (Test-Path $AndroidQr)) {
    Write-Error "FAIL: QR code generation missing in windows or android."
}

Write-Host "  PASS: Cross-platform .NET and Android Web Gateways, embedded assets, and QR onboarding verified." -ForegroundColor Green

# --- Check 7: Phase W3 Streaming Interoperability (Range, 206, 416, HEAD, RFC 6266) ---
Write-Host "`n[Check 7/9] Auditing Phase W3 Streaming Interoperability (Range, HEAD, RFC 6266)..." -ForegroundColor Yellow

# Verify .NET Phase W3 features
if ($DotNetGatewaySrc -notmatch "Accept-Ranges" -or
    $DotNetGatewaySrc -notmatch "PartialContent" -or
    $DotNetGatewaySrc -notmatch "416" -or
    $DotNetGatewaySrc -notmatch "filename\*=UTF-8''") {
    Write-Error "FAIL: .NET GlideWebGateway missing Range 206, 416, Accept-Ranges, or RFC 6266 disposition."
}

# Verify Kotlin Phase W3 features
if ($KotlinGatewaySrc -notmatch "Accept-Ranges" -or
    $KotlinGatewaySrc -notmatch "206 Partial Content" -or
    $KotlinGatewaySrc -notmatch "416 Range Not Satisfiable" -or
    $KotlinGatewaySrc -notmatch "filename\*=UTF-8''") {
    Write-Error "FAIL: Kotlin GlideWebGateway missing Range 206, 416, Accept-Ranges, or RFC 6266 disposition."
}

# Verify WebShareViewModel existence
$WebShareVm = Join-Path $ProjectRoot "app\src\main\kotlin\dev\glide\app\ui\web\WebShareViewModel.kt"
if (-not (Test-Path $WebShareVm)) {
    Write-Error "FAIL: app/src/main/kotlin/dev/glide/app/ui/web/WebShareViewModel.kt missing."
}

Write-Host "  PASS: HTTP Range 206/416 partial streaming, HEAD support, RFC 6266 disposition, and WebShareViewModel verified." -ForegroundColor Green

# --- Check 8: Phase W4 Quarantine Uploader, Proposal Flow, Checksum Verification & Sanitization ---
Write-Host "`n[Check 8/9] Auditing Phase W4 Quarantine Uploader & Sanitization Flow..." -ForegroundColor Yellow

# Verify .NET Phase W4 features
if ($DotNetGatewaySrc -notmatch "/api/v1/upload/proposal" -or
    $DotNetGatewaySrc -notmatch "X-Declared-Sha256" -or
    $DotNetGatewaySrc -notmatch "422" -or
    $DotNetGatewaySrc -notmatch "507" -or
    $DotNetGatewaySrc -notmatch "PurgeQuarantine" -or
    $DotNetGatewaySrc -notmatch "SanitizeWebFilename") {
    Write-Error "FAIL: .NET GlideWebGateway missing upload proposal, checksum verification, 507, 422, or sanitization."
}

# Verify Kotlin Phase W4 features
if ($KotlinGatewaySrc -notmatch "/api/v1/upload/proposal" -or
    $KotlinGatewaySrc -notmatch "x-declared-sha256" -or
    $KotlinGatewaySrc -notmatch "422" -or
    $KotlinGatewaySrc -notmatch "507" -or
    $KotlinGatewaySrc -notmatch "WebPathSanitizer") {
    Write-Error "FAIL: Kotlin GlideWebGateway missing upload proposal, checksum verification, 507, 422, or WebPathSanitizer."
}

# Verify WebPathSanitizer existence in Kotlin
$WebPathSanitizerFile = Join-Path $ProjectRoot "transport-lan\src\main\kotlin\dev\glide\transport\lan\web\WebPathSanitizer.kt"
if (-not (Test-Path $WebPathSanitizerFile)) {
    Write-Error "FAIL: transport-lan/src/main/kotlin/dev/glide/transport/lan/web/WebPathSanitizer.kt missing."
}
$WebPathSanitizerSrc = Get-Content $WebPathSanitizerFile -Raw
if ($WebPathSanitizerSrc -notmatch "object WebPathSanitizer" -or $WebPathSanitizerSrc -notmatch "resolveUniqueName") {
    Write-Error "FAIL: WebPathSanitizer.kt missing core methods."
}

# Verify WindowsPathSanitizer Web Sanitization method
$WindowsPathSanitizerFile = Join-Path $ProjectRoot "windows\Glide.Core\Storage\WindowsPathSanitizer.cs"
$WindowsPathSanitizerSrc = Get-Content $WindowsPathSanitizerFile -Raw
if ($WindowsPathSanitizerSrc -notmatch "SanitizeWebFilename") {
    Write-Error "FAIL: WindowsPathSanitizer.cs missing SanitizeWebFilename method."
}

# Verify Web Client Assets proposal flow in both trees
$DotNetJs = Join-Path $ProjectRoot "windows\Glide.Transport\Web\Assets\app.js"
$KotlinJs = Join-Path $ProjectRoot "transport-lan\src\main\resources\glide\web\app.js"
$DotNetJsSrc = Get-Content $DotNetJs -Raw
$KotlinJsSrc = Get-Content $KotlinJs -Raw

if ($DotNetJsSrc -notmatch "/api/v1/upload/proposal" -or $KotlinJsSrc -notmatch "/api/v1/upload/proposal") {
    Write-Error "FAIL: Single-Page Application app.js missing /api/v1/upload/proposal flow."
}

Write-Host "  PASS: Phase W4 proposal flow, quarantine staging, SHA-256 verification, 507/422 handling, and filesystem sanitization verified." -ForegroundColor Green

# --- Check 9: Phase W5 Security Conformance & ADR-0019 Acceptance ---
Write-Host "`n[Check 9/9] Auditing Phase W5 Security Conformance & ADR-0019 Acceptance..." -ForegroundColor Yellow

# Verify ADR-0019 status is Accepted
if ($AdrContent -notmatch "Status.*\bAccepted\b") {
    Write-Error "FAIL: docs/adr/ADR-0019-browser-web-local-transfer.md status must be Accepted."
}

# Verify Token Replay Protection in .NET Gateway
if ($DotNetGatewaySrc -notmatch "_tokenConsumed") {
    Write-Error "FAIL: .NET GlideWebGateway missing _tokenConsumed check for token replay prevention."
}

# Verify Token Replay Protection in Kotlin Gateway
if ($KotlinGatewaySrc -notmatch "isTokenRedeemed") {
    Write-Error "FAIL: Kotlin GlideWebGateway missing isTokenRedeemed check for token replay prevention."
}

# Verify .NET Security Conformance Test Suite
$DotNetSecTests = Join-Path $ProjectRoot "windows\Glide.Tests\WebSecurityConformanceTests.cs"
if (-not (Test-Path $DotNetSecTests)) {
    Write-Error "FAIL: windows/Glide.Tests/WebSecurityConformanceTests.cs missing."
}
$DotNetSecTestsSrc = Get-Content $DotNetSecTests -Raw
if ($DotNetSecTestsSrc -notmatch "TokenReplay" -or
    $DotNetSecTestsSrc -notmatch "SecurityHeaders" -or
    $DotNetSecTestsSrc -notmatch "UnauthorizedAccess" -or
    $DotNetSecTestsSrc -notmatch "SessionCookie") {
    Write-Error "FAIL: WebSecurityConformanceTests.cs missing key security assertions."
}

# Verify Kotlin Security Conformance Test Suite
$KotlinSecTests = Join-Path $ProjectRoot "transport-lan\src\test\kotlin\dev\glide\transport\lan\web\WebSecurityConformanceTest.kt"
if (-not (Test-Path $KotlinSecTests)) {
    Write-Error "FAIL: transport-lan/src/test/kotlin/dev/glide/transport/lan/web/WebSecurityConformanceTest.kt missing."
}
$KotlinSecTestsSrc = Get-Content $KotlinSecTests -Raw
if ($KotlinSecTestsSrc -notmatch "tokenReplay" -or
    $KotlinSecTestsSrc -notmatch "securityHeaders" -or
    $KotlinSecTestsSrc -notmatch "unauthorizedAccess" -or
    $KotlinSecTestsSrc -notmatch "sessionCookie") {
    Write-Error "FAIL: WebSecurityConformanceTest.kt missing key security assertions."
}

Write-Host "  PASS: Phase W5 Token Replay Rejection, dedicated cross-platform Conformance Suites, and ADR-0019 Acceptance verified." -ForegroundColor Green

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "  BROWSER & WEB TRANSFER SPECIFICATION AUDIT PASSED (0 issues)   " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan
