#!/usr/bin/env python3
"""Installs mods from Modrinth into a mods folder the way the Breeze launcher
(1.0.28, src-tauri/src/lib.rs install_modrinth_project_recursive) does:

- versions listed for that Minecraft version with the fabric loader, releases
  before betas before alphas, the author's featured one first within each,
  then Modrinth's order (newest first);
- a dependency the dependent pins (its version_id) is tried first;
- a version is taken only when its fabric.mod.json fits: what the mods already
  in the folder ask of it, and what it asks of Minecraft, Fabric Loader and the
  mods in the folder (a dependency still missing is fine, it comes next);
  up to six are tried, else the first is taken and said so;
- required dependencies the same way, each project once. Fabric API is left
  to the caller, which installs it already.

    scripts/ci/modrinth-pick.py <mc> <loader> <mods dir> <slug> [slug ...]

Prints one line per mod. Version ranges are read as Fabric reads them; one it
cannot compare is never held against a version.
"""
import hashlib
import io
import json
import re
import sys
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

UA = 'utaib/Breeze-Client modrinth-pick (CI)'
API = 'https://api.modrinth.com/v2'
FABRIC_API = 'P7dR8mSH'
TYPE_RANK = {'release': 0, 'beta': 1, 'alpha': 2}


def get(url):
    req = urllib.request.Request(url, headers={'User-Agent': UA})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except Exception:
            if attempt == 3:
                raise


# ── Fabric's version ranges ──────────────────────────────────────────────────

def parse(v):
    """(numbers, prerelease) or None when not semantic."""
    v = v.split('+', 1)[0].strip()
    core, _, pre = v.partition('-')
    parts = core.split('.')
    if not parts or not all(re.fullmatch(r'\d+|[xX*]', p) for p in parts):
        return None
    return ([p for p in parts], pre)


def cmp(a, b):
    na, nb = a[0], b[0]
    for i in range(max(len(na), len(nb))):
        x = int(na[i]) if i < len(na) and na[i].isdigit() else 0
        y = int(nb[i]) if i < len(nb) and nb[i].isdigit() else 0
        if x != y:
            return -1 if x < y else 1
    if a[1] == b[1]:
        return 0
    if not a[1]:
        return 1
    if not b[1]:
        return -1
    return -1 if a[1] < b[1] else 1


def satisfies_one(version, pred):
    """True, False, or None when it cannot be compared."""
    pred = pred.strip()
    if pred in ('*', ''):
        return True
    for op in ('>=', '<=', '>', '<', '=', '~', '^'):
        if pred.startswith(op):
            target = pred[len(op):].strip()
            break
    else:
        op, target = '', pred
    v, t = parse(version), parse(target)
    if v is None or t is None:
        return (version == target) if op in ('', '=') else None
    if op == '' and any(not p.isdigit() for p in t[0]):
        # 1.21.x: the parts before the first wildcard must match.
        fixed = []
        for p in t[0]:
            if not p.isdigit():
                break
            fixed.append(int(p))
        have = [int(x) if x.isdigit() else 0 for x in v[0][:len(fixed)]]
        return have + [0] * (len(fixed) - len(have)) == fixed
    c = cmp(v, t)
    if op in ('', '='):
        return c == 0
    if op == '>=':
        return c >= 0
    if op == '<=':
        return c <= 0
    if op == '>':
        return c > 0
    if op == '<':
        return c < 0
    nums = [int(x) for x in t[0]]
    if op == '~':
        upper = (nums[0], nums[1] + 1) if len(nums) > 1 else (nums[0] + 1,)
    else:
        upper = (nums[0] + 1,)
    return c >= 0 and cmp(v, ([str(x) for x in upper], '')) < 0


def satisfies(version, predicate):
    results = [satisfies_one(version, p) for p in predicate.split()]
    if any(r is False for r in results):
        return False
    return None if any(r is None for r in results) else True


def satisfies_any(version, any_of):
    rs = [satisfies(version, p) for p in any_of]
    if any(r is True for r in rs):
        return True
    return None if any(r is None for r in rs) else False


def ranges(value):
    if isinstance(value, list):
        return [str(x) for x in value]
    return [str(value)]


# ── Jars ─────────────────────────────────────────────────────────────────────

