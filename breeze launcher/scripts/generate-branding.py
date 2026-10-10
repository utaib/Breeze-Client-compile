#!/usr/bin/env python3
"""
Breeze branding asset generator.

Regenerates every binary asset the Tauri bundler needs from exactly two
source images that already ship in `public/`:

  public/breeze-logo.png   -> src-tauri/icons/*        (app icon, all sizes)
  public/login-artwork.png -> src-tauri/installer/*.bmp (NSIS installer art)

Run this whenever either source image changes:

    pip install pillow --break-system-packages
    python3 scripts/generate-branding.py

It is NOT part of `npm run tauri build` — the generated files are committed
to the repo so a fresh clone builds with zero Python dependency. This script
exists purely so the installer/icon art can be regenerated on demand instead
of hand-edited pixel by pixel.
"""
import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOGO_SRC = os.path.join(ROOT, "public", "breeze-logo.png")
ART_SRC = os.path.join(ROOT, "public", "login-artwork.png")
ICONS_DIR = os.path.join(ROOT, "src-tauri", "icons")
INSTALLER_DIR = os.path.join(ROOT, "src-tauri", "installer")


def generate_icons():
    """Build every icon file src-tauri/tauri.conf.json's bundle.icon list expects."""
    logo = Image.open(LOGO_SRC).convert("RGBA")
    os.makedirs(ICONS_DIR, exist_ok=True)

    def resized(size):
        return logo.resize((size, size), Image.LANCZOS)

    resized(32).save(os.path.join(ICONS_DIR, "32x32.png"))
    resized(128).save(os.path.join(ICONS_DIR, "128x128.png"))
    resized(256).save(os.path.join(ICONS_DIR, "128x128@2x.png"))

    # Multi-resolution .ico for the NSIS installer + taskbar/exe icon.
    ico_sizes = [16, 24, 32, 48, 64, 128, 256]
    resized(256).save(
        os.path.join(ICONS_DIR, "icon.ico"),
        sizes=[(s, s) for s in ico_sizes],
    )

    # macOS .icns (harmless to ship even though this project targets NSIS/Windows).
    resized(1024).save(os.path.join(ICONS_DIR, "icon.icns"))

    print("Icons written to", ICONS_DIR)


def _cover_crop(im, target_w, target_h):
    """Crop+resize im to exactly (target_w, target_h), centered, no distortion."""
    src_w, src_h = im.size
    target_ratio = target_w / target_h
    src_ratio = src_w / src_h
    if src_ratio > target_ratio:
        # Source is relatively wider than target -> crop left/right.
        new_w = int(src_h * target_ratio)
        left = (src_w - new_w) // 2
        box = (left, 0, left + new_w, src_h)
    else:
        # Source is relatively taller than target -> crop top/bottom.
        new_h = int(src_w / target_ratio)
        top = (src_h - new_h) // 2
        box = (0, top, src_w, top + new_h)
    return im.crop(box).resize((target_w, target_h), Image.LANCZOS)


def _vertical_gradient_overlay(im, strength=0.55):
    """Darken the bottom portion so a future logo/text overlay stays legible,
    without hiding the artwork. Pure black->transparent gradient over the
    bottom third of the image."""
    im = im.convert("RGB")
    w, h = im.size
    overlay = Image.new("L", (1, h), 0)
    fade_start = int(h * 0.55)
    for y in range(h):
        if y < fade_start:
            overlay.putpixel((0, y), 0)
        else:
            t = (y - fade_start) / max(1, (h - fade_start))
            overlay.putpixel((0, y), int(255 * strength * t))
    overlay = overlay.resize((w, h))
    black = Image.new("RGB", (w, h), (6, 8, 16))
    return Image.composite(black, im, overlay.point(lambda p: 255 - p))


