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
        arm=None,  # raised front paw(s): dict(side=-1|1, x=.., y=..) or a list of them — paw position, body space
        extras=[],
    )


GROUND = (64, 122)
# Shrinks the whole character a little so ears (jumps) and tail (sway) stay inside the frame.
FIT = Tf.scale(0.9, 0.9, 62, 124)
TAIL_MAX_RIGHT = 6.0
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
    # Swinging right (positive) pushes the tail toward the frame edge — soft-cap it.
    tail_angle = pose["tail"]
    if tail_angle > 0:
        tail_angle = TAIL_MAX_RIGHT * math.tanh(tail_angle / TAIL_MAX_RIGHT)
    tail_tf = body_tf @ Tf.rotate(tail_angle, *base)
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

    arms = pose["arm"]
    for arm in ([arms] if isinstance(arms, dict) else arms or []):
        draw_arm(cv, arm, body_tf)

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
        elif kind == "grumpy":
            # lower half of the eye under a lid slanting down toward the nose
            lid_out, lid_in = ey - 1.5, ey + 1.8
            lid = [(ex - side * 8, lid_out), (ex + side * 8, lid_in)]
            below = Canvas.poly_mask([lid[0], lid[1], (ex + side * 8, ey + 12), (ex - side * 8, ey + 12)], tf)
            eye_mask = clip(Canvas.poly_mask(ellipse_pts(ex, ey + 1, 6.3, 7.5), tf), below)
            cv.paint(eye_mask, DARK)
            cv.paint(clip(Canvas.poly_mask(ellipse_pts(ex + 1.6, ey + 3.2, 1.7, 1.7), tf), eye_mask), WHITE)
            cv.paint(Canvas.stroke_mask([(ex - side * 7.5, lid_out - 0.5), (ex + side * 7.5, lid_in - 0.5)], 2.2, tf), DARK)
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
    elif kind == "yawn":
        k = clamp(pose["mouth_open"], 0.0, 1.0)
        if k < 0.1:
            draw_mouth(cv, dict(pose, mouth="w"), tf)
            return
        m = Canvas.poly_mask(ellipse_pts(64, 79 + 3 * k, 4 + 3.5 * k, 2 + 6.5 * k), tf)
        cv.paint(m, MOUTH)
        cv.paint(clip(Canvas.poly_mask(ellipse_pts(64, 81 + 7 * k, 4.5 * k, 3 * k), tf), m), TONGUE)
    elif kind == "frown":
        arc = bezier((58.5, 81), (64, 76.5), (69.5, 81), 12)
        cv.paint(Canvas.stroke_mask(arc, 1.6, tf), DARK)
    elif kind == "o":
        cv.paint(Canvas.poly_mask(ellipse_pts(64, 80.5, 3.6, 4.4), tf), MOUTH)
        cv.paint(Canvas.poly_mask(ellipse_pts(64, 82.5, 2.2, 1.6), tf), TONGUE)


def draw_arm(cv, arm, tf):
    """Raised front paw: an orange limb from the shoulder with a dark paw at the end."""
    side = arm["side"]
    shoulder = (64 + side * 22, 96)
    paw = (arm["x"], arm["y"])
    elbow = (lerp(shoulder[0], paw[0], 0.5) + side * 6, lerp(shoulder[1], paw[1], 0.5) + 2)
    limb = bezier(shoulder, elbow, paw, 16)
    cv.paint(Canvas.stroke_mask(limb, 16, tf), OUTLINE)
    cv.paint(Canvas.stroke_mask(limb, 12.5, tf), ORANGE_TOP)
    cv.paint(Canvas.poly_mask(ellipse_pts(paw[0], paw[1], 8.2, 7.4), tf), OUTLINE)
    cv.paint(Canvas.poly_mask(ellipse_pts(paw[0], paw[1], 6.6, 5.8), tf), PAW)


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
    elif kind == "zzz":
        h = 4.5 * s
        strokes = [([(x - h, y - h), (x + h, y - h), (x - h, y + h), (x + h, y + h)], 2.0 * s)]
        layer = _sticker([], (90, 110, 210), strokes, opacity=op)
    elif kind == "question":
        hook = bezier((x - 4 * s, y - 9 * s), (x + 6 * s, y - 16 * s), (x + 4 * s, y - 5 * s), 12)
        hook += bezier((x + 4 * s, y - 5 * s), (x, y - 2 * s), (x, y + 1.5 * s), 8)[1:]
        dot = ellipse_pts(x, y + 6 * s, 1.9 * s, 1.9 * s)
        layer = _sticker([dot], (90, 110, 210), [(hook, 2.4 * s)], opacity=op, out_px=1.4)
    elif kind == "anger":
        strokes = []
        for qx, qy in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            a = (x + qx * 2 * s, y + qy * 6.5 * s)
            b = (x + qx * 6.5 * s, y + qy * 2 * s)
            strokes.append((bezier(a, (x + qx * 2 * s, y + qy * 2 * s), b, 8), 2.2 * s))
        layer = _sticker([], (232, 48, 48), strokes, opacity=op, out_px=1.2)
    elif kind == "puff":
        blobs = [ellipse_pts(x + dx * s, y + dy * s, r * s, r * s)
                 for dx, dy, r in ((0, 0, 4.5), (5, -2, 3.8), (-4.5, -1.5, 3.4), (2, -5, 3.4))]
        layer = _sticker(blobs, (240, 244, 252), outline=(150, 160, 185), opacity=op, out_px=0.9)
    elif kind == "phone":
        layer = _phone(x, y, s, e.get("rot", 0.0), op)
    elif kind == "battery":
        layer = _battery(x, y, s, e.get("level", 0.15), e.get("blink", 1.0), op)
    elif kind == "waves":
        strokes = []
        side = e.get("side", 1)
        for k in (0, 1):
            r = (4 + 4 * k) * s
            strokes.append((bezier((x, y - r), (x + side * r * 1.25, y), (x, y + r), 10), 1.6 * s))
        layer = _sticker([], (90, 110, 210), strokes, opacity=op, out_px=1.0)
    elif kind == "drop":
        r = math.radians(e.get("rot", 0.0))
        c, si = math.cos(r), math.sin(r)
        pts = [(0, -5), (3.2, 1.2), (0, 3.8), (-3.2, 1.2)]
        drop = chaikin([(x + (px * c - py * si) * s, y + (px * si + py * c) * s) for px, py in pts], 3)
        layer = _sticker([drop], (110, 190, 255), opacity=op, out_px=1.0)
    elif kind == "whoosh":
        strokes = [([(x - 7 * s + dx, y + dy), (x + 7 * s + dx, y + dy)], 1.8)
                   for dx, dy in ((0, -7 * s), (-5 * s, 0), (2 * s, 7 * s))]
        layer = _sticker([], (150, 165, 195), strokes, opacity=op, out_px=0.8)
    else:
        return
    img.alpha_composite(layer)


