#!/usr/bin/env python3
"""Draws every texture the Breeze packs ship, from code, in Breeze colours.

Nothing here copies or traces Mojang art: each picture is drawn from simple
shapes at the size the game expects for that path (sizes read from the
names-only list in tools/vanilla_paths.json). Run it after changing the art;
its output is committed, so packaging needs no Pillow.

    python3 tools/make_art.py
"""
import math
import random
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
UI = ROOT / "packs" / "breeze_ui"
HUD = ROOT / "packs" / "breeze_hud"
ICON_SRC = ROOT.parent / "breeze launcher" / "src-tauri" / "icons" / "128x128@2x.png"

# Breeze colours (the launcher's BreezeV2.css and icon).
NAVY = (18, 21, 29)
SURFACE = (25, 29, 39)
ACCENT = (37, 99, 235)
ACCENT_LIGHT = (96, 165, 250)
VIOLET = (129, 102, 199)
TEXT = (231, 233, 238)


CROSSHAIRS = (("game", "Game crosshair"), ("cross", "Breeze cross"), ("dot", "Breeze dot"), ("circle", "Breeze circle"))
FIRES = (("normal", "normal fire"), ("low", "low fire"))
SUBPACKS = [
    (f"{c}_{f}", f"{cn}, {fn}", c, f) for c, cn in CROSSHAIRS for f, fn in FIRES
]


def save(img, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    # No metadata and a fixed compression level, so the bytes are the same
    # on every run with the same Pillow.
    img.save(path, format="PNG", optimize=False, compress_level=9)


def hud_background():
    """6x6 nine-slice behind the action bar and titles (2 px border)."""
    im = Image.new("RGBA", (6, 6), NAVY + (200,))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 5, 5], outline=ACCENT + (230,))
    # Corners softened.
    for x, y in ((0, 0), (5, 0), (0, 5), (5, 5)):
        im.putpixel((x, y), (0, 0, 0, 0))
    return im


def hotbar_slot(index):
    """20x22 slot; the first and last carry the bar's rounded ends."""
    im = Image.new("RGBA", (20, 22), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 19, 21], fill=SURFACE + (190,))
    d.line([0, 0, 19, 0], fill=ACCENT + (200,))
    d.line([0, 21, 19, 21], fill=ACCENT + (200,))
    # Inner well where the item sits.
    d.rectangle([2, 3, 17, 18], fill=NAVY + (150,))
    if index == 0:
        d.line([0, 0, 0, 21], fill=ACCENT + (200,))
    if index == 8:
        d.line([19, 0, 19, 21], fill=ACCENT + (200,))
    return im


def hotbar_cap():
    im = Image.new("RGBA", (1, 22), ACCENT + (200,))
    return im


def selected_slot():
    """24x24 frame drawn over the selected hotbar slot."""
    im = Image.new("RGBA", (24, 24), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 23, 23], outline=ACCENT_LIGHT + (255,))
    d.rectangle([1, 1, 22, 22], outline=TEXT + (255,))
    d.rectangle([2, 2, 21, 21], outline=ACCENT + (200,))
    for x, y in ((0, 0), (23, 0), (0, 23), (23, 23)):
        im.putpixel((x, y), (0, 0, 0, 0))
    return im


def crosshair(style):
    """16x16 crosshairs. White with a dark edge so they read on snow and sky."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    white = (255, 255, 255, 255)
    edge = (0, 0, 0, 140)
    if style == "cross":
        shapes = [((7, 2), (8, 5)), ((7, 10), (8, 13)), ((2, 7), (5, 8)), ((10, 7), (13, 8))]
        for (a, b) in shapes:
            d.rectangle([a[0] - 1, a[1] - 1, b[0] + 1, b[1] + 1], fill=edge)
        for (a, b) in shapes:
            d.rectangle([a, b], fill=white)
    elif style == "dot":
        d.rectangle([6, 6, 9, 9], fill=edge)
        d.rectangle([7, 7, 8, 8], fill=white)
    elif style == "circle":
        d.ellipse([2, 2, 13, 13], outline=edge, width=3)
        d.ellipse([3, 3, 12, 12], outline=white, width=1)
        d.rectangle([7, 7, 8, 8], fill=white)
    return im


def low_flames():
    """16x512: 32 frames of short Breeze-blue flames in the bottom third.

    Replaces textures/flame_atlas (the flames drawn on burning entities and
    over the screen when the player burns). World fire is left as the game
    draws it.
    """
    rng = random.Random(1300)
    im = Image.new("RGBA", (16, 512), (0, 0, 0, 0))
    for f in range(32):
        top = f * 16
        phase = f / 32 * 2 * math.pi
        for x in range(16):
            h = 3 + 2.0 * (1 + math.sin(phase + x * 0.9)) + rng.random() * 1.5
            h = int(min(6, h))
            for k in range(h):
                y = top + 15 - k
                t = k / max(1, h - 1)
                col = tuple(int(ACCENT[i] * (1 - t) + ACCENT_LIGHT[i] * t) for i in range(3))
                if k == h - 1:
                    col = TEXT
                im.putpixel((x, y), col + (int(230 - 90 * t),))
    return im


def main():
    ui_tex = UI / "textures" / "ui"
    save(hud_background(), ui_tex / "hud_tip_text_background.png")
    for i in range(9):
        save(hotbar_slot(i), ui_tex / f"hotbar_{i}.png")
    save(hotbar_cap(), ui_tex / "hotbar_start_cap.png")
    save(hotbar_cap(), ui_tex / "hotbar_end_cap.png")
    save(selected_slot(), ui_tex / "selected_hotbar_slot.png")
    # One subpack per crosshair and fire choice: the player picks one in
    # the pack's settings (the game allows only one subpack at a time).
    for folder, _name, cross, fire in SUBPACKS:
        sub = UI / "subpacks" / folder / "textures"
        if cross != "game":
            save(crosshair(cross), sub / "ui" / "cross_hair.png")
        if fire == "low":
            save(low_flames(), sub / "flame_atlas.png")
    if not ICON_SRC.exists():
        sys.exit(f"missing {ICON_SRC}")
    icon = Image.open(ICON_SRC).convert("RGBA").resize((256, 256), Image.LANCZOS)
    for pack in (UI, HUD):
        save(icon, pack / "pack_icon.png")
    print("art written")


if __name__ == "__main__":
    main()
