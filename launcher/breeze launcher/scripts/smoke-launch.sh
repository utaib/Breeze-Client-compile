#!/usr/bin/env bash
# Start a built launcher against an empty home folder and wait for its UI to
# call a native command.
#
# launcher-settings.json is only ever created by the React app calling
# get_launcher_settings. When it appears, the window opened, the webview loaded
# the bundled front end, and a command passed Tauri's permission check on this
# OS. A process that merely stays alive proves none of that.
#
# Usage: smoke-launch.sh <path-to-binary> [output-dir]
set -euo pipefail

BIN="$1"
OUT="${2:-smoke}"
TIMEOUT="${SMOKE_TIMEOUT:-180}"

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
FAKE_HOME="$(mktemp -d)"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    PLATFORM=windows
    # home_dir() prefers USERPROFILE on Windows.
    export USERPROFILE="$(cygpath -w "$FAKE_HOME")"
    ;;
  Darwin)
    PLATFORM=macos
    export HOME="$FAKE_HOME"
    ;;
  *)
    PLATFORM=linux
    export HOME="$FAKE_HOME"
    ;;
esac
SETTINGS="$FAKE_HOME/.breezeclient/launcher-settings.json"

"$BIN" > "$OUT/launcher.log" 2>&1 &
PID=$!

stop_launcher() {
  if [ "$PLATFORM" = windows ]; then
    taskkill //F //IM "$(basename "$BIN")" > /dev/null 2>&1 || true
  else
    kill "$PID" 2> /dev/null || true
  fi
}
trap stop_launcher EXIT

take_screenshot() {
  case "$PLATFORM" in
    linux) import -window root -display "${DISPLAY:-:99}" "$OUT/screen.png" || true ;;
    macos) screencapture -x "$OUT/screen.png" || true ;;
    windows)
      powershell -NoProfile -Command "
        Add-Type -AssemblyName System.Windows.Forms, System.Drawing;
        \$b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds;
        \$bmp = New-Object System.Drawing.Bitmap \$b.Width, \$b.Height;
        [System.Drawing.Graphics]::FromImage(\$bmp).CopyFromScreen(\$b.Location, [System.Drawing.Point]::Empty, \$b.Size);
        \$bmp.Save('$(cygpath -w "$OUT/screen.png")')" || true
      ;;
  esac
}

for second in $(seq 1 "$TIMEOUT"); do
  if [ -f "$SETTINGS" ]; then
    echo "The launcher UI called get_launcher_settings after ${second}s on $PLATFORM."
    # Wait out the startup animation before the screenshot, so the image shows
    # the launcher after the hand-off rather than a scene mid-flight. The
    # longest scene dwells about 12s, plus its exit and the dissolve.
    sleep "${SMOKE_SETTLE:-22}"
    take_screenshot
    if ! kill -0 "$PID" 2> /dev/null && [ "$PLATFORM" != windows ]; then
      echo "::error::The launcher exited right after its UI loaded."
      cat "$OUT/launcher.log"
      exit 1
    fi
    exit 0
  fi
  if [ "$PLATFORM" != windows ] && ! kill -0 "$PID" 2> /dev/null; then
    echo "::error::The launcher exited before its UI loaded."
    cat "$OUT/launcher.log"
    exit 1
  fi
  sleep 1
done

take_screenshot
echo "::error::The launcher UI never called a native command within ${TIMEOUT}s on $PLATFORM."
cat "$OUT/launcher.log"
exit 1