def meta_of(data):
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            text = z.read('fabric.mod.json').decode('utf-8', 'replace')
    except Exception:
        return None
    text = re.sub(r'^\s*//.*$', '', text, flags=re.M)
    try:
        return json.loads(text, strict=False)
    except Exception:
        return None


def nested_ids(data, depth=0):
    """(id, version) of the jar and everything bundled in it."""
    out = []
    try:
        z = zipfile.ZipFile(io.BytesIO(data))
    except Exception:
        return out
    m = meta_of(data)
    if m:
        out.append((m.get('id'), str(m.get('version', ''))))
        for p in m.get('provides', []) or []:
            out.append((p, str(m.get('version', ''))))
    if depth < 3:
        for name in z.namelist():
            if name.startswith('META-INF/jars/') and name.endswith('.jar'):
                out += nested_ids(z.read(name), depth + 1)
    return out


def folder(mods):
    provided, wants = {}, {}
    for jar in sorted(Path(mods).glob('*.jar')):
        data = jar.read_bytes()
        for mid, ver in nested_ids(data):
            provided.setdefault(mid, []).append(ver)
        m = meta_of(data) or {}
        for dep, rng in (m.get('depends') or {}).items():
            wants.setdefault(dep, []).append(ranges(rng))
    return provided, wants


def fits(meta, mc, loader, provided, wants):
    mid, ver = meta.get('id'), str(meta.get('version', ''))
    for any_of in wants.get(mid, []):
        if satisfies_any(ver, any_of) is False:
            return f'the mods here need {mid} {" or ".join(any_of)}'
    builtin = {'minecraft': mc, 'fabricloader': loader, 'fabric-loader': loader}
    for dep, rng in (meta.get('depends') or {}).items():
        if dep == 'java':
            continue
        found = [builtin[dep]] if dep in builtin else provided.get(dep, [])
        if not found:
            continue
        if all(satisfies_any(v, ranges(rng)) is False for v in found):
            return f'needs {dep} {rng}, here {", ".join(found)}'
    return None


# ── Modrinth ─────────────────────────────────────────────────────────────────

def versions(project, mc):
    q = urllib.parse.urlencode({'loaders': json.dumps(['fabric']), 'game_versions': json.dumps([mc])})
    vs = json.loads(get(f'{API}/project/{project}/version?{q}'))
    order = sorted(range(len(vs)), key=lambda i: (TYPE_RANK.get(vs[i].get('version_type'), 0), not vs[i].get('featured'), i))
    return [vs[i] for i in order]


def install(project, mc, loader, mods, seen, pins, depth=0):
    if project in seen or project == FABRIC_API:
        return
    seen.add(project)
    cands = versions(project, mc)
    pin = pins.get(project)
    if pin:
        cands.sort(key=lambda v: v['id'] != pin)
    if not cands:
        print(f'{"  " * depth}{project}: none for {mc}')
        return
    provided, wants = folder(mods)
    chosen = first = None
    for v in cands[:6]:
        f = next((f for f in v['files'] if f.get('primary')), None) or (v['files'][0] if v['files'] else None)
        if not f:
            continue
        data = get(f['url'])
        if hashlib.sha1(data).hexdigest() != f['hashes'].get('sha1'):
            raise SystemExit(f'{f["filename"]}: sha1 does not match Modrinth')
        meta = meta_of(data) or {}
        why = fits(meta, mc, loader, provided, wants) if meta else None
        if why is None:
            chosen = (v, f, data)
            break
        print(f'{"  " * depth}{project}: skipped {v["version_number"]} ({why})')
        first = first or (v, f, data)
    v, f, data = chosen or first
    (Path(mods) / f['filename']).write_bytes(data)
    note = '' if chosen else ' (none of the first six fit; the Mods page would say why)'
    print(f'{"  " * depth}{project}: {v["version_number"]} [{v.get("version_type")}] {f["filename"]}{note}')
    for d in v.get('dependencies', []):
        if d.get('dependency_type') == 'required' and d.get('project_id'):
            if d.get('version_id'):
                pins[d['project_id']] = d['version_id']
            install(d['project_id'], mc, loader, mods, seen, pins, depth + 1)


def main():
    mc, loader, mods, *slugs = sys.argv[1:]
    Path(mods).mkdir(parents=True, exist_ok=True)
    seen, pins = set(), {}
    for slug in slugs:
        install(slug, mc, loader, mods, seen, pins)


if __name__ == '__main__':
    main()