def _rect_pts(cx, cy, w, h, rot=0.0, round_=2):
    r = math.radians(rot)
    c, s = math.cos(r), math.sin(r)
    corners = [(-w / 2, -h / 2), (w / 2, -h / 2), (w / 2, h / 2), (-w / 2, h / 2)]
    return chaikin([(cx + px * c - py * s, cy + px * s + py * c) for px, py in corners], round_)


def _phone(x, y, s, rot, op):
    cv = Canvas()
    cv.paint(Canvas.poly_mask(_rect_pts(x, y, 10 * s, 18 * s, rot)), (52, 58, 78))
    cv.paint(Canvas.poly_mask(_rect_pts(x, y - 0.6 * s, 7 * s, 12.5 * s, rot, 1)), (130, 200, 255))
    img = cv.outlined(1.1, WHITE)
    if op < 1:
        img.putalpha(img.getchannel("A").point(lambda v: int(v * op)))
    return img


def _battery(x, y, s, level, blink, op):
    """Horizontal battery; the charge bar is red when low and fades with `blink`."""
    cv = Canvas()
    w, h = 22 * s, 11 * s
    cv.paint(Canvas.poly_mask(_rect_pts(x, y, w, h)), (60, 64, 80))
    cv.paint(Canvas.poly_mask(_rect_pts(x + w / 2 + 1.6 * s, y, 3 * s, 5 * s, 0, 1)), (60, 64, 80))
    cv.paint(Canvas.poly_mask(_rect_pts(x, y, w - 4 * s, h - 4 * s, 0, 1)), WHITE)
    fill_w = max(1.0, (w - 6 * s) * clamp(level))
    col = (236, 56, 48) if level < 0.3 else (70, 200, 90)
    bar = _rect_pts(x - (w - 6 * s) / 2 + fill_w / 2, y, fill_w, h - 6 * s, 0, 1)
    cv.paint(Canvas.poly_mask(bar), col, clamp(blink))
    img = cv.outlined(1.1, WHITE)
    if op < 1:
        img.putalpha(img.getchannel("A").point(lambda v: int(v * op)))
    return img


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
    p["sx"] = 1 + 0.06 * squash - 0.03 * jump + 0.04 * land
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


# ---- wave 1: idle variants, interaction, events

def _floating(t, kind, x, y, rise=26, delay=0.0, life=0.6, size=1.0, drift=3.0, **kw):
    """One sticker that pops in at `delay`, floats up by `rise` and fades out within `life`."""
    ph = (t - delay) / life
    if not 0 < ph < 1:
        return None
    return dict(kind=kind, x=x + drift * math.sin(ph * 2 * math.pi), y=y - rise * ph,
                scale=size * (0.6 + 0.5 * smooth(min(1.0, ph * 2.5))),
                opacity=ramp(ph, 0, 0.12) * (1 - ramp(ph, 0.7, 1.0)), **kw)


def _zzz(t, cycles=1):
    out = []
    for k in range(3):
        ph = (t * cycles + k / 3) % 1.0
        out.append(dict(kind="zzz", x=96 + 10 * ph + 2 * math.sin(ph * 6), y=40 - 30 * ph,
                        scale=0.55 + 0.5 * ph, opacity=ramp(ph, 0, 0.15) * (1 - ramp(ph, 0.75, 1.0))))
    return out


def yawn(i, n):
    t = i / n
    p = default_pose()
    breathe(p, t, 0.6)
    y = bump(t, 0.12, 0.78)                    # whole yawn
    wide = bump(t, 0.2, 0.7)
    p["sy"] *= 1 + 0.06 * wide
    p["sx"] *= 1 - 0.03 * wide
    p["head_tilt"] = -5 * wide
    p["head_dy"] = -1.5 * wide
    p["ear_out"] = 10 * y
    p["ear_scale"] = 1 - 0.06 * y
    p["eyes"] = "closed" if 0.18 < t < 0.76 else "open"
    p["open"] = 1 - bump(t, 0.82, 0.92)
    p["mouth"] = "yawn"
    p["mouth_open"] = wide
    p["tail"] = 6 * math.sin(2 * math.pi * t)
    p["blush"] = 0.4
    tear = _floating(t, "sweat", 39, 56, rise=-6, delay=0.55, life=0.35, size=0.55, drift=0)
    p["extras"] = [tear] if tear else []
    return p


