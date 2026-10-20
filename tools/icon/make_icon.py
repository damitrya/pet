#!/usr/bin/env python3
"""
Launcher icon of Mimio (adaptive icon: background + foreground layers, all densities).

    python3 tools/icon/make_icon.py            # writes app/src/main/res/mipmap-*/ic_launcher_*.png
    python3 tools/icon/make_icon.py --preview  # also tools/icon/preview.png (circle / squircle / rounded masks)

If tools/icon/source.png exists (a square illustration, e.g. the artwork from the designer, any size,
white margin and rounded corners are fine), the icon is made from it: the picture is trimmed to its
coloured square and placed in the middle of the adaptive icon over a blurred copy of itself, so no
launcher mask cuts the fox's ears. Without it, the icon is drawn here: the fox cub from
tools/fox/generate.py hugging a green slime, on an evening sky with stars.

Also writes tools/icon/ic_launcher_512.png (store listing).
"""

import math
import os
import sys

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
RES = os.path.join(ROOT, "app", "src", "main", "res")
SOURCE = os.path.join(HERE, "source.png")

sys.path.insert(0, os.path.join(ROOT, "tools", "fox"))
import generate as g  # noqa: E402

# Adaptive icon layer: 108 dp; launchers show about the middle 72 dp, the 66 dp circle is always safe.
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
MASTER = 1080                     # px for 108 dp, everything is drawn at this size and scaled down

ANYDPI_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""


def dp(v):
    return v * MASTER / 108


# ---------------------------------------------------------------- drawn version

def sky():
    """Evening sky: blue at the top through violet to pink, soft clouds, sparkling stars."""
    h = MASTER
    ys = np.linspace(0, 1, h)[:, None]
    stops = [(0.0, (46, 132, 255)), (0.5, (150, 100, 245)), (0.85, (255, 128, 200)), (1.0, (255, 170, 150))]
    rgb = np.zeros((h, 1, 3))
    for (a, ca), (b, cb) in zip(stops, stops[1:]):
        k = np.clip((ys - a) / (b - a), 0, 1)
        inside = (ys >= a) & (ys <= b)
        for c in range(3):
            rgb[:, :, c] = np.where(inside, ca[c] + (cb[c] - ca[c]) * k, rgb[:, :, c])
    img = Image.fromarray(np.repeat(rgb, h, axis=1).astype(np.uint8), "RGB").convert("RGBA")

    clouds = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(clouds)
    for cx, cy, r, col in ((12, 70, 16, (255, 190, 220)), (24, 76, 14, (255, 170, 205)), (96, 64, 15, (255, 186, 225)),
                           (86, 74, 14, (255, 160, 200)), (104, 84, 16, (255, 196, 180)), (6, 88, 18, (255, 200, 170))):
        d.ellipse([dp(cx - r), dp(cy - r * 0.7), dp(cx + r), dp(cy + r * 0.7)], fill=col + (170,))
    img.alpha_composite(clouds.filter(ImageFilter.GaussianBlur(dp(2.2))))

    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    stars = Image.new("RGBA", img.size, (0, 0, 0, 0))
    gd, sd = ImageDraw.Draw(glow), ImageDraw.Draw(stars)
    for x, y, r in ((26, 22, 4.2), (44, 13, 2.6), (84, 17, 3.2), (93, 36, 3.8), (16, 44, 2.4), (100, 56, 2.2)):
        pts = []
        for k in range(8):
            a = math.pi * k / 4 - math.pi / 2
            rr = r if k % 2 == 0 else r * 0.3
            pts.append((dp(x + rr * math.cos(a)), dp(y + rr * math.sin(a))))
        sd.polygon(pts, fill=(255, 248, 210, 255))
        gd.ellipse([dp(x - r * 1.4), dp(y - r * 1.4), dp(x + r * 1.4), dp(y + r * 1.4)], fill=(255, 240, 200, 120))
    for x, y in ((36, 30), (70, 10), (102, 28), (10, 30), (60, 22)):
        sd.ellipse([dp(x - 0.9), dp(y - 0.9), dp(x + 0.9), dp(y + 0.9)], fill=(255, 250, 230, 230))
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(dp(1.6))))
    img.alpha_composite(stars)
    return img


