#!/usr/bin/env bash
# Load test of the Breeze add-on in Mojang's Bedrock Dedicated Server (BDS).
# Runs only on the public build repository (bds.yml), never on a player's
# machine. The owner accepted the BDS licence for this test on 2026-11-28.
#
#   ci/bds_test.sh <linux|windows> <oldest|release|preview> <work dir>
#
# Downloads BDS from Mojang's official links, puts the packs from dist/ into
# a fresh test world (world_behavior_packs.json, world_resource_packs.json),
# turns the content log on, starts the server, waits for "Server started."
# and checks its output. Two passes:
#   shipped  the packs exactly as dist/ has them: no pack or script error;
#   probe    the same packs with a CI-only block appended to a copy of
#            main.js that logs "[Breeze CI]" lines, proving the script ran
#            and the APIs it calls exist. The probe never leaves CI.
# Writes <work dir>/result-<os>-<kind>.txt and the logs; exits non-zero on a
# failure.
set -uo pipefail
OS=${1:?linux or windows}; KIND=${2:?oldest, release or preview}; WORK=${3:?work dir}
here=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$here/.." && pwd)
VERSION=$(tr -d '\r\n' < "$ROOT/VERSION")
mkdir -p "$WORK"; WORK=$(cd "$WORK" && pwd)
RESULT="$WORK/result-$OS-$KIND.txt"; : > "$RESULT"
UA="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
say() { echo "$*" | tee -a "$RESULT"; }
fail() { say "FAIL: $*"; exit 1; }

# ---- 1. the server -----------------------------------------------------------
LINKS_API=https://net-secondary.web.minecraft-services.net/api/v1.0/download/links
url=""
if [ "$KIND" = oldest ]; then
  # The oldest version the add-on supports (min_engine_version 1.21.0). The
  # links API offers only the newest, so try the 1.21.0 to 1.21.2 builds
  # Mojang published, oldest first, under the same official path.
  dir=bin-linux; ext=""; [ "$OS" = windows ] && dir=bin-win
  for v in 1.21.0.03 1.21.0.02 1.21.0.01 1.21.1.03 1.21.1.02 1.21.2.02 1.21.2.01; do
    u="https://www.minecraft.net/bedrockdedicatedserver/$dir/bedrock-server-$v.zip"
    if curl -fsIL -A "$UA" -o /dev/null "$u"; then url=$u; break; fi
    echo "not offered: $u"
  done
else
  curl -fsSL -A "$UA" -o "$WORK/links.json" "$LINKS_API" || fail "links API not reachable"
  type=serverBedrock; [ "$KIND" = preview ] && type=serverBedrockPreview
  [ "$OS" = windows ] && type="${type}Windows" || type="${type}Linux"
  url=$(python - "$WORK/links.json" "$type" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
for l in data.get("result", {}).get("links", []):
    if l.get("downloadType") == sys.argv[2]:
        print(l["downloadUrl"])
PY
)
fi
[ -n "$url" ] || fail "no official BDS download found for $OS $KIND"
bds_version=$(basename "$url" .zip | sed 's/^bedrock-server-//')
say "BDS $OS $KIND: $bds_version"
say "download: $url"
curl -fsSL -A "$UA" -o "$WORK/bds.zip" "$url" || fail "download failed"
say "bds.zip sha256: $(sha256sum "$WORK/bds.zip" | cut -d' ' -f1)"

# ---- 2. the packs ------------------------------------------------------------
packs="$WORK/packs"; rm -rf "$packs"; mkdir -p "$packs/hud" "$packs/ui"
unzip -q "$ROOT/dist/Breeze-HUD-$VERSION.mcpack" -d "$packs/hud" || fail "HUD mcpack unreadable"
unzip -q "$ROOT/dist/Breeze-UI-$VERSION.mcpack" -d "$packs/ui" || fail "UI mcpack unreadable"
read -r HUD_UUID HUD_VER UI_UUID UI_VER < <(python - "$packs" <<'PY'
import json, sys
h = json.load(open(sys.argv[1] + "/hud/manifest.json"))["header"]
u = json.load(open(sys.argv[1] + "/ui/manifest.json"))["header"]
print(h["uuid"], json.dumps(h["version"]).replace(" ", ""), u["uuid"], json.dumps(u["version"]).replace(" ", ""))
PY
)
say "Breeze HUD $HUD_UUID $HUD_VER, Breeze UI $UI_UUID $UI_VER"

probe_block() {
  cat <<'JS'

// ---- CI only (Breeze-Bedrock/ci/bds_test.sh); never in a shipped pack ----
console.warn("[Breeze CI] main.js loaded");
system.runTimeout(() => {
  try {
    const parts = [
      `players=${world.getAllPlayers().length}`,
      `clock=${clock(world.getTimeOfDay())}`,
      `day=${world.getDay() + 1}`,
      `facing=${facing(90)}`,
      `effect=${effectName("minecraft:speed", 1)} ${duration(1250)}`,
      `defaults=${JSON.stringify(parseSettings(undefined)) === JSON.stringify(defaultSettings())}`,
      `slots=${SLOTS.length}`,
      `durability=${typeof ItemDurabilityComponent.componentId}`,
      `equippable=${typeof EntityEquippableComponent.componentId}`,
    ];
    new ActionFormData().title("Breeze").body("ci").button("a");
    new ModalFormData().title("Breeze HUD").toggle("t", true);
    parts.push("forms=ok");
    console.warn(`[Breeze CI] script ran: ${parts.join(" ")}`);
  } catch (e) {
    console.warn(`[Breeze CI] probe failed: ${e}`);
  }
}, 60);
JS
}

