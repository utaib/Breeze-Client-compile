#!/usr/bin/env bash
# The recorder's second launch (BREEZE_WATCH=<recording> in run-minecraft-test.sh):
# the self-test watches the recording made in the first launch to its end and
# closes the game. This waits for that, then compares the moments it saw with
# the same moments while recording: the player's turn, and the screenshots
# (each watched picture must be closer to the recorded one of its own moment
# than to the other moment's). Usage: drive-watch.sh <out-dir> <game-dir>
set -u
OUT="$1"
RUN="$2"
LOG="$OUT/breeze-autotest.log"
DRIVER="$OUT/driver.log"
FAILED=0
: > "$DRIVER"
say() { echo "[driver] $*" | tee -a "$DRIVER"; }
pass() { say "PASS $*"; }
fail() { say "FAIL $*"; FAILED=$((FAILED + 1)); }

i=0
while [ "$i" -lt 400 ]; do
  grep -q '"event":"watch-done"' "$LOG" 2>/dev/null && break
  if [ -n "${BREEZE_GAME_PID:-}" ] && ! kill -0 "$BREEZE_GAME_PID" 2>/dev/null; then break; fi
  sleep 1; i=$((i + 1))
done
if [ -n "${BREEZE_EXPORT:-}" ]; then
  res=$(grep '"event":"export-check"' "$LOG" 2>/dev/null | tail -1)
  say "export: ${res:-no export-check event}"
  video=$(printf '%s' "$res" | jq -r .video 2>/dev/null)
  if [ -n "$video" ] && [ -s "$video" ]; then
    cp "$video" "$OUT/export.mp4"
    probe=$(ffprobe -v error -select_streams v:0 -count_frames -show_entries stream=codec_name,width,height,r_frame_rate,nb_read_frames -of csv=p=0 "$video")
    dur=$(grep '"event":"rec-check"' "${BREEZE_REC_LOG:-/dev/null}" | tail -1 | jq -r .durationMs)
    say "ffprobe: $probe (recording ${dur:-?} ms)"
    frames=$(printf '%s' "$probe" | awk -F, '{print $NF}')
    rate=$(printf '%s' "$probe" | awk -F, '{print $4}')
    want=$(python3 -c "import math; print(math.ceil(${dur:-0} * $BREEZE_EXPORT / 1000))")
    if [ "$rate" = "$BREEZE_EXPORT/1" ] && python3 -c "import sys; sys.exit(0 if abs($frames - $want) <= $BREEZE_EXPORT else 1)"; then
      pass "the recording was exported to an H.264 video at $BREEZE_EXPORT fps with a frame for every 1/$BREEZE_EXPORT s ($frames frames, $want expected)"
    else
      fail "the exported video's frames: $probe, $want expected"
    fi
    ffmpeg -v error -ss 3 -i "$video" -frames:v 1 "$OUT/export-3s.png" -y && say "frame at 3 s: export-3s.png"
  else
    fail "no video was exported: ${res:-no result}"
  fi
  exit "$FAILED"
fi
res=$(grep '"event":"watch-check"' "$LOG" 2>/dev/null | tail -1)
say "watch: ${res:-no watch-check event}"
case "$res" in
  *'"pass":"true"'*) pass "the recording opened from the title screen as a world and played to its end, every packet accepted" ;;
  *) fail "watching the recording: ${res:-it never finished}" ;;
esac
case "$(grep '"event":"watch-bar"' "$LOG" | tail -1)" in
  *'"screen":"true"'*) pass "the playback bar opened over the playing recording" ;;
  *) [ -z "${BREEZE_WATCH_PACK:-}" ] && fail "the playback bar did not open" ;;
esac
case "$(grep '"event":"watch-freecam"' "$LOG" | tail -1)" in
  *'"pass":"true"'*) pass "the free camera held the viewer's own place while the recording played, and Player view gave the camera back" ;;
  *) [ -z "${BREEZE_WATCH_PACK:-}" ] && fail "the free camera: $(grep '"event":"watch-freecam"' "$LOG" | tail -1)" ;;
