#!/usr/bin/env bash
# A partial compile check for a version module when Minecraft cannot be
# downloaded (no access to Fabric's Maven or Mojang):
#
#   scripts/check-java-offline.sh 1.20.1 --since f8665ed
#   scripts/check-java-offline.sh 1.20.1 path/to/File.java ...
#
# javac compiles the given files against common/ with no Minecraft on the
# classpath, but is told to type-check every method body anyway
# (-XDshould-stop.ifError=FLOW). Errors that only say a Minecraft, Fabric, CEF
# or other library type is missing are dropped; anything left is a real mistake
# in Breeze's own code: a misspelt Breeze method, a wrong argument count, a
# missing import of a Breeze class, a syntax error.
#
# What it cannot check: calls into Minecraft itself, and any call with an
# argument whose type comes from Minecraft (javac skips looking up a method
# when an argument's type is unknown, so browser.resise(width, height) inside a
# Screen goes unreported). Only a real build checks those; this never replaces
# one.
set -euo pipefail
cd "$(dirname "$0")/.."

mc=${1:?usage: scripts/check-java-offline.sh <minecraft version> (--since <rev> | file...)}
shift
src="versions/$mc/src/main/java"
[ -d "$src" ] || { echo "no $src"; exit 1; }

files=()
if [ "${1:-}" = "--since" ]; then
  rev=${2:?--since needs a revision}
  # Changed since the revision, plus new files not yet committed.
  while IFS= read -r f; do [ -f "$f" ] && files+=("$f"); done \
    < <({ git diff --name-only --relative "$rev" -- "$src"; git ls-files --others --exclude-standard -- "$src"; } \
        | grep '\.java$' | sort -u || true)
else
  files=("$@")
fi
[ ${#files[@]} -gt 0 ] || { echo "no Java files to check"; exit 0; }

./gradlew -q :common:compileJava --configure-on-demand >/dev/null
gson=$(find "${GRADLE_USER_HOME:-$HOME/.gradle}" -name 'gson-2.10.1.jar' | head -1)
[ -n "$gson" ] || { echo "gson 2.10.1 not in the Gradle cache; run ./gradlew :common:test once"; exit 1; }

out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
javac -XDshould-stop.ifError=FLOW -proc:none -Xmaxerrs 100000 -d "$out/classes" \
  -cp "common/build/classes/java/main:$gson" -sourcepath "$src" "${files[@]}" > "$out/javac.txt" 2>&1 || true

python3 - "$out/javac.txt" "${files[@]}" <<'PY'
import re, sys
lines = open(sys.argv[1], encoding='utf-8', errors='replace').read().split('\n')
wanted = sys.argv[2:]
head = re.compile(r'^(.+\.java):(\d+): error: (.*)$')
external = re.compile(r'(net\.minecraft|com\.mojang|org\.lwjgl|net\.fabricmc|org\.cef|com\.cinemamod'
                      r'|org\.spongepowered|org\.slf4j|org\.joml|it\.unimi|com\.google\.common)')

errors = []
for i, line in enumerate(lines):
    m = head.match(line)
    if m and m.group(1) in wanted:
        ctx = lines[i + 1:i + 6]
        sym = next((c.strip() for c in ctx if c.strip().startswith('symbol:')), '')
        loc = next((c.strip() for c in ctx if c.strip().startswith('location:')), '')
        errors.append((m.group(1), int(m.group(2)), m.group(3), sym, loc, lines[i + 1].strip()))

# Types javac could not find, per file: Minecraft's and other libraries'.
missing = {}
for f, _, msg, sym, _, _ in errors:
    mm = re.match(r'symbol:\s+class ([A-Z]\w*)', sym)
    if msg == 'cannot find symbol' and mm:
        missing.setdefault(f, set()).add(mm.group(1))

def external_super(f):
    """The file's class extends or implements a type javac could not find."""
    text = open(f, encoding='utf-8').read()
    return any(re.search(r'\b(extends|implements)\b[^{]*\b' + re.escape(n) + r'\b', text) for n in missing.get(f, ()))

real, unknown = [], []
for f, n, msg, sym, loc, src in errors:
    if 'does not exist' in msg and external.search(msg):
        continue
    # Outer.Inner on a class whose import failed reads as a package "Outer".
    pm = re.match(r'package ([A-Z]\w*) does not exist$', msg)
    if pm and re.search(r'^import\s+' + external.pattern + r'[\w.]*\.' + pm.group(1) + r'\s*;',
                        open(f, encoding='utf-8').read(), re.M):
        continue
    if msg == 'cannot find symbol' and re.match(r'symbol:\s+class [A-Z]', sym):
        continue
    # A static call or method reference on a missing class
    # (Minecraft.getInstance(), GameRenderer::getPositionTexColorShader) reads
    # as a missing variable named like a class.
    vm = re.match(r'symbol:\s+variable ([A-Z]\w*)$', sym)
    if msg == 'cannot find symbol' and vm and re.search(r'\b' + vm.group(1) + r'(\.|::)', src):
        continue
    entry = f"{f}:{n}: {msg}  {sym}\n    {src}"
    inherited = (msg.startswith('method does not override')
                 or (msg == 'cannot find symbol' and re.match(r'symbol:\s+variable (super|[a-z]\w*)$', sym)
                     and loc.startswith('location: class')))
    if (inherited and external_super(f)) or msg.startswith('reference to') and msg.endswith('is ambiguous'):
        unknown.append(entry)
    else:
        real.append(entry)

print(f"checked {len(wanted)} file(s): {len(real)} error(s) in Breeze's own code, "
      f"{len(unknown)} that depend on Minecraft types and need a real build")
for r in real:
    print(r)
sys.exit(1 if real else 0)
PY
