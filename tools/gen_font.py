#!/usr/bin/env python3
"""
Generates the signed-distance-field font atlas used by the in-game UI.

  python3 tools/gen_font.py

Faces (same typography as the Roblox version of the game):
  0 UI    - Titillium Web SemiBold   (labels, buttons, body text)
  1 TITLE - Sarpanch Bold            (titles, headings)
  2 MONO  - Roboto Mono Medium       (numbers, readouts)
Glyphs a face lacks (arrows, triangles) fall back to Inter SemiBold.

Outputs (into app/src/main/assets/font/):
  ui.sdf  - "SDF1" + int32 width + int32 height + width*height distance bytes (0.5 = glyph edge)
  ui.fnt  - "face <i> cap=<px>" lines and "g <face> <code> x y w h xoff yoff advance" per glyph
One atlas texture holds all faces, so the whole UI still renders in a single draw call.
Requires: pillow, numpy, scipy, fonttools.
"""
import os
import struct

import numpy as np
from fontTools.ttLib import TTFont
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import distance_transform_edt

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONTS = os.path.join(ROOT, "tools", "fonts")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "font")

BASE = 48          # glyph size in atlas pixels
SS = 4             # supersampling used to compute accurate distances
SPREAD = 6         # distance range (atlas pixels) on each side of the edge
ATLAS_W = 1024
ASCII = [chr(c) for c in range(32, 127)]
SYMBOLS = list("·°×–—…←→↑↓▲▼◀▶●○◆■")

FACES = [
    ("TitilliumWeb-SemiBold.ttf", None, ASCII + SYMBOLS),
    ("Sarpanch-Bold.ttf", None, ASCII + SYMBOLS),
    ("RobotoMono[wght].ttf", 500, ASCII + SYMBOLS),
]
FALLBACK = "Inter-SemiBold.otf"


def load(name, weight):
    f = ImageFont.truetype(os.path.join(FONTS, name), BASE * SS)
    if weight is not None:
        f.set_variation_by_axes([weight])
    return f


def has_glyph(path, ch):
    return ord(ch) in TTFont(path).getBestCmap()


def render(font, ch):
    """Returns (cell uint8 array, xoff, yoff, advance) or (None, 0, 0, advance) for blanks."""
    pad = SPREAD * SS + SS
    adv = font.getlength(ch) / SS
    l, t, r, b = font.getbbox(ch, anchor="ls")
    if ch == " " or r <= l or b <= t:
        return None, 0.0, 0.0, adv
    w = r - l + 2 * pad
    h = b - t + 2 * pad
    w += (-w) % SS
    h += (-h) % SS
    img = Image.new("L", (w, h), 0)
    ImageDraw.Draw(img).text((pad - l, pad - t), ch, font=font, fill=255, anchor="ls")
    a = np.asarray(img) > 127
    sd = (distance_transform_edt(~a) - distance_transform_edt(a)) / SS
    sd = sd[SS // 2::SS, SS // 2::SS]
    v = np.clip(0.5 - sd / (2.0 * SPREAD), 0.0, 1.0)
    return (v * 255.0 + 0.5).astype(np.uint8), (l - pad) / SS, (t - pad) / SS, adv


def main():
    fallback = load(FALLBACK, None)
    fallback_path = os.path.join(FONTS, FALLBACK)
    glyphs = []  # (face, code, cell, xoff, yoff, adv)
    caps = []
    for face, (name, weight, chars) in enumerate(FACES):
        path = os.path.join(FONTS, name)
        font = load(name, weight)
        cmap = TTFont(path).getBestCmap()
        caps.append(-font.getbbox("H", anchor="ls")[1] / SS)
        for ch in chars:
            src = font if ord(ch) in cmap or ch == " " else (fallback if has_glyph(fallback_path, ch) else None)
            if src is None:
                continue
            cell, xoff, yoff, adv = render(src, ch)
            glyphs.append((face, ord(ch), cell, xoff, yoff, adv))

    # shelf packing, tallest first for a tighter atlas
    order = sorted(range(len(glyphs)), key=lambda i: -(glyphs[i][2].shape[0] if glyphs[i][2] is not None else 0))
    x = y = row_h = 0
    pos = {}
    for i in order:
        cell = glyphs[i][2]
        if cell is None:
            pos[i] = (0, 0, 0, 0)
            continue
        gh, gw = cell.shape
        if x + gw + 1 > ATLAS_W:
            x = 0
            y += row_h + 1
            row_h = 0
        pos[i] = (x, y, gw, gh)
        x += gw + 1
        row_h = max(row_h, gh)
    height = 1
    while height < y + row_h + 1:
        height *= 2
    atlas = np.zeros((height, ATLAS_W), dtype=np.uint8)
    for i, (face, code, cell, xoff, yoff, adv) in enumerate(glyphs):
        if cell is not None:
            gx, gy, gw, gh = pos[i]
            atlas[gy:gy + gh, gx:gx + gw] = cell

    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "ui.sdf"), "wb") as f:
        f.write(b"SDF1")
        f.write(struct.pack("<ii", ATLAS_W, height))
        f.write(atlas.tobytes())
    with open(os.path.join(OUT, "ui.fnt"), "w") as f:
        f.write(f"info base={BASE} spread={SPREAD} width={ATLAS_W} height={height}\n")
        for face, cap in enumerate(caps):
            f.write(f"face {face} cap={cap:.3f}\n")
        for i in sorted(range(len(glyphs)), key=lambda k: (glyphs[k][0], glyphs[k][1])):
            face, code, cell, xoff, yoff, adv = glyphs[i]
            gx, gy, gw, gh = pos[i]
            f.write(f"g {face} {code} {gx} {gy} {gw} {gh} {xoff:.3f} {yoff:.3f} {adv:.3f}\n")
    Image.fromarray(atlas).save(os.path.join(ROOT, "tools", "ui-atlas-preview.png"))
    print(f"atlas {ATLAS_W}x{height}, {len(glyphs)} glyphs in {len(FACES)} faces, caps {caps}")


if __name__ == "__main__":
    main()
