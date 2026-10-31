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

# ── Keys: the menu's Keys page path, run by the self-test at the title ──
keys=$(grep '"event":"keys-check"' "$LOG" | tail -1)
say "keys: ${keys:-no keys-check event}"
case "$keys" in
  *'"pass":"true"'*) pass "a Breeze key was rebound to J the way the Keys page does it, listed as J, and put back" ;;
  *) fail "a Breeze key was rebound to J the way the Keys page does it, listed as J, and put back" ;;
esac

# ── Other mods (run with them: EXPECT_MODMENU=1 when Mod Menu is among them) ──
ints=$(grep '"event":"integrations"' "$LOG" | tail -1)
say "integrations: $ints"
printf '%s' "$ints" | jq -r '.problems[]? | "integration problem: \(.mod): \(.detail)"' 2>/dev/null | while read -r line; do say "$line"; done
if [ "${EXPECT_MODMENU:-0}" = 1 ]; then
  case "$ints" in
    *'"id":"modmenu"'*) pass "Breeze found Mod Menu through its API" ;;
    *) fail "Breeze found Mod Menu through its API" ;;
  esac
  opened=$(grep '"event":"integration-open"' "$LOG" | grep '"id":"modmenu"' | tail -1)
  say "Mod Menu opened: $opened"
  case "$opened" in
    *'"pass":"true"'*'terraformersmc'*|*'terraformersmc'*'"pass":"true"'*) pass "the menu's Mod Menu entry opened Mod Menu's own list" ;;
    *) fail "the menu's Mod Menu entry opened Mod Menu's own list" ;;
  esac
fi
if [ "${EXPECT_TITLE_BUTTONS:-0}" = 1 ]; then
  case "$ints" in
    *'"provider":"title-screen"'*) pass "Breeze offers the buttons other mods put on the title screen" ;;
    *) fail "Breeze offers the buttons other mods put on the title screen" ;;
  esac
  # Which mod each button was credited to (the menu groups them by it); a
  # button a mixin added inside the title screen cannot be told and shows as
  # "unknown", which the menu files under "Title screen buttons".
  printf '%s' "$ints" | jq -r '.integrations[]? | select(.provider == "title-screen") | .actions[]? | "title screen button owner: \(.label) -> \(.mod // "unknown")"' 2>/dev/null | while read -r line; do say "$line"; done
  pressed=$(grep '"event":"integration-open"' "$LOG" | grep '"id":"title:' | tail -1)
  say "title screen button pressed: $pressed"
  case "$pressed" in
    *'"pass":"true"'*) pass "a title screen button pressed from the Breeze menu opened its mod's screen" ;;
    *) fail "a title screen button pressed from the Breeze menu opened its mod's screen" ;;
  esac
fi
if [ "${EXPECT_MODMENU:-0}" != 1 ] && [ "${EXPECT_TITLE_BUTTONS:-0}" != 1 ]; then
  case "$ints" in
    *'"integrations":[]'*) pass "with no other mods, no integration entries are offered" ;;
    *) say "integrations offered: $ints" ;;
  esac
fi

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
  grep -qE '"event":"world-(loaded|joined)"' "$LOG" && break
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
# The self-test checks every HUD module and sweeps every module's settings
# before it opens the HUD editor (WORLD_READY): several minutes.
if wait_new '"event":"WORLD_READY"' 900 "$b"; then
  pass "a new singleplayer world loaded with Breeze"
  # Software rendered: comparable only with other runs on the same machines.
  fps=$(grep '"event":"fps-sample"' "$LOG" | tail -1)
  if [ -n "$fps" ]; then say "frame rate before the checks: $fps"; fi
else
  fail "a new singleplayer world loaded with Breeze (last screen: $(last_kind))"
  exit "$FAILED"
fi
shot 07-in-world

# ── What the self-test saw in the world ──────────────────────────────────
hud=$(grep '"event":"hud-check"' "$LOG" | tail -1)
say "hud: $hud"
case "$hud" in
  *'"pass":"true"'*) pass "every HUD module drew in the world without an error, none on the hotbar" ;;
  *) fail "a HUD module did not draw in the world, threw, or was put on the hotbar" ;;
