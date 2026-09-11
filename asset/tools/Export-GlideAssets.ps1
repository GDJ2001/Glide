#Requires -Version 5.1
<#!
.SYNOPSIS
Mechanically export and validate Glide's generated PNG masters.
.DESCRIPTION
Reads asset-config.json, preserves each original master, resizes whole images,
and packages explicitly configured PNG and ICO outputs. It never crops, pads,
recolors, or changes the alpha channel independently of image resampling.

Configuration schemaVersion is 1. sources[] entries require id, path under
masters/, category, variant, and optionally square, alpha, and exports[].
alpha is any, required, or opaque. Each export specifies path and width;
height is optional and otherwise follows the master's aspect ratio.
ico[] entries specify path, sourceId, and unique sizes between 1 and 256.

All input paths and output paths must remain beneath this asset directory.
All sources and output instructions are validated before files are exported.
The manifest is published only after all outputs pass validation. A failed
run exits with an error and never prints the completion summary.
.EXAMPLE
& .\asset\tools\Export-GlideAssets.ps1
.EXAMPLE
& .\asset\tools\Export-GlideAssets.ps1 -ValidateOnly
#>
[CmdletBinding()]
param(
    [string]$ConfigPath = 'asset-config.json',
    [switch]$ValidateOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

if (-not ('GlideAssetExport.AlphaProbe' -as [type])) {
    Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

namespace GlideAssetExport {
    public sealed class AlphaInfo {
        public long TransparentPixels { get; set; }
        public long SemitransparentPixels { get; set; }
        public long OpaquePixels { get; set; }
    }
    public static class AlphaProbe {
        public static AlphaInfo Inspect(Bitmap bitmap) {
            AlphaInfo info = new AlphaInfo();
            Rectangle rectangle = new Rectangle(0, 0, bitmap.Width, bitmap.Height);
            BitmapData data = bitmap.LockBits(rectangle, ImageLockMode.ReadOnly,
                                              PixelFormat.Format32bppArgb);
            try {
                byte[] row = new byte[bitmap.Width * 4];
                for (int y = 0; y < bitmap.Height; y++) {
                    Marshal.Copy(IntPtr.Add(data.Scan0, y * data.Stride), row, 0, row.Length);
                    for (int x = 0; x < bitmap.Width; x++) {
                        byte alpha = row[x * 4 + 3];
                        if (alpha == 0) info.TransparentPixels++;
                        else if (alpha == 255) info.OpaquePixels++;
                        else info.SemitransparentPixels++;
                    }
                }
            }
            finally { bitmap.UnlockBits(data); }
            return info;
        }
    }
}
'@
}

$script:AssetRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$script:AssetPrefix = $script:AssetRoot.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
$script:OutputPaths = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)

function Get-OptionalProperty {
    param([object]$Object, [string]$Name, [object]$Default)
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return $Default }
    return $property.Value
}

function Get-RequiredString {
    param([object]$Object, [string]$Name, [string]$Context)
    $value = Get-OptionalProperty $Object $Name ''
    if ($value -isnot [string] -or [string]::IsNullOrWhiteSpace($value)) {
        throw "$Context requires a nonempty '$Name' string."
    }
    return $value
}

