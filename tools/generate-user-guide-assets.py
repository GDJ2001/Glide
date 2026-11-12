#!/usr/bin/env python3
"""Render numbered, embedded-source screenshot SVGs to lossless PNG.
Source screenshots are embedded in SVGs to prevent external network access and drift.
Run: python tools/generate-user-guide-assets.py
Dependency: pip install cairosvg
"""
from pathlib import Path
import re
import sys

try:
    import cairosvg
except ImportError as exc:
    raise SystemExit("CairoSVG is required: python -m pip install cairosvg") from exc

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "doc" / "user-guide" / "assets"
NAMES = [
    "01_home_discovery_annotated",
    "02_file_picker_annotated",
    "03_active_transfer_annotated",
    "04_receive_consent_annotated",
    "05_activity_history_annotated",
    "06_pair_devices_annotated",
    "07_settings_privacy_annotated",
]

def main():
    for name in NAMES:
        source = ASSETS / f"{name}.svg"
        target = ASSETS / f"{name}.png"
        if not source.is_file():
            raise SystemExit(f"Source image missing: {source}")
        markup = source.read_text(encoding="utf-8")
        if 'data:image/png;base64,' not in markup:
            raise SystemExit(f"SVG must embed its original screenshot: {source}")
        if not re.search(r"<circle[^>]*", markup):
            raise SystemExit(f"No numbered badges found: {source}")
        cairosvg.svg2png(url=str(source), write_to=str(target))
        header = target.read_bytes()[:24]
        if header[:8] != b"\x89PNG\r\n\x1a\n":
            raise SystemExit(f"Invalid PNG: {target}")
        print(f"OK: {target.relative_to(ROOT)} ({target.stat().st_size:,} bytes)")
    print(f"PASS: {len(NAMES)} authentic screenshot-backed annotated PNGs generated.")

if __name__ == "__main__":
    main()