def ear_scratch(i, n):
    t = i / n
    p = default_pose()
    breathe(p, t, 0.6)
    up = ramp(t, 0.06, 0.2) * (1 - ramp(t, 0.8, 0.94))
    scratch = math.sin(2 * math.pi * t * 9) * ramp(t, 0.2, 0.26) * (1 - ramp(t, 0.74, 0.8))
    p["head_tilt"] = 10 * up
    p["lean"] = 3 * up
    p["ear_r"] = -6 * up + 7 * scratch
    p["arm"] = dict(side=1, x=lerp(86, 96 + 2 * scratch, up), y=lerp(108, 50 + 4 * scratch, up)) if up > 0.02 else None
    p["eyes"] = "closed" if up > 0.6 else "open"
    p["mouth"] = "w"
    p["blush"] = 0.5 + 0.3 * up
    p["tail"] = 8 * math.sin(2 * math.pi * t * 2) * up
    return p


def music_nod(i, n):
    t = i / n
    beat = (i % 15) / 15
    p = default_pose()
    nod = math.exp(-beat * 5)
    p["head_dy"] = 4 * nod
    p["head_tilt"] = 3 * math.sin(2 * math.pi * t * 2)
    p["sy"] = 1 - 0.04 * nod
    p["sx"] = 1 + 0.025 * nod
    p["ear_out"] = -4 * nod
    p["ear_scale"] = 1 + 0.05 * nod
    p["tail"] = 14 * math.sin(2 * math.pi * t * 4)
    p["eyes"] = "closed"
    p["mouth"] = "w"
    p["blush"] = 0.65
    p["extras"] = _notes(t, 2, 100, 112, 1, NOTE_COLORS) + _notes((t + 0.5) % 1, 2, 26, 14, -1, NOTE_COLORS[::-1])
    return p


def head_tilt(i, n):
    t = i / n
    p = default_pose()
    breathe(p, t, 0.6)
    tilt = ramp(t, 0.06, 0.24) * (1 - ramp(t, 0.78, 0.96))
    p["head_tilt"] = 16 * tilt
    p["ear_l"] = -10 * tilt
    p["ear_r"] = 6 * tilt
    p["look"] = (0.5 * tilt, -0.8 * tilt)
    p["eyes"] = "open"
    p["open"] = 1 + 0.1 * tilt - bump(t, 0.5, 0.58)
    p["mouth"] = "o" if 0.28 < t < 0.7 else "w"
    p["tail"] = 6 * math.sin(2 * math.pi * t)
    q = tilt * (1 + 0.25 * bump(t, 0.18, 0.34))
    p["extras"] = [dict(kind="question", x=104, y=26, scale=q, opacity=min(1.0, tilt * 1.5))]
    return p


def annoyed(i, n):
    t = i / n
    p = default_pose()
    huff = bump(t, 0.0, 0.18)
    stomp = math.sin(i * 1.9) * bump(t, 0.05, 0.45)
    puff = ramp(t, 0.1, 0.25) * (1 - ramp(t, 0.8, 0.95))
    p["sy"] = 1 - 0.08 * huff
    p["sx"] = 1 + 0.06 * huff + 0.02 * puff
    p["dx"] = 1.5 * stomp
    p["head_tilt"] = -6 * puff
    p["ear_out"] = 16 * puff
    p["ear_scale"] = 1 - 0.1 * puff
    p["tail"] = -14 * puff + 10 * math.sin(i * 0.9) * puff
    p["eyes"] = "grumpy" if t < 0.88 else "open"
    p["look"] = (-0.8 * puff, 0)
    p["mouth"] = "frown" if t < 0.88 else "w"
    p["blush"] = 0.85 * puff
    pulse = 1 + 0.18 * math.sin(i * 0.8)
    ex = [dict(kind="anger", x=100, y=28, scale=ramp(t, 0.04, 0.14) * pulse, opacity=1 - ramp(t, 0.78, 0.9))]
    for k, d in enumerate((0.15, 0.4)):
        f = _floating(t, "puff", 30 - 6 * k, 80, rise=10, delay=d, life=0.35, size=0.8, drift=-4)
        if f:
            ex.append(f)
    p["extras"] = ex
    return p


def hearts(i, n):
    t = i / n
    p = default_pose()
    hop = math.sin(math.pi * clamp((t - 0.05) / 0.3)) if 0.05 < t < 0.35 else 0
    p["dy"] = -5 * hop
    p["sy"] = 1 + 0.04 * hop - 0.06 * bump(t, 0.33, 0.45)
    p["ear_out"] = 8
    p["head_tilt"] = 6 * math.sin(2 * math.pi * t)
    p["tail"] = 18 * math.sin(2 * math.pi * t * 3)
    p["eyes"] = "happy"
    p["mouth"] = "open" if t < 0.6 else "w"
    p["mouth_open"] = 0.7
    p["blush"] = 0.95
    ex = []
    for k, (hx, hy, d) in enumerate(((64, 30, 0.0), (100, 44, 0.12), (26, 42, 0.22), (84, 22, 0.34))):
        f = _floating(t, "heart", hx, hy, rise=28, delay=d, life=0.6, size=0.9 if k == 0 else 0.7)
        if f:
            ex.append(f)
    p["extras"] = ex
    return p


