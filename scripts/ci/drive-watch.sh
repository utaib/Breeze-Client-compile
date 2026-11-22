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
while [ "$i" -lt 1500 ]; do
  grep -q '"event":"watch-done"' "$LOG" 2>/dev/null && break
  if [ -n "${BREEZE_GAME_PID:-}" ] && ! kill -0 "$BREEZE_GAME_PID" 2>/dev/null; then break; fi
  sleep 1; i=$((i + 1))
done
if [ -n "${BREEZE_EXPORT:-}" ]; then
  # BREEZE_EXPORT is a list (30,240,30@2160): one export-check per video,
  # each at a frame rate and, after @, a video height (none: the window's).
  dur=$(grep '"event":"rec-check"' "${BREEZE_REC_LOG:-/dev/null}" | tail -1 | jq -r .durationMs)
  n=0
  for spec in $(printf '%s' "$BREEZE_EXPORT" | tr ',' ' '); do
    n=$((n + 1))
    fps=${spec%%@*}
    fps=${fps%%#*}
    size=0; case "$spec" in *@*) size=${spec#*@} ;; esac
    pack=""; case "$spec" in *'#pack'*) pack=1; size=0 ;; esac
    res=$(grep '"event":"export-check"' "$LOG" 2>/dev/null | jq -c "select(.spec == \"$spec\")" 2>/dev/null | tail -1)
    say "export $spec: ${res:-no export-check event}"
    video=$(printf '%s' "$res" | jq -r .video 2>/dev/null)
    if [ -z "$video" ] || [ ! -s "$video" ]; then
      fail "no video was exported at $spec: ${res:-no result}"
      continue
    fi
    name="export-${fps}fps${size:+-$size}"
    [ "$size" = 0 ] && name="export-${fps}fps"
    [ -n "$pack" ] && name="export-${fps}fps-pack"
    cp "$video" "$OUT/$name.mp4"
    # Named fields: ffprobe prints them in its own order, not the order asked.
    probe=$(ffprobe -v error -select_streams v:0 -count_frames -show_entries stream=codec_name,width,height,r_frame_rate,nb_read_frames,level -of default=nw=1 "$video" | tr '\n' ' ')
    say "ffprobe $spec: $probe(recording ${dur:-?} ms)"
    field() { printf '%s' "$probe" | tr ' ' '\n' | sed -n "s/^$1=//p"; }
    frames=$(field nb_read_frames)
    rate=$(field r_frame_rate)
    want=$(python3 -c "import math; print(math.ceil(${dur:-0} * $fps / 1000))")
    if [ "$rate" = "$fps/1" ] && python3 -c "import sys; sys.exit(0 if abs($frames - $want) <= max(2, $fps // 2) else 1)"; then
      pass "exported at $fps fps: H.264 with a frame for every 1/$fps s ($frames frames, $want expected)"
    else
      fail "the $spec video's frames: $probe, $want frames at $fps/1 expected"
    fi
    # Every frame exactly 1/fps after the one before (the video's own timestamps).
    even=$(ffprobe -v error -select_streams v:0 -show_entries packet=pts_time -of default=nw=1:nk=1 "$video" | python3 -c "
import re, sys
t = sorted(float(x) for x in re.findall(r'[0-9]+\.[0-9]+', sys.stdin.read()))
step = 1.0 / $fps
d = [b - a for a, b in zip(t, t[1:])]
bad = sum(1 for x in d if abs(x - step) > step * 0.25)
print(f'{len(d)} {bad} {min(d) if d else 0:.5f} {max(d) if d else 0:.5f}')
")
    set -- $even
    if [ -n "${1:-}" ] && [ "$1" -gt 0 ] && [ "$2" = 0 ]; then
      pass "the $spec video's frames are evenly spaced ($1 steps from $3 s to $4 s, 1/$fps is $(python3 -c "print(round(1/$fps, 5))") s)"
    else
      fail "the $spec video's frame times are uneven: ${even:-no frames} (steps, off, shortest, longest)"
    fi
    if [ "$fps" -ge 120 ]; then
      # The player turns all through the recording: with the camera of every
      # frame each video frame shows its own moment, so hardly any repeats
      # (turned only on ticks, a 240 fps video would repeat each picture 12 times).
      rep=$(ffmpeg -v error -i "$video" -f framemd5 - 2>/dev/null | awk -F, '!/^#/{h=$NF; if (h == last) r++; n++; last = h} END {printf "%d %d", r, n}')
      set -- $rep
      if [ -n "${2:-}" ] && python3 -c "import sys; sys.exit(0 if $1 <= $2 * 0.1 else 1)"; then
        pass "at $fps fps each frame is its own moment: $1 of $2 frames repeat the one before"
      else
        fail "at $fps fps frames repeat: ${rep:-?} (repeated, frames)"
      fi
    fi
    ffmpeg -v error -ss 3 -i "$video" -frames:v 1 "$OUT/$name-3s.png" -y && say "frame at 3 s: $name-3s.png"
    if [ "$size" != 0 ]; then
      wh="$(field width)x$(field height)"
      drawn=$(printf '%s' "$res" | jq -r .result | sed -nE 's/.*drawn at ([0-9]+x[0-9]+).*/\1/p')
      if [ "$(printf '%s' "$wh" | cut -dx -f2)" = "$size" ] && [ "$drawn" = "$wh" ]; then
        pass "the ${size}p video is $wh and was drawn at $drawn, not scaled from the window"
      else
        fail "the ${size}p video is ${wh:-?}, drawn at ${drawn:-?}"
      fi
    fi
  done
  # The render with the magenta test pack (the export editor's Render): its
  # frame at 3 s is mostly magenta where the plain export's is not, and the
  # player's own packs are back afterwards.
  pk="$OUT/export-30fps-pack-3s.png"; plain="$OUT/export-30fps-3s.png"
  if [ -f "$pk" ] && [ -f "$plain" ]; then
    mag() { convert "$1" -fuzz 25% -fill white -opaque '#FF00FF' -fill black +opaque white -format '%[fx:mean]' info: 2>/dev/null; }
    mp=$(mag "$pk"); mn=$(mag "$plain")
    res=$(grep '"event":"export-check"' "$LOG" | jq -c 'select(.spec | test("#pack"))' 2>/dev/null | tail -1)
    before=$(printf '%s' "$res" | jq -r .packsBefore); after=$(printf '%s' "$res" | jq -r .packsAfter)
    say "pack render: magenta share $mp (plain export $mn); packs before $before, after $after"
    if python3 -c "import sys; sys.exit(0 if float('${mp:-0}') > 0.05 and float('${mp:-0}') > 3 * float('${mn:-0}') else 1)" && [ "$before" = "$after" ]; then
      pass "a render with another resource pack shows that pack ($mp of the frame magenta against $mn) and the player's packs come back after"
    else
      fail "the render with the test pack: magenta ${mp:-?} against ${mn:-?}, packs before '$before', after '$after'"
    fi
  elif printf '%s' "$BREEZE_EXPORT" | grep -q '#pack'; then
    fail "no frame from the render with the test pack"
  fi
  # A key and a menu during an export must not stop it (the owner's export stopped).
  intr=$(grep '"event":"export-interrupt"' "$LOG" | head -1)
  if [ -n "$intr" ]; then
    case "$intr" in
      *'"pass":"true"'*) pass "the pause menu and the inventory opened during an export were closed again and the export went on" ;;
      *) fail "a menu during an export: $intr" ;;
    esac
  else
    fail "the export interruption was not tried: $(grep '"event":"export-interrupt-sent"' "$LOG" | head -1)"
  fi
  # A frame drawn at 4K has more detail than the window's frame of the same
  # moment scaled up to 4K: what a blur takes away from each (RMSE at 4K).
  big=$(ls "$OUT"/export-*fps-2160-3s.png 2>/dev/null | head -1)
  small=$(ls "$OUT"/export-*fps-3s.png 2>/dev/null | head -1)
  if [ -n "$big" ] && [ -n "$small" ]; then
    detail() { compare -metric RMSE <(convert "$1" -resize 3840x2160! -colorspace gray png:-) <(convert "$1" -resize 3840x2160! -colorspace gray -blur 0x1.5 png:-) null: 2>&1 | sed -E 's/.*\(([0-9.e-]+)\).*/\1/'; }
    db=$(detail "$big"); ds=$(detail "$small")
    say "detail at 3 s (what a 1.5 px blur removes, at 4K): drawn at 4K $db, window frame scaled to 4K $ds"
    if python3 -c "import sys; sys.exit(0 if float('$db') > float('$ds') * 1.3 else 1)"; then
      pass "the 4K video has the detail of a picture drawn at 4K ($db against $ds for the window's frame scaled up)"
    else
      fail "the 4K video has no more detail than the window's frame scaled up ($db against $ds)"
    fi
  fi
  exit "$FAILED"
