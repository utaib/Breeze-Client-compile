#!/usr/bin/env bash
# Puts in <mods dir> exactly the mods the Breeze launcher (1.0.26) installs
# before launching <mc>: Fabric API, and MCEF where Modrinth has a build for
# that version (skipped with WITHOUT_MCEF=true). Each file's sha1 is checked
# against Modrinth's. Prints LAUNCHER_LOADER=<the Fabric Loader the launcher
# would use>, for $GITHUB_ENV.
#
#   scripts/ci/launcher-deps.sh <mc> <mods dir>
set -euo pipefail
mc="$1"; mods=$(realpath -m "$2")
# shellcheck source=launcher-pick.sh
. "$(dirname "$0")/launcher-pick.sh"
mkdir -p "$mods"

launcher_fetch fabric-api "$mc" "$mods"
if [ "${WITHOUT_MCEF:-false}" = true ]; then
  echo "mcef: left out on purpose (WITHOUT_MCEF)" >&2
else
  launcher_fetch mcef "$mc" "$mods"
fi
loader=$(launcher_loader "$mc")
[ "$loader" != none ] || { echo "no Fabric Loader for $mc" >&2; exit 1; }
echo "loader: $loader" >&2
echo "LAUNCHER_LOADER=$loader"
