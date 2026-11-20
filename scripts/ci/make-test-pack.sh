#!/usr/bin/env bash
# A resource pack for the recorder test: the common ground, plant and tree
# blocks of every biome in magenta (the test world's seed is random), so a recording watched with it is told apart by its colours
# (drive-watch.sh counts magenta). Usage: make-test-pack.sh <out.zip>
set -eu
OUT="$(realpath -m "$1")"
T="$(mktemp -d)"
mkdir -p "$T/assets/minecraft/textures/block"
convert -size 16x16 xc:'#FF00FF' "$T/magenta.png"
for b in grass_block_top grass_block_side dirt stone snow sand gravel deepslate andesite granite diorite \
         podzol coarse_dirt short_grass oak_leaves spruce_leaves birch_leaves jungle_leaves acacia_leaves \
         dark_oak_leaves oak_log spruce_log birch_log water_still water_flow snow_block grass_block_snow \
         sandstone sandstone_top tuff calcite moss_block \
         jungle_log jungle_log_top acacia_log dark_oak_log mangrove_log cherry_log oak_log_top spruce_log_top \
         birch_log_top acacia_log_top dark_oak_log_top mangrove_leaves cherry_leaves azalea_leaves vine \
         cobblestone mossy_cobblestone clay mud red_sand terracotta ice packed_ice blue_ice \
         fern large_fern_top large_fern_bottom tall_grass_top tall_grass_bottom mycelium_top rooted_dirt; do
  cp "$T/magenta.png" "$T/assets/minecraft/textures/block/$b.png"
done
# Mushroom blocks too (a random world once put a giant red one in front of the camera).
for b in red_mushroom_block brown_mushroom_block mushroom_stem mushroom_block_inside; do
  cp "$T/magenta.png" "$T/assets/minecraft/textures/block/$b.png"
done
# The hotbar, which is on screen in every watched moment whatever the world
# shows: tinted grass and leaves come out too dark to count as magenta.
# From 1.20.2 a sprite of its own, before that the top left of widgets.png.
mkdir -p "$T/assets/minecraft/textures/gui/sprites/hud"
convert -size 182x22 xc:'#FF00FF' "$T/assets/minecraft/textures/gui/sprites/hud/hotbar.png"
convert -size 256x256 xc:none -fill '#FF00FF' -draw 'rectangle 0,0 181,21' "PNG32:$T/assets/minecraft/textures/gui/widgets.png"
rm "$T/magenta.png"
# Every pack format from 1.17 to 26.x: the game warns, but loads it from options.txt.
printf '{"pack":{"description":"Breeze recorder test","pack_format":75,"supported_formats":[1,200],"min_format":1,"max_format":200}}\n' > "$T/pack.mcmeta"
(cd "$T" && rm -f "$OUT" && zip -qr "$OUT" .)
rm -rf "$T"
