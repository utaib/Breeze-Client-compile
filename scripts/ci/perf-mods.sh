#!/usr/bin/env bash
# Adds other mods to <mods dir> for <mc>, each the build Modrinth lists for
# that exact version (featured first, as the launcher picks), sha1 checked,
# with their required dependencies the same way. A mod with no build for the
# version is skipped and said so. For testing Breeze beside the mods players
# run (Mod Menu, Sodium, Zoomify, Xaero's maps, Flashback, Replay Mod), and
# comparing frame rates with and without the optimisation ones.
#
#   scripts/ci/perf-mods.sh <mc> <mods dir> [slug ...]
#
# Default slugs: sodium lithium ferrite-core.
set -euo pipefail
mc="$1"; mods=$(realpath -m "$2"); shift 2
# shellcheck source=launcher-pick.sh
. "$(dirname "$0")/launcher-pick.sh"
mkdir -p "$mods"
[ "$#" -gt 0 ] || set -- sodium lithium ferrite-core
for slug in "$@"; do launcher_fetch_deps "$slug" "$mc" "$mods"; done
rm -f "$mods/.fetched-projects"
