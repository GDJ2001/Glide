# Glide Local Verification Script
# Runs the full local verification suite matching CI

[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

Write-Host "=== 1. Validating Canonical Brand Assets ===" -ForegroundColor Cyan
& "$PSScriptRoot\..\assets\tools\Export-GlideAssets.ps1" -ValidateOnly

Write-Host "`n=== 2. Enforcing Architecture & Static Analysis ===" -ForegroundColor Cyan
$LASTEXITCODE = 0
& "$PSScriptRoot\check-architecture.ps1" -FailFast
if ($LASTEXITCODE -ne 0) {
    Write-Error "Architecture and static-analysis checks failed with exit code $LASTEXITCODE."
    exit $LASTEXITCODE
}

Write-Host "`n=== 3. Building and Testing All Modules ===" -ForegroundColor Cyan
$LASTEXITCODE = 0
& "$PSScriptRoot\..\gradlew.bat" assembleDebug test
if ($LASTEXITCODE -ne 0) {
    Write-Error "assembleDebug test failed with exit code $LASTEXITCODE."
    exit $LASTEXITCODE
}

Write-Host "`n=== 4. Running Android Lint Analysis ===" -ForegroundColor Cyan
$LASTEXITCODE = 0
& "$PSScriptRoot\..\gradlew.bat" :android:app:lintDebug
if ($LASTEXITCODE -ne 0) {
    Write-Error "lintDebug failed with exit code $LASTEXITCODE."
    exit $LASTEXITCODE
}

Write-Host "`n=== Verification Complete: ALL CHECKS PASSED ===" -ForegroundColor Green
