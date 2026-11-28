#!/usr/bin/env bash
# Runs the Breeze Bedrock add-on checks on the public build repository
# (utaib/Breeze-Client-compile), never in Breeze-Client (its Actions are
# blocked). Same pattern as the launcher's scripts/public-build/push.sh:
# the source goes in one commit on the branch bedrock-addon (no history
# shared with that repository's main), the next commit removes it and adds
# the workflow, which restores it from HEAD~1. Empty the branch afterwards.
#
#   Breeze-Bedrock/ci/push.sh <build repository clone> [commit]
#   Breeze-Bedrock/ci/push.sh --empty <build repository clone> <run id>
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
top=$(git -C "$here" rev-parse --show-toplevel)
BRANCH=bedrock-addon
who=(-c user.name=utaib -c user.email=utaib092008@gmail.com)

if [ "${1:-}" = "--empty" ]; then
  clone=${2:?build repository clone}; run=${3:?run id}
  git -C "$clone" fetch -q origin "+refs/heads/$BRANCH:refs/remotes/origin/$BRANCH"
  git -C "$clone" checkout -q --no-track -B "$BRANCH" "refs/remotes/origin/$BRANCH"
  git -C "$clone" rm -q -r --ignore-unmatch .github bedrock
  printf '# %s\n\nUsed to check the Breeze Bedrock add-on; last run %s. Nothing here now.\n' "$BRANCH" "$run" > "$clone/README.md"
  git -C "$clone" add README.md
  git -C "$clone" "${who[@]}" commit -q -m "$BRANCH: emptied after run $run"
  git -C "$clone" push -q origin "HEAD:refs/heads/$BRANCH"
  exit 0
fi

clone=${1:?build repository clone}; commit=$(git -C "$top" rev-parse "${2:-HEAD}")
stage=$(mktemp -d); trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/bedrock"
git -C "$top" archive --format=tar "$commit" -- Breeze-Bedrock "breeze launcher/src-tauri/icons/128x128@2x.png" | tar -x -C "$stage/bedrock"
printf '%s\n' "utaib/Breeze-Client $commit, Breeze-Bedrock $(cat "$stage/bedrock/Breeze-Bedrock/VERSION")" \
  "Here for one check run; the next commit on this branch removes it." > "$stage/bedrock/SOURCE.txt"
if grep -rIlE '(sk_live_|service_role|ghp_[A-Za-z0-9]{20}|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY|client_secret)' "$stage/bedrock" | grep -v "/Breeze-Bedrock/ci/push.sh$"; then
  echo "key-like text; nothing pushed"; exit 1
fi
git -C "$clone" fetch -q origin "+refs/heads/$BRANCH:refs/remotes/origin/$BRANCH" 2>/dev/null || true
if git -C "$clone" rev-parse -q --verify "refs/remotes/origin/$BRANCH" > /dev/null; then
  git -C "$clone" checkout -q --no-track -B "$BRANCH" "refs/remotes/origin/$BRANCH"
else
  git -C "$clone" checkout -q --orphan "$BRANCH"
fi
git -C "$clone" rm -q -rf --ignore-unmatch . > /dev/null
cp -r "$stage/bedrock" "$clone/bedrock"
git -C "$clone" add -A
git -C "$clone" "${who[@]}" commit -q -m "Breeze Bedrock source for one check run ($(git -C "$top" rev-parse --short "$commit")); the next commit removes it"
git -C "$clone" rm -q -r bedrock
mkdir -p "$clone/.github/workflows"
cp "$here/bedrock.yml" "$clone/.github/workflows/bedrock.yml"
printf '# %s\n\nChecks the Breeze Bedrock add-on. The source is in the commit before this one and is emptied after the run.\n' "$BRANCH" > "$clone/README.md"
git -C "$clone" add -A
git -C "$clone" "${who[@]}" commit -q -m "Breeze Bedrock checks: source removed again; the workflow checks the commit before"
git -C "$clone" -c push.negotiate=true push -q origin "HEAD:refs/heads/$BRANCH"
git -C "$clone" log --oneline -2
echo "pushed; the push starts bedrock.yml on $BRANCH"
