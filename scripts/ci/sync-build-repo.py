#!/usr/bin/env python3
"""Copies a private worktree's Breeze-Mod-New/ (committed HEAD only) into a
clone of the public build repository (utaib/Breeze-Client-compile), minus
private-only files, and writes its .github/workflows/build.yml from the
private breeze-mod-new.yml. This repository's Actions are blocked by billing,
so full runs of every version happen there (docs/BUILD.md, "Builds on the
public build repository").

    scripts/ci/sync-build-repo.py <private worktree> <build repository clone>

Check out the build repository branch to run on first (main for a release
run, probe-web or ci-<x> for others), then compare the two trees blob for
blob, commit, push, start the workflow, and afterwards empty the branch to
its README again.
"""
import io
import os
import re
import shutil
import subprocess
import sys
import tarfile

src, dst = sys.argv[1:3]
PRIVATE = {'CLAUDE.md', '.mcp.json', 'TODO.md', 'docs/PROJECT_MEMORY.md', 'docs/PHASE2_FEATURE_REVIEW.md'}
PRIVATE_PREFIX = ('Breeze Jars/', 'Breeze Jars')

for name in os.listdir(dst):
    if name == '.git':
        continue
    p = os.path.join(dst, name)
    if os.path.isdir(p) and not os.path.islink(p):
        shutil.rmtree(p)
    else:
        os.remove(p)

data = subprocess.run(['git', '-C', src, 'archive', '--format=tar', 'HEAD:Breeze-Mod-New'],
                      check=True, capture_output=True).stdout
with tarfile.open(fileobj=io.BytesIO(data)) as t:
    def rel(m):
        return m.name[2:] if m.name.startswith('./') else m.name
    keep = [m for m in t.getmembers() if rel(m) not in PRIVATE and not rel(m).startswith(PRIVATE_PREFIX)]
    t.extractall(dst, members=keep)

wf = subprocess.run(['git', '-C', src, 'show', 'HEAD:.github/workflows/breeze-mod-new.yml'],
                    check=True, capture_output=True, text=True).stdout


def sub(pattern, repl, text, count=1, flags=0):
    new, n = re.subn(pattern, repl, text, count=count, flags=flags)
    if n == 0:
        sys.exit(f'pattern not found: {pattern!r}')
    return new


head = '''name: Breeze mod build and test

# Build server for the Breeze Minecraft mod. The private repository
# (utaib/Breeze-Client, Breeze-Mod-New/) is the source of truth; this copy
# exists so Actions runs free on a public repository. Run by hand only:
#   versions     comma-separated Minecraft versions (empty for all 34)
#   screenshots  in-game screenshots on a branch ci/evidence-<run id>
#   publish      if all 34 pass, the tested jars on a branch ci/jars-<run id>
#
'''
wf = sub(r'\A.*?(?=# For every version project)', head, wf, flags=re.S)
wf = sub(r"  push:\n    branches: \[main\]\n    paths:\n(?:      - .*\n)+", '', wf)
wf = sub(r"env:\n  PUSH_VERSIONS: '[^']*'\n\n", '', wf)
wf = sub(r"\$\{\{ github\.event_name == 'push' && env\.PUSH_VERSIONS \|\| inputs\.versions \}\}",
         '${{ inputs.versions }}', wf)
wf = sub(r"defaults:\n  run:\n    working-directory: Breeze-Mod-New\n\n", '', wf)
wf = sub(r'          rm -f "\$jars"/\*\.jar\n          cp "\$RUNNER_TEMP"/tested/\*\.jar "\$jars/"\n'
         r'          python3 scripts/ci/jars-readme\.py [^\n]*\n',
         '          mkdir -p "$jars"\n'
         '          cp "$RUNNER_TEMP"/tested/*.jar "$jars/"\n'
         '          (cd "$jars" && sha256sum -- *.jar > SHA256SUMS)\n', wf)
wf = sub(r'git commit -q -m "Breeze-Mod-New: Breeze Jars', 'git commit -q -m "Breeze Jars', wf)
wf = sub(r'# the jars go into Breeze-Mod-New/Breeze Jars/ \(README table regenerated\)',
         '# the jars go into Breeze Jars/ (with SHA256SUMS)', wf)
wf = wf.replace('Breeze-Mod-New/', '').replace('(utaib/Breeze-Client, )', '(utaib/Breeze-Client, Breeze-Mod-New/)')
if wf.count('Breeze-Mod-New') != 1:
    sys.exit('Breeze-Mod-New still named in the workflow')
os.makedirs(os.path.join(dst, '.github', 'workflows'), exist_ok=True)
open(os.path.join(dst, '.github', 'workflows', 'build.yml'), 'w', encoding='utf-8').write(wf)
print('synced', src, '->', dst)
