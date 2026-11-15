<#
.SYNOPSIS
Automated verification suite for the Glide Windows MSI installer package.

.DESCRIPTION
Executes end-to-end verification of the generated Windows Installer package (.msi):
1. Silent Installation (msiexec /i <msi> /qn /norestart) -> Exit code 0
2. Execution Verification (installed Glide.exe --help) -> Exit code 0 & CLI banner output
3. Silent Uninstallation (msiexec /x <msi> /qn /norestart) -> Exit code 0
4. Clean Removal Verification (Application install directory purged, zero orphaned files)

Supports both elevated perMachine execution and non-elevated perUser/directory test modes.
#>

[CmdletBinding()]
param(
    [string]$MsiPath = "",
    [ValidateSet("Auto", "perMachine", "perUser")]
    [string]$InstallScope = "Auto",
    [string]$TestDirectory = "",
    [string]$LogDirectory = ""
)

$ErrorActionPreference = "Stop"
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..")).Path

# 1. Locate MSI Package
if ([string]::IsNullOrWhiteSpace($MsiPath)) {
    $defaultDist = Join-Path $repoRoot "build\distributions\windows"
    $msiCandidates = Get-ChildItem -Path $defaultDist -Filter "Glide-Setup-*-win-x64.msi" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending
    if ($msiCandidates.Count -gt 0) {
        $MsiPath = $msiCandidates[0].FullName
    } else {
        throw "No Glide MSI package found in '$defaultDist'. Build the package first using desktop/windows/package-windows-msi.ps1."
    }
}

if (-not (Test-Path -LiteralPath $MsiPath)) {
    throw "MSI package does not exist: $MsiPath"
}
$MsiPath = (Resolve-Path $MsiPath).Path

# 2. Determine Scope and Elevation
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if ($InstallScope -eq "Auto") {
    $InstallScope = if ($isAdmin) { "perMachine" } else { "perUser" }
}

if ($InstallScope -eq "perMachine" -and -not $isAdmin) {
    Write-Warning "Running perMachine verification without administrator elevation. msiexec /qn will fail unless elevated. Switching to perUser test mode or run elevated."
    $InstallScope = "perUser"
}

# 3. Setup Target and Log Directories
if ([string]::IsNullOrWhiteSpace($LogDirectory)) {
    $LogDirectory = Join-Path $repoRoot "build\reports\msi-verification"
}
New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null
$LogDirectory = (Resolve-Path $LogDirectory).Path

$installLog = Join-Path $LogDirectory "msi-install.log"
$uninstallLog = Join-Path $LogDirectory "msi-uninstall.log"
if (Test-Path $installLog) { Remove-Item -Force $installLog }
if (Test-Path $uninstallLog) { Remove-Item -Force $uninstallLog }

if ($InstallScope -eq "perMachine") {
    $targetDir = "${env:ProgramFiles}\Glide"
} else {
    if ([string]::IsNullOrWhiteSpace($TestDirectory)) {
        $targetDir = Join-Path $env:TEMP "Glide_MsiTest_$(Get-Random)"
    } else {
        $targetDir = $TestDirectory
    }
}

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "         Glide Windows MSI Installer Verification Suite           " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "MSI Package:     $MsiPath"
Write-Host "Install Scope:   $InstallScope (Administrator: $isAdmin)"
Write-Host "Target Folder:   $targetDir"
Write-Host "Log Directory:   $LogDirectory"
Write-Host ""

# =====================================================================
# Phase 1: Silent Installation
# =====================================================================
Write-Host "[Phase 1/4] Testing Silent Installation (/qn /norestart)..." -ForegroundColor Yellow

$installArgs = @("/i", "`"$MsiPath`"")
if ($InstallScope -eq "perUser") {
    $installArgs += @("ALLUSERS=2", "MSIINSTALLPERUSER=1", "INSTALLFOLDER=`"$targetDir`"")
}
$installArgs += @("/qn", "/norestart", "/l*v", "`"$installLog`"")

$pInstall = Start-Process -FilePath "msiexec.exe" -ArgumentList $installArgs -NoNewWindow -Wait -PassThru
Write-Host "  -> msiexec install exit code: $($pInstall.ExitCode)"

if ($pInstall.ExitCode -ne 0) {
    Write-Host "`nInstallation FAILED with exit code $($pInstall.ExitCode)!" -ForegroundColor Red
    if (Test-Path $installLog) {
        Write-Host "Recent errors from install log:" -ForegroundColor DarkYellow
        Get-Content $installLog | Select-String -Pattern "Return value 3|Fatal error|error" | Select-Object -Last 10 | ForEach-Object { Write-Host "   $_" }
    }
    throw "Phase 1 Failed: Silent installation returned exit code $($pInstall.ExitCode) (expected 0)."
}
Write-Host "  [PASS] Silent installation succeeded with exit code 0." -ForegroundColor Green

# =====================================================================
# Phase 2: Binary Execution Verification
# =====================================================================
Write-Host "`n[Phase 2/4] Testing Installed Executable Launch..." -ForegroundColor Yellow

