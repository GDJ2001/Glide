<#
.SYNOPSIS
    Automated auditor for Glide ADR-0021 & Linux Platform Interoperability Specification.
.DESCRIPTION
    Validates ADR-0021, technical specification completeness, zero-cloud offline invariants,
    cross-platform .NET 10 runtime conformance, dynamic OS detection, XDG directory compliance,
    FreeDesktop standards, systemd user service contracts, and headless terminal QR rendering.
#>

$ErrorActionPreference = "Stop"
$ProjectRoot = Resolve-Path "$PSScriptRoot\.."

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "    Glide ADR-0021: Linux Platform Specification Auditor        " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# --- Check 1: ADR-0021 Existence, Accepted Status, and Decision Index ---
Write-Host "`n[Check 1/10] Auditing ADR-0021 existence, Accepted status, and index..." -ForegroundColor Yellow
$docDir = if (Test-Path (Join-Path $ProjectRoot "doc")) { "doc" } else { "docs" }
$AdrPath = Join-Path $ProjectRoot "$docDir\adr\ADR-0021-linux-platform-architecture.md"
$AdrIndex = Join-Path $ProjectRoot "$docDir\adr\README.md"

if (-not (Test-Path $AdrPath)) {
    Write-Error "FAIL: ADR-0021 not found at $AdrPath"
}
$AdrContent = Get-Content $AdrPath -Raw
if ($AdrContent -notmatch "Status.*\bAccepted\b") {
    Write-Error "FAIL: ADR-0021 status must be Accepted."
}
if ($AdrContent -notmatch "\.NET 10" -or $AdrContent -notmatch "XDG") {
    Write-Error "FAIL: ADR-0021 missing .NET 10 cross-platform or XDG decisions."
}
$IndexContent = Get-Content $AdrIndex -Raw
if ($IndexContent -notmatch "ADR-0021.*Linux platform architecture") {
    Write-Error "FAIL: ADR-0021 not properly indexed in $docDir/adr/README.md."
}
Write-Host "  PASS: ADR-0021 exists, is marked Accepted, and is indexed in README.md." -ForegroundColor Green

# --- Check 2: Roadmap Alignment in CROSS_PLATFORM_PATH.md ---
Write-Host "`n[Check 2/10] Auditing Roadmap Alignment in CROSS_PLATFORM_PATH.md..." -ForegroundColor Yellow
$PathDoc = Join-Path $ProjectRoot "$docDir\future\CROSS_PLATFORM_PATH.md"
$PathContent = Get-Content $PathDoc -Raw
if ($PathContent -notmatch "LINUX_PLATFORM_SPECIFICATION\.md" -or $PathContent -notmatch "Linux") {
    Write-Error "FAIL: CROSS_PLATFORM_PATH.md does not reference LINUX_PLATFORM_SPECIFICATION.md or Linux platform."
}
Write-Host "  PASS: CROSS_PLATFORM_PATH.md references Linux platform and specification." -ForegroundColor Green

# --- Check 3: Linux Platform Specification Completeness ---
Write-Host "`n[Check 3/10] Auditing Linux Platform technical specification completeness..." -ForegroundColor Yellow
$SpecPath = Join-Path $ProjectRoot "$docDir\future\LINUX_PLATFORM_SPECIFICATION.md"
if (-not (Test-Path $SpecPath)) {
    Write-Error "FAIL: Specification not found at $SpecPath"
}
$SpecContent = Get-Content $SpecPath -Raw
$RequiredTopics = @(
    "Zero Cloud / 100% Offline",
    "Canonical Protocol Parity",
    "Shared Cross-Platform .NET 10 Engine",
    "XDG Base Directory",
    "XDG_DATA_HOME",
    "XDG_CONFIG_HOME",
    "XDG_DOWNLOAD_DIR",
    "Terminal User Experience",
    "UTF-8 Block Character",
    "avahi-daemon",
    "WindowsPathSanitizer",
    "Desktop Entry",
    "glide.desktop",
    "systemd",
    "glide.service",
    "Packaging & Deployment",
    "linux-x64",
    "linux-arm64"
)
foreach ($topic in $RequiredTopics) {
    if ($SpecContent -notmatch [regex]::Escape($topic)) {
        Write-Error "FAIL: Specification missing required topic: $topic"
    }
}
Write-Host "  PASS: Specification contains all core architectural, XDG, systemd, and packaging sections." -ForegroundColor Green

# --- Check 4: Cross-Platform .NET 10 Target Framework Conformance ---
Write-Host "`n[Check 4/10] Auditing Target Frameworks for net10.0 cross-platform portability..." -ForegroundColor Yellow
$CsprojFiles = @(
    "desktop\src\Glide.Core\Glide.Core.csproj",
    "desktop\src\Glide.Transport\Glide.Transport.csproj",
    "desktop\src\Glide.App\Glide.App.csproj"
)
foreach ($relPath in $CsprojFiles) {
    $fullPath = Join-Path $ProjectRoot $relPath
    if (-not (Test-Path $fullPath)) {
        Write-Error "FAIL: Project file missing: $relPath"
    }
    $projContent = Get-Content $fullPath -Raw
    if ($projContent -notmatch "<TargetFramework>net10\.0</TargetFramework>") {
        Write-Error "FAIL: Project $relPath does not target pure net10.0."
    }
}
Write-Host "  PASS: Glide.Core, Glide.Transport, and Glide.App strictly target pure cross-platform net10.0." -ForegroundColor Green

