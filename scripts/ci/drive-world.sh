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

# ── Other mods (run with them: EXPECT_MODMENU=1 when Mod Menu is among them) ──
ints=$(grep '"event":"integrations"' "$LOG" | tail -1)
say "integrations: $ints"
printf '%s' "$ints" | jq -r '.problems[]? | "integration problem: \(.mod): \(.detail)"' 2>/dev/null | while read -r line; do say "$line"; done
if [ "${EXPECT_MODMENU:-0}" = 1 ]; then
  case "$ints" in
    *'"id":"modmenu"'*) pass "Breeze found Mod Menu through its API" ;;
    *) fail "Breeze found Mod Menu through its API" ;;
  esac
  opened=$(grep '"event":"integration-open"' "$LOG" | tail -1)
  say "Mod Menu opened: $opened"
  case "$opened" in
    *'"pass":"true"'*'terraformersmc'*|*'terraformersmc'*'"pass":"true"'*) pass "the menu's Mod Menu entry opened Mod Menu's own list" ;;
    *) fail "the menu's Mod Menu entry opened Mod Menu's own list" ;;
  esac
else
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

# A test player that died fails everything after it; say why, once.
if grep -q '"event":"player-died"' "$LOG"; then
  fail "the test player died in the world ($(grep '"event":"player-died"' "$LOG" | tail -1 | sed -E 's/.*"during":"([A-Z_]+)".*/during \1/'))"
fi

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