esac
layout=$(grep '"event":"hud-layout"' "$LOG" | tail -1)
say "layout: $layout"
case "$layout" in
  *'"pass":"true"'*) pass "every HUD module was moved, saved, read back and drawn where it was put" ;;
  *) fail "a HUD module did not keep the place it was moved to" ;;
esac
sweep=$(grep '"event":"module-sweep"' "$LOG" | tail -1)
say "modules: $sweep"
case "$sweep" in
  *'"pass":"true"'*) pass "every module was switched on and every setting changed in the world, none threw" ;;
  *) fail "a module threw when switched on or when a setting changed" ;;
esac
persist=$(grep '"event":"settings-persist"' "$LOG" | tail -1)
say "settings saved: $persist"
case "$persist" in
  *'"pass":"true"'*) pass "changed settings were saved and read back" ;;
  *) fail "a changed setting did not survive saving and reading back" ;;
esac
icon=$(grep '"event":"tag-icon"' "$LOG" | tail -1)
say "tag icon: $icon"
case "$icon" in
  *'"pass":"true"'*) pass "every colour's Wind Charge tag picture is in the game's font" ;;
  *) fail "a Wind Charge tag picture is missing from the game's font (tags would show a box)" ;;
esac
font=$(grep '"event":"font-check"' "$LOG" | tail -1)
say "font: $font"
case "$font" in
  *'"pass":"true"'*) pass "Minecraft's own text draws (i is narrower than W): Breeze's font files did not replace the game's font" ;;
  *) fail "Minecraft's text draws as empty boxes: a font file replaced the game's own font" ;;
esac
cape=$(grep '"event":"cape-check"' "$LOG" | tail -1)
say "cape: $cape"
case "$cape" in
  *'"pass":"true"'*) pass "the Breeze cape is the cape Minecraft draws, and the cape layer ran" ;;
  *) fail "the Breeze cape did not reach Minecraft's cape layer" ;;
esac
# With the stand-in API, the account has a cape equipped while the cosmetic
# state answers "no account", as the live API did for every player until its
# fix. That cape must be the one drawn, not the local Custom Cape (2.9.5).
case "$cape" in
  *'"stub":"true"'*)
    case "$cape" in
      *'"source":"account"'*) pass "the account's equipped cape is drawn though the cosmetic state could not read the account" ;;
      *) fail "the account's equipped cape was not drawn (a state without an account hid it)" ;;
    esac
    # Then the account switches to a personal cape (an upload, served on
    # /cape/:uuid). It must replace the catalogue cape without a restart.
    personal=$(grep '"event":"cape-personal-check"' "$LOG" | tail -1)
    say "personal cape: $personal"
    case "$personal" in
      *'"pass":"true"'*) pass "a personal cape replaced the catalogue cape and is the cape Minecraft draws" ;;
      *) fail "the account's personal cape was not drawn" ;;
    esac ;;
esac
cosmetic=$(grep '"event":"cosmetic-check"' "$LOG" | tail -1)
say "cosmetic: $cosmetic"
case "$cosmetic" in
  *'"pass":"true"'*) pass "3D cosmetics (GLBs built by the test: a hat, a flying pet, a trail) were drawn, none threw" ;;
  *) fail "a 3D cosmetic was not drawn on the player, or threw while drawing" ;;
esac
armor=$(grep '"event":"armor-check"' "$LOG" | tail -1)
say "armor: $armor"
case "$armor" in
  *'"pass":"true"'*) pass "the Armor HUD drew four armour pieces, the sword and the shield, the worn chestplate in red" ;;
  *) fail "the Armor HUD did not draw the worn gear as it should" ;;
