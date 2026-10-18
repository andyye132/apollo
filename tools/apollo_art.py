#!/usr/bin/env python3
"""Turn Apollo cut-outs (transparent PNGs in "Apollo images/mascot/") into proto-app assets.

Usage: python3 tools/apollo_art.py [preview.png]
  apollo_cares.png       -> res/drawable-nodpi/apollo_cares.png   (holding a heart: the Care tab)
  apollo_ask.png         -> res/drawable-nodpi/apollo_ask.png     (one brow up: "Temperature too?")
  apollo_happy_icon.png  -> res/drawable-nodpi/ic_launcher_art.png (the launcher icon's foreground)
Each cut-out keeps only its main shape (the generator leaves faint red specks around it), is cropped and
centred on a square like the other moods (tools/make_ui_assets.py). The icon is sized so every opaque
pixel, the leaf's tip included, sits inside the adaptive icon's safe circle (66 of 108 dp), so no launcher
mask clips it. With a path, also writes a preview of all three on the app's ivory.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "Apollo images" / "mascot"
OUT = ROOT / "proto-app" / "res" / "drawable-nodpi"
IVORY = (246, 243, 238)


def clean(img):
    """Keep the largest opaque shape and its soft edge; drop specks elsewhere."""
    a = np.asarray(img.convert("RGBA")).copy()
    lab, k = ndimage.label(a[..., 3] > 40)
    main = lab == (int(np.argmax(ndimage.sum(np.ones(lab.shape), lab, range(1, k + 1)))) + 1)
    keep = ndimage.binary_dilation(ndimage.binary_fill_holes(main), iterations=3)
    a[..., 3] = np.where(keep, a[..., 3], 0)
    return Image.fromarray(a)


def square(img, size, margin=0.03):
    """Crop to the shape and centre it on a transparent square, `margin` of the side free all round."""
    img = img.crop(img.getbbox())
    side = int(max(img.size) * (1 + 2 * margin))
    sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    sq.paste(img, ((side - img.width) // 2, (side - img.height) // 2), img)
    return sq.resize((size, size), Image.LANCZOS)


def icon(img, size=432, safe=0.61 / 2 * 0.97):
    """The launcher foreground: the shape centred, scaled so its farthest pixel stays inside the safe circle."""
    img = img.crop(img.getbbox())
    al = np.asarray(img)[..., 3] > 24
    edge = al & ~ndimage.binary_erosion(al)   # the outline is enough to find the farthest pixel
    ys, xs = np.nonzero(edge)
    best = None
    for fx in np.linspace(0.35, 0.65, 31):   # the centre of the smallest circle holding the whole shape
        for fy in np.linspace(0.35, 0.65, 31):
            cx, cy = img.width * fx, img.height * fy
            reach = np.sqrt((xs - cx) ** 2 + (ys - cy) ** 2).max()
            if best is None or reach < best[0]:
                best = (reach, cx, cy)
    reach, cx, cy = best
    scale = safe * size / reach
    small = img.resize((max(1, round(img.width * scale)), max(1, round(img.height * scale))), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(small, (round(size / 2 - cx * scale), round(size / 2 - cy * scale)), small)
    return out


def main():
    made = {}
    for name in ("apollo_cares", "apollo_ask"):
        made[name] = square(clean(Image.open(SRC / f"{name}.png")), 360)
        made[name].save(OUT / f"{name}.png", optimize=True)
    made["ic_launcher_art"] = icon(clean(Image.open(SRC / "apollo_happy_icon.png")))
    made["ic_launcher_art"].save(OUT / "ic_launcher_art.png", optimize=True)
    for n, im in made.items():
        print(f"{n}.png {im.size} -> {OUT.relative_to(ROOT)}")
    if len(sys.argv) > 1:   # the three on ivory, and the icon inside a launcher's circle mask
        row = Image.new("RGB", (360 * 2 + 432, 432), IVORY)
        row.paste(made["apollo_cares"], (0, 36), made["apollo_cares"])
        row.paste(made["apollo_ask"], (360, 36), made["apollo_ask"])
        disc = Image.new("RGB", (432, 432), IVORY)
        mask = Image.new("L", (432, 432), 0)
        from PIL import ImageDraw
        ImageDraw.Draw(mask).ellipse((432 * 0.167, 432 * 0.167, 432 * 0.833, 432 * 0.833), fill=255)   # the visible 72 of 108 dp
        white = Image.new("RGB", (432, 432), (255, 255, 255))
        white.paste(made["ic_launcher_art"], (0, 0), made["ic_launcher_art"])
        disc.paste(white, (0, 0), mask)
        row.paste(disc, (720, 0))
        row.save(sys.argv[1])


if __name__ == "__main__":
    main()
