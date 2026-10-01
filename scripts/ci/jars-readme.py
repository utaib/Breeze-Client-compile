#!/usr/bin/env python3
"""Rewrites the jar table in "Breeze Jars/README.md" from the jars present.

    scripts/ci/jars-readme.py <jars dir> <commit> <run id> <mod version>

Each jar's own fabric.mod.json must carry the mod version and its Minecraft
version; the table lists it with its SHA-256 and whether its menus were the
web ones (versions/<mc>/gradle.properties has an mcef_version) or the native
ones. Only the text from "All " up to "What the test checked" is replaced.
"""
import hashlib
import json
import os
import sys
import zipfile

jars_dir, commit, run, mod_version = sys.argv[1:5]
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')


def key(v):
    return [int(x) for x in v.split('.')]


def has_mcef(mc):
    props = os.path.join(root, 'versions', mc, 'gradle.properties')
    if not os.path.exists(props):
        return False
    for line in open(props, encoding='utf-8'):
        if line.startswith('mcef_version=') and line.strip() != 'mcef_version=':
            return True
    return False


versions = sorted((f[:-4] for f in os.listdir(jars_dir) if f.endswith('.jar')), key=key)
rows = []
for mc in versions:
    path = os.path.join(jars_dir, mc + '.jar')
    meta = json.loads(zipfile.ZipFile(path).read('fabric.mod.json'))
    if meta['version'] != mod_version:
        sys.exit(f'{mc}.jar is Breeze {meta["version"]}, not {mod_version}')
    if meta['depends']['minecraft'] != mc:
        sys.exit(f'{mc}.jar declares Minecraft {meta["depends"]["minecraft"]}')
    sha = hashlib.sha256(open(path, 'rb').read()).hexdigest()
    menus = 'web (MCEF 2.1.6)' if has_mcef(mc) else 'native'
    rows.append(f'| `{mc}.jar` | {mod_version} | {mc} | {menus} | `{sha}` |')

readme = os.path.join(jars_dir, 'README.md')
text = open(readme, encoding='utf-8').read()
start = text.index('All ')
end = text.index('\n\nWhat the test checked')
head = f'''All {len(versions)} were built from commit `{commit}` on `feature/breeze-mod-2-rebuild`
and tested in run {run} of this repository's "Breeze Mod New" workflow.

| Jar | Breeze | Minecraft | Menus tested | SHA-256 |
| --- | --- | --- | --- | --- |
'''
open(readme, 'w', encoding='utf-8').write(text[:start] + head + '\n'.join(rows) + text[end:])
print(len(versions), 'jars:', ' '.join(versions))
