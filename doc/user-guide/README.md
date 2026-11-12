# Glide illustrated user guides

Open [START-HERE.html](START-HERE.html), then choose English or Sinhala + English. The offline HTML and PDF editions are generated from `build_guides.py` and the supplied screenshots.

## Screenshot provenance

The user's originals are preserved under `C:\Users\GDJ\Pictures\Screenshots\Glide UI\_originals`. The active screenshot collection uses descriptive names in its Windows, Linux, and Android folders. The published images in `assets/` are byte-identical copies of those originals. The user explicitly authorized publication of the complete images without blur or masking. `screenshot-manifest.json` maps original and descriptive names and records source and published SHA-256 hashes.

The 16 Ubuntu screenshots were obtained from the Ubuntu 26.04.1 LTS VM's `~/Pictures/Screenshots` folder and checked against a SHA-256 manifest generated inside the guest. The guest originals remain in place.

## Rebuild

Run `python doc/user-guide/package_guides.py` from the repository root to refresh the assets, HTML, PDFs, SHA-256 manifest, and transport ZIP. The package script uses Windows Microsoft Edge to print searchable PDFs with section bookmarks. `build_guides.py` refreshes only the assets and HTML.

The equivalent manual PDF command, if needed, is:

```powershell
$guide = (Resolve-Path 'doc/user-guide').Path
$edge = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
foreach ($language in @('en','si')) {
    $page = ([uri](Join-Path $guide "guide-$language.html")).AbsoluteUri
    $pdf = Join-Path $guide "guide-$language.pdf"
    $profile = Join-Path $env:TEMP ("glide-guide-" + [guid]::NewGuid().ToString('N'))
    & $edge --headless=new --disable-gpu --user-data-dir="$profile" --no-pdf-header-footer --virtual-time-budget=10000 --print-to-pdf="$pdf" "$page"
}
```

Use `coverage-report.md` to identify illustrations or physical routes still awaiting confirmation. Verify the installed interface again before updating control names for a later release.
