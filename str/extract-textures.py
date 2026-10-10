#!/usr/bin/env python3
"""
extract-textures.py — pull the 20 block textures the splash needs out of a
Minecraft client.jar, on the machine that already owns the game.

    python3 extract-textures.py ~/.minecraft/versions/1.21.5/1.21.5.jar

Writes:
    out/textures/*.png          the raw 16x16 files
    out/custom_tex.js           a ready-to-paste CUSTOM_TEX block (data URIs)

Nothing is redistributed: this runs against the user's own installed jar, where
the textures are licensed to them. Ship the SCRIPT (or its logic, ported to
Rust in the launcher), never the PNGs.

If a resource pack is selected, point this at the pack .zip instead and the
splash quietly matches the player's pack.
"""
import base64, json, os, sys, zipfile

# splash slot name  ->  candidate paths inside the jar, first match wins
SLOTS = {
    "oak_log":            ["assets/minecraft/textures/block/oak_log.png"],
    "oak_leaves":         ["assets/minecraft/textures/block/oak_leaves.png"],
    "spruce_log":         ["assets/minecraft/textures/block/spruce_log.png"],
    "spruce_leaves":      ["assets/minecraft/textures/block/spruce_leaves.png"],
    "birch_log":          ["assets/minecraft/textures/block/birch_log.png"],
    "birch_leaves":       ["assets/minecraft/textures/block/birch_leaves.png"],
    "grass_block_side":   ["assets/minecraft/textures/block/grass_block_side.png"],
    "grass_block_top":    ["assets/minecraft/textures/block/grass_block_top.png"],
    "dirt":               ["assets/minecraft/textures/block/dirt.png"],
    "stone":              ["assets/minecraft/textures/block/stone.png"],
    "cobblestone":        ["assets/minecraft/textures/block/cobblestone.png"],
    "short_grass":        ["assets/minecraft/textures/block/short_grass.png",
                           "assets/minecraft/textures/block/grass.png"],
    "oak_log_top":        ["assets/minecraft/textures/block/oak_log_top.png"],
    "water_still":        ["assets/minecraft/textures/block/water_still.png"],
    "leaf_litter":        ["assets/minecraft/textures/block/leaf_litter.png"],
    "moss_block":         ["assets/minecraft/textures/block/moss_block.png"],
    "firefly_bush":       ["assets/minecraft/textures/block/firefly_bush_front.png",
                           "assets/minecraft/textures/block/firefly_bush.png"],
    "pale_oak_log":       ["assets/minecraft/textures/block/pale_oak_log.png"],
    "pale_oak_leaves":    ["assets/minecraft/textures/block/pale_oak_leaves.png"],
    "pale_oak_log_top":   ["assets/minecraft/textures/block/pale_oak_log_top.png"],
}

# Notes on the awkward ones:
#  - grass_block_top and short_grass are GREYSCALE in vanilla; the game applies a
#    biome colour multiply at runtime. Tint them yourself or they render white.
#    Plains foliage is roughly #79C05A / #91BD59. Same for oak_leaves.
#  - water_still.png is a vertical ANIMATION STRIP (16 x 16N). Take the first
#    frame, or step through frames for a live-flowing waterfall.
#  - leaf_litter, firefly_bush and pale_oak only exist in 1.21.4+. Missing slots
#    fall back to the bundled painted tile, so older jars still work.

BIOME_TINT = (0x79, 0xC0, 0x5A)   # applied to greyscale foliage; edit to taste
TINT_SLOTS = {"grass_block_top", "short_grass", "oak_leaves",
              "spruce_leaves", "birch_leaves"}


def first_frame_and_tint(raw: bytes, slot: str) -> bytes:
    """Crop animation strips to frame 0 and biome-tint greyscale foliage."""
    try:
        from PIL import Image
        import io
    except ImportError:
        return raw                                  # Pillow optional; raw still works
    im = Image.open(io.BytesIO(raw)).convert("RGBA")
    if im.height > im.width:                        # animation strip
        im = im.crop((0, 0, im.width, im.width))
    if slot in TINT_SLOTS:
        px = im.load()
        for y in range(im.height):
            for x in range(im.width):
                r, g, b, a = px[x, y]
                if a == 0:
                    continue
                px[x, y] = (r * BIOME_TINT[0] // 255,
                            g * BIOME_TINT[1] // 255,
                            b * BIOME_TINT[2] // 255, a)
    buf = io.BytesIO()
    im.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    jar = sys.argv[1]
    outdir = sys.argv[2] if len(sys.argv) > 2 else "out"
    os.makedirs(os.path.join(outdir, "textures"), exist_ok=True)

    found, missing, entries = {}, [], []
    with zipfile.ZipFile(jar) as z:
        names = set(z.namelist())
        for slot, candidates in SLOTS.items():
            path = next((c for c in candidates if c in names), None)
            if not path:
                missing.append(slot)
                continue
            data = first_frame_and_tint(z.read(path), slot)
            with open(os.path.join(outdir, "textures", slot + ".png"), "wb") as f:
                f.write(data)
            found[slot] = data
            entries.append('  %s: "data:image/png;base64,%s",'
                           % (slot, base64.b64encode(data).decode()))

    with open(os.path.join(outdir, "custom_tex.js"), "w") as f:
        f.write("// paste over CUSTOM_TEX in the splash\nconst CUSTOM_TEX = {\n")
        f.write("\n".join(entries))
        f.write("\n};\n")

    print("extracted %d/%d" % (len(found), len(SLOTS)))
    if missing:
        print("missing (bundled fallback will be used): " + ", ".join(missing))
    print("wrote %s/custom_tex.js" % outdir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
