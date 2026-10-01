#!/usr/bin/env bash
# What the Breeze launcher (1.0.26) would give a player on every Minecraft
# version Breeze-Mod-New builds for, next to what the in-game tests ran with:
#
#   scripts/ci/launcher-facts.sh [api base]
#
# Per version it makes the launcher's own choices (src-tauri/src/lib.rs):
#   loader      fetch_latest_fabric_loader: the first stable loader Fabric's
#               meta lists for the version, else the first
#   fabric-api  install_modrinth_project_recursive, slug fabric-api
#   mcef        ensure_mcef, slug mcef: Modrinth versions for the exact game
#               version and loader fabric, featured first, then Modrinth's
#               order; the primary file
# (scripts/ci/launcher-pick.sh), and reads what the Breeze API serves (GET
# /versions/mod/resolve, public, read only). Nothing is downloaded but
# metadata.
set -euo pipefail
cd "$(dirname "$0")/../.."
api="${1:-https://api.breezeclient.net}"
# shellcheck source=launcher-pick.sh
. scripts/ci/launcher-pick.sh
get() { launcher_get "$@"; }

echo "API $api"
echo "== /versions/mod/list"
get "$api/versions/mod/list" | jq -c '.data // . | {versions, files}' || echo "unreachable"
echo

printf '%-8s | %-28s | %-34s | %-44s | %s\n' mc loader fabric-api mcef "API serves"
for props in $(printf '%s\n' versions/*/gradle.properties | sort -V); do
  p() { sed -n "s/^$1=//p" "$props" | head -1; }
  mc=$(p minecraft_version)
  ci_loader=$(p loader_version); ci_api=$(p fabric_api_version); ci_mcef=$(p mcef_version)

  loader=$(launcher_loader "$mc")
  fa=$(launcher_modrinth fabric-api "$mc")
  mcef=$(launcher_modrinth mcef "$mc")

  fa_note="$(echo "$fa" | cut -d' ' -f1)"
  [ "$fa_note" = "$ci_api" ] || fa_note="$fa_note (tested $ci_api)"
  mcef_ver=$(echo "$mcef" | cut -d' ' -f1)
  if [ -z "$ci_mcef" ] && [ "$mcef_ver" = none ]; then mcef_note="none, native menus"
  elif [ -z "$ci_mcef" ]; then mcef_note="$mcef_ver INSTALLED, jar is native"
  elif [ "$mcef_ver" = "$ci_mcef" ]; then mcef_note="$mcef_ver = tested"
  else mcef_note="$mcef_ver DIFFERS (tested $ci_mcef)"; fi
  loader_note="$loader"; [ "$loader" = "$ci_loader" ] || loader_note="$loader (tested $ci_loader)"

  served=$(get "$api/versions/mod/resolve?mc=$mc" \
    | jq -r '.data // . | "\(.file) \(if .available then "" else "MISSING " end)\(.modVersion // "?") mc=\(.minecraftRange // "?") \(.supportStatus)"' \
    2>/dev/null || echo "unreachable")
  printf '%-8s | %-28s | %-34s | %-44s | %s\n' "$mc" "$loader_note" "$fa_note" "$mcef_note" "$served"
  echo "         fabric-api: $fa"
  echo "         mcef:       $mcef"
done
