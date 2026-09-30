# Sourced by the drivers: finds the game's X11 window.
#
# Up to 26.2 (GLFW) the game has one window. From 26.3 Minecraft opens it
# through SDL 3 and its renderer also creates unmapped helper windows whose
# titles contain "Minecraft" ("... Hidden Utility Window"), so the largest
# mapped window whose title has "Minecraft" and not "Hidden" is taken, waiting
# up to 30 seconds for it to be mapped. On failure every top-level
# window with a title is written to the driver log with its map state and size.

game_window() { # driver-log
  local i=0 w best="" area best_area=0
  while [ "$i" -lt 30 ]; do
    for w in $(xdotool search --onlyvisible --name 'Minecraft' 2>/dev/null); do
      case "$(xdotool getwindowname "$w" 2>/dev/null)" in *Hidden*) continue ;; esac
      eval "$(xdotool getwindowgeometry --shell "$w" 2>/dev/null)" || continue
      area=$((WIDTH * HEIGHT))
      [ "$area" -gt "$best_area" ] && { best=$w; best_area=$area; }
    done
    [ -n "$best" ] && { echo "$best"; return 0; }
    sleep 1; i=$((i + 1))
  done
  {
    echo "[driver] named windows:"
    for w in $(xdotool search --name '.' 2>/dev/null | head -40); do
      echo "  $w '$(xdotool getwindowname "$w" 2>/dev/null)' $(xwininfo -id "$w" 2>/dev/null | grep -E 'Map State|-geometry' | tr -s ' \n' ' ')"
    done
    echo "[driver] managed windows:"; wmctrl -l 2>&1 | sed 's/^/  /'
  } >> "$1"
  return 1
}
