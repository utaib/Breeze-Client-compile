#!/usr/bin/env bash
# Ensure nginx accepts uploads large enough for Breeze cosmetics.
#
# The problem
# -----------
# nginx's client_max_body_size defaults to 1 MB. The API accepts more than that,
# so a larger cape upload is rejected before Express ever sees it. Worse, nginx
# serves its own 413 page, which never passes through the app's CORS middleware,
# so the browser blocks the response and the user sees an unexplained
# "failed to fetch" rather than "file too large".
#
# What this does
# --------------
# Run it at server start. It is idempotent and safe to run every boot:
#   - If nginx is not present or not manageable (a Pterodactyl container, a
#     managed host), it prints what to ask for and exits 0. A missing nginx is
#     not a reason to stop the API from booting.
#   - If the limit is already correct, it changes nothing and exits 0.
#   - Otherwise it writes one conf snippet, validates with `nginx -t`, and only
#     then reloads. A bad config is never activated.
#
# Usage
#   ./scripts/ensure-nginx-upload-limit.sh            # default 8m
#   BREEZE_UPLOAD_LIMIT=16m ./scripts/...             # override
#   npm start                                          # runs automatically

set -uo pipefail

LIMIT="${BREEZE_UPLOAD_LIMIT:-8m}"
SNIPPET="/etc/nginx/conf.d/breeze-upload.conf"
TAG="breeze-upload-limit"

say() { printf '[breeze/nginx] %s\n' "$1"; }

# --- Can we manage nginx at all? ---------------------------------------------
if ! command -v nginx >/dev/null 2>&1; then
  say "nginx not found on PATH. Nothing to configure here."
  say "If uploads fail with a network error, ask your host to set:"
  say "    client_max_body_size ${LIMIT};"
  exit 0
fi

if [ ! -d /etc/nginx/conf.d ]; then
  say "/etc/nginx/conf.d does not exist; this nginx is not configured the usual way."
  say "Ask your host to set: client_max_body_size ${LIMIT};"
  exit 0
fi

# Writing to /etc needs root. In a container without it, say so plainly rather
# than failing with a bare permission error.
if [ "$(id -u)" -ne 0 ] && ! sudo -n true 2>/dev/null; then
  say "Not running as root and passwordless sudo is unavailable."
  say "Run this once with elevated permissions, or ask your host to set:"
  say "    client_max_body_size ${LIMIT};"
  exit 0
fi

SUDO=""
[ "$(id -u)" -ne 0 ] && SUDO="sudo"

# --- Already correct? ---------------------------------------------------------
# Check the running config rather than just our snippet: another file may
# already set a large enough limit, in which case touching anything is churn.
if $SUDO nginx -T 2>/dev/null | grep -qE "client_max_body_size\s+${LIMIT}\s*;"; then
  say "client_max_body_size is already ${LIMIT}. No change."
  exit 0
fi

# --- Write, validate, then reload --------------------------------------------
say "Setting client_max_body_size to ${LIMIT}."

TMP="$(mktemp)"
cat > "$TMP" <<EOF
# Managed by Breeze (${TAG}). Regenerated on server start.
#
# Cosmetic uploads exceed nginx's 1 MB default. Without this, uploads are
# rejected before reaching the API, and because nginx's 413 page carries no
# CORS headers the browser reports an unreadable "failed to fetch".
client_max_body_size ${LIMIT};
client_body_timeout 120s;
EOF

BACKUP=""
if [ -f "$SNIPPET" ]; then
  BACKUP="${SNIPPET}.bak.$(date +%s)"
  $SUDO cp "$SNIPPET" "$BACKUP"
fi

$SUDO cp "$TMP" "$SNIPPET"
rm -f "$TMP"

if ! $SUDO nginx -t >/dev/null 2>&1; then
  say "nginx rejected the new config. Reverting; nothing was reloaded."
  if [ -n "$BACKUP" ]; then $SUDO cp "$BACKUP" "$SNIPPET"; else $SUDO rm -f "$SNIPPET"; fi
  $SUDO nginx -t 2>&1 | sed 's/^/[breeze\/nginx]   /'
  exit 0
fi

# Reload, never restart: a reload keeps existing connections alive, so this does
# not drop anyone mid-download.
if $SUDO nginx -s reload >/dev/null 2>&1 \
   || $SUDO systemctl reload nginx >/dev/null 2>&1; then
  say "Applied and reloaded. Uploads up to ${LIMIT} are now accepted."
else
  say "Config written and valid, but the reload command failed."
  say "It will take effect the next time nginx restarts."
fi

[ -n "$BACKUP" ] && $SUDO rm -f "$BACKUP"
exit 0
