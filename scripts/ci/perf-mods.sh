#!/usr/bin/env bash
# Adds the usual Fabric optimisation mods to <mods dir> for <mc>, each the
# build Modrinth lists for that exact version (featured first, as the
# launcher picks), sha1 checked. A mod with no build for the version is
# skipped and said so. For testing Breeze beside them, and comparing frame
# rates with and without them on the same machines.
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
for slug in "$@"; do launcher_fetch "$slug" "$mc" "$mods"; done