def relief(i, n):
    t = i / n
    p = default_pose()
    sigh = bump(t, 0.05, 0.6)
    p["sy"] = 1 + 0.04 * bump(t, 0.0, 0.2) - 0.07 * ramp(t, 0.2, 0.5) * (1 - ramp(t, 0.75, 1.0))
    p["sx"] = 1 + 0.04 * ramp(t, 0.2, 0.5) * (1 - ramp(t, 0.75, 1.0))
    p["head_dy"] = 2 * sigh
    p["ear_out"] = 10 * sigh
    p["eyes"] = "closed" if 0.12 < t < 0.7 else "open"
    p["open"] = 0.7 + 0.3 * ramp(t, 0.7, 0.85)
    p["mouth"] = "o" if 0.2 < t < 0.5 else "w"
    p["blush"] = 0.5
    p["tail"] = 5 * math.sin(2 * math.pi * t)
    ex = []
    f = _floating(t, "puff", 46, 82, rise=12, delay=0.22, life=0.5, size=0.9, drift=-6)
    if f:
        ex.append(f)
    sw = 1 - ramp(t, 0.0, 0.45)
    if sw > 0:
        ex.append(dict(kind="sweat", x=24, y=46 + 6 * (1 - sw), scale=1.0, opacity=sw))
    p["extras"] = ex
    return p


def greeting(i, n):
    t = i / n
    p = default_pose()
    pop = ramp(t, 0.0, 0.18)
    over = bump(t, 0.12, 0.28)
    p["sy"] = 0.7 + 0.3 * pop + 0.06 * over
    p["sx"] = 1.12 - 0.12 * pop - 0.03 * over
    p["dy"] = 8 * (1 - pop)
    raise_ = ramp(t, 0.18, 0.3) * (1 - ramp(t, 0.84, 0.96))
    wave = math.sin(2 * math.pi * (t - 0.3) * 4) * ramp(t, 0.3, 0.36) * (1 - ramp(t, 0.78, 0.84))
    if raise_ > 0.02:
        p["arm"] = dict(side=-1, x=lerp(42, 24 + 5 * wave, raise_), y=lerp(108, 62 - 3 * abs(wave), raise_))
    p["lean"] = 3 * wave * raise_
    p["head_tilt"] = -6 * raise_
    p["ear_out"] = -6 * over + 4 * raise_
    p["tail"] = 16 * math.sin(2 * math.pi * t * 3)
    p["eyes"] = "happy" if 0.2 < t < 0.85 else "open"
    p["mouth"] = "open" if 0.2 < t < 0.85 else "w"
    p["mouth_open"] = 0.8
    p["blush"] = 0.7
    ex = []
    for sx_, sy_, d in ((104, 30, 0.2), (98, 66, 0.35)):
        f = _floating(t, "sparkle", sx_, sy_, rise=6, delay=d, life=0.4, size=1.0, drift=0, rot=60 * t)
        if f:
            ex.append(f)
    p["extras"] = ex
    return p


def trip_start(i, n):
    t = i / n
    p = default_pose()
    hops = [(0.06, 0.34), (0.4, 0.68)]
    h = 0.0
    for a, b in hops:
        if a < t < b:
            h = math.sin(math.pi * (t - a) / (b - a))
    land = bump(t, 0.32, 0.42) + bump(t, 0.66, 0.76)
    p["dy"] = -7 * h
    p["sy"] = 1 + 0.06 * h - 0.1 * land
    p["sx"] = 1 - 0.03 * h + 0.06 * land
    p["lean"] = -4 * h
    p["ear_out"] = -8 * h + 6 * land
    p["tail"] = 22 * math.sin(2 * math.pi * t * 3) * (1 - ramp(t, 0.8, 1.0))
    p["eyes"] = "happy" if t < 0.82 else "open"
    p["mouth"] = "open" if t < 0.8 else "w"
    p["mouth_open"] = 1.0
    p["blush"] = 0.8
    ex = []
    wh = bump(t, 0.05, 0.75)
    if wh > 0:
        ex.append(dict(kind="whoosh", x=18, y=92, scale=1.0, opacity=wh))
    for sx_, sy_, d in ((22, 30, 0.08), (106, 24, 0.2), (100, 64, 0.42)):
        f = _floating(t, "sparkle", sx_, sy_, rise=6, delay=d, life=0.4, size=1.1, drift=0, rot=80 * t)
        if f:
            ex.append(f)
    p["extras"] = ex
    return p


def fall_asleep(i, n):
    t = i / n
    p = default_pose()
    breathe(p, t, 1.2)
    # two drowsy head drops, the second one stays down
    drop1 = bump(t, 0.15, 0.45)
    drop2 = ramp(t, 0.55, 0.8)
    d = max(drop1 * 0.6, drop2)
    p["head_dy"] = 3 * d
    p["head_tilt"] = 7 * d
    p["ear_out"] = 16 * ramp(t, 0.1, 0.8)
    p["ear_scale"] = 1 - 0.06 * ramp(t, 0.1, 0.8)
    p["sy"] *= 1 - 0.03 * drop2
    p["eyes"] = "open" if t < 0.6 else "closed"
    p["open"] = 0.6 - 0.35 * drop1 + 0.15 * (1 - ramp(t, 0.0, 0.1))
    p["mouth"] = "w"
    p["blush"] = 0.45
    p["tail"] = 4 * math.sin(2 * math.pi * t) * (1 - drop2)
    p["extras"] = [z for z in _zzz(t * 0.5) if t > 0.78]
    return p


def sleep(i, n):
    t = i / n
    p = default_pose()
    breathe(p, t, 2.0)
    p["head_dy"] = 3 + 0.8 * math.sin(2 * math.pi * t)
    p["head_tilt"] = 7
    p["ear_out"] = 16
    p["ear_scale"] = 0.94
    p["ear_r"] = 6 * bump(t, 0.6, 0.66)          # a twitch in its sleep
    p["sy"] *= 0.97
    p["eyes"] = "closed"
    p["mouth"] = "w"
    p["blush"] = 0.45
    p["tail"] = 3 * math.sin(2 * math.pi * t)
    p["extras"] = _zzz(t)
    return p


