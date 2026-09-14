# Glide Store Assets Generator & Screenshot Curator (GLIDE-061)
# Prepares 512x512 icon, 1024x500 feature graphic, and curates real device screenshots for Google Play Console.

[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path "$PSScriptRoot\..").Path
$storeAssetsDir = "$projectRoot\docs\release\store_assets"
$phoneScreensDir = "$storeAssetsDir\screenshots\phone"
$tabletScreensDir = "$storeAssetsDir\screenshots\tablet"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "   Glide Google Play Store Assets Generator & Curator       " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

# Ensure target directories exist
if (-not (Test-Path $phoneScreensDir)) { New-Item -ItemType Directory -Path $phoneScreensDir -Force | Out-Null }
if (-not (Test-Path $tabletScreensDir)) { New-Item -ItemType Directory -Path $tabletScreensDir -Force | Out-Null }

Add-Type -AssemblyName System.Drawing

# --------------------------------------------------------------------------------------------------
# 1. 512x512 Play Store App Icon
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Step 1/3] Preparing 512x512 Store Icon..." -ForegroundColor Yellow
$sourceIcon = "$projectRoot\asset\platforms\android\play-store-icon.png"
$targetIcon = "$storeAssetsDir\icon_512.png"

if (Test-Path $sourceIcon) {
    Copy-Item -Path $sourceIcon -Destination $targetIcon -Force
    $iconImg = [System.Drawing.Image]::FromFile($targetIcon)
    Write-Host "  Icon dimensions: $($iconImg.Width)x$($iconImg.Height) (Target: 512x512)" -ForegroundColor Green
    $iconImg.Dispose()
} else {
    Write-Error "Source icon not found at $sourceIcon"
}

# --------------------------------------------------------------------------------------------------
# 2. 1024x500 Feature Graphic Composition
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Step 2/3] Generating 1024x500 Feature Graphic..." -ForegroundColor Yellow
$targetFeatureGraphic = "$storeAssetsDir\feature_graphic_1024x500.png"
$sourceLogo = "$projectRoot\asset\masters\glide-logo-horizontal-white.png"

$fgWidth = 1024
$fgHeight = 500

$bitmap = New-Object System.Drawing.Bitmap($fgWidth, $fgHeight, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic

# Background Gradient: Dark brand navy to deep indigo
$rect = New-Object System.Drawing.Rectangle(0, 0, $fgWidth, $fgHeight)
$startColor = [System.Drawing.ColorTranslator]::FromHtml("#090D16")
$endColor = [System.Drawing.ColorTranslator]::FromHtml("#16163F")
$brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, $startColor, $endColor, 45.0)
$graphics.FillRectangle($brush, $rect)
$brush.Dispose()

# Subtle brand radial glow in center
$centerGlowRect = New-Object System.Drawing.Rectangle(200, 50, 624, 400)
$glowPath = New-Object System.Drawing.Drawing2D.GraphicsPath
$glowPath.AddEllipse($centerGlowRect)
$pathBrush = New-Object System.Drawing.Drawing2D.PathGradientBrush($glowPath)
$pathBrush.CenterColor = [System.Drawing.Color]::FromArgb(45, 99, 102, 241) # Indigo #6366F1 with alpha
$pathBrush.SurroundColors = @([System.Drawing.Color]::FromArgb(0, 9, 13, 22))
$graphics.FillPath($pathBrush, $glowPath)
$pathBrush.Dispose()
$glowPath.Dispose()

# Draw centered master logo (respecting Google Play safe zones: at least 15% margins)
if (Test-Path $sourceLogo) {
    $logoImg = [System.Drawing.Image]::FromFile($sourceLogo)
    # Target logo width = 600px, calculate height keeping aspect ratio
    $logoTargetWidth = 560
    $logoTargetHeight = [int]($logoImg.Height * ($logoTargetWidth / $logoImg.Width))
    
    $logoX = [int](($fgWidth - $logoTargetWidth) / 2)
    $logoY = [int](($fgHeight - $logoTargetHeight) / 2 - 20) # slightly above center
    
    $graphics.DrawImage($logoImg, $logoX, $logoY, $logoTargetWidth, $logoTargetHeight)
    $logoImg.Dispose()
}

# Add subtle tagline under logo: "SEAMLESS LOCAL FILE SHARING"
$fontFamily = [System.Drawing.FontFamily]::GenericSansSerif
$font = New-Object System.Drawing.Font($fontFamily, 14, [System.Drawing.FontStyle]::Bold)
$textBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#94A3B8"))
$sf = New-Object System.Drawing.StringFormat
$sf.Alignment = [System.Drawing.StringAlignment]::Center
$taglineRect = New-Object System.Drawing.RectangleF(0, 310, $fgWidth, 40)
$graphics.DrawString("SEAMLESS OFFLINE PEER-TO-PEER SHARING", $font, $textBrush, $taglineRect, $sf)

$textBrush.Dispose()
$font.Dispose()
$graphics.Dispose()

$bitmap.Save($targetFeatureGraphic, [System.Drawing.Imaging.ImageFormat]::Png)
$bitmap.Dispose()

Write-Host "  Feature graphic saved: $targetFeatureGraphic (1024x500)" -ForegroundColor Green

# --------------------------------------------------------------------------------------------------
# 3. Screenshot Curation
# --------------------------------------------------------------------------------------------------
Write-Host "`n[Step 3/3] Curating Real Device Screenshots..." -ForegroundColor Yellow
$evidenceScreenshots = "$projectRoot\docs\testing\evidence\screenshots"

# Curated Phone Screens
$phoneMappings = @{
    "01_home_discovery.png"     = "$evidenceScreenshots\glide_057_automation_home.png"
    "02_file_selection.png"     = "$evidenceScreenshots\glide_057_automation_selection.png"
    "03_active_transfer.png"    = "$evidenceScreenshots\glide_emulator_transfer_active_single.png"
    "04_receive_consent.png"    = "$evidenceScreenshots\glide_057_automation_receive.png"
    "05_transfer_history.png"   = "$evidenceScreenshots\glide_057_automation_history.png"
    "06_settings_privacy.png"   = "$evidenceScreenshots\glide_057_automation_settings.png"
}

foreach ($dest in $phoneMappings.Keys) {
    $src = $phoneMappings[$dest]
    if (Test-Path $src) {
        Copy-Item -Path $src -Destination "$phoneScreensDir\$dest" -Force
        Write-Host "  Curated Phone Screen: $dest" -ForegroundColor Gray
    } else {
        Write-Host "  WARNING: Source screen not found: $src" -ForegroundColor Yellow
    }
}

# Curated Tablet Screens
$tabletMappings = @{
    "01_tablet_landscape_overview.png" = "$evidenceScreenshots\glide_057_automation_landscape.png"
    "02_tablet_receive_mode.png"        = "$evidenceScreenshots\glide_emulator_receive_motion_normal.png"
}

foreach ($dest in $tabletMappings.Keys) {
    $src = $tabletMappings[$dest]
    if (Test-Path $src) {
        Copy-Item -Path $src -Destination "$tabletScreensDir\$dest" -Force
        Write-Host "  Curated Tablet Screen: $dest" -ForegroundColor Gray
    } else {
        Write-Host "  WARNING: Source screen not found: $src" -ForegroundColor Yellow
    }
}

Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host "   STORE ASSETS GENERATION COMPLETE                         " -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Cyan
