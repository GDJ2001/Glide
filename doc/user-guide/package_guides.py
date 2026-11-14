"""Render searchable guide PDFs with bookmarks, refresh checksums, and create the offline ZIP."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path

from build_guides import ROOT, TITLES, build_all


def _import_pypdf():
    try:
        import pypdf  # type: ignore
        return pypdf
    except ModuleNotFoundError:
        local_app = Path(os.environ.get("LOCALAPPDATA", str(Path.home() / "AppData" / "Local")))
        uv_cache = local_app / "uv" / "cache" / "archive-v0"
        if uv_cache.is_dir():
            for pkg_dir in sorted(uv_cache.iterdir()):
                if (pkg_dir / "pypdf").is_dir():
                    sys.path.insert(0, str(pkg_dir))
                    import pypdf  # type: ignore
                    return pypdf
        raise


pypdf = _import_pypdf()

EDGE = Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe")
OUT = Path.home() / "Downloads" / "Glide-User-Guides-1.0.0-2026-11-12.zip"

def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _skeleton(text: str) -> str:
    """Normalize text for Sinhala PDF glyph/kombuwa extraction reordering."""
    return "".join(
        ch
        for ch in text.lower()
        if ch.isalnum() or ("\u0D85" <= ch <= "\u0DC6")
    )


def _find_heading_page(page_lines: list[list[str]], index: int, title: str, min_page: int) -> int:
    target = _skeleton(f"{index}.{title}")
    for p_idx in range(min_page, len(page_lines)):
        for line in page_lines[p_idx]:
            sk = _skeleton(line)
            if sk == target or (sk.startswith(target) and len(sk) <= len(target) + 4):
                return p_idx
    # Secondary substring pass in case Chromium wrapped or joined a heading line
    for p_idx in range(min_page, len(page_lines)):
        joined = _skeleton("".join(page_lines[p_idx]))
        if target in joined:
            return p_idx
    raise RuntimeError(f"Missing PDF heading on any page >= {min_page + 1}: {index}. {title}")


def render(lang: str) -> None:
    if not EDGE.exists():
        raise RuntimeError(f"Microsoft Edge executable not found at {EDGE}")
    page = ROOT / f"guide-{lang}.html"
    target = ROOT / f"guide-{lang}.pdf"
    target.unlink(missing_ok=True)
    profile = tempfile.mkdtemp(prefix=f"glide-guide-{lang}-")
    try:
        subprocess.run(
            [
                str(EDGE),
                "--headless=new",
                "--disable-gpu",
                f"--user-data-dir={profile}",
                "--no-pdf-header-footer",
                "--virtual-time-budget=12000",
                f"--print-to-pdf={target}",
                page.as_uri(),
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        stable = 0
        previous_size = -1
        for _ in range(80):
            try:
                if target.exists():
                    size = target.stat().st_size
                    reader = pypdf.PdfReader(str(target))
                    text_len = sum(len(p.extract_text() or "") for p in reader.pages)
                    valid = len(reader.pages) > 1 and text_len > 1000
                    stable = stable + 1 if valid and size == previous_size else 0
                    previous_size = size
                    if stable >= 3:
                        break
            except Exception:
                stable = 0
            time.sleep(0.5)
        else:
            raise RuntimeError(f"PDF rendering failed or timed out: {target}")
    finally:
        shutil.rmtree(profile, ignore_errors=True)

    reader = pypdf.PdfReader(str(target))
    page_lines = [(p.extract_text() or "").splitlines() for p in reader.pages]

    writer = pypdf.PdfWriter()
    writer.append(reader)
    min_page = 0
    for index, title in enumerate(TITLES[lang], 1):
        p_idx = _find_heading_page(page_lines, index, title, min_page)
        writer.add_outline_item(f"{index}. {title}", p_idx)
        min_page = p_idx

    staged = target.with_suffix(".bookmarked.pdf")
    with staged.open("wb") as fh:
        writer.write(fh)
    staged.replace(target)


def main() -> None:
    parser = argparse.ArgumentParser(description="Package Glide offline EN/SI HTML+PDF user guides.")
    parser.add_argument(
        "--source",
        type=Path,
        default=None,
        help="Optional screenshot source folder forwarded non-destructively to build_guides.py.",
    )
    args = parser.parse_args()

    manifest, _used = build_all(args.source)
    if any(x["sha256"] != x["published_sha256"] or x["redaction_regions"] for x in manifest):
        raise RuntimeError("Published images differ from the supplied originals")

    for language in ("en", "si"):
        render(language)

    paths = [
        ROOT / p
        for p in [
            "START-HERE.html",
            "guide-en.html",
            "guide-si.html",
            "guide-en.pdf",
            "guide-si.pdf",
            "screenshot-manifest.json",
            "coverage-report.md",
            "README.md",
        ]
    ]
    paths += sorted((ROOT / "assets").rglob("*.png")) + sorted((ROOT / "assets").rglob("*.jpg"))
    hashes = "".join(f"{digest(p)}  {p.relative_to(ROOT).as_posix()}\n" for p in paths)
    (ROOT / "SHA256SUMS.txt").write_text(hashes, encoding="utf-8")
    paths.append(ROOT / "SHA256SUMS.txt")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for p in paths:
            z.write(p, p.relative_to(ROOT).as_posix())
    (OUT.parent / (OUT.name + ".sha256.txt")).write_text(
        f"{digest(OUT)}  {OUT.name}\n", encoding="utf-8"
    )
    with zipfile.ZipFile(OUT) as z:
        for entry in z.infolist():
            if z.read(entry.filename) != (ROOT / entry.filename).read_bytes():
                raise RuntimeError(f"ZIP mismatch: {entry.filename}")

    print(
        json.dumps(
            {
                "zip": str(OUT),
                "zip_sha256": digest(OUT),
                "files": len(paths),
                "screenshots": len(manifest),
                "unique_screenshots": len(set(x["published"] for x in manifest)),
                "annotated_screenshots": len(set(x["annotated"] for x in manifest if x.get("annotated"))),
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
