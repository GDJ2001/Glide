<#
.SYNOPSIS
    Builds release distribution archives for Glide on Windows.
.DESCRIPTION
    Compiles Glide.Windows.UI targeting win-x64, stages required desktop assets,
    and produces distribution zip archives.
#>

param(
    [string]$Configuration = "Release",
    [string[]]$Runtimes = @("win-x64")
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path "$ScriptDir\..\.."
$ProjectFile = Join-Path $RepoRoot "desktop\windows\Glide.Windows.UI\Glide.Windows.UI.csproj"
$OutputDir = Join-Path $RepoRoot "build\distributions\windows"

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "       Building Glide Windows Distribution Packages              " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

foreach ($rid in $Runtimes) {
    Write-Host "`n[BUILD] Publishing Glide.Windows.UI for $rid ($Configuration)..." -ForegroundColor Yellow
    $publishDir = Join-Path $OutputDir "stage-$rid"
    if (Test-Path $publishDir) { Remove-Item -Recurse -Force $publishDir }

    dotnet publish $ProjectFile `
        -c $Configuration `
        -r $rid `
        --self-contained false `
        -o $publishDir

    if ($LASTEXITCODE -ne 0) {
        Write-Error "Failed to publish for runtime $rid"
    }

    # Create zip archive
    $zipPath = Join-Path $OutputDir "glide-windows-$rid.zip"
    Write-Host "[PACKAGE] Packaging $zipPath..." -ForegroundColor Green
    if (Test-Path $zipPath) { Remove-Item -Force $zipPath }
    Compress-Archive -Path "$publishDir\*" -DestinationPath $zipPath -CompressionLevel Optimal

    $zipItem = Get-Item $zipPath
    Write-Host "[SUCCESS] Artifact created: $zipPath ($($zipItem.Length) bytes)" -ForegroundColor Green
}

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "        All Windows Distributions Built Successfully!            " -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Cyan
