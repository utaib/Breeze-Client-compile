#!/usr/bin/env bash
# One JSON line per jar in <mods dir>: file, sha1, and what its
# fabric.mod.json declares (id, version, provides, depends, breaks,
# conflicts) plus the same for the jars nested in META-INF/jars. Metadata
# only, never code: the evidence the launcher's own mods-folder checks
# (duplicates, missing dependencies) are tested against.
#
#   scripts/ci/mods-summary.sh <mods dir> [extra jar ...]
set -euo pipefail
dir="$1"; shift
meta() { # jar -> fabric.mod.json fields, or null
  unzip -p "$1" fabric.mod.json 2>/dev/null | tr -d '\r' | sed 's#^\s*//.*##' \
    | jq -c '{id, version, provides: (.provides // []), depends: (.depends // {}), breaks: (.breaks // {}), conflicts: (.conflicts // {})}' 2>/dev/null || echo null
}
nested() { # jar -> [{id, version}] of META-INF/jars (one level)
  local tmp out="[]" j m
  tmp=$(mktemp -d)
  unzip -qq -o "$1" 'META-INF/jars/*.jar' -d "$tmp" 2>/dev/null || true
  for j in "$tmp"/META-INF/jars/*.jar; do
    [ -e "$j" ] || continue
    m=$(unzip -p "$j" fabric.mod.json 2>/dev/null | tr -d '\r' | jq -c '{id, version}' 2>/dev/null || echo null)
    [ "$m" = null ] || out=$(jq -c --argjson m "$m" '. + [$m]' <<< "$out")
  done
  rm -rf "$tmp"
  echo "$out"
}
for jar in "$dir"/*.jar "$@"; do
  [ -f "$jar" ] || continue
  jq -cn --arg file "$(basename "$jar")" --arg sha1 "$(sha1sum "$jar" | cut -d' ' -f1)" \
    --argjson meta "$(meta "$jar")" --argjson nested "$(nested "$jar")" \
    '{file: $file, sha1: $sha1, meta: $meta, nested: $nested}'
done
