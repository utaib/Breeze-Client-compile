#!/usr/bin/env python3
"""Packs the Breeze add-on into dist/, byte for byte the same on every run.

    python3 tools/package.py            # write dist/
    python3 tools/package.py --verify   # build again in memory, compare with dist/

Output: Breeze-UI-<v>.mcpack and Breeze-HUD-<v>.mcpack (each a zip with
the pack's manifest at its root), Breeze-<v>.mcaddon (a zip of the two
.mcpack files: opening it imports both), and SHA256SUMS.

Reproducible: entries are sorted, stored without compression (so no zlib
version can change the bytes), dated 1980-01-01 and given fixed
permissions. Only the standard library is used.
"""
import hashlib
import io
import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DIST = ROOT / "dist"
PACKS = {"breeze_ui": "Breeze-UI", "breeze_hud": "Breeze-HUD"}
DATE = (1980, 1, 1, 0, 0, 0)
SKIP = {".DS_Store", "Thumbs.db"}


def _entry(name, is_dir=False):
    info = zipfile.ZipInfo(name + ("/" if is_dir else ""), DATE)
    info.compress_type = zipfile.ZIP_STORED
    info.create_system = 3  # unix, so the permissions below are read
    info.external_attr = ((0o40755 if is_dir else 0o100644) << 16) | (0x10 if is_dir else 0)
    return info


def zip_bytes(files, dirs=()):
    """files: {name: bytes}; dirs: names of directories to record even if empty."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for d in sorted(dirs):
            z.writestr(_entry(d, True), b"")
        for name in sorted(files):
            z.writestr(_entry(name), files[name])
    return buf.getvalue()


def pack_bytes(folder):
    files = {}
    for p in sorted(folder.rglob("*")):
        if p.is_file() and p.name not in SKIP:
            files[p.relative_to(folder).as_posix()] = p.read_bytes()
    manifest = json.loads((folder / "manifest.json").read_text())
    # Subpacks with nothing in them (the game's own crosshair and fire) are
    # still folders the game looks for.
    dirs = [f"subpacks/{s['folder_name']}" for s in manifest.get("subpacks", [])]
    return zip_bytes(files, dirs)


def build():
    version = (ROOT / "VERSION").read_text().strip()
    out = {}
    for folder, name in PACKS.items():
        out[f"{name}-{version}.mcpack"] = pack_bytes(ROOT / "packs" / folder)
    out[f"Breeze-{version}.mcaddon"] = zip_bytes(dict(out))
    sums = "".join(f"{hashlib.sha256(out[n]).hexdigest()}  {n}\n" for n in sorted(out))
    out["SHA256SUMS"] = sums.encode()
    return out


def main():
    out = build()
    if "--verify" in sys.argv:
        bad = [n for n, b in out.items() if not (DIST / n).exists() or (DIST / n).read_bytes() != b]
        extra = [p.name for p in DIST.iterdir() if p.name not in out] if DIST.exists() else []
        if bad or extra:
            sys.exit(f"dist/ is not what the packs build: differs {bad}, unexpected {extra}")
        print("dist/ matches a fresh build:\n" + out["SHA256SUMS"].decode(), end="")
        return
    DIST.mkdir(exist_ok=True)
    for old in DIST.iterdir():
        if old.name not in out:
            old.unlink()
    for n, b in out.items():
        (DIST / n).write_bytes(b)
    print(out["SHA256SUMS"].decode(), end="")


if __name__ == "__main__":
    main()