def wake(i, n):
    t = i / n
    p = default_pose()
    up = ramp(t, 0.15, 0.4)
    stretch = bump(t, 0.3, 0.7)
    p["head_dy"] = 3 * (1 - up)
    p["head_tilt"] = 7 * (1 - up)
    p["ear_out"] = 16 * (1 - up) - 6 * bump(t, 0.35, 0.55)
    p["ear_scale"] = 0.94 + 0.06 * up + 0.06 * bump(t, 0.35, 0.55)
    p["sy"] = 0.97 + 0.03 * up + 0.07 * stretch
    p["sx"] = 1 - 0.03 * stretch
    p["eyes"] = "closed" if t < 0.18 else "open"
    p["open"] = 0.4 + 0.6 * ramp(t, 0.2, 0.45) - bump(t, 0.55, 0.65) * 0.9
    p["mouth"] = "yawn" if 0.3 < t < 0.7 else "w"
    p["mouth_open"] = stretch * 0.7
    p["blush"] = 0.5
    p["tail"] = 10 * math.sin(2 * math.pi * t * 2) * up
    sp = _floating(t, "sparkle", 104, 34, rise=6, delay=0.7, life=0.3, size=0.9, drift=0)
    p["extras"] = [sp] if sp else []
    return p


# ---- wave 2: variety

def tail_wag(i, n):
    t = i / n
    p = default_pose()
    breathe(p, t, 0.8)
    wag = ramp(t, 0.05, 0.15) * (1 - ramp(t, 0.85, 0.97))
    p["tail"] = 24 * math.sin(2 * math.pi * t * 6) * wag + 4 * math.sin(2 * math.pi * t)
    p["lean"] = -1.6 * math.sin(2 * math.pi * t * 6) * wag
    p["head_tilt"] = 5 * wag
    p["look"] = (0.9 * wag, 0.0)
    p["ear_out"] = 6 * wag
    p["eyes"] = "happy" if 0.2 < t < 0.8 else "open"
    p["mouth"] = "open" if 0.2 < t < 0.8 else "w"
    p["mouth_open"] = 0.6
    p["blush"] = 0.5 + 0.3 * wag
    return p


def stretch(i, n):
    t = i / n
    p = default_pose()
    up = ramp(t, 0.1, 0.32) * (1 - ramp(t, 0.72, 0.9))
    shiver = math.sin(i * 2.1) * bump(t, 0.34, 0.68)
    p["sy"] = 1 + 0.09 * up - 0.04 * bump(t, 0.84, 0.96)
    p["sx"] = 1 - 0.04 * up
    p["head_dy"] = -2 * up
    p["head_tilt"] = 1.5 * shiver
    p["ear_out"] = -8 * up
    p["ear_scale"] = 1 + 0.06 * up
    if up > 0.02:
        p["arm"] = [dict(side=-1, x=lerp(42, 18, up), y=lerp(108, 54, up) + shiver),
                    dict(side=1, x=lerp(86, 108, up), y=lerp(108, 54, up) - shiver)]
    p["eyes"] = "closed" if up > 0.3 else "open"
    p["open"] = 1 - bump(t, 0.9, 0.98)
    p["mouth"] = "yawn" if up > 0.3 else "w"
    p["mouth_open"] = 0.6 * up
    p["tail"] = 10 * up + 4 * math.sin(2 * math.pi * t)
    p["blush"] = 0.5
    sp = _floating(t, "sparkle", 104, 26, rise=6, delay=0.74, life=0.25, size=0.9, drift=0)
    p["extras"] = [sp] if sp else []
    return p


def shake(i, n):
    t = i / n
    p = default_pose()
    sh = bump(t, 0.12, 0.72)
    wob = math.sin(2 * math.pi * t * 9)
    p["lean"] = 8 * wob * sh
    p["head_tilt"] = -10 * wob * sh
    p["dx"] = 1.5 * wob * sh
    p["sx"] = 1 + 0.04 * sh
    p["sy"] = 1 - 0.03 * sh
    p["ear_l"] = 18 * wob * sh
    p["ear_r"] = 18 * wob * sh
    p["tail"] = 18 * math.sin(2 * math.pi * t * 9 + 1) * sh
    p["eyes"] = "closed" if 0.14 < t < 0.74 else "open"
    p["open"] = 1 - bump(t, 0.8, 0.88)
    p["mouth"] = "w"
    p["blush"] = 0.4
    ex = []
    for k, (ang, d) in enumerate(((-150, 0.2), (-30, 0.28), (-110, 0.38), (-60, 0.46), (-170, 0.54), (-10, 0.6))):
        ph = (t - d) / 0.25
        if 0 < ph < 1:
            r = math.radians(ang)
            dist = 34 + 22 * ph
            ex.append(dict(kind="drop", x=64 + dist * math.cos(r), y=68 + dist * math.sin(r) + 10 * ph * ph,
                           scale=0.8, rot=ang + 90, opacity=1 - ramp(ph, 0.6, 1.0)))
    p["extras"] = ex
    return p


