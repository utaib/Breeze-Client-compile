#!/usr/bin/env bash
# What the live Breeze API answers on the public routes the mod reads capes
# and tags from, for the given players (Minecraft uuids, dashed or not):
#
#   scripts/ci/cosmetics-facts.sh "<uuid> <uuid> ..." [api base]
#
# GET only, no token (as the mod asks before it signs in). Prints a summary of
# each answer: status, account name and role, the equipped cape, how many
# capes are owned, the tag; for image routes only the status, type and size.
# Then the public cape catalogue and which mod jar the API serves for each
# Minecraft version. Read only.
set -euo pipefail
uuids="${1:-}"
api="${2:-https://api.breezeclient.net}"
ua='utaib/Breeze-Client cosmetics-facts (CI)'
get() { curl -sS -A "$ua" --retry 2 --max-time 20 "$@"; }
status() { get -o /dev/null -w '%{http_code} %{content_type} %{size_download}B' "$1" || echo unreachable; }
dash() {
  local s; s=$(echo "$1" | tr -d '-' | tr 'A-F' 'a-f')
  echo "${s:0:8}-${s:8:4}-${s:12:4}-${s:16:4}-${s:20:12}"
}

for raw in $uuids; do
  u=$(dash "$raw")
  echo "== player $u"
  body=$(get -w '\n%{http_code}' "$api/cosmetics/state/$u" || echo $'\nunreachable')
  code=$(echo "$body" | tail -n1); json=$(echo "$body" | sed '$d')
  echo "  GET /cosmetics/state: $code"
  echo "$json" | jq -c '{success, error, username, role, cape: (.cape | if . then {id, name, imageUrl, animated, frames: (.frames | length)} else null end), ownedCapes: (.ownedCapes | if . then [.[] | {id, name}] else null end), tag: (.tag | if . then {name, color, icon} else null end), badge: (.badge | if . then {slug, color} else null end), availableTags: (.availableTags | if . then [.[] | .name] else null end), revision}' 2>/dev/null \
    || echo "  (not JSON) $(echo "$json" | head -c 300)"
  echo "  GET /selected: $(get -w ' [%{http_code}]' "$api/selected/$u" | head -c 200 || echo unreachable)"
  echo "  GET /capes/<uuid> (owned names): $(get -w ' [%{http_code}]' "$api/capes/$u" | head -c 600 || echo unreachable)"
  echo "  GET /cape/<uuid> (image): $(status "$api/cape/$u")"
  sel=$(get "$api/selected/$u" 2>/dev/null || true)
  if echo "$sel" | grep -Eq '^[0-9a-fA-F-]{36}$'; then
    echo "  GET /capefile/<selected> (image): $(status "$api/capefile/$sel")"
  fi
done

echo "== GET /capes (public catalogue)"
get "$api/capes" | jq -c '{success, count: (.capes // .data.capes // [] | length), capes: [(.capes // .data.capes // [])[] | {id, name, rarity, is_animated, frames: (.animation_frames // [] | length), image: (.image_url | split("/")[2])}]}' 2>/dev/null \
  || echo "  (not JSON)"
echo "== GET /tag without a token (the mod sends its game token)"
echo "  $(status "$api/tag")"
echo "== GET /cosmetics (3D catalogue)"
get "$api/cosmetics" | jq -c '{success, count: ((.cosmetics // .data.cosmetics // []) | length)}' 2>/dev/null || echo "  (not JSON)"

echo "== which mod jar the API serves (GET /versions/mod/resolve)"
for mc in 1.17 1.17.1 1.18 1.18.1 1.18.2 1.19 1.19.1 1.19.2 1.19.3 1.19.4 \
          1.20 1.20.1 1.20.2 1.20.3 1.20.4 1.20.5 1.20.6 \
          1.21 1.21.1 1.21.2 1.21.3 1.21.4 1.21.5 1.21.6 1.21.7 1.21.8 1.21.9 1.21.10 1.21.11 \
          26.1 26.1.1 26.1.2 26.2 26.3; do
  printf '  %-8s ' "$mc"
  get "$api/versions/mod/resolve?mc=$mc" \
    | jq -r '.data // . | "\(.file // "-") \(if .available then "" else "MISSING " end)\(.modVersion // "?") mc=\(.minecraftRange // "?") \(.supportStatus // "")"' 2>/dev/null \
    || echo unreachable
done
