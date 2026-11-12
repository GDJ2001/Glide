"""Render searchable guide PDFs, add bookmarks, and create the offline ZIP."""
from __future__ import annotations

import hashlib
import json
import subprocess
import tempfile
import time
import zipfile
from pathlib import Path

import fitz

from build_guides import ROOT, prepare_assets, make_html

EDGE = Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe")
OUT = Path.home() / "Downloads" / "Glide-User-Guides-1.0.0-2026-11-12.zip"
TITLES = {
    "en": ["Getting started", "Screen guide", "Pair devices", "Send and receive", "Find received files", "Activity and History", "Settings", "Troubleshooting", "Apple status and glossary"],
    "si": ["ආරම්භය", "තිර හඳුනාගැනීම", "Device pair කිරීම", "Files යැවීම සහ ලැබීම", "ලැබුණු files සොයාගැනීම", "Activity සහ History", "Settings", "ගැටලු විසඳීම", "Apple තත්ත්වය සහ වචන"],
}

def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def render(lang: str):
    page=ROOT/f"guide-{lang}.html"
    target=ROOT/f"guide-{lang}.pdf"
    target.unlink(missing_ok=True)
    profile=tempfile.mkdtemp(prefix=f"glide-guide-{lang}-")
    subprocess.run([str(EDGE),"--headless=new","--disable-gpu",f"--user-data-dir={profile}","--no-pdf-header-footer","--virtual-time-budget=10000",f"--print-to-pdf={target}",page.as_uri()],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    stable=0
    previous_size=-1
    for _ in range(80):
        try:
            doc=fitz.open(target)
            size=target.stat().st_size
            valid=len(doc)>1 and sum(len(p.get_text()) for p in doc)>1000
            doc.close()
            stable=stable+1 if valid and size==previous_size else 0
            previous_size=size
            if stable>=3: break
        except Exception: pass
        time.sleep(.5)
    else: raise RuntimeError(f"PDF rendering failed: {target}")
    doc=fitz.open(target)
    toc=[]
    for index,title in enumerate(TITLES[lang],1):
        marker=f"{index}. {title}"
        page_no=next((n+1 for n,p in enumerate(doc) if marker in p.get_text()),None)
        if page_no is None: raise RuntimeError(f"Missing PDF heading: {marker}")
        toc.append([1,title,page_no])
    doc.set_toc(toc)
    staged=target.with_suffix(".bookmarked.pdf")
    doc.save(staged,garbage=3,deflate=True)
    doc.close()
    staged.replace(target)

if __name__=="__main__":
    manifest=prepare_assets()
    if any(x["sha256"]!=x["published_sha256"] or x["redaction_regions"] for x in manifest):
        raise RuntimeError("Published images differ from the supplied originals")
    for language in ("en","si"):
        (ROOT/f"guide-{language}.html").write_text(make_html(language=="si"),encoding="utf-8")
        render(language)
    paths=[ROOT/p for p in ["START-HERE.html","guide-en.html","guide-si.html","guide-en.pdf","guide-si.pdf","screenshot-manifest.json","coverage-report.md","README.md"]]
    paths += sorted((ROOT/"assets").rglob("*.png")) + sorted((ROOT/"assets").rglob("*.jpg"))
    hashes=''.join(f"{digest(p)}  {p.relative_to(ROOT).as_posix()}\n" for p in paths)
    (ROOT/"SHA256SUMS.txt").write_text(hashes,encoding="utf-8")
    paths.append(ROOT/"SHA256SUMS.txt")
    OUT.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(OUT,"w",zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for p in paths:z.write(p,p.relative_to(ROOT).as_posix())
    (OUT.parent/(OUT.name+".sha256.txt")).write_text(f"{digest(OUT)}  {OUT.name}\n",encoding="utf-8")
    with zipfile.ZipFile(OUT) as z:
        for entry in z.infolist():
            if z.read(entry.filename)!= (ROOT/entry.filename).read_bytes():raise RuntimeError(f"ZIP mismatch: {entry.filename}")
    print(json.dumps({"zip":str(OUT),"zip_sha256":digest(OUT),"files":len(paths),"screenshots":len(manifest),"unique_screenshots":len(set(x["published"] for x in manifest))},indent=2))