def tail_play(i, n):
    t = i / n
    p = default_pose()
    # notice the tail → pounce → it slips away → pounce again → pleased
    notice = ramp(t, 0.04, 0.14) * (1 - ramp(t, 0.86, 0.96))
    hops = [(0.18, 0.38), (0.48, 0.68)]
    h = 0.0
    for a, b in hops:
        if a < t < b:
            h = math.sin(math.pi * (t - a) / (b - a))
    reach = max(bump(t, 0.2, 0.42), bump(t, 0.5, 0.72))
    escape = 22 * math.sin(2 * math.pi * t * 3) * (1 - ramp(t, 0.8, 0.95))
    p["dy"] = -5 * h
    p["sy"] = 1 + 0.05 * h - 0.07 * (bump(t, 0.36, 0.44) + bump(t, 0.66, 0.74))
    p["lean"] = 5 * notice + 3 * reach
    p["head_tilt"] = 12 * notice
    p["look"] = (1.4 * notice, 0.9 * notice)
    p["tail"] = escape - 8 * reach
    p["tail_curl"] = 0.4 * reach
    if reach > 0.05:
        p["arm"] = dict(side=1, x=lerp(86, 100, reach), y=lerp(108, 78, reach))
    p["eyes"] = "happy" if t > 0.78 else "open"
    p["open"] = 1.0
    p["mouth"] = "open" if (reach > 0.3 or t > 0.78) else "w"
    p["mouth_open"] = 0.7
    p["blush"] = 0.6
    ex = []
    for sx_, sy_, d in ((104, 34, 0.38), (26, 30, 0.8)):
        f = _floating(t, "sparkle", sx_, sy_, rise=6, delay=d, life=0.2, size=0.9, drift=0, rot=60 * t)
        if f:
            ex.append(f)
    p["extras"] = ex
    return p


def surprise(i, n):
    t = i / n
    p = default_pose()
    jolt = bump(t, 0.08, 0.3)
    held = ramp(t, 0.1, 0.18) * (1 - ramp(t, 0.66, 0.86))
    p["dy"] = -4 * jolt
    p["sy"] = 1 + 0.06 * jolt - 0.05 * bump(t, 0.28, 0.4)
    p["sx"] = 1 - 0.03 * jolt
    p["ear_out"] = -10 * held
    p["ear_scale"] = 1 + 0.12 * held
    p["tail"] = -6 * held + 4 * math.sin(2 * math.pi * t)
    p["tail_curl"] = 0.5 * held
    p["eyes"] = "wide" if held > 0.3 else "open"
    p["open"] = 1 - bump(t, 0.88, 0.96)
    p["look"] = (0.0, -0.3 * held)
    p["mouth"] = "o" if held > 0.3 else "w"
    p["blush"] = 0.35
    ex = []
    sh = held * (1 + 0.3 * bump(t, 0.1, 0.24))
    if sh > 0.02:
        ex.append(dict(kind="shock", x=26, y=26, scale=0.7 * sh, opacity=min(1.0, held * 2)))
        ex.append(dict(kind="shock", x=102, y=26, scale=0.7 * sh, opacity=min(1.0, held * 2), angles=(-30, -60, -90)))
    p["extras"] = ex
    return p


def bounce(i, n):
    t = i / n
    p = default_pose()
    hops = [(0.04, 0.3, 9), (0.34, 0.58, 7), (0.62, 0.8, 4)]
    h, land = 0.0, 0.0
    for a, b, height in hops:
        if a < t < b:
            h = math.sin(math.pi * (t - a) / (b - a)) * height / 9
        land += bump(t, b - 0.05, b + 0.05)
    p["dy"] = -9 * h
    p["sy"] = 1 + 0.08 * h - 0.12 * land
    p["sx"] = 1 - 0.04 * h + 0.07 * land
    p["ear_out"] = -10 * h + 8 * land
    p["tail"] = 20 * math.sin(2 * math.pi * t * 3) * (1 - ramp(t, 0.82, 1.0))
    p["eyes"] = "happy" if t < 0.84 else "open"
    p["mouth"] = "open" if t < 0.82 else "w"
    p["mouth_open"] = 1.0
    p["blush"] = 0.8
    ex = []
    for sx_, sy_, d in ((22, 40, 0.06), (104, 30, 0.2), (30, 76, 0.38), (100, 70, 0.52)):
        f = _floating(t, "sparkle", sx_, sy_, rise=8, delay=d, life=0.35, size=1.0, drift=0, rot=90 * t)
        if f:
            ex.append(f)
    p["extras"] = ex
    return p


def laugh(i, n):
    t = i / n
    p = default_pose()
    lg = ramp(t, 0.04, 0.12) * (1 - ramp(t, 0.82, 0.94))
    beat = abs(math.sin(2 * math.pi * t * 5))
    p["sy"] = 1 - 0.05 * beat * lg
    p["sx"] = 1 + 0.03 * beat * lg
    p["head_dy"] = -1.5 * beat * lg
    p["head_tilt"] = -8 * lg
    p["lean"] = -3 * lg
    p["ear_out"] = 8 * lg
    p["tail"] = 16 * math.sin(2 * math.pi * t * 4) * lg
    if lg > 0.05:
        p["arm"] = dict(side=1, x=lerp(86, 76, lg), y=lerp(108, 100 - 2 * beat, lg))  # holds its belly
    p["eyes"] = "happy" if lg > 0.2 else "open"
    p["mouth"] = "open" if lg > 0.2 else "w"
    p["mouth_open"] = 0.6 + 0.4 * beat
    p["blush"] = 0.5 + 0.45 * lg
    ex = []
    for k, d in enumerate((0.3, 0.55)):
        f = _floating(t, "drop", 38 if k == 0 else 90, 60, rise=-10, delay=d, life=0.3, size=0.6, drift=0)
        if f:
            ex.append(f)
    for sx_, sy_, d in ((104, 30, 0.12), (24, 34, 0.42)):
        f = _floating(t, "sparkle", sx_, sy_, rise=6, delay=d, life=0.3, size=0.9, drift=0, rot=60 * t)
        if f:
            ex.append(f)
    p["extras"] = ex
    return p


