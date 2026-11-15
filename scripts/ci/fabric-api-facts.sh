#!/usr/bin/env bash
# For each Minecraft version given, prints the newest Fabric API build that
# Modrinth lists for it, the mod id and Minecraft range in that jar's
# fabric.mod.json, and which of the modules Breeze uses it bundles. Old Fabric
# API builds use the mod id "fabric" (later "fabric-api"), and a "+1.17" build
# may not accept 1.17 itself, so neither can be read from the version string.
#
# Usage: scripts/ci/fabric-api-facts.sh 1.17 1.17.1 1.18 ...   (needs curl, jq, unzip)
set -u
MODULES='fabric-rendering-v1 fabric-item-api-v1 fabric-key-binding-api-v1 fabric-lifecycle-events-v1'
tmp=$(mktemp -d)
for mc in "$@"; do
  q="game_versions=%5B%22$mc%22%5D&loaders=%5B%22fabric%22%5D"
  json=$(curl -sSf "https://api.modrinth.com/v2/project/fabric-api/version?$q") || { echo "$mc: Modrinth request failed"; continue; }
  count=$(printf '%s' "$json" | jq length)
  [ "$count" -gt 0 ] || { echo "$mc: no Fabric API on Modrinth"; continue; }
  num=$(printf '%s' "$json" | jq -r '.[0].version_number')
  url=$(printf '%s' "$json" | jq -r '.[0].files | (map(select(.primary)) + .)[0].url')
  jar="$tmp/$mc.jar"
  curl -sSfL -o "$jar" "$url" || { echo "$mc: $num download failed"; continue; }
  meta=$(unzip -p "$jar" fabric.mod.json | jq -c '{id, mc: .depends.minecraft}')
  nested=$(unzip -l "$jar" | grep -oE 'META-INF/jars/[^ ]+\.jar' | sed 's#META-INF/jars/##')
  have=""; miss=""
  for m in $MODULES; do
    if printf '%s\n' "$nested" | grep -q "^$m-"; then have="$have $m"; else miss="$miss $m"; fi
  done
  echo "$mc: fabric-api $num ($count builds list $mc) $meta size $(stat -c %s "$jar")"
  echo "    bundles:${have:- none}"
  [ -n "$miss" ] && echo "    missing:$miss"
done
rm -rf "$tmp"
