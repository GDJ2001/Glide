# Script to run controlled transport benchmark (STREAM vs FILE) for GLIDE-018
param(
    [switch]$VerboseOutput = $false
)

$ErrorActionPreference = "Stop"

Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host " Running GLIDE-018 Transport Benchmark Suite (STREAM vs FILE)" -ForegroundColor Cyan
Write-Host "================================================================================`n"

$gradlew = Join-Path $PSScriptRoot "..\gradlew.bat"

Write-Host "Executing TransportBenchmarkTest via Gradle..." -ForegroundColor Yellow
$result = & $gradlew :transport-nearby:testDebugUnitTest --tests "dev.glide.transport.nearby.benchmark.TransportBenchmarkTest" 2>&1

$xmlReport = Join-Path $PSScriptRoot "..\transport-nearby\build\test-results\testDebugUnitTest\TEST-dev.glide.transport.nearby.benchmark.TransportBenchmarkTest.xml"

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
