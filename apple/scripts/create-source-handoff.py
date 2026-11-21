"""Package the current Apple working tree for a Mac without losing local edits.

The ZIP contains a Git bundle at HEAD, a binary patch for tracked changes, and
the Apple files that are currently untracked. It is a source handoff, not build
or test evidence.
"""

from __future__ import annotations

import hashlib
import json
import subprocess
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "build" / "apple-source-handoff"


def git(*args: str) -> bytes:
    return subprocess.check_output(["git", "-C", str(ROOT), *args])


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    commit = git("rev-parse", "HEAD").decode().strip()
    branch = git("branch", "--show-current").decode().strip()
    if branch != "main":
        raise SystemExit(f"Expected main branch, found {branch!r}")

    changed = git("diff", "--name-only", "HEAD").decode().splitlines()
    outside = [path for path in changed if not path.startswith("apple/")]
    if outside:
        raise SystemExit(f"Tracked changes outside apple/: {outside}")

    untracked = [
        name.decode()
        for name in git("ls-files", "--others", "--exclude-standard", "-z").split(b"\0")
        if name
    ]
    allowed = lambda path: path.startswith("apple/") or path == "doc/testing/APPLE_PARITY_SOURCE_STATUS_2026-11-17.md"
    outside = [path for path in untracked if not allowed(path)]
    if outside:
        raise SystemExit(f"Untracked files outside Apple handoff: {outside}")

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    target = OUTPUT / f"glide-apple-source-{commit[:12]}-{stamp}.zip"
    with tempfile.TemporaryDirectory() as temporary:
        bundle = Path(temporary) / "glide-main.bundle"
        subprocess.run(["git", "-C", str(ROOT), "bundle", "create", str(bundle), "main"], check=True)
        patch = git("diff", "--binary", "HEAD", "--", "apple/")
        contents: dict[str, bytes] = {
            "glide-main.bundle": bundle.read_bytes(),
            "apple-changes.patch": patch,
        }
        for path in untracked:
            contents[f"new-files/{path}"] = (ROOT / path).read_bytes()

        instructions = (
            "Glide Apple source handoff\n"
            f"Base commit: {commit}\n"
            "This archive contains uncompiled local source changes.\n\n"
            "On a Mac with Xcode 26.6 and its iOS Simulator platform:\n"
            "1. git clone -b main glide-main.bundle Glide\n"
            "2. cd Glide && git apply --binary ../apple-changes.patch\n"
            "3. Copy the files from new-files/ into this checkout, retaining paths.\n"
            "4. Run tools/check-architecture.ps1 and inspect git diff --check.\n"
            "5. Commit the reviewed source so packaging can record one commit.\n"
            "6. Run apple/scripts/run-backend-tests.sh on the Mac.\n"
            "7. Record target-host results and physical-device checks separately.\n"
            "GitHub Actions must remain disabled.\n"
        ).encode()
        contents["README.txt"] = instructions
        manifest = {
            "base_commit": commit,
            "created_utc": stamp,
            "status": "source_only_uncompiled",
            "files": {name: digest(data) for name, data in sorted(contents.items())},
        }
        contents["manifest.json"] = (json.dumps(manifest, indent=2) + "\n").encode()
        with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for name, data in sorted(contents.items()):
                archive.writestr(name, data)

    print(target)
    print(f"SHA-256 {digest(target.read_bytes())}")


if __name__ == "__main__":
    main()
