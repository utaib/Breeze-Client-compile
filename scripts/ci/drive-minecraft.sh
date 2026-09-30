#!/usr/bin/env bash
# Drives a running Minecraft client with real X11 input and checks what the
# game did. Used by scripts/ci/run-minecraft-test.sh.
#
# Input goes through XTEST (xdotool without --window), the same path a
# physical mouse and keyboard take, into GLFW, then into MCEF's Chromium.
# Screenshots are of the actual X display the game renders to.
#
# Every expectation is checked against breeze-autotest.log, which the
# in-game harness (dev.breeze.devtest.AutoTest) writes: screen changes and
# the bridge actions the page sent. Results go to $OUT/driver.log; the exit
# code is the number of failed checks.
#
# Coordinates assume a 1280x720 window, where the interface scale is 1.05
# (1rem = 16.8 px): rail buttons are centred at x=45; Home, Singleplayer and
# Multiplayer at y=100, 154 and 209; Settings and Quit at y=622 and 677. These
# were measured in the production bundle at 1278x720 in Chrome 116.
set -u
OUT="$1"
LOG="$OUT/breeze-autotest.log"
DRIVER="$OUT/driver.log"
FAILED=0
: > "$DRIVER"

say() { echo "[driver] $*" | tee -a "$DRIVER"; }
pass() { say "PASS $*"; }
fail() { say "FAIL $*"; FAILED=$((FAILED + 1)); }

wait_for() { # pattern timeout_seconds
  local i=0
  while [ "$i" -lt "$2" ]; do
    grep -q -- "$1" "$LOG" 2>/dev/null && return 0
    sleep 1; i=$((i + 1))
  done
  return 1
}

count() { grep -c -- "$1" "$LOG" 2>/dev/null || true; }

expect_new() { # description pattern timeout, counts matches after a baseline
  local before="$4" i=0
  while [ "$i" -lt "$3" ]; do
    [ "$(count "$2")" -gt "$before" ] && { pass "$1"; return 0; }
    sleep 1; i=$((i + 1))
  done
  fail "$1 (no '$2' in the harness log)"
}

shot() {
  sleep "${2:-1.2}"
  import -window root "$OUT/$1.png" && say "screenshot $1.png"
}

# Stops early when the harness reports a failure, rather than waiting out the
# full timeout for a menu it has already said will not come.
wait_ready() { # timeout_seconds
  local i=0
  while [ "$i" -lt "$1" ]; do
    grep -q '"event":"READY_FOR_INPUT"' "$LOG" 2>/dev/null && return 0
    grep -q '"event":"FAIL"' "$LOG" 2>/dev/null && return 1
    sleep 1; i=$((i + 1))
  done
  return 1
}

say "waiting for the Breeze title menu to paint"
if ! wait_ready 1200; then
  fail "the menu never became ready"
  grep '"event":"FAIL"' "$LOG" | tee -a "$DRIVER"
  exit 99
fi
pass "Breeze title menu painted in a real Minecraft client"

WID=$(xdotool search --onlyvisible --name 'Minecraft' | head -1)
[ -n "$WID" ] || { fail "no Minecraft window"; exit 98; }
xdotool windowactivate --sync "$WID" 2>/dev/null || xdotool windowfocus "$WID"
eval "$(xdotool getwindowgeometry --shell "$WID")"
say "window $WID at ${X},${Y} size ${WIDTH}x${HEIGHT}"
click() { xdotool mousemove --window "$WID" "$1" "$2"; sleep 0.25; xdotool click 1; }
shot 01-title-menu 2

# Keyboard: Tab through the rail (Home, Singleplayer, Multiplayer, Mods) and
# press Enter. The Mods page focuses its search field, so typing filters.
b=$(count '"route":"mods/modules"')
xdotool key Tab Tab Tab Tab Return
expect_new "keyboard Tab and Enter opened Mods" '"route":"mods/modules"' 8 "$b"
shot 02-mods
xdotool type --delay 90 'armor'
shot 03-mods-typed-search

# Mouse: click Settings in the rail.
b=$(count '"route":"settings"')
click 45 622
expect_new "mouse click opened Settings" '"route":"settings"' 8 "$b"
shot 04-settings

# Wheel: scroll the settings page.
xdotool mousemove --window "$WID" 700 400
for _ in 1 2 3 4 5 6; do xdotool click 5; sleep 0.1; done
shot 05-settings-scrolled

# Escape is answered by the page: back to Home, and acknowledged as handled.
b=$(count '"handled":true')
xdotool key Escape
expect_new "Escape went back and the page acknowledged it" '"handled":true' 5 "$b"
shot 06-home

# Singleplayer opens Minecraft's own flow: the world list, or Create World
# directly when there are no worlds yet, as on a new game folder. Both screens
# are in Minecraft's worldselection package.
b=$(count 'screens.worldselection.')
click 45 154
expect_new "Singleplayer opened Minecraft's world selection or Create World" 'screens.worldselection.' 8 "$b"
shot 07-select-world

# Minecraft's Back returns to Breeze, which opens a fresh browser.
b=$(count '"class":"dev.breeze.web.BreezeWebScreen"')
xdotool key Escape
expect_new "Back from world selection returned to the Breeze menu" '"class":"dev.breeze.web.BreezeWebScreen"' 8 "$b"
shot 08-back-to-menu 3

# Escape at the root of the title menu must not close anything.
b=$(count '"handled":false')
xdotool key Escape
expect_new "Escape at the title root was declined by the page" '"handled":false' 5 "$b"
sleep 1.5
last_screen=$(grep '"event":"screen"' "$LOG" | tail -1)
case "$last_screen" in
  *BreezeWebScreen*) pass "the title menu stayed open after Escape at the root" ;;
  *) fail "the title menu closed on Escape: $last_screen" ;;
esac

# Hand over to the harness for 20 open and close cycles.
touch "$OUT/driver-done"
if wait_for '"event":"AUTOTEST_DONE"' 300; then
  result=$(grep '"event":"stress-result"' "$LOG" | tail -1)
  say "stress: $result"
  case "$result" in
    *'"pass":"true"'*) pass "20 open and close cycles left no browser behind" ;;
    *) fail "browsers were left open after closing the menu" ;;
  esac
else
  fail "the open and close cycles did not finish"
fi
shot 09-after-cycles 3

# Quit through the menu itself: the Quit button asks, Enter confirms.
b=$(count '"action":"game.quit"')
click 45 677
shot 10-quit-dialog 1
xdotool key Return
expect_new "Quit, confirmed from the dialog, reached the game" '"action":"game.quit"' 8 "$b"

grep '"event":"FAIL"' "$LOG" | while read -r line; do fail "harness: $line"; done
say "done: $FAILED failed check(s)"
exit "$FAILED"