esac
hotbar=$(grep '"event":"armor-hotbar-check"' "$LOG" | tail -1)
say "armor hotbar: $hotbar"
case "$hotbar" in
  *'"pass":"true"'*) pass "the Armor HUD's Hotbar look drew the six pieces in slots cut from this version's own hotbar picture" ;;
  *) fail "the Armor HUD's Hotbar look did not find the hotbar picture or did not draw the six pieces" ;;
esac
row=$(grep '"event":"armor-hotbar-row-check"' "$LOG" | tail -1)
say "armor hotbar row: $row"
case "$row" in
  *'"pass":"true"'*) pass "the Hotbar look as a row of the four armour pieces beside the hotbar, Minecraft's own durability bars, no words" ;;
  *) fail "the Hotbar look's row beside the hotbar was not drawn as it should be" ;;
esac
inventory=$(grep '"event":"inventory-check"' "$LOG" | tail -1)
say "inventory: $inventory"
case "$inventory" in
  *'"pass":"true"'*) pass "the Inventory HUD drew the real inventory (16 slots, counts, armour, off hand) at its default place above the hotbar, clear of Minecraft's pop-ups, the totem, held item and armour modules drew Minecraft's own items and icons" ;;
  *) fail "the Inventory HUD or an item module did not draw the player's real items or Minecraft's pictures" ;;
esac
outline=$(grep '"event":"block-outline-check"' "$LOG" | tail -1)
say "block outline: $outline"
case "$outline" in
  *'"skipped"'*) say "SKIP: $outline" ;;
  *'"pass":"true"'*) pass "Block Overlay outlined the stairs by their real shape (two boxes) with fill and width, then as a full square, in place of Minecraft's outline" ;;
  *) fail "Block Overlay did not outline the stairs by their shape and then as a full square" ;;
esac
ghost=$(grep '"event":"ghost-check"' "$LOG" | tail -1)
say "ping ghost: $ghost"
case "$ghost" in
  *'"skipped"'*) say "SKIP: $ghost" ;;
  *'"pass":"true"'*) pass "Ping Ghost drew its see-through figure" ;;
  *) fail "Ping Ghost did not draw its figure" ;;
esac
freelook=$(grep '"event":"freelook-check"' "$LOG" | tail -1)
say "free look: $freelook"
shape=$(grep '"event":"camera-shape"' "$LOG" | tail -1)
[ -n "$shape" ] && say "camera: $shape"
case "$freelook" in
  *'"skipped"'*) say "SKIP: $freelook" ;;
  *'"pass":"true"'*) pass "Free Look turned the camera, not the player, in third person while its key was held, and put the camera and the mouse back when it was let go" ;;
  '') fail "the self-test checked Free Look" ;;
  *) fail "Free Look did not turn the camera alone and restore it" ;;
esac
crosshair=$(grep '"event":"crosshair-check"' "$LOG" | tail -1)
say "crosshair: $crosshair"
case "$crosshair" in
  *'"skipped"'*) say "SKIP: $crosshair" ;;
  *'"pass":"true"'*) pass "the Custom Crosshair took a share code's settings, drew them in the world, gave the same code back and refused a damaged one" ;;
  '') fail "the self-test checked the Custom Crosshair" ;;
  *) fail "the Custom Crosshair did not take, draw or give back a share code" ;;
esac
tnt=$(grep '"event":"tnt-check"' "$LOG" | tail -1)
say "tnt: $tnt"
case "$tnt" in
  *'"skipped"'*) say "SKIP: $tnt" ;;
  *'"pass":"true"'*) pass "TNT Countdown labelled a lit TNT with the seconds its fuse has left" ;;
  '') fail "the self-test checked TNT Countdown" ;;
  *) fail "TNT Countdown did not label a lit TNT with its seconds" ;;
esac
hbox=$(grep '"event":"hitbox-check"' "$LOG" | tail -1)
say "hitboxes: $hbox"
case "$hbox" in
  *'"skipped"'*) say "SKIP: $hbox" ;;
  *'"pass":"true"'*) pass "Hitboxes drew the player's own box, as a player's, in third person" ;;
  '') fail "the self-test checked Hitboxes" ;;
  *) fail "Hitboxes drew no box for the player in third person" ;;
