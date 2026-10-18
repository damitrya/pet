#!/usr/bin/env python3
"""
Procedural generator for the second companion character — the fox cub.

Every emotion is described as a sequence of poses (body squash, head tilt,
ear/tail angles, eye and mouth shapes, floating extras). Each pose is drawn
with vector primitives at 4x resolution, outlined, downscaled to 128x128
and packed into an animated WebP in app/src/main/res/raw/fox_<state>.webp.

Usage:
    python3 tools/fox/generate.py            # write all WebP files
    python3 tools/fox/generate.py --preview  # also write preview sheet/GIF
"""

import math
import os
import sys

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter

SIZE = 128
SS = 4                       # supersampling factor
W = SIZE * SS
FRAME_MS = 40                # 25 fps

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "res", "raw")

# Palette
ORANGE_TOP = (255, 164, 84)
ORANGE_BOT = (238, 100, 30)
ORANGE_SHADE = (214, 82, 22)
CREAM = (255, 247, 236)
EAR_INNER = (255, 205, 180)
DARK = (58, 30, 22)
OUTLINE = (104, 42, 16)
PAW = (86, 40, 24)
BLUSH = (255, 112, 128)
MOUTH = (150, 40, 40)
TONGUE = (255, 128, 138)
WHITE = (255, 255, 255)


# ---------------------------------------------------------------- geometry

class Tf:
    """2D affine transform (row-major 3x3)."""

    def __init__(self, m=None):
        self.m = np.eye(3) if m is None else m

    def __matmul__(self, other):
        return Tf(self.m @ other.m)

    def apply(self, pts):
        a = np.asarray(pts, dtype=float)
        a = np.hstack([a, np.ones((len(a), 1))])
        return (a @ self.m.T)[:, :2]

    @staticmethod
    def translate(dx, dy):
        return Tf(np.array([[1, 0, dx], [0, 1, dy], [0, 0, 1]], dtype=float))

    @staticmethod
    def rotate(deg, cx, cy):
        r = math.radians(deg)
        c, s = math.cos(r), math.sin(r)
        rot = Tf(np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]], dtype=float))
        return Tf.translate(cx, cy) @ rot @ Tf.translate(-cx, -cy)

    @staticmethod
    def scale(sx, sy, cx, cy):
        sc = Tf(np.array([[sx, 0, 0], [0, sy, 0], [0, 0, 1]], dtype=float))
        return Tf.translate(cx, cy) @ sc @ Tf.translate(-cx, -cy)


IDENTITY = Tf()


def ellipse_pts(cx, cy, rx, ry, rot=0.0, n=72):
    r = math.radians(rot)
    c, s = math.cos(r), math.sin(r)
    out = []
    for i in range(n):
        a = 2 * math.pi * i / n
        x, y = rx * math.cos(a), ry * math.sin(a)
        out.append((cx + x * c - y * s, cy + x * s + y * c))
    return out


def chaikin(pts, iterations=3, closed=True):
    p = list(pts)
    for _ in range(iterations):
        q = []
        n = len(p)
        rng = range(n) if closed else range(n - 1)
        if not closed:
            q.append(p[0])
        for i in rng:
            a, b = p[i], p[(i + 1) % n]
            q.append((0.75 * a[0] + 0.25 * b[0], 0.75 * a[1] + 0.25 * b[1]))
            q.append((0.25 * a[0] + 0.75 * b[0], 0.25 * a[1] + 0.75 * b[1]))
        if not closed:
            q.append(p[-1])
        p = q
    return p


