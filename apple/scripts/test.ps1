<#
.SYNOPSIS
    Cross-platform PowerShell test runner for Glide Apple components.
#>
[CmdletBinding()]
param()

$appleDir = Split-Path -Parent $PSScriptRoot
$coreKitDir = Join-Path $appleDir "GlideCoreKit"

Write-Host "=== Glide Apple Test Runner ===" -ForegroundColor Cyan

if (Get-Command swift -ErrorAction SilentlyContinue) {
    Push-Location $coreKitDir
    try {
        Write-Host "Running swift test in $coreKitDir..." -ForegroundColor Yellow
        swift test
        if ($LASTEXITCODE -ne 0) {
            Write-Error "Swift test suite failed with exit code $LASTEXITCODE"
            exit $LASTEXITCODE
        }
        Write-Host "Swift tests passed successfully." -ForegroundColor Green
    }
    finally {
        Pop-Location
    }
} else {
    Write-Host "BLOCKER: Swift toolchain not installed on this host. An Xcode/macOS build host is required." -ForegroundColor Red
    exit 1
}