# ---- 3. one server run -------------------------------------------------------
run_pass() {
  local pass=$1; local srv="$WORK/bds-$pass" world log
  rm -rf "$srv"; mkdir -p "$srv"
  unzip -q "$WORK/bds.zip" -d "$srv" || fail "bds.zip unreadable"
  world="$srv/worlds/breeze_test"
  mkdir -p "$world/behavior_packs" "$world/resource_packs"
  cp -r "$packs/hud" "$world/behavior_packs/breeze_hud"
  cp -r "$packs/ui" "$world/resource_packs/breeze_ui"
  if [ "$pass" = probe ]; then probe_block >> "$world/behavior_packs/breeze_hud/scripts/main.js"; fi
  printf '[{"pack_id":"%s","version":%s}]\n' "$HUD_UUID" "$HUD_VER" > "$world/world_behavior_packs.json"
  printf '[{"pack_id":"%s","version":%s}]\n' "$UI_UUID" "$UI_VER" > "$world/world_resource_packs.json"
  python - "$srv/server.properties" <<'PY'
import sys
p = sys.argv[1]
want = {
    "level-name": "breeze_test", "online-mode": "false", "content-log-file-enabled": "true",
    "content-log-console-output-enabled": "true", "content-log-level": "verbose",
    "texturepack-required": "true", "server-port": "19132", "server-portv6": "19133",
}
lines, seen = [], set()
for line in open(p, encoding="utf-8").read().splitlines():
    k = line.split("=", 1)[0].strip()
    if k in want:
        line = f"{k}={want[k]}"; seen.add(k)
    lines.append(line)
lines += [f"{k}={v}" for k, v in want.items() if k not in seen]
open(p, "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
PY
  log="$WORK/server-$OS-$KIND-$pass.log"; : > "$log"
  local exe=./bedrock_server; [ "$OS" = windows ] && exe=./bedrock_server.exe
  chmod +x "$srv/bedrock_server" 2>/dev/null || true
  # The console reads commands from a file fed through tail, which works on
  # Linux and in Git Bash on Windows alike.
  local ctl="$WORK/stdin-$pass"; : > "$ctl"
  ( cd "$srv" && tail -f "$ctl" | LD_LIBRARY_PATH=. "$exe" > "$log" 2>&1 ) &
  local pid=$!
  local started=0
  for _ in $(seq 1 240); do
    if grep -q "Server started" "$log"; then started=1; break; fi
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  [ "$started" = 1 ] && sleep 20  # let the script run its timers
  echo stop >> "$ctl"
  for _ in $(seq 1 60); do grep -q "Quit correctly" "$log" && break; kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  sleep 2
  pkill -f "tail -f $ctl" 2>/dev/null || true
  [ "$OS" = windows ] && taskkill //F //IM bedrock_server.exe > /dev/null 2>&1
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  # Content log files, wherever this version writes them.
  find "$srv" -iname '*contentlog*' -type f -exec sh -c 'for f; do echo "== $f"; cat "$f"; done' sh {} + > "$WORK/contentlog-$OS-$KIND-$pass.txt" 2>/dev/null || true
  cp "$world/world_behavior_packs.json" "$world/world_resource_packs.json" "$WORK/" 2>/dev/null || true

  say "--- $pass pass"
  [ "$started" = 1 ] || { cat "$log"; fail "$pass: the server never said it started"; }
  say "$pass: Server started"
  local bad
  bad=$(grep -hiE 'error|warn|fail|exception|invalid|missing|could not|unable' "$log" "$WORK/contentlog-$OS-$KIND-$pass.txt" \
        | grep -v '\[Breeze CI\] script ran' | grep -v '\[Breeze CI\] main.js loaded' \
        | grep -viE 'NO LOG FILE|content-log|telemetry|Opening level|Microsoft.*(diagnostic|telemetry)' || true)
  if [ -n "$bad" ]; then
    say "$pass: warnings or errors in the output:"; echo "$bad" | sed 's/^/    /' | tee -a "$RESULT"
  else
    say "$pass: no error, warning or failure lines (server output and content log)"
  fi
  grep -hE '\[Scripting\]|\[Breeze' "$log" | sed 's/^/    /' | tee -a "$RESULT" || true
  if [ "$pass" = probe ]; then
    grep -q '\[Breeze CI\] main.js loaded' "$log" || fail "probe: main.js never ran"
    grep -q '\[Breeze CI\] script ran: .*forms=ok' "$log" || fail "probe: the script did not finish its checks"
    say "probe: Breeze HUD's main.js ran and every API it uses answered"
  fi
  [ -z "$bad" ] || fail "$pass: see the lines above"
}

run_pass shipped
run_pass probe
say "PASS: $OS $KIND (BDS $bds_version)"
