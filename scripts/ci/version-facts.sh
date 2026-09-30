#!/usr/bin/env bash
# Prints the facts each version module is built on, read from the sources
# themselves rather than remembered: Mojang's releases and the Java each needs,
# whether Minecraft still ships obfuscated with Mojang's mappings, Fabric's
# support, the newest Fabric API build, Loom, and which versions MCEF (the
# embedded browser) publishes for Fabric.
#
# Needs curl and jq, and network access to Mojang, Fabric and Modrinth (CI has
# it; the cloud development container does not). Optional GITHUB_TOKEN lists
# the branches of Fabric's example mod, which show the build setup Fabric
# recommends for each Minecraft version.
set -euo pipefail
OLDEST="${1:-1.17}"
tmp=$(mktemp -d)
get() { curl -sSfL --retry 3 "$@"; }

get https://piston-meta.mojang.com/mc/game/version_manifest_v2.json > "$tmp/manifest.json"
get https://meta.fabricmc.net/v2/versions/game > "$tmp/game.json"
get https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml > "$tmp/fapi.xml"
get https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml > "$tmp/loom.xml"
get 'https://api.modrinth.com/v2/project/mcef/version' > "$tmp/mcef.json"

echo "== Mojang"
jq -r '.latest | "latest release \(.release), latest snapshot \(.snapshot)"' "$tmp/manifest.json"
echo "== Fabric Loader, newest stable"
get https://meta.fabricmc.net/v2/versions/loader | jq -r '[.[] | select(.stable)][0].version'
echo "== Loom, newest 12 versions"
grep -o '<version>[^<]*</version>' "$tmp/loom.xml" | sed 's/<[^>]*>//g' | grep -vE 'SNAPSHOT' | tail -12 | tr '\n' ' '
echo

echo "== Per release, $OLDEST onwards"
printf '%-8s %-5s %-11s %-7s %-12s %-26s %s\n' mc java obfuscated fabric intermediary fabric-api mcef
ids=$(jq -r '.versions[] | select(.type=="release") | .id' "$tmp/manifest.json")
for id in $ids; do
  url=$(jq -r --arg id "$id" '.versions[] | select(.id==$id) | .url' "$tmp/manifest.json")
  get "$url" > "$tmp/v.json"
  java=$(jq -r '.javaVersion.majorVersion // "?"' "$tmp/v.json")
  obf=$(jq -r 'if .downloads.client_mappings then "yes" else "no" end' "$tmp/v.json")
  fabric=$(jq -r --arg id "$id" 'if any(.[]; .version==$id) then "yes" else "no" end' "$tmp/game.json")
  inter=$(get "https://meta.fabricmc.net/v2/versions/intermediary/$id" | jq -r 'if length > 0 then "yes" else "no" end')
  fapi=$(grep -o "<version>[^<]*+$id</version>" "$tmp/fapi.xml" | sed 's/<[^>]*>//g' | tail -1)
  mcef=$(jq -r --arg id "$id" '[.[] | select(.loaders | index("fabric")) | select(.game_versions | index($id)) | .version_number] | first // "-"' "$tmp/mcef.json")
  printf '%-8s %-5s %-11s %-7s %-12s %-26s %s\n' "$id" "$java" "$obf" "$fabric" "$inter" "${fapi:--}" "$mcef"
  [ "$id" = "$OLDEST" ] && break
done

echo "== MCEF Fabric files"
jq -r '.[] | select(.loaders | index("fabric")) | "\(.version_number)\t\(.game_versions | join(","))\t\(.files[0].url)"' "$tmp/mcef.json"

if [ -n "${GITHUB_TOKEN:-}" ]; then
  echo "== Fabric example mod branches"
  get -H "Authorization: Bearer $GITHUB_TOKEN" 'https://api.github.com/repos/FabricMC/fabric-example-mod/branches?per_page=100' \
    | jq -r '.[].name' | tr '\n' ' '
  echo
  for branch in ${EXAMPLE_BRANCHES:-}; do
    for f in gradle.properties build.gradle settings.gradle gradle/wrapper/gradle-wrapper.properties; do
      echo "-- fabric-example-mod $branch: $f"
      get "https://raw.githubusercontent.com/FabricMC/fabric-example-mod/$branch/$f" || echo "(none)"
    done
  done
fi
