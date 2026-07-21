#!/usr/bin/env python3
"""Generates clearly-marked placeholder sprites for roster art that hasn't
been dropped in yet.

For every entry in assets_manifest.json whose resourcePath is missing, this
writes a rounded tile (rarity tint + creature initial + stage pips + a
"PLACEHOLDER" ribbon) so the plugin renders a legible full roster out of the
box. Every generated PNG embeds a tEXt marker:

    Software = runie-placeholder

tools/fetch_assets.sh uses that marker to know which files are safe to
overwrite with the real, downloaded art. Files WITHOUT the marker are treated
as real art and are never touched by either tool.

Idempotent: existing marked placeholders are refreshed in place; existing
unmarked (real) files are skipped. Run from the repo root:

    python3 tools/gen_placeholders.py

Requires Pillow (dev/CI tool only — never a plugin dependency).
"""
import json
import os

from PIL import Image, ImageDraw, ImageFont
from PIL.PngImagePlugin import PngInfo

MARKER = "runie-placeholder"

RARITY_COLORS = {
    "COMMON": (157, 157, 157),
    "UNCOMMON": (30, 200, 60),
    "RARE": (0, 112, 221),
    "ELITE": (163, 53, 238),
    "MASTER": (255, 128, 0),
    "GRANDMASTER": (230, 204, 128),
}


def is_marked_placeholder(path):
    try:
        with Image.open(path) as img:
            return img.info.get("Software") == MARKER
    except Exception:
        return False


def load_font(size):
    for p in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
              "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf"):
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def rounded_tile(name, rarity, stage, style, size=192):
    base = RARITY_COLORS[rarity]
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # stage tints the tile darker->brighter; pixel style gets tick marks
    f = 0.55 + 0.15 * stage
    fill = tuple(min(255, int(c * f)) for c in base) + (255,)
    edge = tuple(min(255, int(c * 1.25)) for c in base) + (255,)
    d.rounded_rectangle([8, 8, size - 8, size - 8], radius=28, fill=fill,
                        outline=edge, width=6)
    if style == "pixel":
        for i in range(16, size - 16, 16):
            d.line([(i, 12), (i, 20)], fill=edge, width=2)
    fnt = load_font(84)
    initial = name[0].upper()
    bb = d.textbbox((0, 0), initial, font=fnt)
    d.text(((size - bb[2] + bb[0]) / 2, (size - bb[3] - bb[1]) / 2 - 14),
           initial, font=fnt, fill=(255, 255, 255, 235))
    fnt2 = load_font(20)
    label = name[:10]
    bb = d.textbbox((0, 0), label, font=fnt2)
    d.text(((size - bb[2] + bb[0]) / 2, size - 62), label, font=fnt2,
           fill=(255, 255, 255, 235))
    for i in range(stage):
        d.ellipse([size / 2 - 24 + i * 18 - 5, size - 32 - 5,
                   size / 2 - 24 + i * 18 + 5, size - 32 + 5],
                  fill=(255, 255, 255, 220))
    fnt3 = load_font(14)
    d.rectangle([8, 12, size - 8, 30], fill=(0, 0, 0, 150))
    bb = d.textbbox((0, 0), "PLACEHOLDER", font=fnt3)
    d.text(((size - bb[2] + bb[0]) / 2, 13), "PLACEHOLDER", font=fnt3,
           fill=(255, 235, 180, 255))
    return img


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    res = os.path.join(root, "src/main/resources")
    with open(os.path.join(res, "com/runie/assets_manifest.json")) as f:
        manifest = json.load(f)
    with open(os.path.join(res, "com/runie/creatures.json")) as f:
        roster = {c["id"]: c for c in json.load(f)}

    written = skipped_real = 0
    for e in manifest["entries"]:
        target = os.path.join(res, e["resourcePath"])
        if os.path.exists(target) and not is_marked_placeholder(target):
            skipped_real += 1
            continue
        c = roster[e["creatureId"]]
        img = rounded_tile(c["name"], c["rarity"], e["stage"], e["style"])
        os.makedirs(os.path.dirname(target), exist_ok=True)
        meta = PngInfo()
        meta.add_text("Software", MARKER)
        img.save(target, "PNG", pnginfo=meta)
        written += 1
    print("placeholders written/refreshed: %d, real art untouched: %d"
          % (written, skipped_real))


if __name__ == "__main__":
    main()