esac
sboard=$(grep '"event":"scoreboard-check"' "$LOG" | tail -1)
say "scoreboard: $sboard"
case "$sboard" in
  *'"skipped"'*) say "SKIP: $sboard" ;;
  *'"pass":"true"'*) pass "the Scoreboard drew the server's sidebar in Minecraft's order, with and without numbers, hid it, and gave it back to Minecraft when off" ;;
  '') fail "the self-test checked the Scoreboard" ;;
  *) fail "the Scoreboard did not draw the server's sidebar as Minecraft would, or did not hide or give it back" ;;
esac
packs=$(grep '"event":"packs-check"' "$LOG" | tail -1)
say "resource packs: $packs"
case "$packs" in
  *'"skipped"'*) say "SKIP: $packs" ;;
  *'"pass":"true"'*) pass "Resource packs from the menu reached Minecraft's own pack list and options, reloaded, and came off again" ;;
  '') fail "the self-test checked resource packs" ;;
  *) fail "Resource packs from the menu did not reach Minecraft's pack list and options, or did not come off again" ;;
esac
dname=$(grep '"event":"displayname-check"' "$LOG" | tail -1)
say "display name: $dname"
case "$dname" in
  *'"skipped"'*) say "SKIP: $dname" ;;
  *'"pass":"true"'*) pass "Display Name showed the chosen name for the player's own chat line and tab entry, and left a line that only spelled the real name alone" ;;
  '') fail "the self-test checked Display Name" ;;
  *) fail "Display Name did not show the chosen name where the player is identified, or changed text it should not" ;;
esac
icons=$(grep '"event":"module-icons"' "$LOG" | tail -1)
say "module icons: $icons"
case "$icons" in
  *'"pass":"true"'*) pass "every module found its Minecraft icon in this version's textures" ;;
  *) fail "a module's Minecraft icon is missing in this version" ;;
esac

# ── HUD editor: drag three kinds of element with the real mouse, Done ──
# FPS (text), Keystrokes (boxes it draws itself) and the Inventory HUD
# (anchored to the right edge, under the editor's panel until H hides it).
drag_el() { # name-regex dx dy
  local xy
  xy=$(where "$(fresh_targets hud-editor)" "$1")
  if [ -z "$xy" ]; then
    fail "the HUD editor shows the element $1"
    return
  fi
  set -- $xy "$2" "$3"
  say "drag from $1 $2 by $3 $4"
  xdotool mousemove --window "$WID" "$1" "$2"
  sleep 0.3
  xdotool mousedown 1
  for i in 1 2 3 4 5 6 7 8 9 10; do
    xdotool mousemove --window "$WID" "$(( $1 + i * $3 / 10 ))" "$(( $2 + i * $4 / 10 ))"
    sleep 0.05
  done
  sleep 0.2
  xdotool mouseup 1
  sleep 0.5
}
if [ -z "$(where "$(fresh_targets hud-editor)" '^hud-fps$')" ]; then
  fail "the HUD editor shows the FPS element"
else
  pass "the HUD editor shows the FPS element"
  drag_el '^hud-fps$' 250 150
  drag_el '^hud-keystrokes$' 250 -150
  # The panel covers the top-right corner; H hides it.
  xdotool key h
  sleep 0.8
  drag_el '^hud-inventory hud$' -300 150
  shot 08-hud-editor-dragged
  b=$(count '"event":"hud-moved"')
  # Enter is Done, with the panel hidden as well.
  xdotool key Return
  if wait_new '"event":"hud-moved"' 10 "$b"; then
    moved=$(grep '"event":"hud-moved"' "$LOG" | tail -1)
    say "moved: $moved"
    case "$moved" in
      *'"pass":"true"'*) pass "drags in the HUD editor moved FPS, Keystrokes and the Inventory HUD, and Done saved them" ;;
      *) fail "a drag in the HUD editor did not move and save its element" ;;
    esac
  else
    fail "the HUD editor closed with Done"
  fi
