# Glide 1.0.0 Illustrated User Guides

Open [START-HERE.html](START-HERE.html), then choose English (`guide-en.html` / `guide-en.pdf`) or Sinhala + English (`guide-si.html` / `guide-si.pdf`). Both offline guides are generated deterministically from a single Python source of truth: `doc/user-guide/build_guides.py`.

## Screenshots and Annotations

- **Immutable originals**: Supplied source captures stay byte-identical inside `assets/windows/`, `assets/linux/`, and `assets/android/`.
- **Numbered callout PNGs**: `build_guides.py` renders lossless PNG copies under `assets/annotated/<platform>/<name>-annotated.png` with rounded accent rectangles (`#5F86ED` on dark screens, `#D97706` amber on light screens), outside-placed numbered circle badges, and `"EXAMPLE ONLY - THIS CODE HAS EXPIRED"` banners on QR/numeric-code captures.
- **Manifest**: `screenshot-manifest.json` maps every original capture, its SHA-256 checksum, its annotated image path and SHA-256, and every callout bounding box and EN/SI description (`52` rows covering all `50` unique captures + `2` exact Android duplicates).

## Rebuild

From the repository root:

- **Rebuild HTML & annotated PNGs only** (uses existing `assets/` by default; never modifies external files):
  ```powershell
  python doc/user-guide/build_guides.py
  ```
- **Optionally import fresh screenshots non-destructively** (copies only; never deletes or renames source files):
  ```powershell
  python doc/user-guide/build_guides.py --source "C:\Path\To\Screenshots"
  ```
- **Rebuild everything (annotated PNGs, EN/SI HTML, bookmarked PDFs via Edge + `pypdf`, `SHA256SUMS.txt`, and offline transport ZIP)**:
  ```powershell
  python doc/user-guide/package_guides.py
  ```

Requires `Pillow` for image annotation, Microsoft Edge (`--headless=new --print-to-pdf`) for offline PDF printing, and `pypdf` for adding the 10-section PDF outline bookmarks.

See [coverage-report.md](coverage-report.md) for screenshot coverage details, uncaptured modal disclosures, and the source-verified UI label audit table (`label -> source file:line`).