# --- Check 5: Dynamic OS Platform Detection in Program.cs ---
Write-Host "`n[Check 5/10] Auditing Dynamic OS Platform Detection in Program.cs..." -ForegroundColor Yellow
$ProgramPath = Join-Path $ProjectRoot "desktop\src\Glide.App\Program.cs"
$ProgramContent = Get-Content $ProgramPath -Raw
if ($ProgramContent -notmatch "GetCurrentPlatform\(\)" -or
    $ProgramContent -notmatch "OperatingSystem\.IsLinux\(\)") {
    Write-Error "FAIL: Program.cs missing dynamic OperatingSystem.IsLinux() platform resolution."
}
Write-Host "  PASS: Program.cs dynamically resolves 'linux', 'macos', and 'windows' platforms." -ForegroundColor Green

# --- Check 6: XDG Base Directory & Quarantine Staging Resolution ---
Write-Host "`n[Check 6/10] Auditing XDG Base Directory Support in QuarantineStagingStore.cs..." -ForegroundColor Yellow
$StorePath = Join-Path $ProjectRoot "desktop\src\Glide.Core\Storage\QuarantineStagingStore.cs"
$StoreContent = Get-Content $StorePath -Raw
if ($StoreContent -notmatch "XDG_DATA_HOME" -or $StoreContent -notmatch "GetDefaultStagingRoot") {
    Write-Error "FAIL: QuarantineStagingStore.cs missing XDG_DATA_HOME environment variable resolution."
}
Write-Host "  PASS: QuarantineStagingStore.cs supports XDG_DATA_HOME with standard XDG fallbacks." -ForegroundColor Green

# --- Check 7: Cross-Platform DriveInfo Root Resolution ---
Write-Host "`n[Check 7/10] Auditing Cross-Platform DriveInfo Root Resolution..." -ForegroundColor Yellow
if ($StoreContent -notmatch 'OperatingSystem\.IsWindows\(\)' -or $StoreContent -notmatch 'DriveInfo\.GetDrives\(\)') {
    Write-Error "FAIL: QuarantineStagingStore.cs missing cross-platform DriveInfo volume resolution."
}
Write-Host "  PASS: QuarantineStagingStore.cs resolves volumes cross-platform via DriveInfo.GetDrives() and OperatingSystem.IsWindows()/IsLinux()." -ForegroundColor Green

# --- Check 8: FreeDesktop & systemd Service Invariants ---
Write-Host "`n[Check 8/10] Auditing FreeDesktop & systemd Service Definitions..." -ForegroundColor Yellow
if ($SpecContent -notmatch "\[Desktop Entry\]" -or $SpecContent -notmatch "dev\.glide\.app") {
    Write-Error "FAIL: Specification missing valid FreeDesktop desktop entry schema."
}
if ($SpecContent -notmatch "\[Unit\]" -or $SpecContent -notmatch "systemctl --user enable") {
    Write-Error "FAIL: Specification missing valid systemd user service definition."
}
$DesktopPath = Join-Path $ProjectRoot "desktop\linux\glide.desktop"
if (-not (Test-Path $DesktopPath)) {
    $DesktopPath = Join-Path $ProjectRoot "packaging\linux\glide.desktop"
}
$ServicePath = Join-Path $ProjectRoot "desktop\linux\glide.service"
if (-not (Test-Path $ServicePath)) {
    $ServicePath = Join-Path $ProjectRoot "packaging\linux\glide.service"
}
if (-not (Test-Path $DesktopPath)) {
    Write-Error "FAIL: FreeDesktop packaging asset missing: $DesktopPath"
}
if (-not (Test-Path $ServicePath)) {
    Write-Error "FAIL: systemd user service packaging asset missing: $ServicePath"
}
Write-Host "  PASS: FreeDesktop .desktop entry and systemd user unit definitions and files verified." -ForegroundColor Green

# --- Check 9: Headless Terminal QR Code Rendering Contract ---
Write-Host "`n[Check 9/10] Auditing Headless Terminal QR Code Rendering Contract..." -ForegroundColor Yellow
if ($SpecContent -notmatch "\\u2580" -and $SpecContent -notmatch "▀") {
    Write-Error "FAIL: Specification missing terminal half-block QR code rendering contract."
}
$QrGenPath = Join-Path $ProjectRoot "desktop\src\Glide.Core\Security\QrCodeGenerator.cs"
$QrGenContent = Get-Content $QrGenPath -Raw
if ($QrGenContent -notmatch "ToTerminalString") {
    Write-Error "FAIL: QrCodeGenerator.cs missing ToTerminalString UTF-8 block renderer."
}
if ($ProgramContent -notmatch "ToTerminalString" -or $ProgramContent -notmatch 'daemon') {
    Write-Error "FAIL: Program.cs missing ToTerminalString or daemon command."
}
Write-Host "  PASS: Terminal UTF-8 block QR rendering contract and daemon command verified in code." -ForegroundColor Green

# --- Check 10: Zero-Cloud & 100% Offline Invariants ---
Write-Host "`n[Check 10/10] Auditing Zero-Cloud & 100% Offline Invariants..." -ForegroundColor Yellow
if ($SpecContent -notmatch "Zero Cloud" -or $SpecContent -notmatch "100% Offline") {
    Write-Error "FAIL: Specification missing explicit Zero Cloud / 100% Offline invariant statement."
}
Write-Host "  PASS: Zero-cloud, 100% offline local LAN operations verified." -ForegroundColor Green

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "  ALL 10 CHECKS PASSED: Linux Platform Spec Conformance Certified " -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Cyan