def shy(i, n):
    t = i / n
    p = default_pose()
    cover = ramp(t, 0.06, 0.2) * (1 - ramp(t, 0.82, 0.95))
    peek = bump(t, 0.42, 0.62)                     # the right paw slides down for a peek
    p["sy"] = 1 - 0.04 * cover
    p["head_dy"] = 2 * cover
    p["head_tilt"] = -6 * cover + 3 * math.sin(2 * math.pi * t * 2) * cover
    p["ear_out"] = 16 * cover
    p["ear_scale"] = 1 - 0.08 * cover
    p["tail"] = 10 * math.sin(2 * math.pi * t * 2) * cover - 6 * cover
    p["tail_curl"] = 0.5 * cover
    if cover > 0.02:
        p["arm"] = [dict(side=-1, x=lerp(42, 47, cover), y=lerp(108, 62, cover)),
                    dict(side=1, x=lerp(86, 81, cover), y=lerp(108, 62 + 14 * peek, cover))]
    p["eyes"] = "open" if peek > 0.4 else "closed"
    p["look"] = (0.6, 0.4)
    p["mouth"] = "w"
    p["blush"] = 0.5 + 0.5 * cover
    ex = []
    for k, (hx, hy, d) in enumerate(((104, 40, 0.18), (24, 36, 0.5))):
        f = _floating(t, "heart", hx, hy, rise=20, delay=d, life=0.4, size=0.6)
        if f:
            ex.append(f)
    p["extras"] = ex
    return p


def music_spin(i, n):
    t = i / n
    beat = (i % 15) / 15
    p = default_pose()
    spin = ramp(t, 0.16, 0.64)                     # one full turn
    turn = math.cos(2 * math.pi * spin)
    p["sx"] = turn if abs(turn) > 0.08 else math.copysign(0.08, turn)
    hop = math.sin(math.pi * clamp((t - 0.14) / 0.52)) if 0.14 < t < 0.66 else 0
    p["dy"] = -6 * hop
    pulse = math.exp(-beat * 6) * (1 - hop)
    p["sy"] = 1 + 0.05 * hop - 0.05 * pulse - 0.08 * bump(t, 0.62, 0.74)
    p["ear_out"] = -6 * hop
    p["tail"] = 14 * math.sin(4 * math.pi * t) * (1 - hop)
    p["eyes"] = "happy"
    p["mouth"] = "open"
    p["mouth_open"] = 0.6 + 0.3 * pulse
    p["blush"] = 0.7
    ex = _notes(t, 3, 100, 112, 1, NOTE_COLORS)
    wh = bump(t, 0.18, 0.62)
    if wh > 0:
        ex.append(dict(kind="whoosh", x=18, y=78, scale=0.9, opacity=wh))
    p["extras"] = ex
    return p


