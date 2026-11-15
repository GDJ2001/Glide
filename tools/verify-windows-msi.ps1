<#
.SYNOPSIS
Root tool runner for Glide Windows MSI installer verification.

.DESCRIPTION
Invokes desktop/windows/verify-windows-msi.ps1 to execute the 4-phase automated MSI verification:
1. Silent install (/qn /norestart -> exit code 0)
2. Execution of installed Glide.exe --help (-> exit code 0)
3. Silent uninstall (/qn /norestart -> exit code 0)
4. Clean removal check (target directory completely purged)

Suitable for local verification and automated CI gates.
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
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..")).Path
$verifierScript = Join-Path $repoRoot "desktop\windows\verify-windows-msi.ps1"

if (-not (Test-Path -LiteralPath $verifierScript)) {
    throw "MSI verification script not found at '$verifierScript'."
}

$params = @{}
if (-not [string]::IsNullOrWhiteSpace($MsiPath)) { $params['MsiPath'] = $MsiPath }
if (-not [string]::IsNullOrWhiteSpace($InstallScope)) { $params['InstallScope'] = $InstallScope }
if (-not [string]::IsNullOrWhiteSpace($TestDirectory)) { $params['TestDirectory'] = $TestDirectory }
if (-not [string]::IsNullOrWhiteSpace($LogDirectory)) { $params['LogDirectory'] = $LogDirectory }

& $verifierScript @params
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
