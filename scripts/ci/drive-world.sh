#!/usr/bin/env bash
# The in-world part of the test, run by drive-minecraft.sh and drive-native.sh
# after their menu checks and before they quit. Usage: drive-world.sh <out-dir>
# <window id>. Exits with the number of failed checks.
#
# The self-test (AutoTest) opens Singleplayer when asked (world-please); this
# script creates a world with Minecraft's own button, waits for the self-test's
# checks (HUD elements drawn, the Custom Cape being the cape Minecraft draws),
# drags the FPS element in the HUD editor with the real mouse, presses Done and
# checks the new place was saved, then saves and quits to the title screen.
set -u
OUT="$1"
WID="$2"
LOG="$OUT/breeze-autotest.log"
DRIVER="$OUT/driver.log"
FAILED=0

say() { echo "[driver] $*" | tee -a "$DRIVER"; }
pass() { say "PASS $*"; }
fail() { say "FAIL $*"; FAILED=$((FAILED + 1)); }
count() { grep -c -- "$1" "$LOG" 2>/dev/null || true; }
shot() { sleep "${2:-1.2}"; import -window root "$OUT/$1.png" && say "screenshot $1.png"; }
click_at() { xdotool mousemove --window "$WID" "$1" "$2"; sleep 0.25; xdotool click 1; }

wait_new() { # pattern seconds baseline
  local i=0
  while [ "$i" -lt "$2" ]; do
    [ "$(count "$1")" -gt "$3" ] && return 0
    sleep 1; i=$((i + 1))
  done
  return 1
}

fresh_targets() { # kind
  local b i=0
  b=$(count '"event":"targets"')
  touch "$OUT/targets-please"
  while [ "$i" -lt 10 ]; do
    [ "$(count '"event":"targets"')" -gt "$b" ] && break
    sleep 0.5; i=$((i + 1))
  done
  grep '"event":"targets"' "$LOG" | grep -- "\"kind\":\"$1\"" | tail -1
}

where() { # targets-json pattern
  printf '%s' "$1" | jq -r --arg re "$2" \
    '[.items[] | select(.active) | select(.name | test($re; "i"))][0] | select(. != null) | "\(.x) \(.y)"'
}

last_kind() { grep '"event":"screen"' "$LOG" | tail -1 | sed -E 's/.*"kind":"([^"]*)".*/\1/'; }

# ── Into a new world ─────────────────────────────────────────────────────
b=$(count '"event":"world-open"')
touch "$OUT/world-please"
if ! wait_new '"event":"world-open"' 15 "$b"; then
  fail "the self-test opened Singleplayer"
  exit "$FAILED"
fi
# Create New World is on the world list and on Create World itself; a new game
# folder may skip the list. Up to three presses, until the world starts loading.
for _ in 1 2 3; do
  sleep 2
  grep -q '"event":"world-joined"' "$LOG" && break
  kind=$(last_kind)
  case "$kind" in
    world-select|create-world) ;;
    *) continue ;;
  esac
  xy=$(where "$(fresh_targets "$kind")" '^create new world$')
  if [ -n "$xy" ]; then
    say "click 'Create New World' on $kind at $xy"
    # shellcheck disable=SC2086
    click_at $xy
  fi
done
b=$(count '"event":"WORLD_READY"')
if wait_new '"event":"WORLD_READY"' 300 "$b"; then
  pass "a new singleplayer world loaded with Breeze"
else
  fail "a new singleplayer world loaded with Breeze (last screen: $(last_kind))"
  exit "$FAILED"
fi
shot 07-in-world

# ── What the self-test saw in the world ──────────────────────────────────
hud=$(grep '"event":"hud-check"' "$LOG" | tail -1)
say "hud: $hud"
case "$hud" in
  *'"pass":"true"'*) pass "every checked HUD element drew in the world" ;;
  *) fail "a HUD element did not draw in the world" ;;
esac
cape=$(grep '"event":"cape-check"' "$LOG" | tail -1)
say "cape: $cape"
case "$cape" in
  *'"pass":"true"'*) pass "the Custom Cape is the cape Minecraft draws, and the cape layer ran" ;;
  *) fail "the Custom Cape did not reach Minecraft's cape layer" ;;
esac
cosmetic=$(grep '"event":"cosmetic-check"' "$LOG" | tail -1)
say "cosmetic: $cosmetic"
case "$cosmetic" in
  *'"pass":"true"'*) pass "a 3D cosmetic (a GLB hat built by the test) was drawn on the player" ;;
  *) fail "the 3D cosmetic was not drawn on the player" ;;
esac

# ── HUD editor: drag FPS with the real mouse, Done ───────────────────────
t=$(fresh_targets hud-editor)
xy=$(where "$t" '^hud-fps$')
if [ -z "$xy" ]; then
  fail "the HUD editor shows the FPS element"
else
  pass "the HUD editor shows the FPS element"
  set -- $xy
  say "drag FPS from $1 $2"
  xdotool mousemove --window "$WID" "$1" "$2"
  sleep 0.3
  xdotool mousedown 1
  for i in 1 2 3 4 5 6 7 8 9 10; do
    xdotool mousemove --window "$WID" "$(( $1 + i * 25 ))" "$(( $2 + i * 15 ))"
    sleep 0.05
  done
  sleep 0.2
  xdotool mouseup 1
  shot 08-hud-editor-dragged
  xy=$(where "$(fresh_targets hud-editor)" '^done$')
  b=$(count '"event":"hud-moved"')
  if [ -n "$xy" ]; then
    # shellcheck disable=SC2086
    click_at $xy
  else
    xdotool key Return
  fi
  if wait_new '"event":"hud-moved"' 10 "$b"; then
    moved=$(grep '"event":"hud-moved"' "$LOG" | tail -1)
    say "moved: $moved"
    case "$moved" in
      *'"pass":"true"'*) pass "a drag in the HUD editor moved the FPS element and Done saved it" ;;
      *) fail "the drag did not move and save the FPS element" ;;
    esac
  else
    fail "the HUD editor closed with Done"
  fi
fi

# ── Save and quit to the title screen ────────────────────────────────────
sleep 1
b=$(count '"kind":"pause"')
xdotool key Escape
if wait_new '"kind":"pause"' 8 "$b"; then
  xy=$(where "$(fresh_targets pause)" 'quit to title|disconnect')
  if [ -n "$xy" ]; then
    b=$(count '"kind":"\(title\|breeze-web\)"')
    # shellcheck disable=SC2086
    click_at $xy
    if wait_new '"kind":"\(title\|breeze-web\)"' 60 "$b"; then
      pass "Save and Quit returned to the title screen"
    else
      fail "Save and Quit returned to the title screen"
    fi
  else
    fail "the pause menu has Save and Quit"
  fi
else
  fail "Escape opened the pause menu"
fi
exit "$FAILED"