fi

# ── Wardrobe: equip a 3D cosmetic with the real mouse (stand-in API) ─────
# Only with the stand-in API (run-minecraft-test.sh starts it): the self-test
# opens the Wardrobe on its 3D tab after the HUD editor; a click on the test
# hat must equip it through the API, and the game must fetch and draw it.
if [ -s "$OUT/stub-port" ]; then
  if wait_new '"event":"wardrobe-open"' 15 0; then
    sleep 3
    t=$(fresh_targets wardrobe)
    xy=$(where "$t" '^wardrobe-3d-stub-hat$')
    if [ -z "$xy" ]; then
      fail "the Wardrobe's 3D tab lists the test hat the API says the player owns"
    else
      b=$(count '"event":"wardrobe-check"')
      # shellcheck disable=SC2086
      click_at $xy
      shot 09-wardrobe-clicked 2
      if wait_new '"event":"wardrobe-check"' 70 "$b"; then
        w=$(grep '"event":"wardrobe-check"' "$LOG" | tail -1)
        say "wardrobe: $w"
        case "$w" in
          *'"pass":"true"'*) pass "a click in the Wardrobe equipped a 3D cosmetic through the API, and it was drawn on the player" ;;
          *) fail "the Wardrobe click did not equip and draw the 3D cosmetic" ;;
        esac
      else
        fail "the self-test checked the Wardrobe"
      fi
    fi
    # Then it shows the Tags tab (autotest-wardrobe-tags.png) and closes the
    # Wardrobe.
    if wait_new '"event":"wardrobe-tags"' 30 0; then
      t=$(grep '"event":"wardrobe-tags"' "$LOG" | tail -1)
      say "wardrobe tags: $t"
      case "$t" in
        *'"pass":"true"'*) pass "the Wardrobe's Tags tab listed the account's tags, each with its Wind Charge picture" ;;
        *) fail "the Wardrobe's Tags tab listed no tags" ;;
      esac
    else
      fail "the self-test showed the Wardrobe's Tags tab"
    fi
    i=0; while [ "$i" -lt 10 ] && [ "$(last_kind)" != none ]; do sleep 1; i=$((i + 1)); done
  else
    fail "the self-test opened the Wardrobe"
  fi

  # Real cosmetics from the public catalogue, worn by the test player (one
  # per slot at a time, in rounds of up to 90 s each): each must be read and
  # drawn. Skipped (not failed) when none could be fetched, since that is
  # the network or the catalogue, not the mod.
  if wait_new '"event":"real-cosmetics"' 420 0; then
    real=$(grep '"event":"real-cosmetics"' "$LOG" | tail -1)
    say "real cosmetics: $real"
    case "$real" in
      *'"pass":"skipped"'*) say "SKIP real cosmetics: none could be fetched from the public catalogue (see the [cosmetics] lines)" ;;
      *'"pass":"true"'*) pass "real cosmetics from the Breeze catalogue were read and drawn on the player" ;;
      *) fail "a real cosmetic from the Breeze catalogue was not read or drawn" ;;
    esac
  else
    fail "the self-test checked real cosmetics"
  fi
fi

# ── M and Right Shift in the world ──────────────────────────────────────
# Players saw a grey screen on Right Shift in a world: the self-test checks
# the menu's page started and drew (or the classic menu came up), with the
# real key. M first: the Music player also records the key code Minecraft
# gets for the display's Right Shift.
close_to_game() {
  local i=0
  xdotool key Escape
  while [ "$i" -lt 16 ]; do
    [ "$(last_kind)" = none ] && return 0
    sleep 0.5; i=$((i + 1))
  done
  return 1
}
press_rshift() { xdotool keydown Shift_R; sleep 0.3; xdotool keyup Shift_R; }
rkey=""
sleep 1
b=$(count '"event":"music-screen"')
xdotool key m
if wait_new '"event":"music-screen"' 20 "$b"; then
  pass "M opened the Music player in the world"
  shot 09-music 0.4
  k=$(count '"event":"screen-key"')
  press_rshift
  wait_new '"event":"screen-key"' 5 "$k" || true
  rkey=$(grep '"event":"screen-key"' "$LOG" | tail -1)
  say "Right Shift as Minecraft read it: ${rkey:-no key event}"
  close_to_game || fail "Escape closed the Music player back to the world"