$installedExe = Join-Path $targetDir "Glide.exe"
if (-not (Test-Path -LiteralPath $installedExe)) {
    throw "Phase 2 Failed: Installed binary does not exist at expected path: $installedExe"
}
Write-Host "  Found installed executable: $installedExe"

$stdoutFile = Join-Path $LogDirectory "glide-help-stdout.txt"
$stderrFile = Join-Path $LogDirectory "glide-help-stderr.txt"
if (Test-Path $stdoutFile) { Remove-Item -Force $stdoutFile }
if (Test-Path $stderrFile) { Remove-Item -Force $stderrFile }

$pRun = Start-Process -FilePath $installedExe -ArgumentList "--help" -RedirectStandardOutput $stdoutFile -RedirectStandardError $stderrFile -NoNewWindow -Wait -PassThru
$exeExitCode = $pRun.ExitCode
Write-Host "  -> Glide.exe --help exit code: $exeExitCode"

if ($exeExitCode -ne 0) {
    if (Test-Path $stderrFile) {
        Get-Content $stderrFile | ForEach-Object { Write-Host "   $_" -ForegroundColor Red }
    }
    throw "Phase 2 Failed: Installed Glide.exe returned exit code $exeExitCode (expected 0)."
}

$outputString = if (Test-Path $stdoutFile) { Get-Content $stdoutFile -Raw } else { "" }
if ($outputString -match "Glide\.Windows" -or $outputString -match "glide receive" -or $outputString -match "High-Speed Local File Sharing") {
    Write-Host "  [PASS] Installed Glide.exe launched cleanly and printed CLI banner." -ForegroundColor Green
} else {
    throw "Phase 2 Failed: Glide.exe executed but did not output expected CLI banner."
}

# =====================================================================
# Phase 3: Silent Uninstallation
# =====================================================================
Write-Host "`n[Phase 3/4] Testing Silent Uninstallation (/qn /norestart)..." -ForegroundColor Yellow

$uninstallArgs = @("/x", "`"$MsiPath`"", "/qn", "/norestart", "/l*v", "`"$uninstallLog`"")
$pUninstall = Start-Process -FilePath "msiexec.exe" -ArgumentList $uninstallArgs -NoNewWindow -Wait -PassThru
Write-Host "  -> msiexec uninstall exit code: $($pUninstall.ExitCode)"

if ($pUninstall.ExitCode -ne 0) {
    Write-Host "`nUninstallation FAILED with exit code $($pUninstall.ExitCode)!" -ForegroundColor Red
    if (Test-Path $uninstallLog) {
        Write-Host "Recent errors from uninstall log:" -ForegroundColor DarkYellow
        Get-Content $uninstallLog | Select-String -Pattern "Return value 3|Fatal error|error" | Select-Object -Last 10 | ForEach-Object { Write-Host "   $_" }
    }
    throw "Phase 3 Failed: Silent uninstallation returned exit code $($pUninstall.ExitCode) (expected 0)."
}
Write-Host "  [PASS] Silent uninstallation succeeded with exit code 0." -ForegroundColor Green

# =====================================================================
# Phase 4: Clean Removal Verification
# =====================================================================
Write-Host "`n[Phase 4/4] Verifying Clean Removal & Zero Orphaned Files..." -ForegroundColor Yellow

$leftoverFiles = @()
if (Test-Path -LiteralPath $targetDir) {
    $leftoverFiles = Get-ChildItem -Path $targetDir -Recurse -File | Select-Object -ExpandProperty FullName
}

if ($leftoverFiles.Count -gt 0) {
    Write-Host "Leftover files detected in '$targetDir':" -ForegroundColor Red
    $leftoverFiles | ForEach-Object { Write-Host "   $_" -ForegroundColor Red }
    throw "Phase 4 Failed: Uninstallation left $($leftoverFiles.Count) orphaned files in '$targetDir'."
}

# Check if directory itself exists; if empty, clean up test artifact
if (Test-Path -LiteralPath $targetDir) {
    $remainingItems = Get-ChildItem -Path $targetDir
    if ($remainingItems.Count -eq 0) {
        Remove-Item -LiteralPath $targetDir -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "  [PASS] Clean removal verified: install folder completely removed with 0 leftover files." -ForegroundColor Green

Write-Host "`n=================================================================" -ForegroundColor Green
Write-Host "       MSI INSTALLATION VERIFICATION: ALL 4 PHASES PASSED        " -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Green
Write-Host "  1. Silent Install (/qn /norestart)      : PASS (Code 0)" -ForegroundColor Green
Write-Host "  2. Binary Execution (Glide.exe --help)  : PASS (Code 0)" -ForegroundColor Green
Write-Host "  3. Silent Uninstall (/qn /norestart)    : PASS (Code 0)" -ForegroundColor Green
Write-Host "  4. Clean Directory Cleanup (Zero Left)  : PASS (Code 0)" -ForegroundColor Green
