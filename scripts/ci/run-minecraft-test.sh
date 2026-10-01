#!/usr/bin/env bash
# Launches Minecraft with Breeze on a virtual display, drives it with real X11
# input, records the screen, and collects the evidence into $OUT. Software
# rendered (Mesa llvmpipe): this proves behaviour, not performance.
#
# Two ways to launch, chosen with BREEZE_LAUNCH:
#
#   dev   (default) the development client, ./gradlew runClient. Mojang names,
#         Loom's classpath. Fast to iterate on, but not what players run.
#   prod  a real Fabric install made by portablemc, as a launcher makes it:
#         vanilla Minecraft, Fabric Loader, and the released jar in mods/ with
#         intermediary names. Needs BREEZE_JAR (the jar under test) and
#         BREEZE_MODS (a folder of other mods to install: Fabric API, MCEF).
#
# Usage: scripts/ci/run-minecraft-test.sh <out-dir>   (from Breeze-Mod-New/)
# Other settings: BREEZE_MC (Minecraft version, default 1.20.1), BREEZE_LOADER
# (Fabric Loader version for prod), BREEZE_GAME_DIR (prod game folder),
# BREEZE_ADDMODS=1 (prod: hand the jar to Fabric with -Dfabric.addMods from
# outside mods/, as the Breeze launcher does, instead of copying it in).
set -u
OUT="$(realpath -m "${1:-autotest}")"
MODE="${BREEZE_LAUNCH:-dev}"
MC="${BREEZE_MC:-1.20.1}"
mkdir -p "$OUT"
rm -f "$OUT/driver-done" "$OUT/breeze-autotest.log"
export DISPLAY=:99
# Minecraft 26.3 opens its window through SDL 3 and asks for an sRGB-capable
# OpenGL framebuffer. Xvfb's GLX offers no visual with that, so SDL's GLX path
# fails ("Couldn't find matching GLX visual"); through EGL the same software
# renderer gives one. Only this virtual display needs it; GLFW (every version
# before 26.3) ignores the variable.
export SDL_VIDEO_FORCE_EGL=1
# With xdotool's XTEST input on Xvfb, SDL 3's XInput2 path delivers every
# click twice (press, release, press, release; reproduced with LWJGL 3.4.3's
# SDL), so a module card switched on and straight back off on 26.3. SDL's own
# switch for that path keeps it to the one core event. Test harness only.
export SDL_VIDEO_X11_XINPUT2=0
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-$(mktemp -d)}"

Xvfb :99 -screen 0 1280x800x24 -nolisten tcp > "$OUT/xvfb.log" 2>&1 &
XVFB=$!
sleep 2
openbox > "$OUT/openbox.log" 2>&1 &
sleep 1
glxinfo -B > "$OUT/glxinfo.txt" 2>&1 || true

ffmpeg -loglevel error -f x11grab -video_size 1280x800 -framerate 8 -i :99 -c:v libx264 -preset ultrafast -pix_fmt yuv420p "$OUT/session.mp4" &
FFMPEG=$!

if [ "$MODE" = prod ]; then
  RUN="$(realpath -m "${BREEZE_GAME_DIR:-$OUT/game}")"
else
  RUN="versions/$MC/run"
fi

# A new game folder opens on Minecraft's accessibility onboarding screen, not
# the title screen, and waits for a click on Continue. Start as a player who
# has seen it once. Only when there is no options.txt yet, so settings from an
# earlier run are never overwritten.
mkdir -p "$RUN"
# tutorialStep:none: no movement tutorial popup over the top-right corner,
# where the Inventory HUD sits by default (it hid it in the screenshots).
[ -f "$RUN/options.txt" ] || printf 'onboardAccessibility:false\ntutorialStep:none\n' > "$RUN/options.txt"

