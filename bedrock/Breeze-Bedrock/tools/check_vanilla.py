#!/usr/bin/env python3
"""Re-reads Mojang/bedrock-samples at each tag in vanilla_paths.json and
fails if a path Breeze replaces is gone or changed size. Needs the network;
the offline checks are in check.py.

    python3 tools/check_vanilla.py
"""
import json
import struct
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
RAW = "https://raw.githubusercontent.com/Mojang/bedrock-samples/{tag}/resource_pack/{path}"


def png_size(data):
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    return list(struct.unpack(">II", data[16:24]))


def main():
    spec = json.loads((HERE / "vanilla_paths.json").read_text())
    bad = 0
    for path, info in spec["paths"].items():
        for tag in spec["samples_tags"]:
            url = RAW.format(tag=tag, path=path)
            try:
                with urllib.request.urlopen(url, timeout=30) as r:
                    data = r.read()
            except Exception as e:  # 404 or network
                seen = tag in info.get("seen_in", [])
                print(f"{'FAIL' if seen else 'note'} {tag} {path}: {e}")
                bad += seen
                continue
            if "size" in info and path.endswith(".png"):
                got = png_size(data)
                if got != info["size"]:
                    print(f"FAIL {tag} {path}: size {got}, expected {info['size']}")
                    bad += 1
                    continue
            print(f"ok   {tag} {path}")
    if bad:
        sys.exit(f"{bad} vanilla path problem(s)")
    print("vanilla paths ok")


if __name__ == "__main__":
    main()
