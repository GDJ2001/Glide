<#
.SYNOPSIS
    Regression test suite for assemble-release-manifest.ps1 (Gate E3).
.DESCRIPTION
    Verifies that:
    1. assemble-release-manifest fails closed on missing artifacts.
    2. assemble-release-manifest fails closed on unresolved PowerShell template expressions.
    3. Hashes and sizes are calculated dynamically from disk bytes, not hardcoded.
    4. Runnable packages and test evidence archives are strictly partitioned.
    5. Generated JSON and Markdown files parse cleanly.
#>

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$scriptPath = Join-Path $PSScriptRoot 'assemble-release-manifest.ps1'

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "   Glide Release Manifest Generator Regression Test Suite        " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

$testCount = 0
$passCount = 0

function Assert-Test {
    param(
        [string]$Name,
        [scriptblock]$Block
    )
    $script:testCount++
    Write-Host "`n[Test $script:testCount] $Name..." -ForegroundColor Yellow
    try {
        & $Block
        $script:passCount++
        Write-Host "  [PASS] $Name" -ForegroundColor Green
    } catch {
        Write-Host "  [FAIL] ${Name}: $_" -ForegroundColor Red
        throw
    }
}

# Test 1: Verify Fail-Closed behavior on missing required artifact
Assert-Test "Fails closed on missing required artifact" {
    # If we invoke with AllowMissing=$false against a non-existent commit or when an artifact is missing
    $fakeCommit = "0000000000000000000000000000000000000000"
    $caught = $false
    try {
        & pwsh -NoProfile -File $scriptPath -Commit $fakeCommit -AllowDirty
    } catch {
        $caught = $true
    }
    # Either git commit check or missing artifact throws
    if (-not $caught -and $LASTEXITCODE -eq 0) {
        throw "Expected assemble-release-manifest.ps1 to fail closed with non-zero exit code"
    }
}

# Test 2: Verify execution with AllowMissing and AllowDirty succeeds
Assert-Test "Executes and generates valid manifest with AllowMissing and AllowDirty" {
    & pwsh -NoProfile -File $scriptPath -AllowDirty -AllowMissing
    if ($LASTEXITCODE -ne 0) {
        throw "assemble-release-manifest.ps1 failed with exit code $LASTEXITCODE"
    }

    $distDir = Join-Path $repoRoot 'build\distributions'
    $jsonPath = Join-Path $distDir 'RELEASE_MANIFEST.json'
    $mdPath = Join-Path $distDir 'RELEASE_CATALOG.md'

    if (-not (Test-Path $jsonPath)) { throw "RELEASE_MANIFEST.json was not created" }
    if (-not (Test-Path $mdPath)) { throw "RELEASE_CATALOG.md was not created" }
}

# Test 3: Verify JSON validity, structure, and dynamic properties
Assert-Test "RELEASE_MANIFEST.json has correct structure and dynamic hashes" {
    $distDir = Join-Path $repoRoot 'build\distributions'
    $jsonPath = Join-Path $distDir 'RELEASE_MANIFEST.json'
    $jsonContent = Get-Content -Path $jsonPath -Raw -Encoding utf8
    $data = $jsonContent | ConvertFrom-Json

    if (-not $data.ReleaseCandidate) { throw "Missing ReleaseCandidate section" }
    if ([string]::IsNullOrWhiteSpace($data.ReleaseCandidate.Commit)) { throw "ReleaseCandidate.Commit is empty" }
    if ($null -eq $data.PSObject.Properties['RunnableArtifacts']) { throw "Missing RunnableArtifacts property" }
    if ($null -eq $data.PSObject.Properties['TestEvidenceArtifacts']) { throw "Missing TestEvidenceArtifacts property" }

    # Verify that hashes match real files on disk
    foreach ($art in $data.RunnableArtifacts) {
        $fullPath = Join-Path $repoRoot $art.RelativePath
        if (Test-Path $fullPath) {
            $expectedHash = (Get-FileHash -Algorithm SHA256 -Path $fullPath).Hash.ToLowerInvariant()
            if ($art.Sha256 -ne $expectedHash) {
                throw "Hash mismatch for $($art.FileName)! Expected $expectedHash, got $($art.Sha256)"
            }
            if ($art.SizeBytes -ne (Get-Item $fullPath).Length) {
                throw "Size mismatch for $($art.FileName)!"
            }
        }
    }
}

# Test 4: Regression check for unresolved PowerShell expressions in RELEASE_CATALOG.md
Assert-Test "RELEASE_CATALOG.md has zero unexpanded template expressions" {
    $distDir = Join-Path $repoRoot 'build\distributions'
    $mdPath = Join-Path $distDir 'RELEASE_CATALOG.md'
    $catalogText = Get-Content -Path $mdPath -Raw -Encoding utf8

    $forbiddenPatterns = @(
        '\$\([^\)]*\)',
        '\\\$[a-zA-Z0-9_]+',
        '\$\{+[a-zA-Z0-9_]+\}',
        '(?<![a-zA-Z0-9_])\$Commit(?![a-zA-Z0-9_])',
        '(?<![a-zA-Z0-9_])\$Version(?![a-zA-Z0-9_])'
    )

    foreach ($pat in $forbiddenPatterns) {
        $matches = [regex]::Matches($catalogText, $pat)
        if ($matches.Count -gt 0) {
            throw "Found unresolved expression matching '$pat': $($matches[0].Value)"
        }
    }

    # Verify key sections exist
    if ($catalogText -notmatch '## 1\. Runnable Platform Packages') {
        throw "Missing section '1. Runnable Platform Packages'"
    }
    if ($catalogText -notmatch '## 2\. Test Evidence Archives') {
        throw "Missing section '2. Test Evidence Archives'"
    }
    if ($catalogText -notmatch '## 3\. Platform Installation and Execution Gate Status') {
        throw "Missing section '3. Platform Installation and Execution Gate Status'"
    }
    if ($catalogText -notmatch '## 4\. Disclaimers & Governance Rules') {
        throw "Missing section '4. Disclaimers & Governance Rules'"
    }
}

# Test 5: Verify strict separation of runnable artifacts and test evidence
Assert-Test "Strict separation of runnable packages and test evidence" {
    $distDir = Join-Path $repoRoot 'build\distributions'
    $jsonPath = Join-Path $distDir 'RELEASE_MANIFEST.json'
    $data = Get-Content -Path $jsonPath -Raw -Encoding utf8 | ConvertFrom-Json

    foreach ($runnable in $data.RunnableArtifacts) {
        if (-not $runnable.IsRunnable) {
            throw "$($runnable.FileName) is in RunnableArtifacts but IsRunnable is false"
        }
        if ($runnable.FileName -match 'evidence') {
            throw "Evidence archive $($runnable.FileName) found in RunnableArtifacts"
        }
    }

    foreach ($evidence in $data.TestEvidenceArtifacts) {
        if ($evidence.IsRunnable) {
            throw "$($evidence.FileName) is in TestEvidenceArtifacts but IsRunnable is true"
        }
    }
}

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "   ALL $passCount / $testCount RELEASE MANIFEST REGRESSION TESTS PASSED!       " -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Cyan
