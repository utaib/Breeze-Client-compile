#!/usr/bin/env bash
# Drives the native Breeze menus (Minecraft versions with no embedded browser)
# with real X11 input and checks what the game did. Called by drive-minecraft.sh
# when the self-test reports READY_FOR_INPUT in native mode.
#
# Nothing here uses fixed pixel positions: the self-test logs where every
# widget and Breeze control is ("targets" events, window pixels), and clicks
# go to those. That keeps one driver working across Minecraft versions, whose
# title screens and GUI scales differ.
set -u
OUT="$1"
LOG="$OUT/breeze-autotest.log"
DRIVER="$OUT/driver.log"
FAILED=0

say() { echo "[driver] $*" | tee -a "$DRIVER"; }
pass() { say "PASS $*"; }
fail() { say "FAIL $*"; FAILED=$((FAILED + 1)); }
count() { grep -c -- "$1" "$LOG" 2>/dev/null || true; }

expect_new() { # description pattern timeout baseline
  local before="$4" i=0
  while [ "$i" -lt "$3" ]; do
    [ "$(count "$2")" -gt "$before" ] && { pass "$1"; return 0; }
    sleep 1; i=$((i + 1))
  done
  fail "$1 (no '$2' in the harness log)"
  return 1
}

shot() {
  sleep "${2:-1.2}"
  import -window root "$OUT/$1.png" && say "screenshot $1.png"
}

# The newest targets snapshot for a screen kind, asked for fresh.
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

# Pixel position of the first item whose name matches a pattern (jq regex,
# case-insensitive) in a targets line.
where() { # targets-json pattern
  printf '%s' "$1" | jq -r --arg re "$2" \
    '[.items[] | select(.active) | select(.name | test($re; "i"))][0] | select(. != null) | "\(.x) \(.y)"'
}

. "$(dirname "$0")/game-window.sh"
WID=$(game_window "$DRIVER")
[ -n "$WID" ] || { fail "no Minecraft window"; exit 98; }
xdotool windowactivate --sync "$WID" 2>/dev/null || xdotool windowfocus "$WID"
eval "$(xdotool getwindowgeometry --shell "$WID")"
say "window $WID at ${X},${Y} size ${WIDTH}x${HEIGHT}, native menus"
click_at() { xdotool mousemove --window "$WID" "$1" "$2"; sleep 0.25; xdotool click 1; }

# click <description> <screen kind> <pattern>: click the named control.
click() {
  local t xy
  t=$(fresh_targets "$2")
  xy=$(where "$t" "$3")
  if [ -z "$xy" ]; then
    fail "$1 (no control matching '$3' on the $2 screen)"
    return 1
  fi
  say "click '$3' at $xy"
  # shellcheck disable=SC2086
  click_at $xy
}

shot 01-title-menu 2
t=$(fresh_targets title)
[ -n "$(where "$t" '^breeze-button$')" ] && pass "Breeze's button is on Minecraft's title screen" \
  || fail "Breeze's button is missing from the title screen"

# The native Breeze menu, from its title screen button.
b=$(count '"kind":"breeze-native"')
click "open Breeze" title '^breeze-button$' \
  && expect_new "the Breeze button opened the native Breeze menu" '"kind":"breeze-native"' 8 "$b"
shot 02-breeze-menu

# Keyboard: the search field filters the module cards; then a click on the
# first card switches that module, which the harness sees from the module
# itself, not from the screen.
# A pause after the click before typing, as a person makes: on 26.3 keys
# become text only once the focused box has switched SDL's text input on, at
# the next frame, and a software-rendered frame here takes about 125 ms. The
# first key of "fps" typed straight after the click was lost that way (run
# 36854828253, the field held "ps").
click "focus search" breeze-native '^search$' && sleep 0.6 && xdotool type --delay 90 'fps'
sleep 0.5
typed=$(fresh_targets breeze-native | jq -r '[.items[] | select(.name | test("^search$"; "i")) | .value][0] // "(no search field)"')
if [ "$typed" = fps ]; then pass "typing reached the search field"; else fail "typing reached the search field (it holds '$typed')"; fi
shot 03-search-typed
b=$(count '"event":"module"')
click "switch the first module" breeze-native '^module-card-0$' \
  && expect_new "a click on a module card switched the module" '"event":"module"' 5 "$b"
shot 04-module-switched
b=$(count '"event":"module"')
click "switch it back" breeze-native '^module-card-0$' \
  && expect_new "a second click switched it back" '"event":"module"' 5 "$b"

# Escape closes the menu back to the title screen.
b=$(count '"kind":"title"')
xdotool key Escape
expect_new "Escape closed the Breeze menu back to the title screen" '"kind":"title"' 5 "$b"

# Singleplayer through Minecraft's own button: the world list, or Create World
# on a new game folder. Escape comes back.
b=$(count '"kind":"\(world-select\|create-world\)"')
click "Singleplayer" title '^singleplayer$' \
  && expect_new "Singleplayer opened world selection or Create World" '"kind":"\(world-select\|create-world\)"' 8 "$b"
shot 05-singleplayer
b=$(count '"kind":"title"')
xdotool key Escape
expect_new "Escape returned to the title screen" '"kind":"title"' 8 "$b"

# Hand over to the harness for 20 open and close cycles of the native menu.
touch "$OUT/driver-done"
i=0
while [ "$i" -lt 300 ] && ! grep -q '"event":"AUTOTEST_DONE"' "$LOG"; do sleep 1; i=$((i + 1)); done
if grep -q '"event":"AUTOTEST_DONE"' "$LOG"; then
  result=$(grep '"event":"stress-result"' "$LOG" | tail -1)
  say "stress: $result"
  case "$result" in
    *'"pass":"true"'*) pass "20 open and close cycles of the native menu" ;;
    *) fail "the open and close cycles reported a problem" ;;
  esac
else
  fail "the open and close cycles did not finish"
fi
shot 06-after-cycles 3

# In a world: HUD elements, the Custom Cape and the HUD editor (drive-world.sh),
# then back to the title screen.
"$(dirname "$0")/drive-world.sh" "$OUT" "$WID"
FAILED=$((FAILED + $?))

# Quit through Minecraft's own Quit button; run-minecraft-test.sh checks that
# the game process then exits.
click "Quit Game" title '^quit game$'

# Every Breeze mixin was applied up front by the self-test (Mixin's audit), so a
# mixin that does not fit this Minecraft version shows here even though its
# target only loads inside a world.
audit=$(grep '"event":"mixin-audit"' "$LOG" | tail -1)
case "$audit" in
  *'"ok":"true"'*) pass "every Breeze mixin applies on this Minecraft version" ;;
  '') fail "the mixin audit never ran" ;;
  *) fail "a Breeze mixin does not apply: $audit" ;;
esac

grep '"event":"FAIL"' "$LOG" | while read -r line; do fail "harness: $line"; done
say "done: $FAILED failed check(s)"
exit "$FAILED"
