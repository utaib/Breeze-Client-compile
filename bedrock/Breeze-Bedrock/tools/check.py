#!/usr/bin/env python3
"""Offline checks for the Breeze Bedrock packs. Exits non-zero on any problem.

    python3 tools/check.py [--schema manifest.schema.json]

- every JSON file parses (strict JSON, no comments);
- manifests: format 2, required fields, versions, min_engine_version, the
  pack version equals VERSION, script dependencies match tools/package.json;
- UUIDs: well formed, unique, and the same as tools/uuids.lock.json;
- subpacks: every folder is declared and every declared folder is allowed;
- textures: each PNG either replaces a known vanilla path at its size
  (vanilla_paths.json) or is referred to by the pack; every texture path a
  JSON file names exists; nine-slice base sizes match their PNG;
- languages: every listed language has pack.name and pack.description;
- scripts: the entry exists, imports are only Script API modules or files
  that exist;
- product text: no em dashes and no placeholder wording in what players read;
- with --schema, the manifests validate against that JSON schema
  (Blockception's published manifest schema in CI).
"""
import argparse
import json
import re
import struct
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
PACKS = ROOT / "packs"
UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
ALLOWED_MODULES = {"@minecraft/server", "@minecraft/server-ui"}
MIN_ENGINE = [1, 21, 0]
BANNED_TEXT = ["—", "coming soon", "not implemented"]

problems = []


def fail(msg):
    problems.append(msg)


def png_size(path):
    data = path.read_bytes()[:24]
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        fail(f"{path}: not a PNG")
        return None
    return list(struct.unpack(">II", data[16:24]))


def load_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:
        fail(f"{path.relative_to(ROOT)}: invalid JSON ({e})")
        return None


def is_version(v):
    return isinstance(v, list) and len(v) == 3 and all(isinstance(x, int) and x >= 0 for x in v)


def check_manifest(pack, m, version):
    rel = pack.relative_to(ROOT)
    if m.get("format_version") != 2:
        fail(f"{rel}: format_version must be 2 (format 3 needs Bedrock 1.21.110)")
    h = m.get("header", {})
    for k in ("name", "description", "uuid", "version", "min_engine_version"):
        if k not in h:
            fail(f"{rel}: header.{k} missing")
    if not is_version(h.get("version")):
        fail(f"{rel}: header.version must be [a, b, c]")
    elif ".".join(map(str, h["version"])) != version:
        fail(f"{rel}: header.version {h['version']} is not VERSION {version}")
    if h.get("min_engine_version") != MIN_ENGINE:
        fail(f"{rel}: min_engine_version must be {MIN_ENGINE} (the stated oldest version)")
    mods = m.get("modules", [])
    if not mods:
        fail(f"{rel}: no modules")
    for mod in mods:
        if mod.get("type") not in ("resources", "data", "script"):
            fail(f"{rel}: module type {mod.get('type')!r}")
        if not is_version(mod.get("version")):
            fail(f"{rel}: module version must be [a, b, c]")
        if mod.get("type") == "script":
            if mod.get("language") != "javascript":
                fail(f"{rel}: script module language must be javascript")
            entry = mod.get("entry")
            if not entry or not (pack / entry).is_file():
                fail(f"{rel}: script entry {entry!r} missing")
    pkg = json.loads((HERE / "package.json").read_text())["devDependencies"]
    for dep in m.get("dependencies", []):
        name = dep.get("module_name")
        if name:
            if name not in ALLOWED_MODULES:
                fail(f"{rel}: dependency on {name} (only stable modules are allowed)")
            elif dep.get("version") != pkg.get(name):
                fail(f"{rel}: {name} {dep.get('version')} but tools/package.json type-checks {pkg.get(name)}")
            if "beta" in str(dep.get("version")):
                fail(f"{rel}: beta module {name} needs an experiment")


def collect_uuids(manifests):
    found = {}
    for pack, m in manifests:
        ids = [("header", m["header"]["uuid"])]
        ids += [(f"module {i}", mod["uuid"]) for i, mod in enumerate(m.get("modules", []))]
        for where, u in ids:
            if not UUID_RE.match(u):
                fail(f"{pack.name}: {where} uuid {u!r} is not a lower-case UUID")
            if u in found.values():
                fail(f"{pack.name}: {where} uuid {u} is used twice")
            found[f"{pack.name}/{where}"] = u
    lock_path = HERE / "uuids.lock.json"
    lock = json.loads(lock_path.read_text()) if lock_path.exists() else {}
    if lock != found:
        fail("UUIDs differ from tools/uuids.lock.json (a pack's UUIDs must never change; "
             "update the lock only for a new pack): " + json.dumps(found, sort_keys=True))


