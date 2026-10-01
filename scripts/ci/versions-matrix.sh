#!/usr/bin/env bash
# Prints the version projects (versions/*/gradle.properties) as a JSON array
# for a CI matrix, oldest Minecraft first:
#
#   scripts/ci/versions-matrix.sh               every version
#   scripts/ci/versions-matrix.sh 1.20,1.21.4   only these
#
# Each entry: mc, loader, fabric_api, mcef ("" where MCEF has no build), java.
set -euo pipefail
cd "$(dirname "$0")/../.."
only=",${1:-},"
for props in versions/*/gradle.properties; do
  get() { sed -n "s/^$1=//p" "$props" | head -1; }
  mc=$(get minecraft_version)
  [ "$only" = ",," ] || [[ "$only" == *",$mc,"* ]] || continue
  jq -n --arg mc "$mc" --arg loader "$(get loader_version)" --arg fabric_api "$(get fabric_api_version)" \
    --arg mcef "$(get mcef_version)" --arg java "$(get java_release)" \
    '{mc: $mc, loader: $loader, fabric_api: $fabric_api, mcef: $mcef, java: $java}'
done | jq -s -c 'sort_by(.mc | split(".") | map(tonumber))'