esac
cp "$RUN"/screenshots/rec-*.png "$RUN"/screenshots/watch-*.png "$OUT/" 2>/dev/null
REC_LOG="${BREEZE_REC_LOG:-}"
# The watched moment is sampled on the first tick at or after 3 s and 6 s,
# which on a slow machine can be half a second late; the player turns at a
# steady rate while recording, so each watched turn is compared with the
# recorded turn at the moment it was actually sampled (interpolated between
# the two recorded moments).
r3=$(grep '"event":"rec-moment"' "$REC_LOG" 2>/dev/null | grep '"rec-3s"' | tail -1)
r6=$(grep '"event":"rec-moment"' "$REC_LOG" 2>/dev/null | grep '"rec-6s"' | tail -1)
for m in 3s 6s; do
  w=$(grep '"event":"watch-moment"' "$LOG" | grep "\"watch-$m\"" | tail -1)
  res=$(python3 - "$r3" "$r6" "$w" <<'PY' 2>/dev/null
import json, sys
try:
    a, b, w = (json.loads(x) for x in sys.argv[1:4])
    ry3, ry6, rm3, rm6 = float(a['yaw']), float(b['yaw']), float(a['ms']), float(b['ms'])
    wy, wm = float(w['yaw']), float(w['ms'])
    rate = (ry6 - ry3) / (rm6 - rm3) if rm6 != rm3 else 0.0
    want = ry3 + rate * (wm - rm3)
    d = abs((wy - want + 180) % 360 - 180)
    print(f"{d:.1f} {wy:.1f} {want:.1f} {wm:.0f}")
except Exception:
    print("")
PY
)
  set -- $res
  if [ -n "${1:-}" ] && python3 -c "import sys; sys.exit(0 if $1 <= 8 else 1)"; then
    pass "at $m the watched player turned as recorded (yaw $2 at $4 ms, recorded $3 then: $1 degrees apart)"
  else
    fail "at $m the watched player's turn differs from the recording (${res:-no moment})"
  fi
done
magenta_share() { # png: purple hue (red and blue both well above green), shaded or tinted as the world draws it
  convert "$1" -resize 320x -fx '(r>g+0.15&&b>g+0.15)?1:0' -format '%[fx:mean]' info: 2>/dev/null
}
if [ -n "${BREEZE_WATCH_SHADERS:-}" ]; then
  # Watched with Iris and a shader pack on: the same moments, drawn by the shaders.
  iris=$(grep -aiE 'iris.*(shader ?pack|using)|Using shaderpack|shaderpack' "$RUN/logs/latest.log" 2>/dev/null | head -3 | cut -c1-200)
  say "iris: ${iris:-no shader pack line in the game log}"
  if [ -f "$OUT/watch-3s.png" ] && [ -f "$BREEZE_WATCH_SHADERS/watch-3s.png" ]; then
    d=$(compare -metric RMSE <(convert "$OUT/watch-3s.png" -resize 320x180! png:-) <(convert "$BREEZE_WATCH_SHADERS/watch-3s.png" -resize 320x180! png:-) null: 2>&1 | sed -E 's/.*\(([0-9.e-]+)\).*/\1/')
    say "picture distance at 3 s, with shaders and without: $d"
    if [ -n "$iris" ] && python3 -c "import sys; sys.exit(0 if float('$d') > 0.04 else 1)"; then
      pass "the recording was drawn with the shader pack turned on now (Iris), not as it was recorded"
    else
      fail "the watched picture with shaders does not differ from the plain one, or Iris loaded no pack"
    fi
  else
    fail "the shaders watch took no picture at 3 s"
  fi
  exit "$FAILED"
fi
if [ -n "${BREEZE_WATCH_PACK:-}" ]; then
  # Watched with the magenta test pack on: the same moments, other colours.
  for m in 3s 6s; do
    r=$(magenta_share "$OUT/rec-$m.png"); w=$(magenta_share "$OUT/watch-$m.png")
    say "magenta share at $m: recorded ${r:-?}, watched with the test pack ${w:-?}"
    if [ -n "$r" ] && [ -n "$w" ] && python3 -c "import sys; sys.exit(0 if $w >= 0.05 and $w > $r * 5 + 0.02 else 1)"; then
      pass "at $m the recording was drawn with the resource pack chosen now, not the one it was recorded with"
    else
      fail "at $m the watched picture does not show the test pack's colours"
    fi
  done
  exit "$FAILED"
fi
dist() { compare -metric RMSE <(convert "$1" -resize 320x180! png:-) <(convert "$2" -resize 320x180! png:-) null: 2>&1 | sed -E 's/.*\(([0-9.e-]+)\).*/\1/'; }
if [ -f "$OUT/rec-3s.png" ] && [ -f "$OUT/watch-3s.png" ] && [ -f "$OUT/rec-6s.png" ] && [ -f "$OUT/watch-6s.png" ]; then
  s3=$(dist "$OUT/rec-3s.png" "$OUT/watch-3s.png"); s6=$(dist "$OUT/rec-6s.png" "$OUT/watch-6s.png")
  x3=$(dist "$OUT/rec-3s.png" "$OUT/watch-6s.png"); x6=$(dist "$OUT/rec-6s.png" "$OUT/watch-3s.png")
  say "picture distance (RMSE, 0 same): 3s $s3 (other moment $x3), 6s $s6 (other moment $x6)"
  if python3 -c "import sys; sys.exit(0 if $s3 < $x3 and $s6 < $x6 else 1)"; then
    pass "each watched moment looks like the same moment recorded, more than like the other moment"
  else
    fail "the watched pictures do not match their recorded moments"
  fi
else
  fail "the recorded and watched screenshots are not all there"
fi
exit "$FAILED"