def bezier(p0, p1, p2, n=24):
    out = []
    for i in range(n + 1):
        t = i / n
        x = (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t ** 2 * p2[0]
        y = (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t ** 2 * p2[1]
        out.append((x, y))
    return out


def heart_pts(cx, cy, s):
    out = []
    for i in range(64):
        t = 2 * math.pi * i / 64
        x = 16 * math.sin(t) ** 3
        y = 13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t)
        out.append((cx + x * s / 16, cy - y * s / 16))
    return out


def star_pts(cx, cy, r, inner=0.38, rot=0.0):
    out = []
    for i in range(8):
        a = math.radians(rot) + math.pi * i / 4 - math.pi / 2
        rr = r if i % 2 == 0 else r * inner
        out.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    return chaikin(out, 2)


# ---------------------------------------------------------------- easing

def clamp(x, a=0.0, b=1.0):
    return max(a, min(b, x))


def smooth(x):
    x = clamp(x)
    return x * x * (3 - 2 * x)


def ramp(t, a, b):
    """0 before a, 1 after b, smooth in between."""
    if b == a:
        return 1.0 if t >= a else 0.0
    return smooth((t - a) / (b - a))


def bump(t, a, b):
    """Smooth 0 → 1 → 0 over [a, b]."""
    if t <= a or t >= b:
        return 0.0
    x = (t - a) / (b - a)
    return math.sin(math.pi * x) ** 2


def lerp(a, b, t):
    return a + (b - a) * t


# ---------------------------------------------------------------- canvas

class Canvas:
    def __init__(self):
        self.img = Image.new("RGBA", (W, W), (0, 0, 0, 0))

    @staticmethod
    def new_mask():
        return Image.new("L", (W, W), 0)

    @staticmethod
    def poly_mask(pts, tf=IDENTITY, mask=None):
        mask = mask or Canvas.new_mask()
        p = tf.apply(pts) * SS
        ImageDraw.Draw(mask).polygon([tuple(x) for x in p], fill=255)
        return mask

    @staticmethod
    def stroke_mask(pts, width, tf=IDENTITY, mask=None):
        mask = mask or Canvas.new_mask()
        p = [tuple(x) for x in tf.apply(pts) * SS]
        d = ImageDraw.Draw(mask)
        w = max(1, int(round(width * SS)))
        d.line(p, fill=255, width=w, joint="curve")
        r = w / 2
        for x, y in (p[0], p[-1]):
            d.ellipse((x - r, y - r, x + r, y + r), fill=255)
        return mask

    def paint(self, mask, color, opacity=1.0):
        if opacity <= 0:
            return
        layer = Image.new("RGBA", (W, W), color + (0,))
        a = mask if opacity >= 1 else mask.point(lambda v: int(v * opacity))
        layer.putalpha(a)
        self.img.alpha_composite(layer)

    def paint_gradient(self, mask, top, bot, y0, y1, opacity=1.0):
        ys = np.clip((np.arange(W) / SS - y0) / max(1e-6, y1 - y0), 0, 1)
        col = np.zeros((W, 3), dtype=float)
        for c in range(3):
            col[:, c] = top[c] + (bot[c] - top[c]) * ys
        rgb = np.repeat(col[:, None, :], W, axis=1).astype(np.uint8)
        layer = Image.fromarray(rgb, "RGB").convert("RGBA")
        a = mask if opacity >= 1 else mask.point(lambda v: int(v * opacity))
        layer.putalpha(a)
        self.img.alpha_composite(layer)

    def outlined(self, px, color=OUTLINE):
        """Return a copy with a solid outline of `px` (output pixels) around the silhouette."""
        alpha = self.img.getchannel("A")
        size = int(px * SS) * 2 + 1
        grown = alpha.point(lambda v: 255 if v > 8 else 0).filter(ImageFilter.MaxFilter(size))
        grown = grown.filter(ImageFilter.GaussianBlur(SS * 0.35))
        base = Image.new("RGBA", (W, W), color + (0,))
        base.putalpha(grown)
        base.alpha_composite(self.img)
        return base


def clip(mask, by):
    return ImageChops.multiply(mask, by)


# ---------------------------------------------------------------- pose

def default_pose():
    return dict(
        sx=1.0, sy=1.0, dx=0.0, dy=0.0, lean=0.0,
        head_tilt=0.0, head_dy=0.0,
        ear_out=0.0, ear_scale=1.0, ear_l=0.0, ear_r=0.0,
        tail=0.0, tail_curl=0.0,
        eyes="open", open=1.0, look=(0.0, 0.0),
        mouth="w", mouth_open=1.0, blush=0.5,
        extras=[],
    )


GROUND = (64, 122)
# Shrinks the whole character a little so ears (jumps) and tail (sway) stay inside the frame.
FIT = Tf.scale(0.9, 0.9, 62, 124)
NECK = (64, 92)


def draw_character(pose):
    cv = Canvas()

    body_tf = (FIT
               @ Tf.translate(pose["dx"], pose["dy"])
               @ Tf.rotate(pose["lean"], *GROUND)
               @ Tf.scale(pose["sx"], pose["sy"], *GROUND))
    head_tf = body_tf @ Tf.translate(0, pose["head_dy"]) @ Tf.rotate(pose["head_tilt"], *NECK)
    grad_dy = pose["dy"]

    def g(y):  # gradient stop in canvas space (follows FIT)
        return 124 + (y - 124) * 0.9

    # ---- tail (behind everything)
    base = (84, 108)
    curl = pose["tail_curl"]
    centre = bezier(base, (120, 100), (110 - 6 * curl, 56 + 4 * curl), n=30)
    tail_tf = body_tf @ Tf.rotate(pose["tail"], *base)
    tail_mask = Canvas.new_mask()
    tip_mask = Canvas.new_mask()
    for i, (x, y) in enumerate(centre):
        t = i / (len(centre) - 1)
        r = 6 + 9 * math.sin(math.pi * min(1.0, t * 0.92 + 0.08)) ** 0.8
        if t > 0.9:
            r *= 1 - (t - 0.9) * 4
        Canvas.poly_mask(ellipse_pts(x, y, r, r, n=32), tail_tf, tail_mask)
        if t > 0.74:
            Canvas.poly_mask(ellipse_pts(x, y, r, r, n=32), tail_tf, tip_mask)
    cv.paint_gradient(tail_mask, ORANGE_TOP, ORANGE_BOT, g(50 + grad_dy), g(110 + grad_dy))
    cv.paint(tip_mask, CREAM)

    # ---- body
    body = ellipse_pts(64, 103, 30, 20)
    body_mask = Canvas.poly_mask(body, body_tf)
    cv.paint_gradient(body_mask, ORANGE_TOP, ORANGE_BOT, g(88 + grad_dy), g(122 + grad_dy))
    belly = Canvas.poly_mask(ellipse_pts(64, 108, 15, 13), body_tf)
    cv.paint(clip(belly, body_mask), CREAM)
    neck_shadow = Canvas.poly_mask(ellipse_pts(64, 88, 30, 8), body_tf)
    cv.paint(clip(neck_shadow, body_mask), ORANGE_SHADE, 0.45)
    for px in (49, 79):
        cv.paint(Canvas.poly_mask(ellipse_pts(px, 119, 9, 5.5), body_tf), PAW)

    # ---- ears
    es = pose["ear_scale"]
    for side in (-1, 1):
        bx = 64 + side * 22
        ang = side * pose["ear_out"] + (pose["ear_l"] if side < 0 else pose["ear_r"])
        ear_tf = head_tf @ Tf.rotate(ang, bx, 36) @ Tf.scale(1, es, bx, 36)
        outer = chaikin([(bx - side * 14, 40), (bx + side * 9, 6), (bx + side * 13, 40)], 3)
        inner = chaikin([(bx - side * 7, 37), (bx + side * 8, 13), (bx + side * 9, 37)], 3)
        ear_mask = Canvas.poly_mask(outer, ear_tf)
        cv.paint_gradient(ear_mask, ORANGE_TOP, ORANGE_BOT, g(6 + grad_dy), g(40 + grad_dy))
        cv.paint(clip(Canvas.poly_mask(inner, ear_tf), ear_mask), EAR_INNER)
        tip = Canvas.poly_mask(ellipse_pts(bx + side * 8, 12, 8, 7), ear_tf)
        cv.paint(clip(tip, ear_mask), PAW, 0.9)

    # ---- head
    head_mask = Canvas.poly_mask(ellipse_pts(64, 58, 40, 33), head_tf)
    for side in (-1, 1):
        tuft = chaikin([(64 + side * 30, 58), (64 + side * 47, 80), (64 + side * 22, 82)], 2)
        Canvas.poly_mask(tuft, head_tf, head_mask)
    cv.paint_gradient(head_mask, ORANGE_TOP, ORANGE_BOT, g(24 + grad_dy), g(90 + grad_dy))

    face = Canvas.new_mask()
    for pts in (ellipse_pts(47, 74, 19, 14, -12), ellipse_pts(81, 74, 19, 14, 12),
                ellipse_pts(64, 81, 15, 9)):
        Canvas.poly_mask(pts, head_tf, face)
    cv.paint(clip(face, head_mask), CREAM)

    # forehead highlight
    hl = Canvas.poly_mask(ellipse_pts(48, 37, 12, 5, -18), head_tf)
    cv.paint(clip(hl, head_mask), WHITE, 0.35)

    # blush
    if pose["blush"] > 0:
        for side in (-1, 1):
            m = Canvas.poly_mask(ellipse_pts(64 + side * 27, 73, 6.5, 3.6), head_tf)
            cv.paint(clip(m, head_mask), BLUSH, pose["blush"])

    draw_eyes(cv, pose, head_tf)

    # nose
    nose = chaikin([(59, 70), (69, 70), (64, 75.5)], 3)
    cv.paint(Canvas.poly_mask(nose, head_tf), DARK)
    cv.paint(Canvas.poly_mask(ellipse_pts(62.3, 70.8, 1.6, 0.9), head_tf), WHITE, 0.7)

    draw_mouth(cv, pose, head_tf)

    out = cv.outlined(1.4)
    for extra in pose["extras"]:
        draw_extra(out, extra)
    return out


def draw_eyes(cv, pose, tf):
    kind = pose["eyes"]
    lx, ly = pose["look"]
    for side in (-1, 1):
        ex, ey = 64 + side * 17 + lx * 2.2, 59 + ly * 1.6
        if kind == "open":
            op = clamp(pose["open"], 0.0, 1.0)
            if op < 0.2:
                arc = bezier((ex - 6, ey + 1), (ex, ey + 5), (ex + 6, ey + 1), 12)
                cv.paint(Canvas.stroke_mask(arc, 2.0, tf), DARK)
                continue
            ry = 8.2 * op
            eye_mask = Canvas.poly_mask(ellipse_pts(ex, ey + (8.2 - ry) * 0.5, 6.3, ry), tf)
            cv.paint(eye_mask, DARK)
            hx, hy = ex + 1.8 + lx * 0.8, ey - 3.0 * op + ly * 0.6
            cv.paint(clip(Canvas.poly_mask(ellipse_pts(hx, hy, 2.4, 2.4 * min(1, op * 1.3)), tf), eye_mask), WHITE)
            cv.paint(clip(Canvas.poly_mask(ellipse_pts(ex - 2.2, ey + 3.2 * op, 1.2, 1.2), tf), eye_mask), WHITE, 0.85)
        elif kind == "happy":
            arc = bezier((ex - 6.5, ey + 3), (ex, ey - 7), (ex + 6.5, ey + 3), 14)
            cv.paint(Canvas.stroke_mask(arc, 2.4, tf), DARK)
        elif kind == "closed":
            arc = bezier((ex - 6.5, ey), (ex, ey + 5.5), (ex + 6.5, ey), 14)
            cv.paint(Canvas.stroke_mask(arc, 2.2, tf), DARK)
        elif kind == "wide":
            white = Canvas.poly_mask(ellipse_pts(ex, ey, 8, 9), tf)
            cv.paint(Canvas.poly_mask(ellipse_pts(ex, ey, 9.2, 10.2), tf), DARK)
            cv.paint(white, WHITE)
            cv.paint(Canvas.poly_mask(ellipse_pts(ex + lx * 2, ey + 0.5 + ly, 3.6, 4.2), tf), DARK)
            cv.paint(Canvas.poly_mask(ellipse_pts(ex + 1.2 + lx * 2, ey - 1.2 + ly, 1.2, 1.2), tf), WHITE)


def draw_mouth(cv, pose, tf):
    kind = pose["mouth"]
    if kind == "w":
        for side in (-1, 1):
            arc = bezier((64, 75.5), (64 + side * 1.5, 79.5), (64 + side * 5, 77.5), 10)
            cv.paint(Canvas.stroke_mask(arc, 1.5, tf), DARK)
    elif kind == "open":
        k = clamp(pose["mouth_open"], 0.2, 1.0)
        top = 77.5
        shape = chaikin([(58.5, top), (69.5, top), (66.5, top + 6.5 * k), (61.5, top + 6.5 * k)], 3)
        m = Canvas.poly_mask(shape, tf)
        cv.paint(m, MOUTH)
        tongue = Canvas.poly_mask(ellipse_pts(64, top + 6.0 * k, 3.6, 2.6 * k), tf)
        cv.paint(clip(tongue, m), TONGUE)
        for side in (-1, 1):
            arc = bezier((64, 75.5), (64 + side * 1.5, 78), (64 + side * 5.5, 77.5), 8)
            cv.paint(Canvas.stroke_mask(arc, 1.4, tf), DARK)
    elif kind == "o":
        cv.paint(Canvas.poly_mask(ellipse_pts(64, 80.5, 3.6, 4.4), tf), MOUTH)
        cv.paint(Canvas.poly_mask(ellipse_pts(64, 82.5, 2.2, 1.6), tf), TONGUE)


# ---------------------------------------------------------------- extras

def _sticker(pts_list, color, stroke_list=(), outline=WHITE, opacity=1.0, out_px=1.2):
    cv = Canvas()
    for pts in pts_list:
        cv.paint(Canvas.poly_mask(pts), color)
    for pts, w in stroke_list:
        cv.paint(Canvas.stroke_mask(pts, w), color)
    img = cv.outlined(out_px, outline)
    if opacity < 1:
        a = img.getchannel("A").point(lambda v: int(v * opacity))
        img.putalpha(a)
    return img


def draw_extra(img, e):
    kind = e["kind"]
    op = e.get("opacity", 1.0)
    if op <= 0.01:
        return
    x, y, s = e["x"], e["y"], e.get("scale", 1.0)
    if s <= 0.01:
        return
    if kind == "note":
        col = e.get("color", (120, 80, 220))
        head1 = ellipse_pts(x, y, 3.6 * s, 2.8 * s, -20)
        strokes = [([(x + 3.0 * s, y - 0.5 * s), (x + 3.0 * s, y - 13 * s)], 1.6 * s),
                   (bezier((x + 3.0 * s, y - 13 * s), (x + 8 * s, y - 10 * s), (x + 7 * s, y - 5 * s), 8), 1.6 * s)]
        layer = _sticker([head1], col, strokes, opacity=op)
    elif kind == "heart":
        layer = _sticker([heart_pts(x, y, 7 * s)], e.get("color", (255, 82, 120)), opacity=op)
    elif kind == "sparkle":
        layer = _sticker([star_pts(x, y, 6 * s, rot=e.get("rot", 0))], (255, 210, 60), opacity=op)
    elif kind == "bang":
        bar = chaikin([(x - 3 * s, y - 16 * s), (x + 3 * s, y - 16 * s), (x + 1.6 * s, y - 3 * s), (x - 1.6 * s, y - 3 * s)], 2)
        dot = ellipse_pts(x, y + 2.5 * s, 2.4 * s, 2.4 * s)
        layer = _sticker([bar, dot], (240, 60, 50), opacity=op, out_px=1.5)
    elif kind == "sweat":
        drop = chaikin([(x, y - 6 * s), (x + 3.8 * s, y + 1.5 * s), (x, y + 4.5 * s), (x - 3.8 * s, y + 1.5 * s)], 3)
        layer = _sticker([drop], (110, 190, 255), opacity=op)
    elif kind == "shock":
        strokes = []
        for a in e.get("angles", (-150, -120, -90)):
            r = math.radians(a)
            r0, r1 = 3 * s, 9 * s
            strokes.append(([(x + r0 * math.cos(r), y + r0 * math.sin(r)),
                             (x + r1 * math.cos(r), y + r1 * math.sin(r))], 1.8))
        layer = _sticker([], (240, 60, 50), strokes, opacity=op, out_px=1.0)
    else:
        return
    img.alpha_composite(layer)


# ---------------------------------------------------------------- emotions

def breathe(p, t, amount=1.0, cycles=1):
    b = math.sin(2 * math.pi * t * cycles)
    p["sy"] *= 1 + 0.022 * b * amount
    p["sx"] *= 1 - 0.014 * b * amount


def idle_small(i, n):
    t = i / n
    p = default_pose()
    breathe(p, t)
    p["tail"] = 7 * math.sin(2 * math.pi * t)
    p["ear_r"] = 14 * bump(t, 0.66, 0.74) + 8 * bump(t, 0.74, 0.8)
    p["open"] = 1 - bump(t, 0.3, 0.38)
    return p


def idle2(i, n):
    t = i / n
    p = default_pose()
    breathe(p, t)
    look = -ramp(t, 0.08, 0.18) * (1 - ramp(t, 0.42, 0.52)) + ramp(t, 0.52, 0.62) * (1 - ramp(t, 0.84, 0.94))
    p["look"] = (look * 1.6, 0.0)
    p["head_tilt"] = look * 6
    p["ear_l"] = -look * 4
    p["ear_r"] = -look * 4
    p["tail"] = 10 * math.sin(4 * math.pi * t)
    p["open"] = 1 - bump(t, 0.46, 0.54)
    return p


def blink(i, n):
    t = i / n
    p = default_pose()
    breathe(p, t, 0.6)
    p["tail"] = 4 * math.sin(2 * math.pi * t)
    p["open"] = 1 - bump(t, 0.25, 0.5) * 1.0 - bump(t, 0.55, 0.75) * 0.9
    return p


def _notes(t, n_notes, x0, x1, side, palette):
    out = []
    for k in range(n_notes):
        ph = (t + k / n_notes) % 1.0
        out.append(dict(
            kind="note",
            x=lerp(x0, x1, ph) + side * 3 * math.sin(ph * 2 * math.pi * 1.5),
            y=lerp(46, 6, ph),
            scale=0.7 + 0.35 * smooth(ph * 3),
            opacity=ramp(ph, 0.0, 0.15) * (1 - ramp(ph, 0.75, 1.0)),
            color=palette[k % len(palette)],
        ))
    return out


NOTE_COLORS = [(120, 80, 220), (40, 160, 200), (230, 80, 160)]


def music(i, n):
    t = i / n
    beat = (i % 15) / 15
    p = default_pose()
    pulse = math.exp(-beat * 6)
    p["sy"] = 1 - 0.06 * pulse
    p["sx"] = 1 + 0.04 * pulse
    p["head_tilt"] = 7 * math.sin(4 * math.pi * t)
    p["head_dy"] = 1.5 * pulse
    p["ear_l"] = -6 * math.sin(4 * math.pi * t)
    p["ear_r"] = -6 * math.sin(4 * math.pi * t)
    p["tail"] = 14 * math.sin(4 * math.pi * t)
    p["eyes"] = "happy"
    p["mouth"] = "open"
    p["mouth_open"] = 0.5 + 0.4 * pulse
    p["blush"] = 0.7
    p["extras"] = _notes(t, 3, 100, 112, 1, NOTE_COLORS)
    return p


def music2(i, n):
    t = i / n
    beat = (i % 15) / 15
    p = default_pose()
    sway = math.sin(2 * math.pi * t * 2)
    p["lean"] = 5 * sway
    p["dx"] = 2.5 * sway
    p["dy"] = -2.5 * abs(math.sin(2 * math.pi * t * 2))
    p["head_tilt"] = -4 * sway
    p["ear_l"] = 6 * sway
    p["ear_r"] = 6 * sway
    p["tail"] = -12 * sway
    p["look"] = (0.6 * sway, -0.4)
    p["mouth"] = "open" if (i // 15) % 2 == 0 else "w"
    p["mouth_open"] = 0.4 + 0.6 * math.sin(math.pi * beat)
    p["blush"] = 0.6
    p["extras"] = _notes(t, 3, 26, 14, -1, NOTE_COLORS[::-1])
    return p


def tap(i, n):
    t = i / n
    p = default_pose()
    squash = bump(t, 0.0, 0.16)
    jump = bump(t, 0.12, 0.5)
    land = bump(t, 0.46, 0.64)
    p["sy"] = 1 - 0.16 * squash + 0.07 * jump - 0.10 * land
    p["sx"] = 1 + 0.12 * squash - 0.04 * jump + 0.08 * land
    p["dy"] = -6 * math.sin(math.pi * clamp((t - 0.14) / 0.36)) if 0.14 < t < 0.5 else 0
    p["ear_out"] = -8 * jump + 10 * land
    p["tail"] = 20 * math.sin(2 * math.pi * t * 2.5) * (1 - ramp(t, 0.7, 1.0))
    happy = t < 0.78
    p["eyes"] = "happy" if happy else "open"
    p["open"] = ramp(t, 0.78, 0.9)
    p["mouth"] = "open" if t < 0.72 else "w"
    p["mouth_open"] = 0.9
    p["blush"] = 0.5 + 0.45 * (1 - ramp(t, 0.7, 1.0))
    sp = []
    for k, (sx, sy, d) in enumerate(((20, 30, 0.08), (108, 22, 0.16), (100, 62, 0.24))):
        life = clamp((t - d) / 0.5)
        if 0 < life < 1:
            sp.append(dict(kind="sparkle", x=sx, y=sy - 6 * life,
                           scale=math.sin(math.pi * life) * 1.1, rot=60 * life,
                           opacity=1 - ramp(life, 0.7, 1.0)))
    p["extras"] = sp
    return p


def alert(i, n):
    t = i / n
    p = default_pose()
    hit = bump(t, 0.0, 0.14)
    calm = ramp(t, 0.72, 0.95)
    shake = math.sin(i * 2.3) * (1 - ramp(t, 0.05, 0.45))
    p["sy"] = 1 - 0.12 * hit + 0.05 * bump(t, 0.1, 0.3)
    p["sx"] = 1 + 0.10 * hit
    p["dx"] = 2.6 * shake
    p["dy"] = 3 * hit
    p["lean"] = -3 * shake
    p["ear_scale"] = 1 + 0.14 * (1 - calm)
    p["ear_out"] = -6 * (1 - calm)
    p["tail"] = -18 * (1 - calm) + 3 * shake
    p["tail_curl"] = 1 - calm
    if t < 0.8:
        p["eyes"] = "wide"
        p["look"] = (0.0, -0.3)
        p["mouth"] = "o"
    else:
        p["eyes"] = "open"
        p["open"] = 0.6 + 0.4 * ramp(t, 0.8, 0.95)
        p["mouth"] = "w"
    p["blush"] = 0.25
    ex = []
    pop = ramp(t, 0.03, 0.12)
    overshoot = 1 + 0.35 * bump(t, 0.06, 0.18)
    bang_op = 1 - ramp(t, 0.7, 0.82)
    ex.append(dict(kind="bang", x=106, y=26, scale=pop * overshoot, opacity=bang_op))
    sw = clamp((t - 0.14) / 0.6)
    if 0 < sw < 1:
        ex.append(dict(kind="sweat", x=24, y=34 + 12 * sw, scale=1.0,
                       opacity=ramp(sw, 0, 0.1) * (1 - ramp(sw, 0.75, 1.0))))
    sh = bump(t, 0.0, 0.4)
    if sh > 0:
        ex.append(dict(kind="shock", x=26, y=24, scale=0.6 + 0.6 * sh, opacity=min(1.0, sh * 2)))
    p["extras"] = ex
    return p


def pet(i, n):
    t = i / n
    p = default_pose()
    breathe(p, t, 1.4, cycles=2)
    p["head_tilt"] = 7 * math.sin(2 * math.pi * t)
    p["ear_out"] = 14
    p["ear_scale"] = 0.9
    p["ear_l"] = 3 * math.sin(2 * math.pi * t * 2)
    p["ear_r"] = -3 * math.sin(2 * math.pi * t * 2)
    p["tail"] = 16 * math.sin(2 * math.pi * t * 3)
    p["eyes"] = "closed"
    p["mouth"] = "w"
    p["blush"] = 0.95
    hearts = []
    for k, (hx, hy) in enumerate(((104, 40), (22, 34))):
        ph = (t + k * 0.5) % 1.0
        hearts.append(dict(kind="heart", x=hx + 3 * math.sin(ph * 2 * math.pi), y=hy - 26 * ph,
                           scale=0.6 + 0.5 * smooth(ph * 2.5),
                           opacity=ramp(ph, 0, 0.12) * (1 - ramp(ph, 0.7, 1.0))))
    p["extras"] = hearts
    return p


EMOTIONS = {
    # file suffix: (pose function, frame count)
    "idle_small": (idle_small, 60),
    "idle2": (idle2, 60),
    "blink": (blink, 30),
    "music": (music, 60),
    "music2": (music2, 60),
    "tap": (tap, 40),
    "alert": (alert, 50),
    "pet": (pet, 48),
}


# ---------------------------------------------------------------- output

def render(fn, n):
    frames = []
    for i in range(n):
        big = draw_character(fn(i, n))
        small = big.convert("RGBa").resize((SIZE, SIZE), Image.LANCZOS).convert("RGBA")
        frames.append(small)
    return frames


def save_webp(frames, path):
    frames[0].save(path, save_all=True, append_images=frames[1:], duration=FRAME_MS,
                   loop=0, lossless=False, quality=90, alpha_quality=100, method=6)


def save_preview(all_frames, out_dir):
    names = list(all_frames)
    cols = 4
    rows = math.ceil(len(names) / cols)
    cell = 192
    bg = (236, 238, 242, 255)
    longest = max(len(f) for f in all_frames.values())
    gif = []
    for i in range(longest):
        sheet = Image.new("RGBA", (cols * cell, rows * cell), bg)
        for k, name in enumerate(names):
            fr = all_frames[name]
            img = fr[i % len(fr)].resize((cell - 16, cell - 16), Image.LANCZOS)
            sheet.alpha_composite(img, ((k % cols) * cell + 8, (k // cols) * cell + 8))
        gif.append(sheet.convert("RGB"))
    gif[0].save(os.path.join(out_dir, "fox_preview.gif"), save_all=True,
                append_images=gif[1:], duration=FRAME_MS, loop=0)
    still = Image.new("RGBA", (cols * cell, rows * cell), bg)
    for k, name in enumerate(names):
        fr = all_frames[name]
        img = fr[len(fr) // 3].resize((cell - 16, cell - 16), Image.LANCZOS)
        still.alpha_composite(img, ((k % cols) * cell + 8, (k // cols) * cell + 8))
    still.convert("RGB").save(os.path.join(out_dir, "fox_preview.png"))


def main():
    preview_dir = None
    if "--preview" in sys.argv:
        idx = sys.argv.index("--preview")
        preview_dir = sys.argv[idx + 1] if len(sys.argv) > idx + 1 else os.path.dirname(__file__)
    only = [a for a in sys.argv[1:] if a in EMOTIONS]
    all_frames = {}
    for name, (fn, n) in EMOTIONS.items():
        if only and name not in only:
            continue
        frames = render(fn, n)
        all_frames[name] = frames
        path = os.path.join(OUT_DIR, f"fox_{name}.webp")
        save_webp(frames, path)
        print(f"{path}: {n} frames, {os.path.getsize(path) // 1024} KB")
    if preview_dir:
        os.makedirs(preview_dir, exist_ok=True)
        save_preview(all_frames, preview_dir)


if __name__ == "__main__":
    main()
