#Requires -Version 5.1
<#
.SYNOPSIS
Derives faithful Android platform resources from canonical brand masters.
.DESCRIPTION
Mechanically generates:
- Adaptive launcher foreground (ic_launcher_foreground.png) across mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi
- Adaptive launcher monochrome (ic_launcher_monochrome.png) across densities
- Legacy launcher icons (ic_launcher.png and ic_launcher_round.png) across densities
- Notification small icon (ic_notification.png) across densities (pure white alpha silhouette)
- Splash screen logo (ic_splash_logo.png) across densities

Preserves canonical brand masters in asset/ without modification.
#>
[CmdletBinding()]
param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot)
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$resDir = Join-Path $ProjectRoot "app\src\main\res"
$mastersDir = Join-Path $ProjectRoot "asset\masters"
$platformAndroidDir = Join-Path $ProjectRoot "asset\platforms\android\res"

$symbolGradientPath = Join-Path $mastersDir "glide-symbol-gradient.png"
$symbolWhitePath = Join-Path $mastersDir "glide-symbol-white.png"
$logoColorPath = Join-Path $mastersDir "glide-logo-horizontal-color.png"
$logoInversePath = Join-Path $mastersDir "glide-logo-horizontal-inverse.png"

if (-not (Test-Path $symbolGradientPath)) { throw "Missing $symbolGradientPath" }
if (-not (Test-Path $symbolWhitePath)) { throw "Missing $symbolWhitePath" }
if (-not (Test-Path $logoColorPath)) { throw "Missing $logoColorPath" }
if (-not (Test-Path $logoInversePath)) { throw "Missing $logoInversePath" }

# Density specifications
# Adaptive icon: 108dp canvas, safe zone 66dp diameter.
# Symbol drawn width: 64dp (centered on 108dp, leaves 22dp margin, fits inside 66dp circle).
$densities = @(
    @{ Name = "mdpi";    Scale = 1.0; AdaptivePx = 108; SymbolDp = 64; NotifCanvasPx = 24; NotifGlyphPx = 20 },
    @{ Name = "hdpi";    Scale = 1.5; AdaptivePx = 162; SymbolDp = 64; NotifCanvasPx = 36; NotifGlyphPx = 30 },
    @{ Name = "xhdpi";   Scale = 2.0; AdaptivePx = 216; SymbolDp = 64; NotifCanvasPx = 48; NotifGlyphPx = 40 },
    @{ Name = "xxhdpi";  Scale = 3.0; AdaptivePx = 324; SymbolDp = 64; NotifCanvasPx = 72; NotifGlyphPx = 60 },
    @{ Name = "xxxhdpi"; Scale = 4.0; AdaptivePx = 432; SymbolDp = 64; NotifCanvasPx = 96; NotifGlyphPx = 80 }
)

