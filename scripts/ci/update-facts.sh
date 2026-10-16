#!/usr/bin/env bash
# What the live Breeze API tells a launcher about updates, from its public
# routes only (no token, so as a signed-out player or a normal user):
#
#   scripts/ci/update-facts.sh [api base]
#
# GET /versions (the manifest), GET /versions/check for a few current
# versions, and a HEAD of every download URL they hand out (status and
# content type), so a URL that leads to a web page instead of an installer
# shows. Read only.
set -euo pipefail
api="${1:-https://api.breezeclient.net}"
ua='utaib/Breeze-Client update-facts (CI)'
get() { curl -sS -A "$ua" --retry 2 "$@"; }
probe() { # url
  if [ -z "$1" ] || [ "$1" = null ]; then echo "    (no url)"; return; fi
  get -o /dev/null -I -L -w '    HEAD %{http_code} %{content_type} %{size_download}B final=%{url_effective}\n' "$1" || echo "    unreachable"
}
echo "== GET /versions"
m=$(get "$api/versions")
echo "$m" | jq '{latestVersion: (.launcher.latestVersion // .latestVersion), downloadUrl: (.launcher.downloadUrl // .downloadUrl), sha256: (.launcher.sha256 // .sha256), mandatory: (.launcher.mandatory // .mandatory), windows: (.launcher.platforms.windows // .platforms.windows // null)}' 2>/dev/null || echo "$m" | head -c 2000
echo "  top-level downloadUrl:"; probe "$(echo "$m" | jq -r '.launcher.downloadUrl // .downloadUrl // empty')"
echo "  windows downloadUrl:"; probe "$(echo "$m" | jq -r '(.launcher.platforms.windows // .platforms.windows // {}).downloadUrl // empty')"
for cur in 1.0.24 1.0.25 1.0.26 1.0.27; do
  echo "== GET /versions/check?current=$cur&platform=windows (no token)"
  c=$(get "$api/versions/check?current=$cur&platform=windows")
  echo "$c" | jq -c '{role, channels, upToDate, stable: (.stable | {version, available, url, fileName}), update: (.update | if . then {channel, version, url, fileName} else null end)}' 2>/dev/null || echo "$c" | head -c 1000
  echo "  update url:"; probe "$(echo "$c" | jq -r '.update.url // empty')"
done
echo "== the base URL the API puts in links it builds (from /versions/mod/resolve)"
get "$api/versions/mod/resolve?mc=1.20.1" | jq -r '.url // "no url"' | sed 's/^/  /'
echo "== the website pages the launcher falls back to"
for u in https://breezeclient.pages.dev/download.html https://breezeclient.net/download.html https://www.breezeclient.net/download.html; do
  echo "  $u"; probe "$u"
done
echo "== GET /versions/launcher/testing (signed out: should be refused)"
get -o /dev/null -w '  %{http_code}\n' "$api/versions/launcher/testing" || true
