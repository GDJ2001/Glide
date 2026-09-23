<#
.SYNOPSIS
    Builds single-file self-contained release distributions for Glide on Linux.
.DESCRIPTION
    Compiles Glide.App targeting linux-x64 and linux-arm64, stages systemd and desktop assets,
    and produces distribution tarball archives.
#>

param(
    [string]$Configuration = "Release",
    [string[]]$Runtimes = @("linux-x64")
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path "$ScriptDir\..\.."
$ProjectFile = Join-Path $RepoRoot "desktop\src\Glide.App\Glide.App.csproj"
$OutputDir = Join-Path $RepoRoot "build\distributions\linux"

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "       Building Glide Linux Self-Contained Distributions        " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

foreach ($rid in $Runtimes) {
    Write-Host "`n[BUILD] Publishing Glide.App for $rid ($Configuration)..." -ForegroundColor Yellow
    $publishDir = Join-Path $OutputDir "stage-$rid"
    if (Test-Path $publishDir) { Remove-Item -Recurse -Force $publishDir }

    dotnet publish $ProjectFile `
        -c $Configuration `
        -r $rid `
        --self-contained true `
        /p:PublishSingleFile=true `
        -o $publishDir

    if ($LASTEXITCODE -ne 0) {
        Write-Error "Failed to publish for runtime $rid"
    }

    # Copy FreeDesktop and systemd metadata
    Copy-Item (Join-Path $ScriptDir "glide.desktop") $publishDir
    Copy-Item (Join-Path $ScriptDir "glide.service") $publishDir

    # Create archive
    $tarPath = Join-Path $OutputDir "glide-linux-$rid.tar.gz"
    Write-Host "[PACKAGE] Packaging $tarPath..." -ForegroundColor Green
    tar -czf $tarPath -C $publishDir .

    Write-Host "[SUCCESS] Artifact created: $tarPath ($(Get-Item $tarPath | Select-Object -ExpandProperty Length) bytes)" -ForegroundColor Green
}

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "        All Linux Distributions Built Successfully!              " -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Cyan
