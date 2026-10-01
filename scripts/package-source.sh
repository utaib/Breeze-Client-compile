#!/usr/bin/env bash
# Packages the source for one Minecraft version as a ZIP that builds on its own:
#
#   scripts/package-source.sh 1.20.1 [git revision]
#
# writes build/release/Breeze Mod for 1.20.1.zip from the committed tree at the
# revision (default HEAD), never from uncommitted files. Only files Git tracks go
# in, so there is no node_modules, build output, local tooling or anything
# ignored. The ZIP holds the Gradle wrapper, common/, contract/, frontend/
# source, docs/, scripts/ and versions/<mc>/ only, plus SOURCE.txt naming the
# exact commit. A version whose source_chain spans several folders
# (gradle/version.gradle, "Sources") gets them merged into its own src/main,
# so the ZIP is one plain folder that settings.gradle finds on its own.
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
for f in build.gradle gradle.properties; do
  git cat-file -e "$commit:${prefix}versions/$mc/$f" 2>/dev/null \
    || { echo "versions/$mc has no $f at $rev"; exit 1; }
done

out="$PWD/build/release"
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
root="$stage/$name"
mkdir -p "$root" "$out"

# The project minus other versions and the files that only configure Claude
# Code sessions (CLAUDE.md, .mcp.json). Run from the top level, where Git reads
# these paths relative to the archived tree rather than to this directory.
git -C "$top" archive --format=tar "$commit:$prefix" -- \
  .gitignore README.md CHANGELOG.md build.gradle gradle.properties settings.gradle \
  gradlew gradlew.bat gradle common contract docs frontend scripts "versions/$mc" \
  | tar -x -C "$root"

# The version's source: its source_chain merged, newest folder first, each
# folder's removed.txt dropping files of the folders before it, then renames.
# The same rules as gradle/version.gradle's mergeSources.
props="$root/versions/$mc/gradle.properties"
chain=$(sed -n 's/^source_chain=//p' "$props" | tr -d ' ')
[ -n "$chain" ] || chain=$mc
IFS=',' read -ra links <<< "$chain"
if [ "$chain" != "$mc" ]; then
  merged="$stage/merged"
  dropped="$stage/dropped.txt"
  mkdir -p "$merged"
  : > "$dropped"
  for (( i=${#links[@]}-1; i>=0; i-- )); do
    link=${links[$i]}
    part="$stage/link-$i"
    mkdir -p "$part"
    if git -C "$top" cat-file -e "$commit:${prefix}versions/$link/src/main" 2>/dev/null; then
      git -C "$top" archive --format=tar "$commit:${prefix}versions/$link/src/main" | tar -x -C "$part"
    fi
    (cd "$part" && find . -type f | sed 's#^\./##' | LC_ALL=C sort) | while IFS= read -r f; do
      grep -qxF "$f" "$dropped" && continue
      [ -e "$merged/$f" ] && continue
      mkdir -p "$merged/$(dirname "$f")"
      cp "$part/$f" "$merged/$f"
    done
    if git -C "$top" cat-file -e "$commit:${prefix}versions/$link/removed.txt" 2>/dev/null; then
      git -C "$top" show "$commit:${prefix}versions/$link/removed.txt" | sed 's/[[:space:]]*$//' \
        | grep -vE '^(#|$)' >> "$dropped" || true
    fi
  done
  # Renames (renames.txt), in chain order, on every Java file: the same rule
  # as mergeSources.
  for link in "${links[@]}"; do
    git -C "$top" cat-file -e "$commit:${prefix}versions/$link/renames.txt" 2>/dev/null || continue
    git -C "$top" show "$commit:${prefix}versions/$link/renames.txt" | sed 's/[[:space:]]*$//' | { grep -vE '^(#|$)' || true; } \
      | while read -r from to extra; do
        [ -n "$to" ] && [ -z "$extra" ] || { echo "versions/$link/renames.txt: expected 'old new'"; exit 1; }
        find "$merged" -name '*.java' -type f -print0 \
          | FROM="$from" TO="$to" xargs -0 perl -pi -e 'my $b = substr($ENV{FROM}, 0, 1) eq "." ? "" : q{(?<![\w\$])}; s/$b\Q$ENV{FROM}\E(?![\w\$])/$ENV{TO}/g'
      done
  done
  rm -rf "$root/versions/$mc/src"
  mkdir -p "$root/versions/$mc/src"
  cp -R "$merged" "$root/versions/$mc/src/main"
  rm -f "$root/versions/$mc/removed.txt"
  sed -i "s/^source_chain=.*/source_chain=$mc/" "$props"
fi
[ -f "$root/versions/$mc/src/main/resources/fabric.mod.json" ] \
  || { echo "versions/$mc has no fabric.mod.json after merging $chain"; exit 1; }
others=$(find "$root/versions" -mindepth 1 -maxdepth 1 -type d ! -name "$mc")
[ -z "$others" ] || { echo "the ZIP would hold other versions: $others"; exit 1; }

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
Merged from: versions/${chain//,/, versions/}
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
