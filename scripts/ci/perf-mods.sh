#!/usr/bin/env bash
# Adds other mods to <mods dir> for <mc> as launcher 1.0.28 installs them
# (modrinth-pick.py: releases first, a pinned dependency first, a version only
# when it fits the game and the mods already there), sha1 checked, with their
# required dependencies. A mod with no build for the version is skipped and
# said so. For testing Breeze beside the mods players
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
[ "$#" -gt 0 ] || set -- sodium lithium ferrite-core
# The loader the game runs: the launcher's pick when the run is set up as the
# launcher does, else the pinned one.
loader="${LAUNCHER_LOADER:-${LOADER:-$(launcher_loader "$mc")}}"
python3 "$(dirname "$0")/modrinth-pick.py" "$mc" "$loader" "$mods" "$@"
