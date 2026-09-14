# Tools script to generate deterministic 10MB and 1GB test fixtures for GLIDE-017
param(
    [string]$OutputDir = "$PSScriptRoot\..\build\fixtures",
    [switch]$Generate1GB = $false
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

function Generate-BinaryFile {
    param(
        [string]$FilePath,
        [long]$SizeBytes
    )

    Write-Host "Generating $([math]::Round($SizeBytes / 1MB, 2)) MB fixture: $FilePath ..."
    $bufferSize = 1024 * 1024 # 1 MiB chunk
    $buffer = [byte[]]::new($bufferSize)
    $rng = [System.Random]::new(42) # Deterministic seed

    $stream = [System.IO.File]::Create($FilePath)
    try {
        $remaining = $SizeBytes
        while ($remaining -gt 0) {
            $toWrite = [math]::Min($remaining, $bufferSize)
            $rng.NextBytes($buffer)
            $stream.Write($buffer, 0, $toWrite)
            $remaining -= $toWrite
        }
    } finally {
        $stream.Dispose()
    }

    $hash = Get-FileHash -Path $FilePath -Algorithm SHA256
    Write-Host "  Completed: $FilePath"
    Write-Host "  Size: $( (Get-Item $FilePath).Length ) bytes"
    Write-Host "  SHA-256: $($hash.Hash)`n"
    return $hash.Hash
}

# 1. Generate 10 MB fixture
$file10MB = Join-Path $OutputDir "glide-test-10mb.bin"
$hash10MB = Generate-BinaryFile -FilePath $file10MB -SizeBytes (10 * 1024 * 1024)

# 2. Optionally generate 1 GB fixture
if ($Generate1GB) {
    $file1GB = Join-Path $OutputDir "glide-test-1gb.bin"
    $hash1GB = Generate-BinaryFile -FilePath $file1GB -SizeBytes (1024 * 1024 * 1024)
} else {
    Write-Host "Skipping 1 GB fixture generation (pass -Generate1GB to include).`n"
}

Write-Host "=== Fixtures Ready for GLIDE-017 Vertical Slice ==="
Write-Host "10 MB Path: $file10MB"
Write-Host "10 MB SHA-256: $hash10MB"