function New-CenteredBitmap {
    param(
        [System.Drawing.Image]$SourceImage,
        [int]$CanvasWidth,
        [int]$CanvasHeight,
        [int]$TargetDrawWidth,
        [int]$TargetDrawHeight,
        [bool]$ForceWhiteRgb = $false
    )

    $targetBmp = New-Object System.Drawing.Bitmap($CanvasWidth, $CanvasHeight, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($targetBmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)

    $x = [int][Math]::Round(($CanvasWidth - $TargetDrawWidth) / 2.0)
    $y = [int][Math]::Round(($CanvasHeight - $TargetDrawHeight) / 2.0)

    $g.DrawImage($SourceImage, $x, $y, $TargetDrawWidth, $TargetDrawHeight)
    $g.Dispose()

    if ($ForceWhiteRgb) {
        # Force all non-transparent pixels to RGB (255, 255, 255) preserving alpha
        $rect = New-Object System.Drawing.Rectangle(0, 0, $CanvasWidth, $CanvasHeight)
        $bmpData = $targetBmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadWrite, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $stride = $bmpData.Stride
        $bytes = New-Object byte[] ($stride * $CanvasHeight)
        [System.Runtime.InteropServices.Marshal]::Copy($bmpData.Scan0, $bytes, 0, $bytes.Length)

        for ($cy = 0; $cy -lt $CanvasHeight; $cy++) {
            for ($cx = 0; $cx -lt $CanvasWidth; $cx++) {
                $idx = $cy * $stride + $cx * 4
                $alpha = $bytes[$idx + 3]
                if ($alpha -gt 0) {
                    $bytes[$idx] = 255     # B
                    $bytes[$idx + 1] = 255 # G
                    $bytes[$idx + 2] = 255 # R
                }
            }
        }

        [System.Runtime.InteropServices.Marshal]::Copy($bytes, 0, $bmpData.Scan0, $bytes.Length)
        $targetBmp.UnlockBits($bmpData)
    }

    return $targetBmp
}

function New-RoundMaskedBitmap {
    param(
        [System.Drawing.Image]$SourceImage
    )

    $w = $SourceImage.Width
    $h = $SourceImage.Height
    $targetBmp = New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($targetBmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)

    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddEllipse(0, 0, $w, $h)
    $g.SetClip($path)
    $g.DrawImage($SourceImage, 0, 0, $w, $h)
    $g.Dispose()

    return $targetBmp
}

$imgGradient = [System.Drawing.Bitmap]::FromFile($symbolGradientPath)
$imgWhite = [System.Drawing.Bitmap]::FromFile($symbolWhitePath)
$imgLogoColor = [System.Drawing.Bitmap]::FromFile($logoColorPath)
$imgLogoInverse = [System.Drawing.Bitmap]::FromFile($logoInversePath)

try {
    foreach ($d in $densities) {
        $densityName = $d.Name
        $scale = $d.Scale
        $adaptivePx = $d.AdaptivePx
        $symbolDrawPx = [int][Math]::Round($d.SymbolDp * $scale)

        $mipmapDir = Join-Path $resDir "mipmap-$densityName"
        $drawableDir = Join-Path $resDir "drawable-$densityName"

        if (-not (Test-Path $mipmapDir)) { New-Item -ItemType Directory -Path $mipmapDir -Force | Out-Null }
        if (-not (Test-Path $drawableDir)) { New-Item -ItemType Directory -Path $drawableDir -Force | Out-Null }

        # 1. Adaptive foreground (ic_launcher_foreground.png)
        $fgBmp = New-CenteredBitmap -SourceImage $imgGradient -CanvasWidth $adaptivePx -CanvasHeight $adaptivePx -TargetDrawWidth $symbolDrawPx -TargetDrawHeight $symbolDrawPx
        $fgPath = Join-Path $mipmapDir "ic_launcher_foreground.png"
        $fgBmp.Save($fgPath, [System.Drawing.Imaging.ImageFormat]::Png)
        $fgBmp.Dispose()

        # 2. Adaptive monochrome (ic_launcher_monochrome.png)
        $monoBmp = New-CenteredBitmap -SourceImage $imgWhite -CanvasWidth $adaptivePx -CanvasHeight $adaptivePx -TargetDrawWidth $symbolDrawPx -TargetDrawHeight $symbolDrawPx -ForceWhiteRgb $true
        $monoPath = Join-Path $mipmapDir "ic_launcher_monochrome.png"
        $monoBmp.Save($monoPath, [System.Drawing.Imaging.ImageFormat]::Png)
        $monoBmp.Dispose()

        # 3. Splash icon (ic_splash_logo.png - centered for 160dp splash window, 96dp symbol)
        $splashCanvasPx = [int][Math]::Round(160 * $scale)
        $splashSymbolPx = [int][Math]::Round(96 * $scale)
        $splashBmp = New-CenteredBitmap -SourceImage $imgGradient -CanvasWidth $splashCanvasPx -CanvasHeight $splashCanvasPx -TargetDrawWidth $splashSymbolPx -TargetDrawHeight $splashSymbolPx
        $splashPath = Join-Path $drawableDir "ic_splash_logo.png"
        $splashBmp.Save($splashPath, [System.Drawing.Imaging.ImageFormat]::Png)
        $splashBmp.Dispose()

        # 4. Notification small icon (ic_notification.png - pure white alpha silhouette)
        $notifCanvasPx = $d.NotifCanvasPx
        $notifGlyphPx = $d.NotifGlyphPx
        $notifBmp = New-CenteredBitmap -SourceImage $imgWhite -CanvasWidth $notifCanvasPx -CanvasHeight $notifCanvasPx -TargetDrawWidth $notifGlyphPx -TargetDrawHeight $notifGlyphPx -ForceWhiteRgb $true
        $notifPath = Join-Path $drawableDir "ic_notification.png"
        $notifBmp.Save($notifPath, [System.Drawing.Imaging.ImageFormat]::Png)
        $notifBmp.Dispose()

        # 5. Horizontal brand lockups for in-app UI (132dp x 44dp, preserving 3:1 aspect ratio)
        $logoWidthPx = [int][Math]::Round(132 * $scale)
        $logoHeightPx = [int][Math]::Round(44 * $scale)

        $logoColorBmp = New-CenteredBitmap -SourceImage $imgLogoColor -CanvasWidth $logoWidthPx -CanvasHeight $logoHeightPx -TargetDrawWidth $logoWidthPx -TargetDrawHeight $logoHeightPx
        $logoColorPath = Join-Path $drawableDir "ic_glide_logo_color.png"
        $logoColorBmp.Save($logoColorPath, [System.Drawing.Imaging.ImageFormat]::Png)
        $logoColorBmp.Dispose()

        $logoInvBmp = New-CenteredBitmap -SourceImage $imgLogoInverse -CanvasWidth $logoWidthPx -CanvasHeight $logoHeightPx -TargetDrawWidth $logoWidthPx -TargetDrawHeight $logoHeightPx
        $logoInvPath = Join-Path $drawableDir "ic_glide_logo_inverse.png"
        $logoInvBmp.Save($logoInvPath, [System.Drawing.Imaging.ImageFormat]::Png)
        $logoInvBmp.Dispose()

        # 6. In-app symbol (48dp x 48dp)
        $symCanvasPx = [int][Math]::Round(48 * $scale)
        $symBmp = New-CenteredBitmap -SourceImage $imgGradient -CanvasWidth $symCanvasPx -CanvasHeight $symCanvasPx -TargetDrawWidth $symCanvasPx -TargetDrawHeight $symCanvasPx
        $symPath = Join-Path $drawableDir "ic_glide_symbol.png"
        $symBmp.Save($symPath, [System.Drawing.Imaging.ImageFormat]::Png)
        $symBmp.Dispose()

        # 7. Legacy flat launcher icons (copy from asset/platforms/android/res)
        $srcLauncherPath = Join-Path $platformAndroidDir "mipmap-$densityName\ic_launcher.png"
        $dstLauncherPath = Join-Path $mipmapDir "ic_launcher.png"
        if (Test-Path $srcLauncherPath) {
            Copy-Item -Path $srcLauncherPath -Destination $dstLauncherPath -Force
            
            # Generate circular round icon
            $srcFlat = [System.Drawing.Bitmap]::FromFile($srcLauncherPath)
            $roundBmp = New-RoundMaskedBitmap -SourceImage $srcFlat
            $dstRoundPath = Join-Path $mipmapDir "ic_launcher_round.png"
            $roundBmp.Save($dstRoundPath, [System.Drawing.Imaging.ImageFormat]::Png)
            $roundBmp.Dispose()
            $srcFlat.Dispose()
        }

        Write-Host "Generated resources for $densityName (adaptive ${adaptivePx}px, notif ${notifCanvasPx}px, logo ${logoWidthPx}x${logoHeightPx}px)" -ForegroundColor Green
    }
}
finally {
    $imgGradient.Dispose()
    $imgWhite.Dispose()
    $imgLogoColor.Dispose()
    $imgLogoInverse.Dispose()
}

Write-Host "`nAll faithful Android brand resource variants successfully derived." -ForegroundColor Cyan
