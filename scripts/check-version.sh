#!/usr/bin/env bash
# The mod version lives in three places and must agree in all of them:
#   gradle.properties (mod_version), frontend/package.json and its lock file,
#   and the newest entry in CHANGELOG.md.
# Versions are plain MAJOR.MINOR.PATCH: features bump MINOR, fixes bump PATCH.
set -eu
cd "$(dirname "$0")/.."
gradle=$(sed -n 's/^mod_version=//p' gradle.properties)
pkg=$(node -p "require('./frontend/package.json').version")
lock=$(node -p "require('./frontend/package-lock.json').version")
log=$(grep -m1 -oE '^## \[[0-9]+\.[0-9]+\.[0-9]+\]' CHANGELOG.md | tr -d '#[] ')
status=0
for pair in "frontend/package.json:$pkg" "frontend/package-lock.json:$lock" "CHANGELOG.md:$log"; do
  if [ "${pair#*:}" != "$gradle" ]; then
    echo "version mismatch: gradle.properties says $gradle, ${pair%%:*} says ${pair#*:}"
    status=1
  fi
done
echo "$gradle" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "mod_version $gradle is not plain MAJOR.MINOR.PATCH"; status=1; }
[ "$status" -eq 0 ] && echo "version $gradle agrees everywhere"
exit "$status"
