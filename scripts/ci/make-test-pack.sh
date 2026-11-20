#!/usr/bin/env bash
# A resource pack for the recorder test: the common ground and tree blocks in
# magenta, so a recording watched with it is told apart by its colours
# (drive-watch.sh counts magenta). Usage: make-test-pack.sh <out.zip>
set -eu
OUT="$(realpath -m "$1")"
T="$(mktemp -d)"
mkdir -p "$T/assets/minecraft/textures/block"
convert -size 16x16 xc:'#FF00FF' "$T/magenta.png"
for b in grass_block_top grass_block_side dirt stone snow sand gravel deepslate andesite granite diorite \
         podzol coarse_dirt short_grass oak_leaves spruce_leaves birch_leaves jungle_leaves acacia_leaves \
         dark_oak_leaves oak_log spruce_log birch_log water_still water_flow snow_block grass_block_snow \
         sandstone sandstone_top tuff calcite moss_block; do
  cp "$T/magenta.png" "$T/assets/minecraft/textures/block/$b.png"
done
rm "$T/magenta.png"
# Every pack format from 1.17 to 26.x: the game warns, but loads it from options.txt.
printf '{"pack":{"description":"Breeze recorder test","pack_format":75,"supported_formats":[1,200],"min_format":1,"max_format":200}}\n' > "$T/pack.mcmeta"
(cd "$T" && rm -f "$OUT" && zip -qr "$OUT" .)
rm -rf "$T"