SLIME_TOP = (186, 246, 96)
SLIME_BOT = (92, 200, 60)
SLIME_DARK = (40, 96, 34)


def _slime(cv, pr, tf, body_tf, lb):
    """Green slime with a sprout, happy closed eyes and an open smile (a prop: hugged by the fox's paw)."""
    C = g.Canvas
    blob = g.chaikin([(-17, 10), (-16, -4), (-9, -14), (0, -16), (9, -14), (16, -4), (17, 10), (8, 14), (-8, 14)], 3)
    m = C.poly_mask(blob, tf)
    for fx in (-9, 9):
        C.poly_mask(g.ellipse_pts(fx, 12, 7, 5), tf, m)
    y0, y1 = (float(tf.apply([(0, yy)])[0][1]) for yy in (-16, 16))
    cv.paint_gradient(m, SLIME_TOP, SLIME_BOT, y0, y1)
    cv.paint(g.clip(C.poly_mask(g.ellipse_pts(-7, -8, 5, 3, -25), tf), m), (255, 255, 255), 0.7)
    cv.paint(g.clip(C.poly_mask(g.ellipse_pts(-11, -2, 1.6, 1.6), tf), m), (255, 255, 255), 0.8)
    for side in (-1, 1):
        arc = g.bezier((side * 6.5 - 3.2, -1), (side * 6.5, -5.5), (side * 6.5 + 3.2, -1), 12)
        cv.paint(C.stroke_mask(arc, 1.7, tf), SLIME_DARK)
        cv.paint(C.poly_mask(g.ellipse_pts(side * 11, 3, 3.2, 2, 0), tf), (255, 128, 128), 0.55)
    mouth = g.chaikin([(-3.5, 2.5), (3.5, 2.5), (2, 7), (-2, 7)], 3)
    cv.paint(C.poly_mask(mouth, tf), (150, 40, 40))
    cv.paint(g.clip(C.poly_mask(g.ellipse_pts(0, 6.5, 2.2, 1.4), tf), C.poly_mask(mouth, tf)), (255, 128, 138))
    cv.paint(C.stroke_mask(g.bezier((0, -15), (0.5, -18), (1.5, -20), 6), 1.4, tf), (70, 150, 50))
    for side, rot in ((-1, -30), (1, 25)):
        leaf = g.ellipse_pts(1.5 + side * 4.5, -21, 5, 2.4, rot)
        cv.paint(C.poly_mask(leaf, tf), (120, 210, 70))
        cv.paint(C.stroke_mask([(1.5 + side * 1.2, -20.4), (1.5 + side * 7, -21.6 + side * 1.4)], 0.6, tf), (80, 160, 50))


def fox_and_slime():
    """The fox cub of the app (same drawing code) leaning toward a slime and hugging it with a paw."""
    g.PROPS["slime"] = _slime
    g.set_size(512)
    p = g.default_pose()
    p["head_tilt"] = -6
    p["lean"] = 3
    p["look"] = (0.7, 0.3)
    p["mouth"] = "open"
    p["mouth_open"] = 0.9
    p["blush"] = 0.75
    p["tail"] = -4
    p["props"] = [dict(kind="slime", x=97, y=103, scale=1.3)]
    p["arm"] = dict(side=1, x=80, y=98)
    p["extras"] = []
    img = g.draw_character(p)
    small = img.convert("RGBa").resize((512, 512), Image.LANCZOS).convert("RGBA")
    g.set_size(128)
    return small


