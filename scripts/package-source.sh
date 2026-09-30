#!/usr/bin/env bash
# Packages the source for one Minecraft version as a ZIP that builds on its own:
#
#   scripts/package-source.sh 1.20.1 [git revision]
#
# writes build/release/Breeze Mod for 1.20.1.zip from the committed tree at the
# revision (default HEAD), never from uncommitted files. Only files Git tracks go
# in, so there is no node_modules, build output, local tooling or anything
# ignored. The ZIP holds the Gradle wrapper, common/, contract/, frontend/
# source, docs/, scripts/ and versions/<mc>/ only, with a settings.gradle that
# includes just that version, plus SOURCE.txt naming the exact commit.
#
# The ZIP is reproducible: file times are the commit's time, entries are sorted,
# and no owner or extended attributes are stored, so the same revision always
# gives the same bytes.
set -euo pipefail
cd "$(dirname "$0")/.."

mc=${1:?usage: scripts/package-source.sh <minecraft version> [git revision]}
rev=${2:-HEAD}
name="Breeze Mod for $mc"

commit=$(git rev-parse --verify -q "$rev^{commit}") || { echo "not a commit: $rev"; exit 1; }
prefix=$(git rev-parse --show-prefix)
top=$(git rev-parse --show-toplevel)
git cat-file -e "$commit:${prefix}versions/$mc/build.gradle" 2>/dev/null \
  || { echo "versions/$mc has no build.gradle at $rev"; exit 1; }

out="$PWD/build/release"
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
root="$stage/$name"
mkdir -p "$root" "$out"

# The project minus other versions and the files that only configure Claude
# Code sessions (CLAUDE.md, .mcp.json). Run from the top level, where Git reads
# these paths relative to the archived tree rather than to this directory.
git -C "$top" archive --format=tar "$commit:$prefix" -- \
  .gitignore README.md CHANGELOG.md gradle.properties settings.gradle \
  gradlew gradlew.bat gradle common contract docs frontend scripts "versions/$mc" \
  | tar -x -C "$root"

# Keep only this version's include lines; comments are left alone.
awk -v want="versions:$mc'" '
  /^[[:space:]]*(include|project\()/ && /versions:/ && index($0, want) == 0 { next }
  { print }
' "$root/settings.gradle" > "$stage/settings.gradle"
mv "$stage/settings.gradle" "$root/settings.gradle"
grep -q "^include 'versions:$mc'" "$root/settings.gradle" \
  || { echo "settings.gradle does not include versions:$mc"; exit 1; }
if grep -E "^[[:space:]]*(include|project\()" "$root/settings.gradle" | grep "versions:" | grep -vq "versions:$mc'"; then
  echo "settings.gradle still includes another version"; exit 1
fi

# Nothing that looks like a credential ships, whatever Git tracks.
leaks=$(cd "$root" && find . -type f \( -name 'env' -o -name '.env*' -o -name '*.pem' -o -name '*.key' \
  -o -name '*.p12' -o -name '*.jks' -o -name 'id_rsa*' \) -print)
[ -z "$leaks" ] || { echo "refusing to package credential-like files:"; echo "$leaks"; exit 1; }

version=$(sed -n 's/^mod_version=//p' "$root/gradle.properties")
"$root/scripts/check-version.sh" >/dev/null || { "$root/scripts/check-version.sh"; exit 1; }

epoch=$(git log -1 --format=%ct "$commit")
cat > "$root/SOURCE.txt" <<EOF
Breeze mod $version for Minecraft $mc
Source: utaib/Breeze-Client, commit $commit
Committed: $(TZ=UTC git log -1 --date=format-local:'%Y-%m-%d %H:%M UTC' --format=%cd "$commit")

Build instructions are in README.md. The jar players install is
versions/$mc/build/libs/$mc.jar.
EOF

find "$stage" -exec touch -h -d "@$epoch" {} +
zip_path="$out/$name.zip"
rm -f "$zip_path"
(cd "$stage" && find "$name" -type f | LC_ALL=C sort | TZ=UTC zip -X -D -q -9 "$zip_path" -@)

files=$(unzip -Z1 "$zip_path" | wc -l)
echo "$zip_path"
echo "breeze $version, minecraft $mc, commit ${commit:0:7}, $files files, $(du -h "$zip_path" | cut -f1)"
echo "sha256 $(sha256sum "$zip_path" | cut -d' ' -f1)"