function Resolve-AssetPath {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { throw 'An asset path cannot be empty.' }
    if ([System.IO.Path]::IsPathRooted($Path)) {
        $resolved = [System.IO.Path]::GetFullPath($Path)
    }
    else {
        $resolved = [System.IO.Path]::GetFullPath((Join-Path $script:AssetRoot $Path))
    }
    if (-not $resolved.StartsWith($script:AssetPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Path must be beneath the asset directory: $Path"
    }
    $relative = $resolved.Substring($script:AssetPrefix.Length)
    if ($relative.IndexOfAny([char[]]':*?') -ge 0) {
        throw "Wildcards and alternate data streams are not accepted: $Path"
    }
    $ancestor = $resolved
    while ($ancestor -and $ancestor.StartsWith($script:AssetRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        if (Test-Path -LiteralPath $ancestor) {
            $item = Get-Item -LiteralPath $ancestor -Force
            if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Asset paths cannot traverse symbolic links or junctions: $Path"
            }
        }
        if ($ancestor.Equals($script:AssetRoot, [System.StringComparison]::OrdinalIgnoreCase)) { break }
        $ancestor = Split-Path -Parent $ancestor
    }
    return $resolved
}

function Get-RelativeAssetPath {
    param([string]$Path)
    return $Path.Substring($script:AssetPrefix.Length).Replace('\', '/')
}

function Register-OutputPath {
    param([string]$Path, [string]$Extension)
    $resolved = Resolve-AssetPath $Path
    $relative = Get-RelativeAssetPath $resolved
    if ($relative -match '^(masters|tools)/' -or $relative -match '^(asset-config|manifest)\.json$') {
        throw "Outputs must not replace masters, tools, configuration, or manifest: $Path"
    }
    if ([System.IO.Path]::GetExtension($resolved) -ine $Extension) {
        throw "Expected a $Extension output path: $Path"
    }
    if (-not $script:OutputPaths.Add($resolved)) { throw "Duplicate output path: $Path" }
    return $resolved
}

function Get-PositiveDimension {
    param([object]$Value, [string]$Context, [int]$Maximum = 16384)
    if ([string]$Value -notmatch '^\d+$') { throw "$Context must be a positive integer." }
    $dimension = [long]$Value
    if ($dimension -lt 1 -or $dimension -gt $Maximum) {
        throw "$Context must be between 1 and $Maximum."
    }
    return [int]$dimension
}

function Get-AlphaExpectation {
    param([object]$Object, [string]$Default = 'any')
    $expectation = [string](Get-OptionalProperty $Object 'alpha' $Default)
    if ($expectation -cnotin @('any', 'required', 'opaque')) {
        throw "Alpha expectation must be any, required, or opaque; got '$expectation'."
    }
    return $expectation
}

function Assert-Alpha {
    param([object]$Info, [string]$Expectation, [string]$Context)
    $nonopaque = $Info.TransparentPixels + $Info.SemitransparentPixels
    if ($Expectation -eq 'required' -and $nonopaque -eq 0) {
        throw "Expected transparent pixels but image is fully opaque: $Context"
    }
    if ($Expectation -eq 'opaque' -and $nonopaque -gt 0) {
        throw "Expected a fully opaque image but found $nonopaque nonopaque pixels: $Context"
    }
}

function Get-PngInformation {
    param([string]$Path, [string]$Alpha = 'any', [int]$Width = 0, [int]$Height = 0)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing PNG: $Path" }
    $signature = New-Object byte[] 8
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        if ($stream.Read($signature, 0, 8) -ne 8 -or
            [System.BitConverter]::ToString($signature) -ne '89-50-4E-47-0D-0A-1A-0A') {
            throw "Invalid PNG signature: $Path"
        }
    }
    finally { $stream.Dispose() }
    $bitmap = [System.Drawing.Bitmap]::new($Path)
    try {
        if (($Width -gt 0 -and $bitmap.Width -ne $Width) -or
            ($Height -gt 0 -and $bitmap.Height -ne $Height)) {
            throw "Dimension mismatch for ${Path}: expected ${Width}x${Height}, got $($bitmap.Width)x$($bitmap.Height)."
        }
        $alphaInfo = [GlideAssetExport.AlphaProbe]::Inspect($bitmap)
        Assert-Alpha $alphaInfo $Alpha $Path
        return [pscustomobject]@{
            Width = $bitmap.Width
            Height = $bitmap.Height
            Alpha = $(if (($alphaInfo.TransparentPixels + $alphaInfo.SemitransparentPixels) -gt 0) { 'transparent' } else { 'opaque' })
        }
    }
    finally { $bitmap.Dispose() }
}

function New-ResizedBitmap {
    param([System.Drawing.Bitmap]$Source, [int]$Width, [int]$Height)
    $target = [System.Drawing.Bitmap]::new($Width, $Height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = $null
    $attributes = $null
    try {
        $graphics = [System.Drawing.Graphics]::FromImage($target)
        $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
        $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $attributes = [System.Drawing.Imaging.ImageAttributes]::new()
        $attributes.SetWrapMode([System.Drawing.Drawing2D.WrapMode]::TileFlipXY)
        $rectangle = [System.Drawing.Rectangle]::new(0, 0, $Width, $Height)
        $graphics.DrawImage($Source, $rectangle, 0, 0, $Source.Width, $Source.Height,
                            [System.Drawing.GraphicsUnit]::Pixel, $attributes)
        return $target
    }
    catch { $target.Dispose(); throw }
    finally {
        if ($null -ne $attributes) { $attributes.Dispose() }
        if ($null -ne $graphics) { $graphics.Dispose() }
    }
}

function Ensure-OutputParent {
    param([string]$Path)
    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
        $null = New-Item -ItemType Directory -Path $parent -Force
    }
}

function Write-PngExport {
    param([System.Drawing.Bitmap]$Source, [string]$SourcePath, [object]$Export)
    Ensure-OutputParent $Export.Path
    if ($Source.Width -eq $Export.Width -and $Source.Height -eq $Export.Height) {
        [System.IO.File]::Copy($SourcePath, $Export.Path, $true)
        return
    }
    $bitmap = New-ResizedBitmap $Source $Export.Width $Export.Height
    try { $bitmap.Save($Export.Path, [System.Drawing.Imaging.ImageFormat]::Png) }
    finally { $bitmap.Dispose() }
}

function Write-IcoExport {
    param([System.Drawing.Bitmap]$Source, [object]$Export)
    $frames = [System.Collections.Generic.List[object]]::new()
    foreach ($size in $Export.Sizes) {
        $bitmap = New-ResizedBitmap $Source $size $size
        $memory = [System.IO.MemoryStream]::new()
        try {
            $bitmap.Save($memory, [System.Drawing.Imaging.ImageFormat]::Png)
            $frames.Add([pscustomobject]@{ Size = $size; Bytes = $memory.ToArray() })
        }
        finally { $memory.Dispose(); $bitmap.Dispose() }
    }
    Ensure-OutputParent $Export.Path
    $stream = [System.IO.File]::Open($Export.Path, [System.IO.FileMode]::Create,
                                   [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
    $writer = [System.IO.BinaryWriter]::new($stream)
    try {
        $writer.Write([uint16]0)
        $writer.Write([uint16]1)
        $writer.Write([uint16]$frames.Count)
        $offset = [uint32](6 + 16 * $frames.Count)
        foreach ($frame in $frames) {
            $encodedSize = if ($frame.Size -eq 256) { 0 } else { $frame.Size }
            $writer.Write([byte]$encodedSize)
            $writer.Write([byte]$encodedSize)
            $writer.Write([byte]0)
            $writer.Write([byte]0)
            $writer.Write([uint16]1)
            $writer.Write([uint16]32)
            $writer.Write([uint32]$frame.Bytes.Length)
            $writer.Write([uint32]$offset)
            $offset += [uint32]$frame.Bytes.Length
        }
        foreach ($frame in $frames) { $writer.Write([byte[]]$frame.Bytes) }
    }
    finally { $writer.Dispose(); $stream.Dispose() }
}

function Get-IcoInformation {
    param([object]$Export)
    if (-not (Test-Path -LiteralPath $Export.Path -PathType Leaf)) { throw "Missing ICO: $($Export.Path)" }
    $stream = [System.IO.File]::OpenRead($Export.Path)
    $reader = [System.IO.BinaryReader]::new($stream)
    $entries = [System.Collections.Generic.List[object]]::new()
    $hasAlpha = $false
    try {
        if ($reader.ReadUInt16() -ne 0 -or $reader.ReadUInt16() -ne 1) {
            throw "Invalid ICO header: $($Export.Path)"
        }
        $count = $reader.ReadUInt16()
        if ($count -ne $Export.Sizes.Count) { throw "ICO frame count mismatch: $($Export.Path)" }
        foreach ($size in $Export.Sizes) {
            $width = [int]$reader.ReadByte()
            $height = [int]$reader.ReadByte()
            if ($width -eq 0) { $width = 256 }
            if ($height -eq 0) { $height = 256 }
            $colors = $reader.ReadByte()
            $reserved = $reader.ReadByte()
            $planes = $reader.ReadUInt16()
            $bits = $reader.ReadUInt16()
            $length = $reader.ReadUInt32()
            $offset = $reader.ReadUInt32()
            if ($width -ne $size -or $height -ne $size -or $colors -ne 0 -or
                $reserved -ne 0 -or $planes -ne 1 -or $bits -ne 32 -or
                $offset -lt (6 + 16 * $count) -or ([long]$offset + $length) -gt $stream.Length) {
                throw "Invalid ICO directory entry for ${size}px: $($Export.Path)"
            }
            $entries.Add([pscustomobject]@{ Size = $size; Length = $length; Offset = $offset })
        }
        foreach ($entry in $entries) {
            $null = $stream.Seek($entry.Offset, [System.IO.SeekOrigin]::Begin)
            $bytes = $reader.ReadBytes([int]$entry.Length)
            if ($bytes.Length -lt 8 -or [System.BitConverter]::ToString($bytes, 0, 8) -ne '89-50-4E-47-0D-0A-1A-0A') {
                throw "ICO frame is not a valid embedded PNG: $($Export.Path)"
            }
            $memory = [System.IO.MemoryStream]::new($bytes, $false)
            $bitmap = $null
            try {
                $bitmap = [System.Drawing.Bitmap]::new($memory)
                if ($bitmap.Width -ne $entry.Size -or $bitmap.Height -ne $entry.Size) {
                    throw "ICO embedded image dimensions mismatch: $($Export.Path)"
                }
                $alphaInfo = [GlideAssetExport.AlphaProbe]::Inspect($bitmap)
                Assert-Alpha $alphaInfo $Export.Alpha "$($Export.Path) [$($entry.Size)px]"
                if (($alphaInfo.TransparentPixels + $alphaInfo.SemitransparentPixels) -gt 0) { $hasAlpha = $true }
            }
            finally {
                if ($null -ne $bitmap) { $bitmap.Dispose() }
                $memory.Dispose()
            }
        }
    }
    finally { $reader.Dispose(); $stream.Dispose() }
    # Ask the Windows image decoder to open the finished container as well.
    $icon = [System.Drawing.Icon]::new($Export.Path)
    try { $null = $icon.Handle }
    finally { $icon.Dispose() }
    $maximum = [int]($Export.Sizes | Measure-Object -Maximum).Maximum
    return [pscustomobject]@{
        Width = $maximum
        Height = $maximum
        Alpha = $(if ($hasAlpha) { 'transparent' } else { 'opaque' })
    }
}

function New-ManifestEntry {
    param([string]$Path, [object]$Info, [string]$Role, [string]$SourceId,
          [string]$Category, [string]$Variant, [int[]]$Sizes = @())
    $file = Get-Item -LiteralPath $Path
    $entry = [ordered]@{
        path = Get-RelativeAssetPath $Path
        role = $Role
        sourceId = $SourceId
        type = [System.IO.Path]::GetExtension($Path).TrimStart('.').ToLowerInvariant()
        category = $Category
        variant = $Variant
        width = [int]$Info.Width
        height = [int]$Info.Height
        alpha = $Info.Alpha
        bytes = [long]$file.Length
        sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    if ($Sizes.Count -gt 0) { $entry.sizes = $Sizes }
    return [pscustomobject]$entry
}

$resolvedConfig = Resolve-AssetPath $ConfigPath
if (-not (Test-Path -LiteralPath $resolvedConfig -PathType Leaf)) { throw "Missing configuration: $resolvedConfig" }
$config = Get-Content -LiteralPath $resolvedConfig -Raw | ConvertFrom-Json
if ((Get-OptionalProperty $config 'schemaVersion' 0) -ne 1) { throw 'Unsupported configuration schemaVersion; expected 1.' }
$sourceConfigurations = @(Get-OptionalProperty $config 'sources' @())
if ($sourceConfigurations.Count -eq 0) { throw 'Configuration sources must contain at least one master.' }
$sources = [System.Collections.Generic.List[object]]::new()
$sourceById = @{}
$sourcePaths = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
$pngExports = [System.Collections.Generic.List[object]]::new()
$icoExports = [System.Collections.Generic.List[object]]::new()

# Validate the complete plan and every source before writing any exports.
foreach ($sourceConfig in $sourceConfigurations) {
    $id = Get-RequiredString $sourceConfig 'id' 'Source'
    if ($sourceById.ContainsKey($id)) { throw "Duplicate source id: $id" }
    $path = Resolve-AssetPath (Get-RequiredString $sourceConfig 'path' "Source $id")
    $relative = Get-RelativeAssetPath $path
    if ($relative -notmatch '^masters/.+\.png$') { throw "Source $id must reference a PNG beneath masters/." }
    if (-not $sourcePaths.Add($path)) { throw "Each master should have one source entry: $relative" }
    $category = Get-RequiredString $sourceConfig 'category' "Source $id"
    $variant = Get-RequiredString $sourceConfig 'variant' "Source $id"
    $alpha = Get-AlphaExpectation $sourceConfig
    $info = Get-PngInformation $path $alpha
    $square = Get-OptionalProperty $sourceConfig 'square' $false
    if ($square -isnot [bool]) { throw "Source $id square must be a JSON boolean." }
    if ($square -and $info.Width -ne $info.Height) { throw "Square master required for ${id}: $($info.Width)x$($info.Height)." }
    $source = [pscustomobject]@{
        Id = $id; Path = $path; Category = $category; Variant = $variant
        Alpha = $alpha; Info = $info; Hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    }
    $sources.Add($source)
    $sourceById[$id] = $source
    foreach ($export in @(Get-OptionalProperty $sourceConfig 'exports' @())) {
        $exportPath = Register-OutputPath (Get-RequiredString $export 'path' "Export of $id") '.png'
        $width = Get-PositiveDimension (Get-OptionalProperty $export 'width' 0) "Export width for $id"
        $proportionalHeight = [int][System.Math]::Max(1, [System.Math]::Round($width * [double]$info.Height / $info.Width, 0, [System.MidpointRounding]::AwayFromZero))
        $height = Get-PositiveDimension (Get-OptionalProperty $export 'height' $proportionalHeight) "Export height for $id"
        if ($height -ne $proportionalHeight) {
            throw "Export would distort $id. Width $width requires height $proportionalHeight, but got $height."
        }
        $pngExports.Add([pscustomobject]@{
            Path = $exportPath; SourceId = $id; Width = $width; Height = $height
            Alpha = Get-AlphaExpectation $export $alpha
            Category = [string](Get-OptionalProperty $export 'category' $category)
            Variant = [string](Get-OptionalProperty $export 'variant' $variant)
        })
    }
}
foreach ($icoConfig in @(Get-OptionalProperty $config 'ico' @())) {
    $id = Get-RequiredString $icoConfig 'sourceId' 'ICO'
    if (-not $sourceById.ContainsKey($id)) { throw "ICO references unknown source: $id" }
    $source = $sourceById[$id]
    if ($source.Info.Width -ne $source.Info.Height) { throw "ICO source must be square: $id" }
    $path = Register-OutputPath (Get-RequiredString $icoConfig 'path' 'ICO') '.ico'
    $sizes = @(Get-OptionalProperty $icoConfig 'sizes' @())
    if ($sizes.Count -lt 1 -or $sizes.Count -gt 256) { throw 'ICO sizes must contain 1-256 entries.' }
    $validSizes = @($sizes | ForEach-Object { Get-PositiveDimension $_ "ICO size for $id" 256 } | Sort-Object)
    if (@($validSizes | Select-Object -Unique).Count -ne $validSizes.Count) { throw "ICO sizes must be unique: $path" }
    $icoExports.Add([pscustomobject]@{
        Path = $path; SourceId = $id; Sizes = [int[]]$validSizes
        Alpha = Get-AlphaExpectation $icoConfig $source.Alpha
        Category = [string](Get-OptionalProperty $icoConfig 'category' $source.Category)
        Variant = [string](Get-OptionalProperty $icoConfig 'variant' $source.Variant)
    })
}

$manifestEntries = [System.Collections.Generic.List[object]]::new()
foreach ($source in $sources) {
    $manifestEntries.Add((New-ManifestEntry $source.Path $source.Info 'master' $source.Id $source.Category $source.Variant))
    $matchingPng = @($pngExports | Where-Object { $_.SourceId -eq $source.Id })
    $matchingIco = @($icoExports | Where-Object { $_.SourceId -eq $source.Id })
    $bitmap = $null
    if (-not $ValidateOnly -and ($matchingPng.Count + $matchingIco.Count) -gt 0) {
        $bitmap = [System.Drawing.Bitmap]::new($source.Path)
    }
    try {
        foreach ($export in $matchingPng) {
            if (-not $ValidateOnly) { Write-PngExport $bitmap $source.Path $export }
            $info = Get-PngInformation $export.Path $export.Alpha $export.Width $export.Height
            $manifestEntries.Add((New-ManifestEntry $export.Path $info 'export' $source.Id $export.Category $export.Variant))
        }
        foreach ($export in $matchingIco) {
            if (-not $ValidateOnly) { Write-IcoExport $bitmap $export }
            $info = Get-IcoInformation $export
            $manifestEntries.Add((New-ManifestEntry $export.Path $info 'export' $source.Id $export.Category $export.Variant $export.Sizes))
        }
    }
    finally { if ($null -ne $bitmap) { $bitmap.Dispose() } }
    if ((Get-FileHash -LiteralPath $source.Path -Algorithm SHA256).Hash -ne $source.Hash) {
        throw "Master changed during export or validation: $($source.Path)"
    }
}

$manifest = [ordered]@{
    schemaVersion = 1
    hashAlgorithm = 'SHA256'
    assetCount = $manifestEntries.Count
    assets = @($manifestEntries | Sort-Object -Property path)
}
$manifestJson = ($manifest | ConvertTo-Json -Depth 8) + [Environment]::NewLine
$manifestPath = Resolve-AssetPath 'manifest.json'
if ($ValidateOnly) {
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw 'No manifest.json exists. Run the exporter first to establish the validated SHA256 manifest.'
    }
    $existingJson = [System.IO.File]::ReadAllText($manifestPath)
    if ($existingJson -cne $manifestJson) {
        throw 'Manifest verification failed: dimensions, metadata, asset list, byte sizes, or SHA256 checksums differ. No files were changed.'
    }
}
else {
    $temporaryManifest = Resolve-AssetPath 'manifest.json.new'
    [System.IO.File]::WriteAllText($temporaryManifest, $manifestJson, [System.Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporaryManifest -Destination $manifestPath -Force
}

$totalBytes = ($manifestEntries | Measure-Object -Property bytes -Sum).Sum
$verb = if ($ValidateOnly) { 'Validated' } else { 'Exported and validated' }
Write-Host "$verb $($sources.Count) masters, $($pngExports.Count) PNG exports, and $($icoExports.Count) ICO containers."
Write-Host "Manifest: $manifestPath ($($manifestEntries.Count) assets; $([long]$totalBytes) bytes)."
Write-Host 'Verified decode validity, exact requested dimensions, configured transparency, and SHA256 hashes; masters are unchanged.'
