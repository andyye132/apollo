"""Cut the Apollo mascot art out of the design boards into transparent PNGs for proto-app.

Usage: python3 tools/make_ui_assets.py
Reads  "Apollo images/" (the design boards), writes proto-app/res/drawable-nodpi/apollo_*.png and the
launcher icon layers. Background removal: flood-fill from the crop's border over pixels close to the
board's background colour, then feather the edge by colour distance. The mascot's cream face can't be
reached by the fill because the coral suit surrounds it.
"""
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "Apollo images"
OUT = ROOT / "proto-app" / "res" / "drawable-nodpi"
ICON = ROOT / "proto-app" / "res" / "drawable-nodpi"


def saturation(rgb):
    mx, mn = rgb.max(axis=2), rgb.min(axis=2)
    return (mx - mn) / np.maximum(mx, 1)


def cut(img, box, pad=12, tol=18.0, feather=22.0):
    """Crop `box` (x0, y0, x1, y1) to the saturated subject inside it, and make its background transparent."""
    a = np.asarray(img.convert("RGB").crop(box)).astype(np.float32)
    sat = saturation(a)
    ys, xs = np.where(sat > 0.22)   # coral suit, leaf, stem: everything but cream background and grey text
    if len(xs) == 0:
        raise ValueError(f"nothing saturated in {box}")
    # Largest saturated blob only (drops stray text/label pixels).
    lab, n = ndimage.label(sat > 0.22)
    sizes = ndimage.sum(np.ones_like(sat), lab, range(1, n + 1))
    keep = lab == (1 + int(np.argmax(sizes)))
    keep = ndimage.binary_dilation(keep, iterations=3)
    ys, xs = np.where(keep | ndimage.binary_fill_holes(keep))
    x0, x1 = max(0, xs.min() - pad), min(a.shape[1], xs.max() + pad)
    y0, y1 = max(0, ys.min() - pad), min(a.shape[0], ys.max() + pad)
    a = a[y0:y1, x0:x1]
    bg = np.median(np.concatenate([a[0], a[-1], a[:, 0], a[:, -1]]), axis=0)
    dist = np.sqrt(((a - bg) ** 2).sum(axis=2))
    near = dist < tol + feather
    lab, _ = ndimage.label(near)
    border = set(np.unique(np.concatenate([lab[0], lab[-1], lab[:, 0], lab[:, -1]]))) - {0}
    outside = np.isin(lab, list(border))
    alpha = np.where(outside, np.clip((dist - tol) / feather, 0, 1), 1.0)
    # Un-mix the background from semi-transparent edge pixels so they don't keep a cream halo.
    al = alpha[..., None]
    rgb = np.where(al > 0.02, (a - bg * (1 - al)) / np.maximum(al, 0.02), a)
    out = np.dstack([np.clip(rgb, 0, 255), alpha * 255]).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def fit(im, size):
    """Scale to fit a size x size square, centred, on transparent."""
    im = im.copy()
    im.thumbnail((size, size), Image.LANCZOS)
    sq = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sq.paste(im, ((size - im.width) // 2, (size - im.height) // 2), im)
    return sq


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    faces = Image.open(SRC / "apollo_faces.png")   # 2536 x 1972: two rows of three mood cards
    w = faces.width / 3
    moods = ["hello", "happy", "calm", "curious", "thinking", "wink"]
    for i, mood in enumerate(moods):
        col, row = i % 3, i // 3
        y0, y1 = (470, 930) if row == 0 else (1290, 1750)
        face = cut(faces, (int(col * w + 60), y0, int((col + 1) * w - 60), y1))
        fit(face, 360).save(OUT / f"apollo_{mood}.png")
        print(f"apollo_{mood}.png from {face.size}")

    board = Image.open(SRC / "images" / "02-brand-direction.png")   # the waving full-body mascot, left panel
    body = cut(board, (480, 640, 1100, 1520), tol=14, feather=18)
    body.thumbnail((420, 640), Image.LANCZOS)
    body.save(OUT / "apollo_wave.png")
    print("apollo_wave.png", body.size)

    # Launcher icon: the reference icon's waving apple, cut out and set on white (his background matches what
    # he sits on) for the adaptive icon's foreground (432 px = 108 dp at 4x). It sits low so the mask crops
    # the body like the reference does.
    icon = Image.open(SRC / "images" / "apollo-app-icon.png").convert("RGB")
    apple = cut(icon, (90, 60, icon.width - 60, icon.height - 2), tol=14, feather=16)
    apple.thumbnail((400, 330), Image.LANCZOS)
    fg = Image.new("RGBA", (432, 432), (255, 255, 255, 255))
    fg.alpha_composite(apple, ((432 - apple.width) // 2 + 6, 432 - apple.height + 4))
    fg.convert("RGB").save(ICON / "ic_launcher_art.png")
    print("ic_launcher_art.png, on white")


if __name__ == "__main__":
    main()