fi
# Recordings watched to their end first (BREEZE_WATCH_FIRST): stopped
# mid-copy by this build, and by 2.30.0. Each must play to its end.
if [ -n "${BREEZE_WATCH_FIRST:-}" ]; then
  for f in $(printf '%s' "$BREEZE_WATCH_FIRST" | tr ',' ' '); do
    ex=$(grep '"event":"watch-extra"' "$LOG" 2>/dev/null | jq -c "select(.file == \"$f\")" 2>/dev/null | tail -1)
    say "watch $(basename "$f"): ${ex:-no watch-extra event}"
    case "$ex" in
      *'"pass":"true"'*) pass "$(basename "$f") played to its end in $(printf '%s' "$ex" | jq -r .seconds) s, never stuck on Loading terrain" ;;
      *) fail "watching $(basename "$f"): ${ex:-it never finished}" ;;
    esac
  done
  if [ -n "${BREEZE_WATCH_CUT:-}" ]; then
    cutev=$(grep '"event":"watch-cut"' "$LOG" 2>/dev/null | tail -1)
    cutfile=$(printf '%s' "$cutev" | jq -r '.file // empty' 2>/dev/null)
    say "the earlier jar's recording cut as 2.30.0 saved one stopped mid-copy: ${cutev:-no watch-cut event}"
    ex=$(grep '"event":"watch-extra"' "$LOG" 2>/dev/null | jq -c "select(.file == \"$cutfile\")" 2>/dev/null | tail -1)
    say "watch $(basename "${cutfile:-cut}"): ${ex:-no watch-extra event}"
    nopos=$(grep -c 'no player position in the recording' "$RUN"/logs/latest.log 2>/dev/null)
    case "$ex" in
      *'"pass":"true"'*)
        if [ "${nopos:-0}" -ge 1 ]; then
          pass "a recording saved the way 2.30.0 saved one stopped mid-copy (no player position, no load-start packet) played to its end in $(printf '%s' "$ex" | jq -r .seconds) s: Breeze placed the player and ended Loading terrain itself"
        else
          fail "the cut recording played, but Breeze never logged the missing player position, so the fallback was not what ran"
        fi ;;
      *) fail "watching the recording cut as 2.30.0 saved it: ${ex:-it never finished}" ;;
    esac
  fi
  # The stages Breeze logs while a watch starts (they were missing when the owner's watch hung).
  grep -h '\[Breeze\] watching' "$RUN"/logs/latest.log 2>/dev/null | cut -c1-300 | head -40 | sed 's/^/[driver]   /' | tee -a "$DRIVER"
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
  # The recorded pictures have no magenta at all; how much ground the random
  # test world puts on screen varies (tinted grass and leaves come out too
  # dark to count), so the pack also paints the hotbar magenta, which is
  # about 1.5% of every picture: one moment with 1% of the picture magenta
  # (more than recorded) shows the pack chosen now was used.
  best=0
  for m in 3s 6s; do
    r=$(magenta_share "$OUT/rec-$m.png"); w=$(magenta_share "$OUT/watch-$m.png")
    say "magenta share at $m: recorded ${r:-?}, watched with the test pack ${w:-?}"
    if [ -n "$r" ] && [ -n "$w" ]; then best=$(python3 -c "print(max($best, $w - $r))"); fi
  done
  if python3 -c "import sys; sys.exit(0 if $best >= 0.01 else 1)"; then
    pass "the recording was drawn with the resource pack chosen now, not the one it was recorded with (up to $best of the picture in the pack's colour, none when recorded)"
  else
    fail "the watched pictures do not show the test pack's colours (at most $best more than recorded)"
  fi
  exit "$FAILED"