else
  fail "M opened the Music player in the world"
fi
menu_in_world() { # how it was opened
  im=$(grep '"event":"ingame-menu"' "$LOG" | tail -1)
  say "menu in the world: $im"
  case "$im" in
    *'"pass":"true"'*) pass "$1 opened the Breeze menu in a world and it drew ($(printf '%s' "$im" | sed -E 's/.*"kind":"([^"]*)".*/\1/'))" ;;
    *) fail "$1 opened the Breeze menu in a world and it drew (grey screen)" ;;
  esac
  shot 08-menu-in-world 0.2
  close_to_game || fail "Escape closed the Breeze menu back to the world"
}
# The menu in the world from the pause screen's Breeze button, when the key
# itself cannot be tested here.
menu_from_pause() {
  local b xy t
  b=$(count '"event":"ingame-menu"')
  xdotool key Escape
  t=$(fresh_targets pause)
  xy=$(where "$t" '^Breeze$')
  if [ -z "$xy" ]; then
    fail "the pause screen offers the Breeze button"
  else
    # shellcheck disable=SC2086
    click_at $xy
    if wait_new '"event":"ingame-menu"' 40 "$b"; then
      menu_in_world "the pause screen's Breeze button"
    else
      fail "the pause screen's Breeze button opened the Breeze menu in a world"
    fi
  fi
}
sleep 1
b=$(count '"event":"ingame-menu"')
# rshift-raw: the self-test saw the game window itself report Right Shift
# held (GLFW; 26.3 reads SDL and reports nothing). The Music screen press
# above is the first one, which shows the window state can be read here.
r0=$(count '"event":"rshift-raw"')
press_rshift
if wait_new '"event":"ingame-menu"' 40 "$b"; then
  say "Right Shift as the window saw it: $(grep '"event":"rshift-raw"' "$LOG" | tail -1)"
  menu_in_world "Right Shift"
else
  say "menu key seen: $(grep '"event":"menu-key"' "$LOG" | tail -1)"
  case "$rkey" in
    *'"rshift":"false"'*)
      # Minecraft received a key, but not as Right Shift: the display's key
      # cannot reach the binding on this version, which is the test
      # display's keyboard, not Breeze. The menu is opened from the pause
      # screen's Breeze button instead, so the in-world check still runs.
      say "SKIP Right Shift: this Minecraft reads the test display's Right Shift as another key, so the key cannot be tested here (UNVERIFIED on this version)"
      menu_from_pause ;;
    *)
      if [ "$(count '"event":"rshift-raw"')" -gt "$r0" ]; then
        say "Right Shift as the window saw it: $(grep '"event":"rshift-raw"' "$LOG" | tail -1)"
        fail "Right Shift in a world opened the Breeze menu (the game window had the key)"
      else
        # Held for longer than the slowest frame here (2 fps), so the
        # window's state cannot miss it between two ticks.
        r1=$(count '"event":"rshift-raw"')
        b=$(count '"event":"ingame-menu"')
        xdotool keydown Shift_R; sleep 1.5; xdotool keyup Shift_R
        if wait_new '"event":"ingame-menu"' 40 "$b"; then
          # The window never had the short press, and the held key opened the
          # menu: the display lost the short one (1.18, run 41059757259; the
          # same code took the short press on 1.18.1 and 1.18.2). Had the
          # window had it, the check above would have failed.
          say "SKIP short Right Shift press: the game window never had it (its own key state), and a 1.5 s hold opened the menu; window: $(grep '"event":"rshift-raw"' "$LOG" | tail -1)"
          menu_in_world "Right Shift held for 1.5 s"
        elif [ "$(count '"event":"rshift-raw"')" -gt "$r1" ]; then
          say "Right Shift as the window saw it: $(grep '"event":"rshift-raw"' "$LOG" | tail -1)"
          fail "Right Shift held for 1.5 s in a world opened the Breeze menu (the game window had the key)"
        elif [ "$r0" -eq 0 ]; then
          fail "Right Shift in a world opened the Breeze menu (this version does not report the window's own key state, so the display cannot be ruled out)"
        else
          # The window reported Right Shift in the Music screen moments ago and
          # takes Escape afterwards, but never had this key held, even for
          # 1.5 s: the test display did not deliver it (seen in the 1.18
          # family, run 41029353398). Minecraft and Breeze never got it.
          say "SKIP Right Shift: the test display did not deliver Right Shift to the game window (its own key state never showed it held in the world, even for 1.5 s, though it did in the Music screen); UNVERIFIED on this version in this run"
          menu_from_pause
        fi
      fi ;;
  esac