if [ "$MODE" = prod ]; then
  : "${BREEZE_JAR:?BREEZE_JAR must name the jar under test}"
  mkdir -p "$RUN/mods"
  rm -f "$RUN"/mods/*.jar
  if [ "${BREEZE_ADDMODS:-}" = 1 ]; then
    mkdir -p "$RUN/breeze-runtime"
    cp "$BREEZE_JAR" "$RUN/breeze-runtime/"
  else
    cp "$BREEZE_JAR" "$RUN/mods/"
  fi
  [ -n "${BREEZE_MODS:-}" ] && cp "$BREEZE_MODS"/*.jar "$RUN/mods/"
  echo "[run] real install at $RUN, mods:" | tee -a "$OUT/driver.log"
  (cd "$RUN/mods" && sha256sum ./*.jar) | tee -a "$OUT/driver.log"
  version="fabric:$MC${BREEZE_LOADER:+:$BREEZE_LOADER}"
  jvm="-Xmx2G -Dbreeze.autotest=$OUT -Dbreeze.autotest.menuTimeoutSeconds=900"
  [ -n "${MCEF_LIBRARIES:-}" ] && jvm="$jvm -Dmcef.libraries.path=$MCEF_LIBRARIES"
  if [ "${BREEZE_ADDMODS:-}" = 1 ]; then
    jvm="$jvm -Dfabric.addMods=$RUN/breeze-runtime/$(basename "$BREEZE_JAR")"
    echo "[run] Breeze handed over with -Dfabric.addMods, as the launcher does" | tee -a "$OUT/driver.log"
  fi
  # A stand-in for the Breeze API (stub-api.mjs) and a game token for a test
  # player, so the in-world test can equip a 3D cosmetic from the Wardrobe and
  # see it drawn. The token is unsigned and only the stub reads it. Turn off
  # with BREEZE_STUB_API=0 to talk to the real API, signed out.
  if [ "${BREEZE_STUB_API:-1}" != 0 ] && command -v node >/dev/null; then
    rm -f "$OUT/stub-port"
    # A few real cosmetics from the public catalogue (read only). Kept in the
    # game folder, which the evidence artifact leaves out: only screenshots
    # of them are published, never the creators' model files.
    REAL_MODELS="$RUN/real-models"
    node "$(dirname "$0")/fetch-cosmetics.mjs" "$REAL_MODELS" 2>&1 | tee -a "$OUT/driver.log" || true
    node "$(dirname "$0")/stub-api.mjs" "$OUT" "$REAL_MODELS" > "$OUT/stub-api.out" 2>&1 &
    STUB=$!
    for _ in $(seq 1 100); do [ -s "$OUT/stub-port" ] && break; sleep 0.1; done
    if [ -s "$OUT/stub-port" ]; then
      b64url() { printf '%s' "$1" | base64 -w0 | tr '+/' '-_' | tr -d '='; }
      stub_uuid=7e57b2ee-0000-4000-8000-00000000b2ee
      stub_exp=$(( $(date +%s) + 3 * 3600 ))
      stub_token="$(b64url '{"alg":"none","typ":"JWT"}').$(b64url "{\"uuid\":\"$stub_uuid\",\"aud\":\"breeze-game\",\"exp\":$stub_exp}").test"
      printf '{"token":"%s"}' "$stub_token" > "$OUT/stub-session.json"
      jvm="$jvm -Dbreeze.api.url=http://127.0.0.1:$(cat "$OUT/stub-port") -Dbreeze.session.file=$OUT/stub-session.json"
      jvm="$jvm -Dbreeze.player.uuid=$stub_uuid -Dbreeze.autotest.stub=1"
      echo "[run] stand-in API on port $(cat "$OUT/stub-port")" | tee -a "$OUT/driver.log"
    else
      echo "[run] the stand-in API did not start; the test uses the real API" | tee -a "$OUT/driver.log"
    fi
  fi
  portablemc --main-dir "${PORTABLEMC_MAIN:-$HOME/.minecraft}" --work-dir "$RUN" \
    start "$version" -u BreezeDev --resolution 1280x720 --jvm-args="$jvm" \
    > "$OUT/launcher.log" 2>&1 &
else
  ./gradlew --no-daemon ":versions:$MC:runClient" -Pbreeze.autotest="$OUT" > "$OUT/runclient.log" 2>&1 &
fi
GAME=$!
export BREEZE_GAME_PID=$GAME

scripts/ci/drive-minecraft.sh "$OUT"
DRIVER_EXIT=$?

# The driver's last step quits through the menu; give the game time to exit.
for _ in $(seq 1 60); do kill -0 "$GAME" 2>/dev/null || break; sleep 1; done
if kill -0 "$GAME" 2>/dev/null; then
  echo "[run] game still running after Quit; stopping it" | tee -a "$OUT/driver.log"
  pkill -f 'net[.]fabricmc[.](devlaunchinjector[.]Main|loader[.]impl[.]launch[.]knot[.]KnotClient)' || true
  kill "$GAME" 2>/dev/null || true
  DRIVER_EXIT=$((DRIVER_EXIT + 1))
else
  echo "[run] game exited on its own after Quit" | tee -a "$OUT/driver.log"
fi

kill -INT "$FFMPEG" 2>/dev/null; sleep 2
kill "$XVFB" 2>/dev/null
[ -n "${STUB:-}" ] && kill "$STUB" 2>/dev/null

cp "$RUN/logs/latest.log" "$OUT/minecraft-latest.log" 2>/dev/null || true
mkdir -p "$OUT/in-game-screenshots"
cp "$RUN"/screenshots/*.png "$OUT/in-game-screenshots/" 2>/dev/null || true
grep -hE 'Exception|ERROR|FATAL' "$OUT/minecraft-latest.log" > "$OUT/errors.txt" 2>/dev/null || true

# A digest in the job's own output, so a failure can be read without
# downloading the evidence: what the harness saw, and what Breeze, MCEF and
# the error lines in the game log said.
echo "== harness: screens, stages and failures"
grep -E '"event":"(start|screen|menu-open|READY_FOR_INPUT|key|FAIL|stress-result|AUTOTEST_DONE|mixin-audit|module)"' "$OUT/breeze-autotest.log" 2>/dev/null | head -80
echo "== harness: in the world"
grep -E '"event":"(world-open|world-joined|hud-check|hud-layout|module-sweep|settings-persist|cape-check|cape-image|cosmetic-check|cosmetic-setup|WORLD_READY|hud-moved|wardrobe-open|wardrobe-check|real-cosmetics-start|real-cosmetics)"' "$OUT/breeze-autotest.log" 2>/dev/null | head -20
echo "== harness: mouse presses (1.21.9 and later)"
grep '"event":"mouse"' "$OUT/breeze-autotest.log" 2>/dev/null | head -40
if [ "$MODE" = prod ]; then
  echo "== launcher (tail)"
  tail -25 "$OUT/launcher.log" 2>/dev/null
fi
for report in "$RUN"/crash-reports/*.txt; do
  [ -f "$report" ] || continue
  cp "$report" "$OUT/"
  echo "== crash report $(basename "$report") (head)"
  head -60 "$report"
done
echo "== game log: Loader, Breeze and MCEF"
grep -E 'Loading [0-9]+ mods|\[Breeze|MCEF|mcef' "$OUT/minecraft-latest.log" 2>/dev/null | head -60
echo "== game log: errors"
head -40 "$OUT/errors.txt" 2>/dev/null
for crash in "$RUN"/crash-reports/*.txt; do
  [ -f "$crash" ] || continue
  cp "$crash" "$OUT/"
  echo "== crash report $(basename "$crash") (head)"
  head -70 "$crash"
done
exit "$DRIVER_EXIT"
