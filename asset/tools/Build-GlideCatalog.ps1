param(
    [string]$AssetRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$ManifestPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$assetDirectory = [System.IO.Path]::GetFullPath($AssetRoot)
if ([string]::IsNullOrWhiteSpace($ManifestPath)) {
    $ManifestPath = Join-Path $assetDirectory 'manifest.json'
}
if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
    throw "The asset manifest does not exist: $ManifestPath"
}

$manifest = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($null -ne $manifest -and $null -ne $manifest.PSObject.Properties['files']) {
    $sourceEntries = @($manifest.files)
} elseif ($null -ne $manifest -and $null -ne $manifest.PSObject.Properties['assets']) {
    $sourceEntries = @($manifest.assets)
} else {
    $sourceEntries = @($manifest)
}

function Get-OptionalProperty {
    param([object]$Entry, [string]$Name, [object]$Fallback = '')
    if ($null -ne $Entry -and $null -ne $Entry.PSObject.Properties[$Name]) {
        return $Entry.$Name
    }
    return $Fallback
}

$records = [System.Collections.Generic.List[object]]::new()
$seenPaths = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
$rootPrefix = $assetDirectory.TrimEnd([char[]]@('\', '/')) + [System.IO.Path]::DirectorySeparatorChar
foreach ($entry in $sourceEntries) {
    $relativePath = [string](Get-OptionalProperty $entry 'path')
    $relativePath = $relativePath.Replace('\', '/') -replace '^(\./)+', ''
    if ([string]::IsNullOrWhiteSpace($relativePath)) { continue }
    if ([System.IO.Path]::IsPathRooted($relativePath) -or $relativePath -match '(^|/)\.\.(/|$)') {
        throw "Manifest paths must be relative to the asset folder: $relativePath"
    }
    $extension = [System.IO.Path]::GetExtension($relativePath).ToLowerInvariant()
    if ($extension -notin @('.svg', '.png', '.webp', '.jpg', '.jpeg', '.gif', '.ico', '.icns', '.xml')) { continue }

    $absolutePath = [System.IO.Path]::GetFullPath((Join-Path $assetDirectory $relativePath))
    if (-not $absolutePath.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Manifest path is outside the asset folder: $relativePath"
    }
    if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
        throw "An asset listed in the manifest is missing: $relativePath"
    }
    if (-not $seenPaths.Add($relativePath)) { continue }

    $category = [string](Get-OptionalProperty $entry 'category' 'other')
    if ([string]::IsNullOrWhiteSpace($category)) { $category = 'other' }
    $variant = [string](Get-OptionalProperty $entry 'variant')
    if ([string]::IsNullOrWhiteSpace($variant)) {
        $variant = [System.IO.Path]::GetFileNameWithoutExtension($relativePath) -replace '[-_](\d+x\d+|\d+px|\d+)$', ''
    }
    $records.Add([ordered]@{
        path = $relativePath
        category = $category
        variant = $variant
        width = Get-OptionalProperty $entry 'width' $null
        height = Get-OptionalProperty $entry 'height' $null
        bytes = Get-OptionalProperty $entry 'bytes' (Get-Item -LiteralPath $absolutePath).Length
        sha256 = Get-OptionalProperty $entry 'sha256'
        role = Get-OptionalProperty $entry 'role'
    })
}
if ($records.Count -eq 0) { throw 'The asset manifest contains no supported graphic assets.' }

# A JSON script element is parsed as HTML before JavaScript reads it. Escape the
# HTML-significant characters so even an unusual filename cannot close the tag.
$json = ConvertTo-Json -InputObject @($records.ToArray()) -Depth 8 -Compress
$json = $json.Replace('&', '\u0026').Replace('<', '\u003c').Replace('>', '\u003e')
$json = $json.Replace([string][char]0x2028, '\u2028').Replace([string][char]0x2029, '\u2029')

$template = @'
<!doctype html>
<html lang="en" data-preview="checker">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="color-scheme" content="light">
  <title>Glide &mdash; Asset Library</title>
  <style>
    :root { --ink:#17203c; --muted:#66708a; --line:#e0e5f0; --blue:#315fff; --page:#f6f8fc; }
    * { box-sizing:border-box; }
    body { margin:0; background:var(--page); color:var(--ink); font:14px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif; }
    a { color:inherit; }
    button,input { font:inherit; }
    button,a,input,summary { -webkit-tap-highlight-color:transparent; }
    button:focus-visible,a:focus-visible,input:focus-visible,summary:focus-visible { outline:3px solid #5a81ff; outline-offset:4px; }
    .shell { width:min(1460px,100%); margin:auto; padding:38px 48px 48px; }
    header { display:flex; align-items:flex-start; justify-content:space-between; gap:28px; padding-bottom:32px; border-bottom:1px solid var(--line); }
    .eyebrow { margin:0 0 14px; color:var(--blue); font-size:11px; font-weight:700; letter-spacing:.16em; text-transform:uppercase; }
    h1 { margin:0; font-size:clamp(32px,4vw,48px); font-weight:600; line-height:1.13; letter-spacing:-.045em; }
    .intro { margin:15px 0 0; max-width:570px; color:var(--muted); }
    .manifest-link { flex:none; margin-top:6px; padding:10px 15px; border:1px solid var(--line); border-radius:9px; background:#fff; text-decoration:none; font-size:12px; }
    .manifest-link:hover { border-color:#a8b9ea; }
    .toolbar { display:flex; flex-wrap:wrap; justify-content:space-between; align-items:center; gap:18px; padding:25px 0 15px; }
    .filters,.backgrounds { display:flex; flex-wrap:wrap; gap:4px; }
    .filters button,.backgrounds button { border:0; cursor:pointer; }
    .filters button { color:var(--muted); background:transparent; padding:9px 14px; border-radius:8px; font-size:13px; }
    .filters button[aria-pressed="true"] { color:#fff; background:var(--ink); }
    .filters button:hover:not([aria-pressed="true"]) { background:#e9edf7; color:var(--ink); }
    .controls { display:flex; flex-wrap:wrap; align-items:center; gap:18px; }
    .backgrounds { border:1px solid var(--line); background:#fff; padding:4px; border-radius:9px; }
    .backgrounds button { min-width:31px; min-height:31px; padding:4px 8px; border-radius:5px; background:transparent; color:var(--muted); font-size:11px; }
    .backgrounds button[aria-pressed="true"] { background:#ebeffb; color:#274bbb; }
    .search { width:210px; min-height:41px; padding:9px 12px; color:var(--ink); background:#fff; border:1px solid var(--line); border-radius:9px; }
    .search::placeholder { color:#8891a6; }
    .status { margin:0 0 20px; color:var(--muted); font-size:12px; }
    .grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); align-items:start; gap:20px; }
    .card { overflow:hidden; border:1px solid var(--line); border-radius:13px; background:#fff; }
    .preview { height:230px; display:flex; justify-content:center; align-items:center; padding:30px; text-decoration:none; border-bottom:1px solid var(--line); }
    [data-preview="checker"] .preview { background-color:#f5f6fa; background-image:linear-gradient(45deg,#e7eaf1 25%,transparent 25%),linear-gradient(-45deg,#e7eaf1 25%,transparent 25%),linear-gradient(45deg,transparent 75%,#e7eaf1 75%),linear-gradient(-45deg,transparent 75%,#e7eaf1 75%); background-size:20px 20px; background-position:0 0,0 10px,10px -10px,-10px 0; }
    [data-preview="light"] .preview { background:#fff; }
    [data-preview="dark"] .preview { background:#10172e; }
    .preview img { display:block; max-width:100%; max-height:100%; width:auto; height:auto; object-fit:contain; }
    .preview.is-square img { max-width:158px; max-height:158px; }
    .preview.is-wide img { width:100%; }
    .no-preview { padding:8px 13px; border:1px solid var(--line); border-radius:7px; background:#fff; color:var(--muted); font-size:12px; }
    .card-body { padding:18px 19px 16px; }
    .card-heading { display:flex; align-items:flex-start; justify-content:space-between; gap:12px; }
    h2 { margin:0; font-size:15px; line-height:1.35; font-weight:600; letter-spacing:-.02em; overflow-wrap:anywhere; }
    .category { margin:5px 0 13px; font-size:11px; color:var(--muted); }
    .export-count { color:var(--muted); font-size:10px; white-space:nowrap; padding-top:2px; }
    .export-list { display:flex; flex-wrap:wrap; gap:6px; }
    .export { display:inline-flex; gap:5px; align-items:center; border:1px solid var(--line); border-radius:6px; background:#fff; padding:5px 8px; text-decoration:none; font-size:10px; color:#435070; }
    .export:hover { color:var(--blue); border-color:#a3b7ff; background:#f5f7ff; }
    .format { font-weight:700; }
    details { margin-top:10px; }
    summary { cursor:pointer; color:var(--muted); font-size:11px; margin-bottom:8px; }
    .empty { padding:70px 20px; text-align:center; color:var(--muted); border:1px dashed #cbd3e5; border-radius:12px; }
    .empty[hidden] { display:none; }
    footer { display:flex; justify-content:space-between; flex-wrap:wrap; gap:12px; padding:27px 0 0; margin-top:12px; color:var(--muted); font-size:11px; }
    footer p { margin:0; }
    .sr-only { position:absolute; width:1px; height:1px; padding:0; margin:-1px; overflow:hidden; clip:rect(0,0,0,0); white-space:nowrap; border:0; }
    @media(min-width:1450px) { .grid { grid-template-columns:repeat(4,minmax(0,1fr)); } .preview { height:210px; } }
    @media(max-width:1060px) { .grid { grid-template-columns:repeat(2,minmax(0,1fr)); } .shell { padding:30px; } }
    @media(max-width:630px) { .shell { padding:25px 18px; } header { gap:18px; flex-direction:column; } .manifest-link { margin:0; } .grid { grid-template-columns:1fr; } .toolbar { align-items:stretch; } .controls { width:100%; justify-content:space-between; gap:9px; } .search { flex:1; min-width:140px; } .filters button { padding:8px 11px; } .preview { height:230px; } }
    @media print { body { background:#fff; } .shell { padding:0; } .controls,.filters,.manifest-link,footer { display:none; } .grid { grid-template-columns:repeat(3,minmax(0,1fr)); gap:10px; } .card { break-inside:avoid; } .preview { height:150px; } .card-body { padding:10px; } details { display:none; } h1 { font-size:28px; } }
  </style>
</head>
<body>
  <main class="shell">
    <header>
      <div>
        <p class="eyebrow">Brand collection / 01</p>
        <h1>Glide asset library</h1>
        <p class="intro">Individual logos, wordmarks, symbols and platform icons. Open a preview to inspect the source, or choose an export below it.</p>
      </div>
      <a class="manifest-link" href="manifest.json">View file manifest &#8599;</a>
    </header>
    <section aria-label="Asset filters">
      <div class="toolbar">
        <div class="filters" role="group" aria-label="Asset category">
          <button type="button" data-filter="all" aria-pressed="true">All assets</button>
          <button type="button" data-filter="logos" aria-pressed="false">Logos</button>
          <button type="button" data-filter="icon" aria-pressed="false">Symbols</button>
          <button type="button" data-filter="app" aria-pressed="false">App icons</button>
          <button type="button" data-filter="platform" aria-pressed="false">Platforms</button>
        </div>
        <div class="controls">
          <div class="backgrounds" role="group" aria-label="Preview background">
            <button type="button" data-background="light" aria-pressed="false" title="White preview background">Light</button>
            <button type="button" data-background="dark" aria-pressed="false" title="Dark preview background">Dark</button>
            <button type="button" data-background="checker" aria-pressed="true" title="Checkerboard preview background">Checker</button>
          </div>
          <label class="sr-only" for="search">Find an asset</label>
          <input class="search" id="search" type="search" placeholder="Find an asset&hellip;" autocomplete="off">
        </div>
      </div>
      <p class="status" id="status" role="status" aria-live="polite"></p>
    </section>
    <div class="grid" id="grid"></div>
    <p class="empty" id="empty" hidden>No assets match this search. Try a different name or category.</p>
    <footer>
      <p>Preview backgrounds change the canvas only; each download keeps its original background.</p>
      <p>Offline catalog &middot; Built from the asset manifest</p>
    </footer>
    <noscript><p>This catalog needs JavaScript to display the local asset manifest. You can also browse the files directly in the asset folder.</p></noscript>
  </main>
  <script id="asset-data" type="application/json">__GLIDE_ASSET_DATA__</script>
  <script>
  (() => {
    'use strict';
    const assets = JSON.parse(document.getElementById('asset-data').textContent);
    const grid = document.getElementById('grid');
    const status = document.getElementById('status');
    const empty = document.getElementById('empty');
    const search = document.getElementById('search');
    let selectedFilter = 'all';
    const grouped = new Map();
    const fileExtension = p => p.split('.').pop().toLowerCase();
    const urlFor = p => p.split('/').map(encodeURIComponent).join('/');
    const readable = value => String(value).replace(/[-_]+/g,' ').replace(/\b\w/g,c => c.toUpperCase());
    const labelFor = file => {
      const ext = fileExtension(file.path).toUpperCase();
      const width = Number(file.width), height = Number(file.height);
      if (ext === 'SVG') return { ext, size:'Vector' };
      return { ext, size:width > 0 && height > 0 ? `${width} \u00d7 ${height}` : '' };
    };
    const byteLabel = bytes => {
      const n = Number(bytes);
      if (!Number.isFinite(n) || n < 0) return '';
      return n < 1024 ? `${n} B` : n < 1048576 ? `${(n/1024).toFixed(1)} KB` : `${(n/1048576).toFixed(1)} MB`;
    };
    const familyFor = (category, variant, path) => {
      const categoryName = String(category).toLowerCase();
      if (/(platform|android|ios|ipad|macos|windows|linux|favicon|pwa|browser|web|desktop|adaptive|mipmap)/.test(categoryName)) return 'platform';
      if (/(app|launcher|maskable)/.test(categoryName)) return 'app';
      if (/(symbol|icon)/.test(categoryName)) return 'icon';
      if (/(wordmark|lockup|primary|horizontal|vertical|stacked|logo)/.test(categoryName)) return 'logos';
      const description = `${category} ${variant} ${path}`.toLowerCase();
      if (/(platform|android|ios|ipad|macos|windows|linux|favicon|pwa|browser|web[-/]|desktop|adaptive|mipmap)/.test(description)) return 'platform';
      if (/(app[-_ /]?icon|appicon|launcher|maskable)/.test(description)) return 'app';
      if (/(wordmark|lockup|primary|horizontal|vertical|stacked|logo)/.test(description)) return 'logos';
      return 'icon';
    };
    for (const file of assets) {
      const key = `${file.category}\u0000${file.variant}`;
      if (!grouped.has(key)) grouped.set(key, { category:file.category, variant:file.variant, files:[] });
      grouped.get(key).files.push(file);
    }
    const priority = { svg:0, png:1, webp:2, jpg:3, jpeg:3, gif:4, ico:5 };
    const groups = Array.from(grouped.values()).map(group => {
      group.files.sort((a,b) => {
        const aRank = priority[fileExtension(a.path)] ?? 10, bRank = priority[fileExtension(b.path)] ?? 10;
        const sourceRank = file => /^(master|source)$/.test(String(file.role).toLowerCase()) ? 0 : 1;
        return sourceRank(a)-sourceRank(b) || aRank-bRank || (Number(b.width)||0)-(Number(a.width)||0) || a.path.localeCompare(b.path);
      });
      group.family = familyFor(group.category,group.variant,group.files[0].path);
      group.searchText = `${group.category} ${group.variant} ${group.files.map(f=>f.path).join(' ')}`.toLowerCase();
      return group;
    }).sort((a,b) => {
      const order = { logos:0, icon:1, app:2, platform:3 };
      return order[a.family]-order[b.family] || a.category.localeCompare(b.category) || a.variant.localeCompare(b.variant);
    });
    function exportLink(file) {
      const link = document.createElement('a');
      link.className = 'export';
      link.href = urlFor(file.path);
      link.download = file.path.split('/').pop();
      const label = labelFor(file);
      const format = document.createElement('span');
      format.className = 'format';
      format.textContent = label.ext;
      link.append(format);
      if (label.size) link.append(document.createTextNode(label.size));
      link.title = [file.path,byteLabel(file.bytes),file.sha256 ? `SHA-256: ${file.sha256}` : ''].filter(Boolean).join('\n');
      link.setAttribute('aria-label',`Download ${file.variant}, ${label.ext}${label.size ? ', '+label.size : ''}`);
      return link;
    }
    function createCard(group) {
      const card = document.createElement('article');
      card.className = 'card';
      const previewFile = group.files.find(f => Object.hasOwn(priority,fileExtension(f.path)));
      const preview = document.createElement('a');
      preview.className = 'preview';
      preview.href = urlFor((previewFile || group.files[0]).path);
      preview.target = '_blank';
      preview.rel = 'noopener';
      preview.setAttribute('aria-label',`Open ${readable(group.variant)} source`);
      if (previewFile) {
        const image = document.createElement('img');
        image.src = urlFor(previewFile.path);
        image.alt = `${readable(group.variant)} \u2014 ${readable(group.category)}`;
        image.loading = 'lazy';
        image.decoding = 'async';
        const width = Number(previewFile.width), height = Number(previewFile.height);
        if (width > 0 && height > 0) {
          image.width = width; image.height = height;
          if (width / height > 1.7) preview.classList.add('is-wide');
          else if (width / height > 0.8 && width / height < 1.2) preview.classList.add('is-square');
        }
        preview.append(image);
      } else {
        const note = document.createElement('span');
        note.className = 'no-preview';
        note.textContent = 'Open platform resource';
        preview.append(note);
      }
      const body = document.createElement('div'); body.className = 'card-body';
      const heading = document.createElement('div'); heading.className = 'card-heading';
      const title = document.createElement('h2'); title.textContent = readable(group.variant);
      const count = document.createElement('span'); count.className = 'export-count'; count.textContent = `${group.files.length} ${group.files.length === 1 ? 'file' : 'files'}`;
      heading.append(title,count);
      const category = document.createElement('p'); category.className = 'category'; category.textContent = readable(group.category);
      const exports = document.createElement('div'); exports.className = 'export-list';
      group.files.slice(0,4).forEach(file => exports.append(exportLink(file)));
      body.append(heading,category,exports);
      if (group.files.length > 4) {
        const details = document.createElement('details');
        const summary = document.createElement('summary'); summary.textContent = `Show ${group.files.length-4} more exports`;
        const additional = document.createElement('div'); additional.className = 'export-list';
        group.files.slice(4).forEach(file => additional.append(exportLink(file)));
        details.append(summary,additional); body.append(details);
      }
      card.append(preview,body);
      return card;
    }
    const rendered = groups.map(group => ({ group, element:createCard(group) }));
    rendered.forEach(item => grid.append(item.element));
    function update() {
      const query = search.value.trim().toLowerCase();
      let visibleGroups = 0, visibleFiles = 0;
      rendered.forEach(({ group, element }) => {
        const visible = (selectedFilter === 'all' || selectedFilter === group.family) && (!query || group.searchText.includes(query));
        element.hidden = !visible;
        if (visible) { visibleGroups++; visibleFiles += group.files.length; }
      });
      status.textContent = `${visibleGroups} ${visibleGroups === 1 ? 'variant' : 'variants'} \u00b7 ${visibleFiles} ${visibleFiles === 1 ? 'file' : 'files'}${visibleFiles === assets.length ? '' : ` of ${assets.length}`}`;
      empty.hidden = visibleGroups > 0;
    }
    document.querySelectorAll('[data-filter]').forEach(button => {
      button.addEventListener('click',() => {
        selectedFilter = button.dataset.filter;
        document.querySelectorAll('[data-filter]').forEach(other => other.setAttribute('aria-pressed',String(other === button)));
        update();
      });
    });
    document.querySelectorAll('[data-background]').forEach(button => {
      button.addEventListener('click',() => {
        document.documentElement.dataset.preview = button.dataset.background;
        document.querySelectorAll('[data-background]').forEach(other => other.setAttribute('aria-pressed',String(other === button)));
      });
    });
    search.addEventListener('input',update);
    update();
  })();
  </script>
</body>
</html>
'@

$html = $template.Replace('__GLIDE_ASSET_DATA__', $json)
$destination = Join-Path $assetDirectory 'index.html'
[System.IO.File]::WriteAllText($destination, $html, [System.Text.UTF8Encoding]::new($false))
Write-Output "Built $destination with $($records.Count) asset files."