def check_textures(pack, vanilla):
    rel_pack = pack.relative_to(ROOT)
    roots = [pack] + [p for p in (pack / "subpacks").glob("*") if p.is_dir()]
    json_text = ""
    for j in pack.rglob("*.json"):
        json_text += j.read_text(encoding="utf-8")
    for base in roots:
        for png in base.rglob("*.png"):
            rel = png.relative_to(base).as_posix()
            if base == pack and rel.startswith("subpacks/"):
                continue
            if rel == "pack_icon.png":
                continue
            size = png_size(png)
            if rel in vanilla:
                want = vanilla[rel].get("size")
                if want and size != want:
                    fail(f"{rel_pack}/{png.relative_to(pack)}: {size}, the game uses {want}")
            elif rel[:-4] not in json_text:
                fail(f"{rel_pack}/{png.relative_to(pack)}: not a known vanilla path and not referred to")
        for j in base.rglob("*.json"):
            rel = j.relative_to(base).as_posix()
            if base == pack and rel.startswith("subpacks/"):
                continue
            data = load_json(j)
            if data is None:
                continue
            for ref in re.findall(r'"(textures/[^"]+)"', json.dumps(data)):
                if not any((b / (ref + ext)).exists() for b in roots for ext in (".png", ".tga", "")):
                    fail(f"{rel_pack}/{rel}: texture {ref} does not exist")
            if "nineslice_size" in data or "base_size" in data:
                png = j.with_suffix(".png")
                if not png.exists():
                    fail(f"{rel_pack}/{rel}: nine-slice without its PNG")
                elif data.get("base_size") and png_size(png) != data["base_size"]:
                    fail(f"{rel_pack}/{rel}: base_size {data['base_size']} but PNG is {png_size(png)}")


def check_subpacks(pack, m):
    declared = [s.get("folder_name") for s in m.get("subpacks", [])]
    if len(set(declared)) != len(declared):
        fail(f"{pack.name}: a subpack folder is declared twice")
    for s in m.get("subpacks", []):
        if not s.get("name") or not isinstance(s.get("memory_tier"), int):
            fail(f"{pack.name}: subpack {s} needs name and memory_tier")
    on_disk = [p.name for p in (pack / "subpacks").glob("*") if p.is_dir()]
    for d in on_disk:
        if d not in declared:
            fail(f"{pack.name}: subpacks/{d} is not in the manifest")


def check_languages(pack):
    langs = load_json(pack / "texts" / "languages.json") or []
    for lang in langs:
        f = pack / "texts" / f"{lang}.lang"
        if not f.exists():
            fail(f"{pack.name}: texts/{lang}.lang missing")
            continue
        keys = dict(
            line.split("=", 1) for line in f.read_text(encoding="utf-8").splitlines()
            if "=" in line and not line.startswith("#")
        )
        for k in ("pack.name", "pack.description"):
            if not keys.get(k):
                fail(f"{pack.name}: {k} missing in {lang}.lang")


def check_scripts(pack):
    for js in pack.rglob("*.js"):
        src = js.read_text(encoding="utf-8")
        for spec in re.findall(r'^\s*import\s[^;]*?from\s+"([^"]+)"', src, re.M):
            if spec.startswith("."):
                if not (js.parent / spec).resolve().is_file():
                    fail(f"{js.relative_to(ROOT)}: import {spec} not found")
            elif spec not in ALLOWED_MODULES:
                fail(f"{js.relative_to(ROOT)}: import of {spec} (not a stable Script API module)")


def check_text(pack):
    for f in list(pack.rglob("*.lang")) + list(pack.rglob("*.js")):
        low = f.read_text(encoding="utf-8").lower()
        for b in BANNED_TEXT:
            if b in low:
                fail(f"{f.relative_to(ROOT)}: product text contains {b!r}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--schema", help="JSON schema for manifest.json (draft 7)")
    args = ap.parse_args()
    version = (ROOT / "VERSION").read_text().strip()
    vanilla = json.loads((HERE / "vanilla_paths.json").read_text())["paths"]
    manifests = []
    for pack in sorted(p for p in PACKS.iterdir() if p.is_dir()):
        m = load_json(pack / "manifest.json")
        if m is None:
            continue
        manifests.append((pack, m))
        check_manifest(pack, m, version)
        check_subpacks(pack, m)
        check_textures(pack, vanilla)
        check_languages(pack)
        check_scripts(pack)
        check_text(pack)
        if not (pack / "pack_icon.png").exists():
            fail(f"{pack.name}: pack_icon.png missing")
    collect_uuids(manifests)
    if args.schema:
        import jsonschema
        schema = json.loads(Path(args.schema).read_text())
        for pack, m in manifests:
            for err in jsonschema.Draft7Validator(schema).iter_errors(m):
                fail(f"{pack.name}/manifest.json: schema: {err.message} at {list(err.path)}")
        print(f"schema checked: {len(manifests)} manifests")
    if problems:
        print("\n".join("FAIL " + p for p in problems))
        sys.exit(f"{len(problems)} problem(s)")
    print(f"ok: {len(manifests)} packs checked")


if __name__ == "__main__":
    main()
