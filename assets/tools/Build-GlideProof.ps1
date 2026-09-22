param([string]$AssetRoot = (Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$manifest = Get-Content -LiteralPath (Join-Path $AssetRoot 'manifest.json') -Raw | ConvertFrom-Json
$masters = @($manifest.assets | Where-Object role -eq 'master')
$cellWidth = 400
$cellHeight = 340
$rows = [int][Math]::Ceiling($masters.Count / 4)
$proof = [System.Drawing.Bitmap]::new(1600,($rows * $cellHeight + 90))
$graphics = [System.Drawing.Graphics]::FromImage($proof)
$titleFont = [System.Drawing.Font]::new('Segoe UI',24,[System.Drawing.FontStyle]::Bold)
$labelFont = [System.Drawing.Font]::new('Segoe UI',11)
$ink = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(21,27,56))
$dark = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(21,27,56))
$light = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(242,245,249))
try {
    $graphics.Clear([System.Drawing.Color]::White)
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.DrawString('Glide / Individual asset collection',$titleFont,$ink,25,20)
    for ($i=0; $i -lt $masters.Count; $i++) {
        $entry = $masters[$i]
        $x = ($i % 4) * $cellWidth
        $y = [int][Math]::Floor($i / 4) * $cellHeight + 90
        $background = if ($entry.variant -in @('white','inverse')) { $dark } else { $light }
        $graphics.FillRectangle($background,($x+12),($y+8),376,280)
        $source = [System.Drawing.Bitmap]::FromFile((Join-Path $AssetRoot $entry.path))
        try {
            $scale = [Math]::Min(350.0/$source.Width,260.0/$source.Height)
            $w = [int][Math]::Round($source.Width*$scale)
            $h = [int][Math]::Round($source.Height*$scale)
            $rect = [System.Drawing.Rectangle]::new(($x+[int]((400-$w)/2)),($y+18+[int]((260-$h)/2)),$w,$h)
            $graphics.DrawImage($source,$rect)
        } finally { $source.Dispose() }
        $label = [IO.Path]::GetFileNameWithoutExtension($entry.path).Replace('glide-','').Replace('-',' ')
        $graphics.DrawString($label,$labelFont,$ink,($x+18),($y+300))
    }
    $proof.Save((Join-Path $AssetRoot 'preview.png'),[System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    $titleFont.Dispose(); $labelFont.Dispose(); $ink.Dispose()
    $dark.Dispose(); $light.Dispose(); $graphics.Dispose(); $proof.Dispose()
}
Write-Output 'Rendered preview.png from unchanged master files.'
