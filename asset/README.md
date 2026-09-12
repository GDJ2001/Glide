# Glide brand assets

Open **index.html** to browse the individual logo variants and download their size exports. The catalog works offline and can preview transparent artwork on light, dark, or checker backgrounds.

**preview.png** shows all 20 individual variants in one contact sheet.

## Included artwork

| Family | Variants | Folder |
| --- | --- | --- |
| Horizontal logo | Color, inverse, black, white | logos/horizontal |
| Stacked logo | Color, inverse, black, white | logos/stacked |
| Standalone symbol | Gradient, blue, indigo, black, white | icons |
| Standalone wordmark | Indigo, black, white | wordmarks |
| App icon | Full square dark, rounded dark, rounded light, rounded gradient | app-icons |

The pack contains 20 individual PNG masters, 156 PNG size exports, and two multi-resolution ICO containers. Masters are in **masters/**. The original brand presentation is in **reference/**.

## Choosing a version

- Use the color logo on light surfaces and the inverse logo on dark surfaces.
- Black and white variants are for monochrome use. White PNGs need a contrasting background to be visible.
- Use the standalone symbol where the full wordmark would be too small.
- Use the opaque square app icon for platform-controlled masking. Rounded app icons have transparent space outside their tile.
- Preserve each file's aspect ratio and existing clear space. Choose an export at least as large as its display size.

## Platform exports

- **platforms/android/**: launcher PNGs at 48, 72, 96, 144, and 192 pixels, plus a 512-pixel store graphic. These are flat launcher assets; no adaptive-icon XML or foreground/background layer integration is included.
- **platforms/ios/AppIcon.appiconset/**: opaque square PNGs and a Contents.json mapping for iPhone, iPad, and the 1024-pixel marketing icon.
- **platforms/web/**: 16/32/48-pixel favicons, favicon.ico, 180-pixel touch icon, and 192/512-pixel web app icons.
- **platforms/windows/**: a multi-resolution ICO with 16/24/32/48/64/128/256-pixel frames.
- **platforms/desktop/**: square PNGs from 16 through 1024 pixels.

These are asset exports, not an application integration or a tested store submission.

## Source and reproducibility

The artwork was created with built-in image generation using the existing Glide concept as reference. The exact prompts for this continuation are in **generation-prompts.json**. Earlier concept prompts are preserved in **reference/**.

These are high-resolution raster masters, not editable vector files. The variants share the same design direction; independently generated masters can have small differences in geometry and spacing. Raster colors may vary slightly from the brief's target palette.

The export scripts only resize complete images and package ICO frames. They preserve the source masters, do not crop or redraw the artwork, and check output dimensions, transparency, decoding, and checksums.

From the repository root in PowerShell:

```powershell
& .\asset\tools\Export-GlideAssets.ps1
& .\asset\tools\Export-GlideAssets.ps1 -ValidateOnly
& .\asset\tools\Build-GlideCatalog.ps1
```

**asset-config.json** defines every exported size. **manifest.json** records dimensions, file sizes, alpha status, and SHA256 hashes for all 178 graphic assets.
