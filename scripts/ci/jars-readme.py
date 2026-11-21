#!/usr/bin/env python3
"""Rewrites the jar table in "Breeze Jars/README.md" from the jars present.

    scripts/ci/jars-readme.py <jars dir> <commit> <run id> <mod version> [where]

<where> names the workflow that ran the tests (default: this repository's
"Breeze Mod New" workflow).

Each jar's own fabric.mod.json must carry the mod version and its Minecraft
version; the table lists it with its SHA-256 and whether its menus were the
web ones (versions/<mc>/gradle.properties has an mcef_version, with
mcef_bundled=true when the browser is inside the jar) or the native ones. Only the text from "All " up to "What the test checked" is replaced.
"""
import hashlib
import json
import os
import sys
import zipfile

jars_dir, commit, run, mod_version = sys.argv[1:5]
where = sys.argv[5] if len(sys.argv) > 5 else 'this repository\'s "Breeze Mod New" workflow'
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')


def key(v):
    return [int(x) for x in v.split('.')]


def menus(mc):
    """'native', or the web menu and the browser it ran on."""
    props = os.path.join(root, 'versions', mc, 'gradle.properties')
    if not os.path.exists(props):
        return 'native'
    p = {}
    for line in open(props, encoding='utf-8'):
        if '=' in line and not line.startswith('#'):
            k, v = line.strip().split('=', 1)
            p[k] = v
    version = p.get('mcef_version', '')
    if not version:
        return 'native'
    release = version.split('-')[0]
    if p.get('mcef_bundled') == 'true':
        return f'web (Rinku {release}, inside the jar)'
    return f'web (MCEF {release})'


def jar_names(folder):
    return [f[:-4] for f in os.listdir(folder) if f.endswith('.jar')] if os.path.isdir(folder) else []


# From 2.27.0 a release folder has one folder per loader (owner, 2026-11-14):
# fabric/, forge/ and neoforge/, each with <minecraft version>.jar, the same
# layout as the API's versions/mods/. Older folders hold the Fabric jars at
# their top level, and Forge or NeoForge ones as <version>-<loader>.jar.
foldered = os.path.isdir(os.path.join(jars_dir, 'fabric'))
if foldered:
    fabric_dir = os.path.join(jars_dir, 'fabric')
    versions = sorted(jar_names(fabric_dir), key=key)
    # (published name, Minecraft version, loader, path)
    loader_jars = [(f'{l}/{mc}.jar', mc, l, os.path.join(jars_dir, l, mc + '.jar'))
                   for l in ('forge', 'neoforge') for mc in sorted(jar_names(os.path.join(jars_dir, l)), key=key)]
    fabric_prefix = 'fabric/'
else:
    fabric_dir = jars_dir
    names = jar_names(jars_dir)
    # Fabric jars are named after the Minecraft version alone; Forge and
    # NeoForge jars add the loader (1.20.1-forge.jar).
    versions = sorted((n for n in names if all(p.isdigit() for p in n.split('.'))), key=key)
    loader_jars = []
    for n in sorted(n for n in names if n not in versions):
        mc, _, l = n.partition('-')
        loader_jars.append((f'{n}.jar', mc, l, os.path.join(jars_dir, n + '.jar')))
    fabric_prefix = ''
rows = []
for mc in versions:
    path = os.path.join(fabric_dir, mc + '.jar')
    meta = json.loads(zipfile.ZipFile(path).read('fabric.mod.json'))
    if meta['version'] != mod_version:
        sys.exit(f'{mc}.jar is Breeze {meta["version"]}, not {mod_version}')
    if meta['depends']['minecraft'] != mc:
        sys.exit(f'{mc}.jar declares Minecraft {meta["depends"]["minecraft"]}')
    sha = hashlib.sha256(open(path, 'rb').read()).hexdigest()
    rows.append(f'| `{fabric_prefix}{mc}.jar` | {mod_version} | {mc} | {menus(mc)} | `{sha}` |')

loader_rows = []
for name, mc, loader, path in loader_jars:
    if loader not in ('forge', 'neoforge'):
        sys.exit(f'{name} is not a Forge or NeoForge jar')
    jar = zipfile.ZipFile(path)
    toml = 'META-INF/neoforge.mods.toml' if loader == 'neoforge' else 'META-INF/mods.toml'
    meta = jar.read(toml).decode('utf-8')
    if f'version="{mod_version}"' not in meta.replace(' ', ''):
        sys.exit(f'{name}: {toml} does not say version {mod_version}')
    manifest = jar.read('META-INF/MANIFEST.MF').decode('utf-8')
    if f'Minecraft-Version: {mc}' not in manifest or f'Breeze-Loader: {loader}' not in manifest:
        sys.exit(f'{name}: its manifest does not name Minecraft {mc} and {loader}')
    sha = hashlib.sha256(open(path, 'rb').read()).hexdigest()
    label = 'NeoForge' if loader == 'neoforge' else 'Forge'
    loader_rows.append(f'| `{name}` | {mod_version} | {mc} | {label} | native | `{sha}` |')

readme = os.path.join(jars_dir, 'README.md')
text = open(readme, encoding='utf-8').read()
start = text.index('All ')
end = text.index('\n\nWhat the test checked')
head = f'''All {len(versions)} were built from commit `{commit}`
and tested in run {run} of {where}.

| Jar | Breeze | Minecraft | Menus tested | SHA-256 |
| --- | --- | --- | --- | --- |
'''
body = head + '\n'.join(rows)
if loader_rows:
    body += f'''

Forge and NeoForge, built in the same run from the same commit:

| Jar | Breeze | Minecraft | Loader | Menus | SHA-256 |
| --- | --- | --- | --- | --- | --- |
''' + '\n'.join(loader_rows)
open(readme, 'w', encoding='utf-8').write(text[:start] + body + text[end:])
print(len(versions), 'jars:', ' '.join(versions))
if loader_jars:
    print(len(loader_jars), 'loader jars:', ' '.join(name for name, *_ in loader_jars))
