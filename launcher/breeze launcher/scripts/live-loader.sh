#!/usr/bin/env bash
# One loader's live test (src-tauri/src/lib.rs, loader_live_tests), from the
# src-tauri folder: installs the loader the way a launch does and starts the
# real game, under a virtual display where there is one. Prints the one line
# that says how it went as a workflow notice, and on failure the reason.
#
#   ../scripts/live-loader.sh forge
set -uo pipefail
loader=${1:?vanilla, fabric, quilt, forge or neoforge}
log="${RUNNER_TEMP:-/tmp}/live-$loader.log"
run=(cargo test --lib -- --ignored --nocapture --test-threads=1 "live_loader_${loader}_starts")
if [ -z "${BREEZE_LIVE_NO_START:-}" ] && command -v xvfb-run > /dev/null; then
  run=(xvfb-run -a -s "-screen 0 1280x720x24" "${run[@]}")
fi
"${run[@]}" 2>&1 | tee "$log" | sed -E 's/\x1b\[[0-9;]*[A-Za-z]//g' | tail -n 400
rc=${PIPESTATUS[0]}
note() { printf '::%s title=%s::%s\n' "$1" "live-$loader" "$(printf '%s' "$2" | tr '\n' ' ' | cut -c1-900)"; }
good=$(grep -E 'resolved in|loaded its resources|installed and ready, not started|main class ' "$log" | tr '\n' ';')
if [ "$rc" -eq 0 ]; then
  note notice "$good"
else
  why=$(grep -E "panicked at|did not install|exited \(|had not loaded|none of|crash report|Error|Exception" "$log" | grep -v '^  | ' | head -n 6 | tr '\n' ';')
  note error "${why:-failed with no reason found} ;; $good"
  # The game's own last words, for reading from the job's annotations.
  grep -E '^  \| ' "$log" | grep -iE 'error|exception|fatal|caused by|missing|failed' | tail -n 12 | while read -r line; do note warning "$line"; done
fi
exit "$rc"
