#!/usr/bin/env bash
# Every check for the Breeze Bedrock add-on, in the order CI runs them.
# Needs Python 3 (with jsonschema for the schema step) and Node 20+.
set -euo pipefail
PY=${PYTHON:-python3}
cd "$(dirname "$0")/.."
SCHEMA_COMMIT=af82975a5b25d64373e4a4c5fd4d76c2ff774fa5  # Blockception/Minecraft-bedrock-json-schemas
echo "== offline checks"
"$PY" tools/check.py
echo "== manifest schema (Blockception, $SCHEMA_COMMIT)"
tmp=$(mktemp -d)
curl -fsSL -o "$tmp/manifest.schema.json" \
  "https://raw.githubusercontent.com/Blockception/Minecraft-bedrock-json-schemas/$SCHEMA_COMMIT/general/manifest.json"
"$PY" tools/check.py --schema "$tmp/manifest.schema.json"
echo "== vanilla paths still in the game (bedrock-samples)"
"$PY" tools/check_vanilla.py
echo "== script: syntax, unit tests, types against @minecraft/server 1.11.0"
node --check packs/breeze_hud/scripts/main.js
node --check packs/breeze_hud/scripts/format.js
node --test tools/test_format.mjs
(cd tools && npm ci --no-audit --no-fund >/dev/null && npx tsc -p tsconfig.json)
echo "== packaging is reproducible"
"$PY" tools/package.py --verify
echo "all Breeze Bedrock checks passed"
