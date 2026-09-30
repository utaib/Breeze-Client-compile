#!/usr/bin/env bash
# Launches the development Minecraft client with Breeze and MCEF on a virtual
# display, drives it with real X11 input, records the screen, and collects
# the evidence into $OUT. Software rendered (Mesa llvmpipe): this proves
# behaviour, not performance.
#
# Usage: scripts/ci/run-minecraft-test.sh <out-dir>   (from Breeze-Mod-New/)
set -u
OUT="$(realpath -m "${1:-autotest}")"
mkdir -p "$OUT"
rm -f "$OUT/driver-done" "$OUT/breeze-autotest.log"
export DISPLAY=:99

Xvfb :99 -screen 0 1280x800x24 -nolisten tcp > "$OUT/xvfb.log" 2>&1 &
XVFB=$!
sleep 2
openbox > "$OUT/openbox.log" 2>&1 &
sleep 1
glxinfo -B > "$OUT/glxinfo.txt" 2>&1 || true

ffmpeg -loglevel error -f x11grab -video_size 1280x800 -framerate 8 -i :99 -c:v libx264 -preset ultrafast -pix_fmt yuv420p "$OUT/session.mp4" &
FFMPEG=$!

# A new game folder opens on Minecraft's accessibility onboarding screen, not
# the title screen, and waits for a click on Continue. Start as a player who
# has seen it once. Only when there is no options.txt yet, so settings from an
# earlier run are never overwritten.
RUN=versions/1.20.1/run
mkdir -p "$RUN"
[ -f "$RUN/options.txt" ] || printf 'onboardAccessibility:false\n' > "$RUN/options.txt"

./gradlew --no-daemon :versions:1.20.1:runClient -Pbreeze.autotest="$OUT" > "$OUT/runclient.log" 2>&1 &
GAME=$!

scripts/ci/drive-minecraft.sh "$OUT"
DRIVER_EXIT=$?

# The driver's last step quits through the menu; give the game time to exit.
for _ in $(seq 1 60); do kill -0 "$GAME" 2>/dev/null || break; sleep 1; done
if kill -0 "$GAME" 2>/dev/null; then
  echo "[run] game still running after Quit; stopping it" | tee -a "$OUT/driver.log"
  pkill -f 'net.fabricmc.devlaunchinjector.Main' || true
  kill "$GAME" 2>/dev/null || true
  DRIVER_EXIT=$((DRIVER_EXIT + 1))
else
  echo "[run] game exited on its own after Quit" | tee -a "$OUT/driver.log"
fi

kill -INT "$FFMPEG" 2>/dev/null; sleep 2
kill "$XVFB" 2>/dev/null

cp "$RUN/logs/latest.log" "$OUT/minecraft-latest.log" 2>/dev/null || true
mkdir -p "$OUT/in-game-screenshots"
cp "$RUN"/screenshots/*.png "$OUT/in-game-screenshots/" 2>/dev/null || true
grep -hE 'Exception|ERROR|FATAL' "$OUT/minecraft-latest.log" > "$OUT/errors.txt" 2>/dev/null || true

# A digest in the job's own output, so a failure can be read without
# downloading the evidence: what the harness saw, and what Breeze, MCEF and
# the error lines in the game log said.
echo "== harness: screens, stages and failures"
grep -E '"event":"(start|screen|menu-open|READY_FOR_INPUT|key|FAIL|stress-result|AUTOTEST_DONE)"' "$OUT/breeze-autotest.log" 2>/dev/null | head -80
echo "== game log: Breeze and MCEF"
grep -E '\[Breeze|MCEF|mcef' "$OUT/minecraft-latest.log" 2>/dev/null | head -60
echo "== game log: errors"
head -40 "$OUT/errors.txt" 2>/dev/null
for crash in "$RUN"/crash-reports/*.txt; do
  [ -f "$crash" ] || continue
  cp "$crash" "$OUT/"
  echo "== crash report $(basename "$crash") (head)"
  head -70 "$crash"
done
exit "$DRIVER_EXIT"
