#!/usr/bin/env bash
# What embedded-browser mods exist for which Minecraft versions, read from
# Modrinth, and what their Fabric jars contain (mod id, dependencies, Java
# packages, the public API of the main class). For choosing a browser for
# versions the original MCEF stops short of (1.21.5 and later, 26.x).
#
#   scripts/ci/browser-facts.sh [slug ...]
#
# Default slugs: mcef mcef-keksuccino rinku ingameweb cubium. Read only.
set -euo pipefail
ua='utaib/Breeze-Client browser-facts (CI)'
get() { curl -sSf -A "$ua" --retry 3 --retry-delay 2 "$@"; }
[ "$#" -gt 0 ] || set -- mcef mcef-keksuccino rinku ingameweb cubium
work=$(mktemp -d)
for slug in "$@"; do
  echo "=================== $slug"
  if ! p=$(get "https://api.modrinth.com/v2/project/$slug" 2>/dev/null); then echo "no such project"; continue; fi
  echo "$p" | jq -r '"title: \(.title)\nlicense: \(.license.id)\nsource: \(.source_url)\nupdated: \(.updated)"'
  v=$(get "https://api.modrinth.com/v2/project/$slug/version")
  echo "-- fabric builds, newest per Minecraft version:"
  echo "$v" | jq -r '[.[] | select((.loaders // []) | index("fabric")) | . as $x | (.game_versions // [])[] | {gv: ., ver: $x.version_number, type: $x.version_type, date: ($x.date_published // "")[0:10]}]
    | group_by(.gv) | map(max_by(.date)) | sort_by(.gv | split(".") | map(tonumber? // 0)) | .[] | "  \(.gv)\t\(.ver)\t\(.type)\t\(.date)"' || echo "  (could not list)"
  echo "-- dependencies of the newest fabric build:"
  echo "$v" | jq -r '[.[] | select((.loaders // []) | index("fabric"))] | max_by(.date_published) // {} | (.dependencies // [])[] | "  \(.dependency_type) \(.project_id // .version_id)"' || echo "  (none)"
  # Look inside the newest fabric jar for three Minecraft versions, if present.
  for gv in 1.21.5 1.21.11 26.3; do
    url=$(echo "$v" | jq -r --arg gv "$gv" '[.[] | select(((.loaders // []) | index("fabric")) and ((.game_versions // []) | index($gv)))] | max_by(.date_published) // {} | ((.files // []) | (map(select(.primary)) | first) // first) // {} | .url // empty' || true)
    [ -n "$url" ] || continue
    jar="$work/$slug-$gv.jar"
    get -L -o "$jar" "$url"
    echo "-- inside the $gv jar ($(basename "$url"), $(stat -c %s "$jar") bytes)"
    unzip -p "$jar" fabric.mod.json 2>/dev/null | jq -c '{id, version, depends, entrypoints, jars: [.jars[]?.file]}' || echo "  no fabric.mod.json"
    echo "  packages:"; unzip -Z1 "$jar" | grep '\.class$' | sed 's|/[^/]*$||' | sort | uniq -c | sort -rn | head -15 | sed 's/^/    /'
    for cls in $(unzip -Z1 "$jar" | grep -E '/(MCEF|Rinku|MCEFBrowser|RinkuBrowser|MCEFInitListener)\.class$' | head -5); do
      echo "  javap ${cls%.class}:"
      (cd "$work" && rm -rf x && mkdir x && cd x && unzip -q "$jar" "$cls" && javap -public "$cls" | sed 's/^/    /')
    done
    # Nested jars (jar-in-jar) often carry the real library.
    for nested in $(unzip -Z1 "$jar" | grep '^META-INF/jars/.*\.jar$' | head -5); do echo "  nested: $nested"; done
  done
done