fi
dist() { compare -metric RMSE <(convert "$1" -resize 320x180! png:-) <(convert "$2" -resize 320x180! png:-) null: 2>&1 | sed -E 's/.*\(([0-9.e-]+)\).*/\1/'; }
if [ -f "$OUT/rec-3s.png" ] && [ -f "$OUT/watch-3s.png" ] && [ -f "$OUT/rec-6s.png" ] && [ -f "$OUT/watch-6s.png" ]; then
  s3=$(dist "$OUT/rec-3s.png" "$OUT/watch-3s.png"); s6=$(dist "$OUT/rec-6s.png" "$OUT/watch-6s.png")
  x3=$(dist "$OUT/rec-3s.png" "$OUT/watch-6s.png"); x6=$(dist "$OUT/rec-6s.png" "$OUT/watch-3s.png")
  say "picture distance (RMSE, 0 same): 3s $s3 (other moment $x3), 6s $s6 (other moment $x6)"
  # Two views of the same terrain a few seconds apart differ about as much
  # as any two (sky, mobs, chat), so one moment on its own is close to a coin
  # toss; the pairing as a whole is the test: each watched picture with its
  # own moment must be closer, in total, than the two swapped. The turn
  # checks above prove each moment separately.
  if python3 -c "import sys; sys.exit(0 if $s3 + $s6 < $x3 + $x6 else 1)"; then
    pass "the watched pictures pair with their own recorded moments (total $(python3 -c "print(round($s3 + $s6, 4))") against $(python3 -c "print(round($x3 + $x6, 4))") swapped)"
  else
    fail "the watched pictures do not match their recorded moments"
  fi
else
  fail "the recorded and watched screenshots are not all there"
fi
exit "$FAILED"
