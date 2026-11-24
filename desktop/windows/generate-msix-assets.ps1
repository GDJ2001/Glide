<#
.SYNOPSIS
    Generates all 25 high-DPI PNG visual assets required for Glide's Microsoft Store MSIX package.
.DESCRIPTION
    Renders 32-bit RGBA PNG icons, Start tiles, Store logos, and splash screens from
    assets/platforms/desktop/glide-1024.png into desktop/windows/msix/Assets/.
#>

[CmdletBinding()]
param(
    [string]$SourceIcon = "",
    [string]$OutputDirectory = ""
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..")).Path

if ([string]::IsNullOrWhiteSpace($SourceIcon)) {
    $SourceIcon = Join-Path $repoRoot "assets\platforms\desktop\glide-1024.png"
}
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $scriptRoot "msix\Assets"
}

if (-not (Test-Path -LiteralPath $SourceIcon)) {
    throw "Source icon not found: $SourceIcon"
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

function Save-MsixPng {
    param(
        [System.Drawing.Image]$SourceImage,
        [string]$DestPath,
        [int]$Width,
        [int]$Height,
        [double]$FillRatio = 1.0
    )

    $bmp = New-Object System.Drawing.Bitmap($Width, $Height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    try {
        $g.Clear([System.Drawing.Color]::Transparent)
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality

        if ($Width -eq $Height -and $FillRatio -ge 0.999) {
            $g.DrawImage($SourceImage, 0, 0, $Width, $Height)
        } else {
            $maxDim = [Math]::Min($Width, $Height) * $FillRatio
            $scale = [Math]::Min($maxDim / $SourceImage.Width, $maxDim / $SourceImage.Height)
            $drawW = [int][Math]::Max(1, [Math]::Round($SourceImage.Width * $scale))
            $drawH = [int][Math]::Max(1, [Math]::Round($SourceImage.Height * $scale))
            $drawX = [int][Math]::Round(($Width - $drawW) / 2.0)
            $drawY = [int][Math]::Round(($Height - $drawH) / 2.0)
            $g.DrawImage($SourceImage, $drawX, $drawY, $drawW, $drawH)
        }
    } finally {
        $g.Dispose()
    }

    try {
        $bmp.Save($DestPath, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $bmp.Dispose()
    }
}

$assetSpecs = @(
    # Square44x44 targetsize (standard & altform-unplated)
    @{ Name = "Square44x44Logo.targetsize-16.png";  W = 16;  H = 16;  Ratio = 1.0 },
    @{ Name = "Square44x44Logo.targetsize-24.png";  W = 24;  H = 24;  Ratio = 1.0 },
    @{ Name = "Square44x44Logo.targetsize-32.png";  W = 32;  H = 32;  Ratio = 1.0 },
    @{ Name = "Square44x44Logo.targetsize-48.png";  W = 48;  H = 48;  Ratio = 1.0 },
    @{ Name = "Square44x44Logo.targetsize-256.png"; W = 256; H = 256; Ratio = 1.0 },

    @{ Name = "Square44x44Logo.targetsize-16_altform-unplated.png";  W = 16;  H = 16;  Ratio = 1.0 },
    @{ Name = "Square44x44Logo.targetsize-24_altform-unplated.png";  W = 24;  H = 24;  Ratio = 1.0 },
    @{ Name = "Square44x44Logo.targetsize-32_altform-unplated.png";  W = 32;  H = 32;  Ratio = 1.0 },
    @{ Name = "Square44x44Logo.targetsize-48_altform-unplated.png";  W = 48;  H = 48;  Ratio = 1.0 },
    @{ Name = "Square44x44Logo.targetsize-256_altform-unplated.png"; W = 256; H = 256; Ratio = 1.0 },

    # Square44x44 scale factors
    @{ Name = "Square44x44Logo.scale-100.png"; W = 44;  H = 44;  Ratio = 1.0 },
    @{ Name = "Square44x44Logo.scale-200.png"; W = 88;  H = 88;  Ratio = 1.0 },
    @{ Name = "Square44x44Logo.scale-400.png"; W = 176; H = 176; Ratio = 1.0 },

    # Square150x150 scale factors
    @{ Name = "Square150x150Logo.scale-100.png"; W = 150; H = 150; Ratio = 1.0 },
    @{ Name = "Square150x150Logo.scale-200.png"; W = 300; H = 300; Ratio = 1.0 },
    @{ Name = "Square150x150Logo.scale-400.png"; W = 600; H = 600; Ratio = 1.0 },

    # Wide310x150 scale factors
    @{ Name = "Wide310x150Logo.scale-100.png"; W = 310; H = 150; Ratio = 0.70 },
    @{ Name = "Wide310x150Logo.scale-200.png"; W = 620; H = 300; Ratio = 0.70 },

    # Square310x310 scale factors
    @{ Name = "Square310x310Logo.scale-100.png"; W = 310; H = 310; Ratio = 1.0 },
    @{ Name = "Square310x310Logo.scale-200.png"; W = 620; H = 620; Ratio = 1.0 },

    # StoreLogo scale factors
    @{ Name = "StoreLogo.scale-100.png"; W = 50;  H = 50;  Ratio = 1.0 },
    @{ Name = "StoreLogo.scale-200.png"; W = 100; H = 100; Ratio = 1.0 },
    @{ Name = "StoreLogo.scale-400.png"; W = 200; H = 200; Ratio = 1.0 },

    # SplashScreen scale factors
    @{ Name = "SplashScreen.scale-100.png"; W = 620;  H = 300; Ratio = 0.60 },
    @{ Name = "SplashScreen.scale-200.png"; W = 1240; H = 600; Ratio = 0.60 }
)

$sourceImage = [System.Drawing.Image]::FromFile((Resolve-Path $SourceIcon).Path)
try {
    foreach ($spec in $assetSpecs) {
        $destPath = Join-Path $OutputDirectory $spec.Name
        Save-MsixPng -SourceImage $sourceImage -DestPath $destPath -Width $spec.W -Height $spec.H -FillRatio $spec.Ratio
    }
} finally {
    $sourceImage.Dispose()
}

Write-Host "Generated $($assetSpecs.Count) MSIX visual assets in '$OutputDirectory'." -ForegroundColor Green