def _load_font(size, bold=True):
    """Segoe UI ships on every Windows the installer can run on; fall back to
    Pillow's default bitmap font so the script never hard-fails elsewhere."""
    candidates = ["segoeuib.ttf" if bold else "segoeui.ttf", "arialbd.ttf" if bold else "arial.ttf"]
    for name in candidates:
        path = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts", name)
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def _paste_logo(canvas, logo, size, center_x, top_y, glow=True):
    """Paste the Breeze logo with a soft accent glow behind it so it reads as
    an intentional brand mark rather than a stamped-on PNG."""
    mark = logo.resize((size, size), Image.LANCZOS)
    if glow:
        pad = size // 2
        glow_im = Image.new("RGBA", (size + pad * 2, size + pad * 2), (0, 0, 0, 0))
        tint = Image.new("RGBA", mark.size, (110, 170, 255, 170))
        glow_im.paste(tint, (pad, pad), mark)
        glow_im = glow_im.filter(ImageFilter.GaussianBlur(size // 6))
        canvas.paste(glow_im, (center_x - size // 2 - pad, top_y - pad), glow_im)
    canvas.paste(mark, (center_x - size // 2, top_y), mark)


# ── Breeze installer palette (deliberately blue-dominant) ────────────────────
NAVY = (9, 26, 56)        # deep top of the gradient
MID_BLUE = (26, 84, 170)  # bottom of the gradient
BRIGHT = (70, 150, 255)   # accent / glow
TEXT_HI = (240, 247, 255)
TEXT_MID = (172, 208, 255)
TEXT_LOW = (188, 212, 244)


def _linear_gradient(w, h, top, bottom):
    """Smooth vertical gradient between two RGB colours."""
    grad = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(1, h - 1)
        grad.putpixel((0, y), tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return grad.resize((w, h), Image.BICUBIC)


def _add_glow(base, cx, cy, radius, colour=BRIGHT, strength=115, blur=None):
    """Soft radial light so a flat gradient gains depth."""
    mask = Image.new("L", base.size, 0)
    ImageDraw.Draw(mask).ellipse(
        [cx - radius, cy - radius, cx + radius, cy + radius], fill=strength
    )
    mask = mask.filter(ImageFilter.GaussianBlur(blur or radius / 2))
    base.paste(Image.new("RGB", base.size, colour), (0, 0), mask)
    return base


def _wind_streaks(w, h, count=7, alpha=24):
    """Faint diagonal streaks — a quiet nod to the Wind Charge motif."""
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for i in range(count):
        y = int((i + 0.5) * h / count)
        d.line([(-20, y + 34), (w + 20, y - 34)], fill=(150, 200, 255, alpha), width=2)
    return layer.filter(ImageFilter.GaussianBlur(1.2))


def generate_installer_art():
    """NSIS 'Modern UI' expects header.bmp (150x57) and sidebar.bmp (164x314),
    both 24-bit BMP. Both are drawn from scratch in Breeze blue — a deep navy
    to mid-blue gradient, a soft glow behind the mark and faint wind streaks —
    so the wizard looks like Breeze instead of a default installer."""
    logo = Image.open(LOGO_SRC).convert("RGBA")
    os.makedirs(INSTALLER_DIR, exist_ok=True)

    # ── Header (150x57): compact blue strip, logo left, wordmark beside it.
    header = _linear_gradient(150, 57, NAVY, MID_BLUE)
    header = _add_glow(header, cx=20, cy=28, radius=54, strength=95)
    header = header.convert("RGBA")
    header = Image.alpha_composite(header, _wind_streaks(150, 57, count=4, alpha=20))
    _paste_logo(header, logo, 32, center_x=26, top_y=12, glow=False)
    hd = ImageDraw.Draw(header)
    hd.text((50, 15), "Breeze", font=_load_font(16, bold=True), fill=TEXT_HI)
    hd.text((51, 34), "CLIENT", font=_load_font(9, bold=False), fill=TEXT_MID)
    header.convert("RGB").save(os.path.join(INSTALLER_DIR, "header.bmp"), "BMP")

    # ── Sidebar (164x314): blue hero panel — glowing mark, wordmark, tagline.
    sidebar = _linear_gradient(164, 314, NAVY, MID_BLUE)
    sidebar = _add_glow(sidebar, cx=82, cy=100, radius=112, strength=120, blur=58)
    sidebar = sidebar.convert("RGBA")
    sidebar = Image.alpha_composite(sidebar, _wind_streaks(164, 314))
    _paste_logo(sidebar, logo, 84, center_x=82, top_y=58)

    draw = ImageDraw.Draw(sidebar)
    word_font = _load_font(26, bold=True)
    sub_font = _load_font(11, bold=False)

    word = "BREEZE"
    word_w = draw.textlength(word, font=word_font)
    draw.text(((164 - word_w) / 2, 168), word, font=word_font, fill=TEXT_HI)

    sub = "CLIENT"
    sub_w = draw.textlength(sub, font=sub_font)
    draw.text(((164 - sub_w) / 2, 202), sub, font=sub_font, fill=TEXT_MID)

    # Thin accent rule under the wordmark.
    draw.rectangle([58, 224, 106, 226], fill=BRIGHT)

    tagline = "Stays out of your way"
    tag_w = draw.textlength(tagline, font=sub_font)
    draw.text(((164 - tag_w) / 2, 272), tagline, font=sub_font, fill=TEXT_LOW)

    sidebar.convert("RGB").save(os.path.join(INSTALLER_DIR, "sidebar.bmp"), "BMP")

    print("Installer art written to", INSTALLER_DIR)


if __name__ == "__main__":
    generate_icons()
    generate_installer_art()
    print("Done. tauri.conf.json already points at these paths — no config changes needed.")
