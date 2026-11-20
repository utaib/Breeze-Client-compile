# Sourced by the drivers: finds the game's X11 window.
#
# Up to 26.2 (GLFW) the game has one window. From 26.3 Minecraft opens it
# through SDL 3 and its renderer also creates unmapped helper windows whose
# titles contain "Minecraft" ("... Hidden Utility Window"). On 26.3 in CI,
# "xdotool search" also returned nothing at all while xwininfo listed the
# game's window (mirror run 36742834431), so the window tree comes from
# xwininfo: the largest mapped window whose title starts with "Minecraft" and
# does not say "Hidden", waiting up to 30 seconds for it. xdotool search is the
# fallback. On failure the tree is written to the driver log and the job log.

# Prints the decimal id of the best candidate in the current tree, if any.
_game_window_from_tree() {
  local line id geom w h area best="" best_area=0
  while IFS= read -r line; do
    case "$line" in *'"Minecraft'*) ;; *) continue ;; esac
    case "$line" in *Hidden*) continue ;; esac
    id=$(printf '%s\n' "$line" | grep -oE '0x[0-9a-f]+' | head -1)
    geom=$(printf '%s\n' "$line" | grep -oE '[0-9]+x[0-9]+[+-]' | head -1)
    [ -n "$id" ] && [ -n "$geom" ] || continue
    xwininfo -id "$id" 2>/dev/null | grep -q 'IsViewable' || continue
    w=${geom%%x*}; h=${geom#*x}; h=${h%[+-]}
    area=$((w * h))
    [ "$area" -gt "$best_area" ] && { best=$((id)); best_area=$area; }
  done < <(xwininfo -root -tree 2>/dev/null)
  [ -n "$best" ] && echo "$best"
}

_game_window_from_xdotool() {
  local w area best="" best_area=0
  for w in $(xdotool search --onlyvisible --name 'Minecraft' 2>/dev/null); do
    case "$(xdotool getwindowname "$w" 2>/dev/null)" in *Hidden*) continue ;; esac
    eval "$(xdotool getwindowgeometry --shell "$w" 2>/dev/null)" || continue
    area=$((WIDTH * HEIGHT))
    [ "$area" -gt "$best_area" ] && { best=$w; best_area=$area; }
  done
  [ -n "$best" ] && echo "$best"
}

game_window() { # driver-log
  local i=0 found
  while [ "$i" -lt 30 ]; do
    found=$(_game_window_from_tree)
    [ -n "$found" ] || found=$(_game_window_from_xdotool)
    [ -n "$found" ] && { echo "$found"; return 0; }
    sleep 1; i=$((i + 1))
  done
  {
    echo "[driver] xdotool search: $(xdotool search --name 'Minecraft' 2>&1 | head -3 | tr '\n' ' ')"
    echo "[driver] window tree:"; xwininfo -root -tree 2>&1 | grep -v '^$' | head -60 | sed 's/^/  /'
  } | tee -a "$1" >&2
  return 1
}