def music_paws(i, n):
    t = i / n
    beat = (i % 15) / 15
    p = default_pose()
    pulse = math.exp(-beat * 6)
    side = 1 if (i // 15) % 2 == 0 else -1          # the paws take turns on every beat
    up = math.sin(math.pi * beat)
    p["sy"] = 1 - 0.04 * pulse
    p["sx"] = 1 + 0.025 * pulse
    p["lean"] = 3 * side * up
    p["head_tilt"] = -5 * side * up
    p["ear_l"] = 5 * side * up
    p["ear_r"] = 5 * side * up
    p["tail"] = -12 * side * up
    hi, lo = (lerp(108, 58, up), 104)
    p["arm"] = [dict(side=-1, x=26 if side < 0 else 40, y=hi if side < 0 else lo),
                dict(side=1, x=102 if side > 0 else 88, y=hi if side > 0 else lo)]
    p["eyes"] = "happy"
    p["mouth"] = "open" if up > 0.4 else "w"
    p["mouth_open"] = 0.5 + 0.4 * up
    p["blush"] = 0.7
    p["extras"] = _notes(t, 2, 100, 112, 1, NOTE_COLORS) + _notes((t + 0.5) % 1, 2, 26, 14, -1, NOTE_COLORS[::-1])
    return p


def drowsy(i, n):
    t = i / n
    p = default_pose()
    breathe(p, t, 1.3)
    nod = bump(t, 0.16, 0.46)                      # head sinks, then jerks back up
    jerk = bump(t, 0.44, 0.52)
    y = bump(t, 0.58, 0.9)                         # small yawn
    p["head_dy"] = 3 * nod - 1.5 * jerk
    p["head_tilt"] = 6 * nod
    p["ear_out"] = 10 + 6 * nod - 8 * jerk
    p["ear_scale"] = 0.96
    p["eyes"] = "closed" if (nod > 0.6 or 0.62 < t < 0.86) else "open"
    p["open"] = 0.42 - 0.2 * nod + 0.5 * jerk
    p["mouth"] = "yawn" if 0.6 < t < 0.88 else "w"
    p["mouth_open"] = 0.6 * y
    p["blush"] = 0.4
    p["tail"] = 3 * math.sin(2 * math.pi * t)
    ex = []
    z = _floating(t, "zzz", 98, 40, rise=16, delay=0.12, life=0.4, size=0.7)
    if z:
        ex.append(z)
    if jerk > 0.1:
        ex.append(dict(kind="shock", x=26, y=26, scale=0.6, opacity=jerk))
    p["extras"] = ex
    return p


def _turn(side):
    """The car turns left (side=-1) or right (side=1); the fox is thrown the other way."""
    def pose(i, n):
        t = i / n
        p = default_pose()
        throw = ramp(t, 0.02, 0.14) * (1 - ramp(t, 0.5, 0.78))
        wob = math.sin(2 * math.pi * (t - 0.5) * 3) * bump(t, 0.5, 0.95)
        lean = -side * 9 * throw + -side * 3 * wob
        p["lean"] = lean
        p["dx"] = -side * 5 * throw
        p["head_tilt"] = -side * 6 * throw
        p["ear_l"] = side * 14 * throw
        p["ear_r"] = side * 14 * throw
        p["tail"] = side * 16 * throw
        p["tail_curl"] = 0.6 * throw
        p["sx"] = 1 + 0.04 * throw
        p["eyes"] = "wide" if t < 0.6 else "open"
        p["look"] = (side * 0.8 * throw, 0.0)
        p["open"] = 1 - bump(t, 0.8, 0.88)
        p["mouth"] = "o" if t < 0.6 else "w"
        p["blush"] = 0.3
        ex = []
        wh = bump(t, 0.02, 0.5)
        if wh > 0:
            ex.append(dict(kind="whoosh", x=64 + side * 46, y=66, scale=0.9, opacity=wh))
        sw = clamp((t - 0.2) / 0.6)
        if 0 < sw < 1:
            ex.append(dict(kind="sweat", x=64 - side * 40, y=36 + 10 * sw, scale=0.9,
                           opacity=ramp(sw, 0, 0.1) * (1 - ramp(sw, 0.75, 1.0))))
        p["extras"] = ex
        return p
    return pose


turn_left = _turn(-1)
turn_right = _turn(1)


def phone_call(i, n):
    t = i / n
    p = default_pose()
    breathe(p, t, 0.6, cycles=2)
    talk = (i // 6) % 2 == 0 and not 0.55 < t < 0.8   # listens for a while, then talks again
    nod = bump(t, 0.6, 0.68) + bump(t, 0.7, 0.78)
    p["head_tilt"] = 9 + 2 * nod
    p["head_dy"] = 1.5 * nod
    p["ear_r"] = -4
    p["ear_l"] = 6 * bump(t, 0.3, 0.36)
    p["arm"] = dict(side=1, x=96, y=62)
    p["eyes"] = "open"
    p["open"] = 1 - bump(t, 0.4, 0.46)
    p["look"] = (-0.6, 0.0)
    p["mouth"] = "open" if talk else "w"
    p["mouth_open"] = 0.45
    p["blush"] = 0.5
    p["tail"] = 5 * math.sin(2 * math.pi * t)
    ex = [dict(kind="phone", x=98, y=56, scale=1.0, rot=12)]
    w = 0.5 + 0.5 * math.sin(2 * math.pi * t * 4)
    if talk:
        ex.append(dict(kind="waves", x=112, y=46, scale=0.8, opacity=0.4 + 0.6 * w))
    p["extras"] = ex
    return p


def low_battery(i, n):
    t = i / n
    p = default_pose()
    worry = ramp(t, 0.04, 0.16) * (1 - ramp(t, 0.84, 0.96))
    sag = bump(t, 0.3, 0.8)
    p["sy"] = 1 - 0.05 * worry - 0.03 * sag
    p["sx"] = 1 + 0.03 * worry
    p["head_dy"] = 2.5 * worry
    p["head_tilt"] = -5 * worry
    p["ear_out"] = 18 * worry
    p["ear_scale"] = 1 - 0.1 * worry
    p["tail"] = -10 * worry
    p["tail_curl"] = 0.3 * worry
    p["eyes"] = "open"
    p["open"] = 1 - 0.45 * worry - bump(t, 0.5, 0.58) * 0.5
    p["look"] = (0.6 * worry, -0.9 * worry)
    p["mouth"] = "frown" if worry > 0.3 else "w"
    p["blush"] = 0.25
    blink = 0.25 + 0.75 * (1 if (i // 6) % 2 == 0 else 0)
    ex = [dict(kind="battery", x=98, y=24, scale=ramp(t, 0.02, 0.12) * (1 + 0.15 * bump(t, 0.06, 0.18)),
               level=0.15, blink=blink, opacity=1 - ramp(t, 0.86, 0.96))]
    sw = clamp((t - 0.2) / 0.6)
    if 0 < sw < 1:
        ex.append(dict(kind="sweat", x=26, y=38 + 10 * sw, scale=0.9,
                       opacity=ramp(sw, 0, 0.1) * (1 - ramp(sw, 0.75, 1.0))))
    p["extras"] = ex
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
    # wave 1
    "yawn": (yawn, 60),
    "ear_scratch": (ear_scratch, 60),
    "music_nod": (music_nod, 60),
    "head_tilt": (head_tilt, 45),
    "annoyed": (annoyed, 50),
    "hearts": (hearts, 40),
    "relief": (relief, 40),
    "greeting": (greeting, 50),
    "trip_start": (trip_start, 50),
    "fall_asleep": (fall_asleep, 60),
    "sleep": (sleep, 75),
    "wake": (wake, 45),
    # wave 2
    "tail_wag": (tail_wag, 50),
    "stretch": (stretch, 60),
    "shake": (shake, 45),
    "tail_play": (tail_play, 70),
    "surprise": (surprise, 40),
    "bounce": (bounce, 50),
    "laugh": (laugh, 50),
    "shy": (shy, 60),
    "music_spin": (music_spin, 60),
    "music_paws": (music_paws, 60),
    "drowsy": (drowsy, 60),
    "turn_left": (turn_left, 40),
    "turn_right": (turn_right, 40),
    "phone_call": (phone_call, 72),
    "low_battery": (low_battery, 55),
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
