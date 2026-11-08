#!/usr/bin/env python3
"""
Generates the signed-distance-field font atlas used by the in-game UI.

  python3 tools/gen_font.py [path/to/font.otf]

Outputs (into app/src/main/assets/font/):
  ui.sdf  - "SDF1" + int32 width + int32 height + width*height distance bytes (0.5 = glyph edge)
  ui.fnt  - text metrics: "info ..." header and one "g code x y w h xoff yoff advance" line per glyph
  ui.png  - preview of the atlas (not shipped in the APK)

One SDF atlas renders crisp text at any size with a single texture lookup per pixel.
Requires: pillow, numpy, scipy.
"""
import os
import struct
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import distance_transform_edt

FONT = sys.argv[1] if len(sys.argv) > 1 else "/usr/share/fonts/opentype/inter/Inter-SemiBold.otf"
BASE = 48          # glyph size in atlas pixels
SS = 4             # supersampling used to compute accurate distances
SPREAD = 6         # distance range (atlas pixels) on each side of the edge
ATLAS_W = 1024
CHARS = [chr(c) for c in range(32, 127)] + list("·°×–—…←→↑↓▲▼◀▶●○◆■")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "font")


def main():
    font = ImageFont.truetype(FONT, BASE * SS)
    pad = SPREAD * SS + SS
    glyphs = []
    for ch in CHARS:
        adv = font.getlength(ch) / SS
        l, t, r, b = font.getbbox(ch, anchor="ls")
        if ch == " " or r <= l or b <= t:
            glyphs.append((ord(ch), None, 0, 0, adv))
            continue
        w = r - l + 2 * pad
        h = b - t + 2 * pad
        # round up so the downsampled grid lines up
        w += (-w) % SS
        h += (-h) % SS
        img = Image.new("L", (w, h), 0)
        ImageDraw.Draw(img).text((pad - l, pad - t), ch, font=font, fill=255, anchor="ls")
        a = np.asarray(img) > 127
        d_out = distance_transform_edt(~a)
        d_in = distance_transform_edt(a)
        sd = (d_out - d_in) / SS  # signed distance in atlas pixels, + outside
        sd = sd[SS // 2::SS, SS // 2::SS]
        v = np.clip(0.5 - sd / (2.0 * SPREAD), 0.0, 1.0)
        cell = (v * 255.0 + 0.5).astype(np.uint8)
        xoff = (l - pad) / SS
        yoff = (t - pad) / SS
        glyphs.append((ord(ch), cell, xoff, yoff, adv))

    # shelf packing
    x = y = 0
    row_h = 0
    placed = []
    for code, cell, xoff, yoff, adv in glyphs:
        if cell is None:
            placed.append((code, 0, 0, 0, 0, 0.0, 0.0, adv, None))
            continue
        ch_, cw_ = cell.shape
        if x + cw_ + 1 > ATLAS_W:
            x = 0
            y += row_h + 1
            row_h = 0
        placed.append((code, x, y, cw_, ch_, xoff, yoff, adv, cell))
        x += cw_ + 1
        row_h = max(row_h, ch_)
    height = 1
    while height < y + row_h + 1:
        height *= 2

    atlas = np.zeros((height, ATLAS_W), dtype=np.uint8)
    for code, gx, gy, gw, gh, xoff, yoff, adv, cell in placed:
        if cell is not None:
            atlas[gy:gy + gh, gx:gx + gw] = cell

    ascent, descent = font.getmetrics()
    cap = -font.getbbox("H", anchor="ls")[1] / SS
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "ui.sdf"), "wb") as f:
        f.write(b"SDF1")
        f.write(struct.pack("<ii", ATLAS_W, height))
        f.write(atlas.tobytes())
    with open(os.path.join(OUT, "ui.fnt"), "w") as f:
        f.write(f"info base={BASE} spread={SPREAD} width={ATLAS_W} height={height} "
                f"ascent={ascent / SS:.3f} descent={descent / SS:.3f} cap={cap:.3f}\n")
        for code, gx, gy, gw, gh, xoff, yoff, adv, cell in sorted(placed, key=lambda g: g[0]):
            f.write(f"g {code} {gx} {gy} {gw} {gh} {xoff:.3f} {yoff:.3f} {adv:.3f}\n")
    Image.fromarray(atlas).save(os.path.join(ROOT, "tools", "ui-atlas-preview.png"))
    print(f"atlas {ATLAS_W}x{height}, {len(placed)} glyphs, cap height {cap:.1f}px")


if __name__ == "__main__":
    main()