fi

# A test player that died fails everything after it; say why, once.
if grep -q '"event":"player-died"' "$LOG"; then
  fail "the test player died in the world ($(grep '"event":"player-died"' "$LOG" | tail -1 | sed -E 's/.*"during":"([A-Z_]+)".*/during \1/'))"
fi

# ── Command keys: the self-test put /me on Ctrl+J; press the real keys ──
ckeys=$(grep '"event":"command-keys-check"' "$LOG" | tail -1)
say "command keys: $ckeys"
case "$ckeys" in
  *'"skipped"'*) say "SKIP: $ckeys" ;;
  *'"pass":"true"'*)
    pass "command keys asked the server's own command list: a command it does not offer was not sent and the player was told; a formatting code was refused"
    # The step before left the game in the world with no screen open; a
    # stray Escape here would open the pause menu, where command keys rest.
    [ "$(last_kind)" = none ] || close_to_game || true
    sleep 0.8
    b=$(count '"event":"command-key-ran"')
    bc=$(count '"event":"chat-line"')
    xdotool keydown Control_L; sleep 0.15; xdotool key j; sleep 0.15; xdotool keyup Control_L
    if wait_new '"event":"command-key-ran"' 6 "$b"; then
      say "ran: $(grep '"event":"command-key-ran"' "$LOG" | tail -1)"
      if wait_new '"event":"chat-line"' 8 "$bc"; then
        say "chat: $(grep '"event":"chat-line"' "$LOG" | tail -1)"
        pass "Ctrl+J, pressed for real, sent the command key's /me, and the game's server answered in chat"
        shot command-key 0.3
      else
        fail "the command key's /me was sent but no answer came back in chat"
      fi
    else
      fail "Ctrl+J, pressed for real, did not run the command key"
    fi ;;
  '') fail "the self-test checked command keys" ;;
  *) fail "command keys did not check commands against the server's list, or kept a refused command" ;;
esac

# ── Save and quit to the title screen ────────────────────────────────────
sleep 1
b=$(count '"kind":"pause"')
xdotool key Escape
if wait_new '"kind":"pause"' 8 "$b"; then
  # Breeze adds its Mods button a tick after Mod Menu would have added its own.
  sleep 0.6
  pause_targets=$(fresh_targets pause)
  mods_buttons=$(printf '%s' "$pause_targets" | jq '[.items[] | select(.name | test("^mods$"; "i"))] | length' 2>/dev/null || echo 0)
  if [ "${EXPECT_MODMENU:-0}" = 1 ]; then
    if [ "$mods_buttons" = 1 ]; then
      pass "the game menu has one Mods button (Mod Menu's own, or Breeze's when Mod Menu's is missing)"
    else
      fail "the game menu has one Mods button, found $mods_buttons"
    fi
  elif [ "$mods_buttons" != 0 ]; then
    fail "without Mod Menu the game menu has no Mods button, found $mods_buttons"
  fi
  shot pause-menu 0.4
  xy=$(where "$pause_targets" 'quit to title|disconnect')
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
