#!/usr/bin/env bash
# Builds the launcher installers on the public build repository
# (utaib/Breeze-Client-compile), for while this repository's Actions are
# blocked. The owner asked for the launcher there "as a separate folder" and
# "for as little time as possible" (2026-10-08), so:
#
# - only the files the build reads go (FILES below: no tests, docs or design
#   drafts), into launcher/ on the branch launcher-build, which shares no
#   history with that repository's main;
# - the source commit is pushed together with a commit on top that removes it
#   again and adds the workflow (launcher.yml here), so the source is never
#   the branch tip; the workflow builds from HEAD~1;
# - installers go to a draft release there (only the owner sees drafts);
#   nothing is cached and no artifacts are kept;
# - when the run is done, empty the branch again: scripts/public-build/push.sh
#   --empty <clone>.
#
# The source commit stays in that branch's history until the branch is
# deleted on GitHub, which is the owner's call.
#
#   breeze launcher/scripts/public-build/push.sh <build repository clone> [commit]
#   breeze launcher/scripts/public-build/push.sh --empty <build repository clone> <run id>
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
top=$(git -C "$here" rev-parse --show-toplevel)
BRANCH=launcher-build

if [ "${1:-}" = "--empty" ]; then
  clone=${2:?build repository clone}; run=${3:?run id}
  git -C "$clone" fetch -q origin "+refs/heads/$BRANCH:refs/remotes/origin/$BRANCH"
  git -C "$clone" checkout -q --no-track -B "$BRANCH" "refs/remotes/origin/$BRANCH"
  git -C "$clone" rm -q -r --ignore-unmatch .github launcher
  cat > "$clone/README.md" <<EOF
# launcher-build

Used to build the Breeze Launcher installers while the private repository's
Actions were unavailable; last run $run. Nothing here now: the launcher
source and the workflow are gone from this branch. The installers are in a
draft release, visible only to the owner.
EOF
  git -C "$clone" add README.md
  git -C "$clone" -c user.name=utaib -c user.email=utaib092008@gmail.com \
    commit -q -m "$BRANCH: emptied after run $run"
  git -C "$clone" push -q origin "HEAD:refs/heads/$BRANCH"
  git -C "$clone" ls-tree --name-only HEAD
  exit 0
fi

clone=${1:?build repository clone}; commit=$(git -C "$top" rev-parse "${2:-HEAD}")
FILES=(
  "breeze launcher/package.json" "breeze launcher/package-lock.json" "breeze launcher/index.html"
  "breeze launcher/vite.config.ts" "breeze launcher/tsconfig.json" "breeze launcher/tsconfig.node.json"
  "breeze launcher/tsconfig.undef.json" "breeze launcher/src" "breeze launcher/public" "breeze launcher/src-tauri"
  "breeze launcher/scripts/build-installer-art.cjs" "breeze launcher/scripts/build-splash.cjs"
  "breeze launcher/scripts/css-motion-check.js" "breeze launcher/scripts/css-var-check.js"
  "breeze launcher/scripts/icon-check.cjs" "breeze launcher/scripts/undef-check.cjs"
  "breeze launcher/scripts/version-sync-check.cjs" "breeze launcher/scripts/smoke-launch.sh"
)
# The splash scenes build-splash.cjs reads from str/ (only the .html files).
mapfile -t SCENES < <(git -C "$top" ls-tree --name-only "$commit" str/ | grep -E '^str/v[0-9]+[a-z]?-.*\.html$')
[ "${#SCENES[@]}" -gt 0 ] || { echo "no splash scenes in str/ at $commit"; exit 1; }

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/launcher"
git -C "$top" archive --format=tar "$commit" -- "${FILES[@]}" "${SCENES[@]}" | tar -x -C "$stage/launcher"
printf '%s\n' "utaib/Breeze-Client $commit, launcher $(node -p "require('$stage/launcher/breeze launcher/package.json').version")" \
  "Only what the build reads. Here for one build; the next commit on this branch removes it." > "$stage/launcher/SOURCE.txt"

# Nothing key-like goes public. The Microsoft client id the launcher uses is
# the public one many launchers share; anything else that matches stops here.
if grep -rIlE '(sk_live_|sk_test_|service_role|ghp_[A-Za-z0-9]{20}|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY|SUPABASE_SERVICE|PAYPAL_SECRET|client_secret)' "$stage/launcher"; then
  echo "key-like text in the files above; nothing pushed"; exit 1
fi

git -C "$clone" fetch -q origin "+refs/heads/$BRANCH:refs/remotes/origin/$BRANCH" 2>/dev/null || true
if git -C "$clone" rev-parse -q --verify "refs/remotes/origin/$BRANCH" > /dev/null; then
  git -C "$clone" checkout -q --no-track -B "$BRANCH" "refs/remotes/origin/$BRANCH"
else
  git -C "$clone" checkout -q --orphan "$BRANCH"
fi
git -C "$clone" rm -q -r --ignore-unmatch . > /dev/null
cp -r "$stage/launcher" "$clone/launcher"
git -C "$clone" add -A
git -C "$clone" -c user.name=utaib -c user.email=utaib092008@gmail.com \
  commit -q -m "Launcher source for one build ($(git -C "$top" rev-parse --short "$commit")); the next commit removes it"
git -C "$clone" rm -q -r launcher
mkdir -p "$clone/.github/workflows"
cp "$here/launcher.yml" "$clone/.github/workflows/launcher.yml"
cat > "$clone/README.md" <<'EOF'
# launcher-build

A branch of its own for building the Breeze Launcher installers while the
private repository's Actions are unavailable. It shares no history with
`main` and holds no launcher source at its tip: the source is pushed in the
commit before the one that runs, the workflow restores it from there, and the
branch is emptied to this README when the build is done.
EOF
git -C "$clone" add -A
git -C "$clone" -c user.name=utaib -c user.email=utaib092008@gmail.com \
  commit -q -m "Launcher build: source removed again; the workflow builds from the commit before"
git -C "$clone" push -q origin "HEAD:refs/heads/$BRANCH"
git -C "$clone" log --oneline -2
echo "pushed; the push starts launcher.yml on $BRANCH"
