# Script to run controlled transport benchmark (STREAM vs FILE) for GLIDE-018
param(
    [switch]$VerboseOutput = $false
)

$ErrorActionPreference = "Stop"

Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host " Running GLIDE-018 Transport Benchmark Suite (STREAM vs FILE)" -ForegroundColor Cyan
Write-Host "================================================================================`n"

$gradlew = Join-Path $PSScriptRoot "..\android\gradlew.bat"

Write-Host "Executing TransportBenchmarkTest via Gradle..." -ForegroundColor Yellow
Push-Location (Join-Path $PSScriptRoot "..\android")
try {
    $result = & $gradlew :android:transport-nearby:testDebugUnitTest --tests "dev.glide.transport.nearby.benchmark.TransportBenchmarkTest" 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Transport benchmark failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

$xmlReport = Join-Path $PSScriptRoot "..\android\transport-nearby\build\test-results\testDebugUnitTest\TEST-dev.glide.transport.nearby.benchmark.TransportBenchmarkTest.xml"
if (-not (Test-Path $xmlReport)) {
    $fallback = Join-Path $PSScriptRoot "..\shared\transport-nearby\build\test-results\testDebugUnitTest\TEST-dev.glide.transport.nearby.benchmark.TransportBenchmarkTest.xml"
    if (Test-Path $fallback) { $xmlReport = $fallback }
}

if (Test-Path $xmlReport) {
    [xml]$xml = Get-Content $xmlReport
    $systemOut = $xml.testsuite.'system-out'.'#cdata-section'
    if (-not $systemOut) { $systemOut = $xml.testsuite.'system-out' }
    if ($systemOut) {
        Write-Host $systemOut
    }
    Write-Host "Benchmark completed successfully. All assertions passed." -ForegroundColor Green
    exit 0
} else {
    Write-Error "Benchmark test results XML not found at $xmlReport"
    exit 1
}