def drawn_layers():
    bg = sky()
    fg = Image.new("RGBA", (MASTER, MASTER), (0, 0, 0, 0))
    char = fox_and_slime()
    bbox = char.getchannel("A").point(lambda v: 255 if v > 8 else 0).getbbox()
    char = char.crop(bbox)
    # Fit inside ~60 dp: everything stays in the 66 dp circle that every launcher mask keeps.
    k = dp(60) / max(char.size)
    char = char.resize((round(char.width * k), round(char.height * k)), Image.LANCZOS)
    shadow = Image.new("RGBA", char.size, (40, 20, 60, 0))
    shadow.putalpha(char.getchannel("A").point(lambda v: int(v * 0.35)))
    shadow = shadow.filter(ImageFilter.GaussianBlur(dp(1.5)))
    x, y = (MASTER - char.width) // 2, round(dp(55) - char.height / 2)
    fg.alpha_composite(shadow, (x, y + round(dp(1.2))))
    fg.alpha_composite(char, (x, y))
    return bg, fg


# ---------------------------------------------------------------- from an image

def source_layers(path):
    src = Image.open(path).convert("RGBA")
    a = np.asarray(src).astype(int)
    # Coloured part: not (near-)white and not transparent.
    colour = (a[:, :, 3] > 200) & (a[:, :, :3].min(axis=2) < 235)
    ys, xs = np.nonzero(colour)
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    side = min(x1 - x0, y1 - y0)
    inset = round(side * 0.05)                      # drop the rounded corners of the artwork
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    half = side // 2 - inset
    art = src.crop((cx - half, cy - half, cx + half, cy + half))
    bg = art.resize((MASTER, MASTER), Image.LANCZOS).filter(ImageFilter.GaussianBlur(dp(4)))
    fg = Image.new("RGBA", (MASTER, MASTER), (0, 0, 0, 0))
    size = round(dp(84))
    piece = art.resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=round(size * 0.18), fill=255)
    piece.putalpha(ImageChops.multiply(piece.getchannel("A"), mask))
    fg.alpha_composite(piece, ((MASTER - size) // 2, (MASTER - size) // 2))
    return bg, fg


# ---------------------------------------------------------------- output

def masked(bg, fg, shape, size):
    full = bg.copy()
    full.alpha_composite(fg)
    # What a launcher shows: the middle 72 dp under its mask.
    cut = round(dp(18))
    view = full.crop((cut, cut, MASTER - cut, MASTER - cut)).resize((size, size), Image.LANCZOS)
    m = Image.new("L", (size * 4, size * 4), 0)
    d = ImageDraw.Draw(m)
    if shape == "circle":
        d.ellipse([0, 0, size * 4 - 1, size * 4 - 1], fill=255)
    else:
        d.rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1], radius=size * 4 * (0.3 if shape == "squircle" else 0.16), fill=255)
    view.putalpha(m.resize((size, size), Image.LANCZOS))
    return view


def main():
    from_source = os.path.exists(SOURCE)
    bg, fg = source_layers(SOURCE) if from_source else drawn_layers()
    for name, k in DENSITIES.items():
        d = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        px = round(108 * k)
        bg.convert("RGB").resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher_background.png"), optimize=True)
        fg.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher_foreground.png"), optimize=True)
    d = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(d, exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(d, name), "w") as fh:
            fh.write(ANYDPI_XML)
    masked(bg, fg, "rounded", 512).save(os.path.join(HERE, "ic_launcher_512.png"))
    if "--preview" in sys.argv:
        sheet = Image.new("RGBA", (3 * 220 + 20, 240), (236, 238, 242, 255))
        for i, shape in enumerate(("circle", "squircle", "rounded")):
            sheet.alpha_composite(masked(bg, fg, shape, 200), (20 + i * 220, 20))
        sheet.convert("RGB").save(os.path.join(HERE, "preview.png"))
    print("icon from", SOURCE if from_source else "the drawing in make_icon.py")


if __name__ == "__main__":
    main()
