#!/usr/bin/env python3
"""
Procedural generator for the second companion character — the fox cub.

Every emotion is described as a sequence of poses (body squash, head tilt,
ear/tail angles, eye and mouth shapes, floating extras). Each pose is drawn
with vector primitives at 4x resolution, outlined, downscaled to 128x128
and packed into an animated WebP in app/src/main/res/raw/fox_<state>.webp.

Usage:
    python3 tools/fox/generate.py                 # write all WebP files
    python3 tools/fox/generate.py tease sneeze    # only these
    python3 tools/fox/generate.py --wave3         # only the wave 3 animations
    python3 tools/fox/generate.py --car           # only the car companion animations
    python3 tools/fox/generate.py --wave4         # only the wave 4 animations (heart, music shows, turning)
    python3 tools/fox/generate.py --wave5         # only the wave 5 animations (bayan, balalaika, punk, battery, leans)
    python3 tools/fox/generate.py --preview DIR   # also write preview sheet/GIF
    python3 tools/fox/generate.py --gallery       # also write tools/fox/gallery.html
    python3 tools/fox/generate.py --gallery-only  # just rebuild the gallery from existing files
    python3 tools/fox/generate.py --size 256 --out DIR   # other resolution (for measurements)
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


def ease_in(x):
    x = clamp(x)
    return x * x


def ease_out(x):
    x = clamp(x)
    return 1 - (1 - x) * (1 - x)


def ease_in_out(x):
    """Cubic: slow start, fast middle, slow stop."""
    x = clamp(x)
    return 4 * x ** 3 if x < 0.5 else 1 - (-2 * x + 2) ** 3 / 2


def ease_out_back(x, s=1.6):
    """Overshoots a little past 1, then settles — for snappy 'pop' motions."""
    x = clamp(x) - 1
    return 1 + (s + 1) * x ** 3 + s * x ** 2


def window(t, a, b, c, d, ease=smooth):
    """0 → 1 over [a, b], held, 1 → 0 over [c, d]."""
    return ease(clamp((t - a) / max(1e-6, b - a))) * (1 - ease(clamp((t - c) / max(1e-6, d - c))))


def keys(t, frames, ease=ease_in_out):
    """Keyframe interpolation: frames = [(time, value), ...], value is a number or a tuple."""
    if t <= frames[0][0]:
        return frames[0][1]
    for (t0, v0), (t1, v1) in zip(frames, frames[1:]):
        if t <= t1:
            k = ease((t - t0) / max(1e-6, t1 - t0))
            if isinstance(v0, tuple):
                return tuple(lerp(a, b, k) for a, b in zip(v0, v1))
            return lerp(v0, v1, k)
    return frames[-1][1]


def gaze(t, frames, snap=0.035):
    """Eye targets with saccades: the eyes hold still, then jump to the next target within `snap`."""
    out = [frames[0]]
    for (_, v0), (t1, v1) in zip(frames, frames[1:]):
        out += [(t1 - snap, v0), (t1, v1)]
    return keys(t, out, ease_out)


def spline(t, frames):
    """Catmull-Rom path through [(time, (x, y)), ...] — smooth flight paths."""
    if t <= frames[0][0]:
        return frames[0][1]
    if t >= frames[-1][0]:
        return frames[-1][1]
    k = max(i for i in range(len(frames) - 1) if frames[i][0] <= t)
    p0 = frames[max(0, k - 1)][1]
    p1, p2 = frames[k][1], frames[k + 1][1]
    p3 = frames[min(len(frames) - 1, k + 2)][1]
    u = (t - frames[k][0]) / (frames[k + 1][0] - frames[k][0])
    return tuple(0.5 * (2 * b + (-a + c) * u + (2 * a - 5 * b + 4 * c - d) * u * u
                        + (-a + 3 * b - 3 * c + d) * u ** 3)
                 for a, b, c, d in zip(p0, p1, p2, p3))


# ---------------------------------------------------------------- motion helpers
# Shared building blocks for natural movement; they only add to a pose, so they combine freely.

def spring(target, t, secs, freq=2.2, zeta=0.32, t0=0.0):
    """
    Value of a damped spring that follows target(t) — the lagging, overshooting
    "follow-through" of soft parts (tail, ears, cheeks). `secs` is the animation length.
    """
    dt = 1 / 240
    w = 2 * math.pi * freq
    x, v = target(t0), 0.0
    steps = int(max(0.0, t - t0) * secs / dt)
    for k in range(steps):
        tt = t0 + (k + 1) * dt / secs
        a = w * w * (target(tt) - x) - 2 * zeta * w * v
        v += a * dt
        x += v * dt
    return x


def lag(target, t, secs, **kw):
    """How far a spring trails behind target(t): 0 when at rest, ± while the target moves."""
    return spring(target, t, secs, **kw) - target(t)


def blink_shut(t, at, style="single", dur=0.07):
    """
    How closed the eyes are (0 open … 1 shut) for a blink starting at `at`.
    Styles: single, double, slow (sleepy, holds shut a moment), half (lazy half-blink).
    """
    if style == "double":
        return max(bump(t, at, at + dur * 0.8), bump(t, at + dur * 0.95, at + dur * 1.75))
    if style == "slow":
        return window(t, at, at + dur * 1.2, at + dur * 2.0, at + dur * 3.4, ease_in_out)
    if style == "half":
        return 0.55 * bump(t, at, at + dur * 1.6)
    return bump(t, at, at + dur)


def ear_flick(t, at, dur=0.07, amp=14.0):
    """A quick ear twitch with a small rebound."""
    return amp * (bump(t, at, at + dur) - 0.35 * bump(t, at + dur, at + dur * 1.9))


def hop(p, t, a, b, height, prep=0.07, settle=0.12):
    """
    A jump from `a` to `b`: crouch before take-off (anticipation), parabolic flight
    (gravity gives ease-out up / ease-in down), stretch while fast, squash on landing
    and a damped settle. Ears trail behind the body.
    """
    pre = bump(t, a - prep, a + 0.012)
    p["sy"] -= 0.12 * pre                # squash toward the ground: the paws stay put
    p["sx"] += 0.05 * pre
    p["ear_out"] += 6 * pre
    if a < t < b:
        x = (t - a) / (b - a)
        speed = abs(1 - 2 * x)
        p["dy"] -= height * 4 * x * (1 - x)
        p["sy"] += 0.07 * speed
        p["sx"] -= 0.04 * speed
        p["ear_out"] -= 8 * (1 - x) * speed
    if b <= t < b + settle:
        x = (t - b) / settle
        sq = math.exp(-3.5 * x) * math.cos(math.pi * 1.5 * x)
        p["sy"] -= 0.12 * sq
        p["sx"] += 0.07 * sq
        p["ear_out"] += 10 * max(0.0, sq)


def soft_hop(p, t, a, b, height):
    """A gentler hop() for fast dances: the crouch, the stretch and the landing squash ease in and out, no snap."""
    pre = bump(t, a - 0.06, a + 0.01)
    p["sy"] -= 0.08 * pre
    p["sx"] += 0.025 * pre                # the big tail is near the edge: widen only a little
    if a < t < b:
        x = (t - a) / (b - a)
        p["dy"] -= height * 4 * x * (1 - x)
        st = 0.04 * math.sin(math.pi * x)
        p["sy"] += st
        p["sx"] -= 0.6 * st
        p["ear_out"] -= 6 * st / 0.04 * (1 - x)
    land = bump(t, b - 0.015, b + 0.09)
    p["sy"] -= 0.08 * land
    p["sx"] += 0.03 * land
    p["ear_out"] += 6 * land


def hop_dy(t, a, b, height):
    """The vertical part of hop() alone, for springs (tail/head inertia)."""
    if a < t < b:
        x = (t - a) / (b - a)
        return -height * 4 * x * (1 - x)
    return 0.0


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
        # --- finer control (defaults keep the original look)
        eye_l=None, eye_r=None,      # per-eye kind override (left/right as seen on screen), e.g. a wink
        open_l=1.0, open_r=1.0,      # per-eye openness multiplier
        cross=0.0,                   # eyes drift toward the nose (looking at something on it)
        ear_scale_l=1.0, ear_scale_r=1.0,
        nose_wrinkle=0.0,            # 0..1 scrunched nose (sneeze build-up)
        tongue=0.0, tongue_dx=0.0,   # tongue sticking out (tease, grooming)
        body_sx=1.0, body_sy=1.0,    # squash of the body only, the head keeps its shape (lying down)
        tail_wrap=0.0,               # 0..1 tail moves around to the front (see TAIL_WRAPS)
        tail_style="face",
        tail_peek=0.0,               # "face" wrap: tail slides down to let the left eye peek out
        props=[],                    # held / nearby objects in body space (see draw_prop), under the paws
        # --- turning and morphing (0 = the original front view, drawn exactly as before)
        yaw=0.0,                     # body turn about the vertical axis: 90 = profile facing right, 180 = back
        head_yaw=0.0,                # extra head turn on top of `yaw` (looking back over the shoulder)
        feet_dy=(0.0, 0.0),          # lift of the left / right front paw (stepping)
        tail_scale=1.0,              # tail length (shrinks toward its base)
        heart=0.0,                   # 0..1 the silhouette morphs into a big heart (see draw_heart_body)
        # --- wave 5 (defaults keep the drawing unchanged)
        morph="heart",               # what `heart` morphs into: "heart" or "cloud" (the 1 % goodbye)
        fade=0.0,                    # 0..1 the whole character fades out (the cloud dissolves)
    )


class Turn:
    """
    2.5D turn about the vertical axis. A point of the front-view drawing at x gets a depth z (toward the
    viewer, model units); turned by `yaw` it lands at screen x = 64 + (x - 64)·cos + z·sin. Flat details
    (eyes, blush, belly) narrow with the angle between their surface and the viewer and vanish once
    they face away, so profile and back views are drawn, not squeezed or mirrored. yaw = 0 is the
    identity: every method returns its input unchanged, the front view stays byte-identical.
    """

    def __init__(self, yaw):
        self.on = yaw != 0.0
        r = math.radians(yaw)
        self.r = r
        self.c, self.s = math.cos(r), math.sin(r)

    def x(self, x, z):
        """Screen x of the model point (x, z)."""
        if not self.on:
            return x
        return 64 + (x - 64) * self.c + z * self.s

    def depth(self, x, z):
        """Distance toward the viewer after the turn (> 0: in front of the centre)."""
        return -(x - 64) * self.s + z * self.c

    def radius(self, rx, rz):
        """Visible half-width of an upright ellipsoid with half-width rx and half-depth rz."""
        if not self.on:
            return rx
        return math.hypot(rx * self.c, rz * self.s)

    def facing(self, x, z):
        """cos of the angle between the surface at (x, z) and the line of sight (1 = faces the viewer)."""
        return math.cos(self.r + math.atan2(x - 64, z))

    def decal(self, x, z, floor=0.0):
        """Width factor of a flat detail on the surface at (x, z): 1 in the front view, 0 once hidden.
        `floor` keeps a sliver of rounded details (snout, chest) visible from the side."""
        if not self.on:
            return 1.0
        f = self.facing(x, z)
        front = math.cos(math.atan2(x - 64, z))
        if front > 0.2:                 # details on the face / chest: full width in the front view
            f /= front
        return clamp(max(f, floor * ramp(self.facing(x, z), -0.3, 0.05)))

    def tail_base(self):
        """The tail grows out of the back: on the right in the front view, behind in profile, centred from behind."""
        return 64 + 20 * self.c * (1 + self.c) / 2 - 18 * self.s

    def tail_stretch(self):
        """Horizontal direction of the tail: it points right and back, so it swings behind in profile."""
        return self.c - 0.65 * self.s


GROUND = (64, 122)
# Shrinks the whole character a little so ears (jumps) and tail (sway) stay inside the frame.
FIT = Tf.scale(0.9, 0.9, 62, 124)
TAIL_MAX_RIGHT = 6.0
NECK = (64, 92)


TAIL_BASE = (84, 108)

# Where the tail goes when it wraps around to the front: (control point, tip, arc lift on the way).
TAIL_WRAPS = {
    "face": ((128, 66), (30, 58), 18),     # up in front of the face (hiding behind it)
    "curl": ((116, 106), (30, 90), 0),     # around the paws up to the nose (curled up asleep)
}
TAIL_PEEK = ((124, 84), (40, 84))          # "face" wrap lowered so the left eye shows


def tail_centre(pose):
    """Centre line of the tail (quadratic Bezier from the hip), wrapped to the front by `tail_wrap`."""
    curl = pose["tail_curl"]
    p1, p2 = (120, 100), (110 - 6 * curl, 56 + 4 * curl)
    w = pose["tail_wrap"]
    if w > 0:
        c1, c2, lift = TAIL_WRAPS[pose["tail_style"]]
        if pose["tail_style"] == "face" and pose["tail_peek"] > 0:
            c1 = tuple(lerp(a, b, pose["tail_peek"]) for a, b in zip(c1, TAIL_PEEK[0]))
            c2 = tuple(lerp(a, b, pose["tail_peek"]) for a, b in zip(c2, TAIL_PEEK[1]))
        p1 = (lerp(p1[0], c1[0], w), lerp(p1[1], c1[1], w))
        p2 = (lerp(p2[0], c2[0], w), lerp(p2[1], c2[1], w) - lift * math.sin(math.pi * w))
    return bezier(TAIL_BASE, p1, p2, n=30)


def _tail_angle(pose):
    # Swinging right (positive) pushes the tail toward the frame edge — soft-cap it.
    tail_angle = pose["tail"]
    if tail_angle > 0:
        tail_angle = TAIL_MAX_RIGHT * math.tanh(tail_angle / TAIL_MAX_RIGHT)
    return tail_angle


def _tail_masks(centre, tail_tf, thick=1.0):
    tail_mask = Canvas.new_mask()
    tip_mask = Canvas.new_mask()
    for i, (x, y) in enumerate(centre):
        t = i / (len(centre) - 1)
        r = 6 + 9 * math.sin(math.pi * min(1.0, t * 0.92 + 0.08)) ** 0.8
        if t > 0.9:
            r *= 1 - (t - 0.9) * 4
        r *= thick
        Canvas.poly_mask(ellipse_pts(x, y, r, r, n=32), tail_tf, tail_mask)
        if t > 0.74:
            Canvas.poly_mask(ellipse_pts(x, y, r, r, n=32), tail_tf, tip_mask)
    return tail_mask, tip_mask


def draw_character(pose):
    cv = Canvas()
    tb = Turn(pose["yaw"])                          # body, tail, paws
    th = Turn(pose["yaw"] + pose["head_yaw"])       # head, ears, face
    heart = pose["heart"]

    body_tf = (FIT
               @ Tf.translate(pose["dx"], pose["dy"])
               @ Tf.rotate(pose["lean"], *GROUND)
               @ Tf.scale(pose["sx"], pose["sy"], *GROUND))
    head_tf = body_tf @ Tf.translate(0, pose["head_dy"]) @ Tf.rotate(pose["head_tilt"], *NECK)
    part_tf = body_tf      # body and feet; squashed alone when lying down
    if pose["body_sx"] != 1.0 or pose["body_sy"] != 1.0:
        part_tf = body_tf @ Tf.scale(pose["body_sx"], pose["body_sy"], *GROUND)
    grad_dy = pose["dy"]

    def g(y):  # gradient stop in canvas space (follows FIT)
        return 124 + (y - 124) * 0.9

    # ---- tail (behind everything; a wrapped tail is drawn again in front further down)
    # (not squashed with the body: lying down, the wrapped tail keeps its target shape)
    tail_tf = body_tf @ Tf.rotate(_tail_angle(pose), *TAIL_BASE)
    centre = tail_centre(pose)
    k = pose["tail_scale"]
    if heart:                                       # the tail tucks in behind the heart
        k *= 1 - 0.85 * ramp(heart, 0.0, 0.8)
        tail_tf = body_tf @ Tf.rotate(_tail_angle(pose) - 50 * heart, *TAIL_BASE)
    if k != 1.0:
        centre = [(TAIL_BASE[0] + (x - TAIL_BASE[0]) * k, TAIL_BASE[1] + (y - TAIL_BASE[1]) * k) for x, y in centre]
    tail_front = False
    if tb.on:
        # The centre line turns with the body; the fur keeps its thickness (seen end-on it is a fluffy ball).
        bx, fx = tb.tail_base(), tb.tail_stretch()
        centre = [(bx + (x - TAIL_BASE[0]) * fx, y) for x, y in centre]
        tail_tf = body_tf @ Tf.rotate(pose["tail"], bx, TAIL_BASE[1])   # no soft cap: it may point anywhere
        tail_front = tb.c < -0.2                    # from behind the tail is in front of the back
    tail_mask, tip_mask = _tail_masks(centre, tail_tf, 0.5 + 0.5 * k if k != 1.0 else 1.0)
    if not tail_front:
        cv.paint_gradient(tail_mask, ORANGE_TOP, ORANGE_BOT, g(50 + grad_dy), g(110 + grad_dy))
        cv.paint(tip_mask, CREAM)
    for prop in pose["props"]:
        if prop.get("back"):
            draw_prop(cv, prop, body_tf)

    arms = pose["arm"]
    arms = [arms] if isinstance(arms, dict) else list(arms or [])
    far_arms = [a for a in arms if tb.on and tb.depth(64 + a["side"] * 22, 8) < -4]
    for arm in far_arms:                            # the far paw in profile, behind the body
        draw_arm(cv, arm, body_tf, tb)

    # ---- body
    fdy = pose["feet_dy"]
    feet = [(49, 16, fdy[0], 1.0), (79, 16, fdy[1], 1.0)]
    if tb.on:
        hind = ramp(1 - tb.c, 0.0, 0.3)             # hind paws show up once the fox turns
        feet += [(52, -18, 0.0, hind), (76, -18, 0.0, hind)]

    def paint_feet(front):
        for px, z, dy, op in feet:
            if (tb.depth(px, z) >= 0 if tb.on else True) != front:
                continue
            cv.paint(Canvas.poly_mask(ellipse_pts(tb.x(px, z), 119 + dy, 9, 5.5), part_tf), PAW, op)

    if tb.on and not heart:
        paint_feet(False)
    brx = tb.radius(30, 34)
    body = ellipse_pts(64, 103, brx, 20)
    body_mask = Canvas.poly_mask(body, part_tf)
    if not heart:
        cv.paint_gradient(body_mask, ORANGE_TOP, ORANGE_BOT, g(88 + grad_dy), g(122 + grad_dy))
        bw = tb.decal(64, 22, 0.35)
        belly = Canvas.poly_mask(ellipse_pts(tb.x(64, 22), 108, 15 * bw, 13), part_tf)
        cv.paint(clip(belly, body_mask), CREAM)
        neck_shadow = Canvas.poly_mask(ellipse_pts(64, 88, brx, 8), part_tf)
        cv.paint(clip(neck_shadow, body_mask), ORANGE_SHADE, 0.45)
        if tb.on:
            sw = tb.decal(64, -30)                      # the spine, from behind
            if sw > 0.02:
                spine = Canvas.poly_mask(ellipse_pts(tb.x(64, -30), 104, 10 * sw, 17), part_tf)
                cv.paint(clip(spine, body_mask), ORANGE_SHADE, 0.35)
        paint_feet(True)

    # ---- ears (the nearer one last; from behind their backs show, without the pink inside)
    es = pose["ear_scale"]
    ear_in = 0.0
    ear_op = 1.0
    if heart:                                       # the ears lean together and sink into the lobes
        es *= 1 - 0.85 * ramp(heart, 0.05, 0.8)
        ear_in = 14 * heart
        if pose["morph"] == "cloud":                # ...or melt into the cloud, which has no lobes to hide them
            ear_op = 1 - ramp(heart, 0.15, 0.55)
    ear_w = 0.4 + 0.6 * abs(th.c) if th.on else 1.0
    inner_op = ramp(th.c, -0.1, 0.3) if th.on else 1.0
    sides = (-1, 1)
    if th.on:
        sides = sorted(sides, key=lambda sd: th.depth(64 + sd * 22, -2))
    for side in sides:
        bx = th.x(64 + side * 22, -2)
        ang = side * pose["ear_out"] + (pose["ear_l"] if side < 0 else pose["ear_r"])
        if heart:
            ang -= side * ear_in
        side_scale = pose["ear_scale_l"] if side < 0 else pose["ear_scale_r"]
        ear_tf = head_tf @ Tf.rotate(ang, bx, 36) @ Tf.scale(1, es * side_scale, bx, 36)
        if th.on:
            ear_tf = ear_tf @ Tf.scale(ear_w, 1, bx, 36)
        outer = chaikin([(bx - side * 14, 40), (bx + side * 9, 6), (bx + side * 13, 40)], 3)
        inner = chaikin([(bx - side * 7, 37), (bx + side * 8, 13), (bx + side * 9, 37)], 3)
        if ear_op <= 0.01:
            continue
        ear_mask = Canvas.poly_mask(outer, ear_tf)
        cv.paint_gradient(ear_mask, ORANGE_TOP, ORANGE_BOT, g(6 + grad_dy), g(40 + grad_dy), ear_op)
        cv.paint(clip(Canvas.poly_mask(inner, ear_tf), ear_mask), EAR_INNER, inner_op * ear_op)
        tip = Canvas.poly_mask(ellipse_pts(bx + side * 8, 12, 8, 7), ear_tf)
        cv.paint(clip(tip, ear_mask), PAW, 0.9 * ear_op)

    # ---- head
    head_mask = Canvas.poly_mask(ellipse_pts(64, 58, th.radius(40, 34), 33), head_tf)
    for side in (-1, 1):
        tuft = chaikin([(th.x(64 + side * 30, 0), 58), (th.x(64 + side * 47, 6), 80), (th.x(64 + side * 22, 14), 82)], 2)
        Canvas.poly_mask(tuft, head_tf, head_mask)
    if th.on:                                       # the snout sticks out in profile
        Canvas.poly_mask(ellipse_pts(th.x(64, 30), 76, 13, 9.5), head_tf, head_mask)
    if heart:
        head_mask = draw_heart_body(cv, pose, body_tf, part_tf, head_mask, body_mask, g, feet)
    else:
        cv.paint_gradient(head_mask, ORANGE_TOP, ORANGE_BOT, g(24 + grad_dy), g(90 + grad_dy))

    face = Canvas.new_mask()
    for x, y, rx, ry, rot, z, fl in ((47, 74, 19, 14, -12, 22, 0.25), (81, 74, 19, 14, 12, 22, 0.25),
                                     (64, 81, 15, 9, 0, 30, 0.55)):
        w = th.decal(x, z, fl)
        if w > 0.02:
            Canvas.poly_mask(ellipse_pts(th.x(x, z), y, rx * w, ry, rot), head_tf, face)
    cv.paint(clip(face, head_mask), CREAM, 1 - 0.45 * heart)

    # forehead highlight
    w = th.decal(48, 24)
    if w > 0.02:
        hl = Canvas.poly_mask(ellipse_pts(th.x(48, 24), 37, 12 * w, 5, -18), head_tf)
        cv.paint(clip(hl, head_mask), WHITE, 0.35)
    if th.on:
        nw = th.decal(64, -30)                      # back of the head: a darker nape
        if nw > 0.02:
            nape = Canvas.poly_mask(ellipse_pts(th.x(64, -30), 72, 24 * nw, 15), head_tf)
            cv.paint(clip(nape, head_mask), ORANGE_SHADE, 0.3)

    # blush
    if pose["blush"] > 0:
        for side in (-1, 1):
            w = th.decal(64 + side * 27, 18)
            if w > 0.02:
                m = Canvas.poly_mask(ellipse_pts(th.x(64 + side * 27, 18), 73, 6.5 * w, 3.6), head_tf)
                cv.paint(clip(m, head_mask), BLUSH, pose["blush"])

    draw_eyes(cv, pose, head_tf, th, head_mask if th.on else None)

    # nose
    nx, nw = th.x(64, 40), th.decal(64, 40, 0.55)
    if nw > 0.02:
        nose = chaikin([(nx - 5 * nw, 70), (nx + 5 * nw, 70), (nx, 75.5)], 3)
        cv.paint(Canvas.poly_mask(nose, head_tf), DARK)
        cv.paint(Canvas.poly_mask(ellipse_pts(nx - 1.7 * nw if th.on else 62.3, 70.8, 1.6 * nw, 0.9), head_tf), WHITE, 0.7)
        if pose["nose_wrinkle"] > 0:
            k = pose["nose_wrinkle"]
            for side in (-1, 1):
                crease = bezier((nx + side * 2.5 * nw, 67.5), (nx + side * 5.5 * nw, 66 - 1.8 * k),
                                (nx + side * 8.5 * nw, 67.8), 8)
                cv.paint(Canvas.stroke_mask(crease, 1.2, head_tf), DARK, 0.75 * k)

    mw = th.decal(64, 36, 0.45)
    if mw > 0.02:
        draw_mouth(cv, pose, head_tf, th.x(64, 36), mw)

    if pose["tail_wrap"] > 0 or tail_front:
        # The wrapped tail crosses in front of the face/body: own outline, faded in while it moves around.
        # Seen from behind, the tail is in front of the back the same way.
        front = 1.0 if tail_front else ramp(pose["tail_wrap"], 0.08, 0.2)
        if front > 0:
            layer = Canvas()
            layer.paint_gradient(tail_mask, ORANGE_TOP, ORANGE_BOT, g(50 + grad_dy), g(110 + grad_dy))
            layer.paint(tip_mask, CREAM)
            img = layer.outlined(1.4)
            if front < 1:
                img.putalpha(img.getchannel("A").point(lambda v: int(v * front)))
            cv.img.alpha_composite(img)

    for prop in pose["props"]:
        if not prop.get("back") and not prop.get("front"):
            draw_prop(cv, prop, body_tf, head_tf)

    for arm in arms:
        if arm not in far_arms:
            draw_arm(cv, arm, body_tf, tb)

    for prop in pose["props"]:
        if prop.get("front"):
            draw_prop(cv, prop, body_tf)

    out = cv.outlined(1.4)
    if pose["fade"] > 0:
        out.putalpha(out.getchannel("A").point(lambda v: int(v * (1 - clamp(pose["fade"])))))
    for extra in pose["extras"]:
        draw_extra(out, extra)
    return out


HEART_CENTRE = (64, 67)          # body space; the heart reaches from y ≈ 30 to 120 and x 14…114
HEART_SIZE = 50
HEART_TOP = (255, 150, 112)
HEART_BOT = (246, 86, 128)
MORPH_RAYS = 180


CLOUD_TOP = (255, 255, 255)
CLOUD_BOT = (222, 230, 246)
CLOUD_SHADE = (204, 214, 236)
# The cloud the fox curls into (body space): puffs as (x, y, r); flat-ish bottom, round top.
CLOUD_PUFFS = ((38, 96, 17), (54, 80, 20), (76, 78, 21), (92, 95, 17), (64, 101, 22), (48, 104, 15), (82, 105, 15))
CLOUD_SHADES = ((50, 112, 18, 5), (80, 113, 16, 5), (66, 90, 6, 3))


def cloud_mask(tf):
    """A fluffy cloud of round puffs (body space) as one mask."""
    mask = Canvas.new_mask()
    for x, y, r in CLOUD_PUFFS:
        Canvas.poly_mask(ellipse_pts(x, y, r, r), tf, mask)
    return mask


def _radial_extent(mask, centre, angles):
    """Distance from `centre` (supersampled px) to the farthest pixel of `mask` along each ray."""
    a = np.asarray(mask) > 127
    rs = np.arange(0, W, 1.0)
    xs = np.rint(centre[0] + np.outer(np.cos(angles), rs)).astype(int)
    ys = np.rint(centre[1] + np.outer(np.sin(angles), rs)).astype(int)
    ok = (xs >= 0) & (xs < W) & (ys >= 0) & (ys < W)
    hit = np.zeros_like(ok)
    hit[ok] = a[ys[ok], xs[ok]]
    idx = np.where(hit, np.arange(1, len(rs) + 1)[None, :], 0).max(axis=1)
    return idx.astype(float)


def draw_heart_body(cv, pose, body_tf, part_tf, head_mask, body_mask, g, feet):
    """
    Head and body as one silhouette that morphs into a big heart (`heart` 0…1). Every ray from the chest
    moves from the fox's outline to the heart's, so the shape really transforms (no cross-fade of two
    pictures); the colours drift from fox orange to orange-pink. The face stays on top (drawn by the
    caller), the paws tuck in. Returns the silhouette mask (the face details are clipped to it).
    """
    m = clamp(pose["heart"])
    cloud = pose["morph"] == "cloud"
    union = ImageChops.lighter(head_mask, body_mask)
    heart_mask = cloud_mask(body_tf) if cloud else Canvas.poly_mask(heart_pts(*HEART_CENTRE, HEART_SIZE), body_tf)
    top, bot = (CLOUD_TOP, CLOUD_BOT) if cloud else (HEART_TOP, HEART_BOT)
    c = body_tf.apply([(64, 86)])[0] * SS
    angles = np.linspace(0, 2 * math.pi, MORPH_RAYS, endpoint=False)
    r = (1 - m) * _radial_extent(union, c, angles) + m * _radial_extent(heart_mask, c, angles)
    pts = [((c[0] + rr * math.cos(a)) / SS, (c[1] + rr * math.sin(a)) / SS) for a, rr in zip(angles, r)]
    blob = Canvas.poly_mask(pts)
    if m < 0.5:
        blob = ImageChops.lighter(blob, clip(union, Canvas.poly_mask(pts)))
    dy = pose["dy"]
    cv.paint_gradient(blob, ORANGE_TOP, ORANGE_BOT, g(24 + dy), g(90 + dy))
    below = clip(ImageChops.subtract(body_mask, head_mask), blob)
    cv.paint_gradient(below, ORANGE_TOP, ORANGE_BOT, g(88 + dy), g(122 + dy), 1 - m)
    cv.paint(clip(below, Canvas.poly_mask(ellipse_pts(64, 88, 30, 8), part_tf)), ORANGE_SHADE, 0.45 * (1 - m))
    cv.paint(clip(clip(Canvas.poly_mask(ellipse_pts(64, 108, 15, 13), part_tf), body_mask), below), CREAM,
             1 - ramp(m, 0.0, 0.6))
    cv.paint_gradient(blob, top, bot, g(30 + dy), g(118 + dy), smooth(m))
    if m > 0 and cloud:
        for x, y, rx, ry in CLOUD_SHADES:              # soft shading between the puffs
            cv.paint(clip(Canvas.poly_mask(ellipse_pts(x, y, rx, ry), body_tf), blob), CLOUD_SHADE, 0.55 * m)
        shine = Canvas.poly_mask(ellipse_pts(46, 66, 10, 5, -20), body_tf)
        cv.paint(clip(shine, blob), WHITE, 0.7 * m)
    elif m > 0:
        shine = Canvas.poly_mask(ellipse_pts(38, 44, 9, 4.5, -38), body_tf)
        cv.paint(clip(shine, blob), WHITE, 0.45 * m)
    # paws tuck into the heart's tip
    f = 1 - ramp(m, 0.0, 0.6)
    if f > 0.02:
        for px, _z, fy, _op in feet[:2]:
            ell = ellipse_pts(lerp(64, px, f), lerp(112, 119 + fy, f), 9 * f, 5.5 * f)
            cv.paint(Canvas.poly_mask(ell, part_tf), PAW)
    return blob


def draw_eyes(cv, pose, tf, turn=None, within=None):
    lx, ly = pose["look"]

    def paint(mask, color, op=1.0):
        cv.paint(mask if within is None else clip(mask, within), color, op)

    for side in (-1, 1):
        kind = (pose["eye_l"] if side < 0 else pose["eye_r"]) or pose["eyes"]
        w = 1.0
        ex, ey = 64 + side * 17 + lx * 2.2, 59 + ly * 1.6
        if turn is not None and turn.on:
            w = turn.decal(64 + side * 17, 28)
            if w < 0.04:
                continue                 # turned away behind the head
            ex = turn.x(64 + side * 17, 28) + lx * 2.2 * w
        if pose["cross"]:
            ex -= side * 2.2 * pose["cross"]
        if kind == "open":
            op = pose["open"] * (pose["open_l"] if side < 0 else pose["open_r"])
            op = clamp(op, 0.0, 1.0)
            if op < 0.2:
                arc = bezier((ex - 6 * w, ey + 1), (ex, ey + 5), (ex + 6 * w, ey + 1), 12)
                paint(Canvas.stroke_mask(arc, 2.0, tf), DARK)
                continue
            ry = 8.2 * op
            eye_mask = Canvas.poly_mask(ellipse_pts(ex, ey + (8.2 - ry) * 0.5, 6.3 * w, ry), tf)
            paint(eye_mask, DARK)
            hx, hy = ex + 1.8 * w + lx * 0.8 * w, ey - 3.0 * op + ly * 0.6
            cv.paint(clip(Canvas.poly_mask(ellipse_pts(hx, hy, 2.4 * w, 2.4 * min(1, op * 1.3)), tf), eye_mask), WHITE)
            cv.paint(clip(Canvas.poly_mask(ellipse_pts(ex - 2.2 * w, ey + 3.2 * op, 1.2 * w, 1.2), tf), eye_mask), WHITE, 0.85)
        elif kind == "happy":
            arc = bezier((ex - 6.5 * w, ey + 3), (ex, ey - 7), (ex + 6.5 * w, ey + 3), 14)
            paint(Canvas.stroke_mask(arc, 2.4, tf), DARK)
        elif kind == "closed":
            arc = bezier((ex - 6.5 * w, ey), (ex, ey + 5.5), (ex + 6.5 * w, ey), 14)
            paint(Canvas.stroke_mask(arc, 2.2, tf), DARK)
        elif kind == "grumpy":
            # lower half of the eye under a lid slanting down toward the nose
            lid_out, lid_in = ey - 1.5, ey + 1.8
            lid = [(ex - side * 8 * w, lid_out), (ex + side * 8 * w, lid_in)]
            below = Canvas.poly_mask([lid[0], lid[1], (ex + side * 8 * w, ey + 12), (ex - side * 8 * w, ey + 12)], tf)
            eye_mask = clip(Canvas.poly_mask(ellipse_pts(ex, ey + 1, 6.3 * w, 7.5), tf), below)
            paint(eye_mask, DARK)
            cv.paint(clip(Canvas.poly_mask(ellipse_pts(ex + 1.6 * w, ey + 3.2, 1.7 * w, 1.7), tf), eye_mask), WHITE)
            paint(Canvas.stroke_mask([(ex - side * 7.5 * w, lid_out - 0.5), (ex + side * 7.5 * w, lid_in - 0.5)], 2.2, tf), DARK)
        elif kind == "wide":
            white = Canvas.poly_mask(ellipse_pts(ex, ey, 8 * w, 9), tf)
            paint(Canvas.poly_mask(ellipse_pts(ex, ey, 9.2 * w, 10.2), tf), DARK)
            paint(white, WHITE)
            paint(Canvas.poly_mask(ellipse_pts(ex + lx * 2 * w, ey + 0.5 + ly, 3.6 * w, 4.2), tf), DARK)
            paint(Canvas.poly_mask(ellipse_pts(ex + 1.2 * w + lx * 2 * w, ey - 1.2 + ly, 1.2 * w, 1.2), tf), WHITE)
        elif kind == "squeeze":
            # tightly shut ">  <" (sneezing): the point of each chevron faces the nose
            tip = (ex - side * 3.5 * w, ey + 0.5)
            pts = [(ex + side * 4.5 * w, ey - 4), tip, (ex + side * 4.5 * w, ey + 5)]
            paint(Canvas.stroke_mask(pts, 2.3, tf), DARK)


def draw_mouth(cv, pose, tf, mx=64, mw=1.0):
    """Mouth centred at `mx` (the snout turns away in profile), `mw` = its width factor."""
    kind = pose["mouth"]
    if kind == "w":
        for side in (-1, 1):
            arc = bezier((mx, 75.5), (mx + side * 1.5 * mw, 79.5), (mx + side * 5 * mw, 77.5), 10)
            cv.paint(Canvas.stroke_mask(arc, 1.5, tf), DARK)
    elif kind == "open":
        k = clamp(pose["mouth_open"], 0.2, 1.0)
        top = 77.5
        shape = chaikin([(mx - 5.5 * mw, top), (mx + 5.5 * mw, top), (mx + 2.5 * mw, top + 6.5 * k),
                         (mx - 2.5 * mw, top + 6.5 * k)], 3)
        m = Canvas.poly_mask(shape, tf)
        cv.paint(m, MOUTH)
        tongue = Canvas.poly_mask(ellipse_pts(mx, top + 6.0 * k, 3.6 * mw, 2.6 * k), tf)
        cv.paint(clip(tongue, m), TONGUE)
        for side in (-1, 1):
            arc = bezier((mx, 75.5), (mx + side * 1.5 * mw, 78), (mx + side * 5.5 * mw, 77.5), 8)
            cv.paint(Canvas.stroke_mask(arc, 1.4, tf), DARK)
    elif kind == "yawn":
        k = clamp(pose["mouth_open"], 0.0, 1.0)
        if k < 0.1:
            draw_mouth(cv, dict(pose, mouth="w"), tf, mx, mw)
            return
        m = Canvas.poly_mask(ellipse_pts(mx, 79 + 3 * k, (4 + 3.5 * k) * mw, 2 + 6.5 * k), tf)
        cv.paint(m, MOUTH)
        cv.paint(clip(Canvas.poly_mask(ellipse_pts(mx, 81 + 7 * k, 4.5 * k * mw, 3 * k), tf), m), TONGUE)
    elif kind == "frown":
        arc = bezier((mx - 5.5 * mw, 81), (mx, 76.5), (mx + 5.5 * mw, 81), 12)
        cv.paint(Canvas.stroke_mask(arc, 1.6, tf), DARK)
    elif kind == "o":
        cv.paint(Canvas.poly_mask(ellipse_pts(mx, 80.5, 3.6 * mw, 4.4), tf), MOUTH)
        cv.paint(Canvas.poly_mask(ellipse_pts(mx, 82.5, 2.2 * mw, 1.6), tf), TONGUE)
    elif kind == "smile":
        # content closed smile, wider than the "w"
        arc = bezier((mx - 7 * mw, 76.5), (mx, 83.5), (mx + 7 * mw, 76.5), 14)
        cv.paint(Canvas.stroke_mask(arc, 1.6, tf), DARK)
        for side in (-1, 1):
            cv.paint(Canvas.stroke_mask([(mx + side * 7 * mw, 76.5), (mx + side * 8.2 * mw, 75.4)], 1.3, tf), DARK)
    elif kind == "aa":
        # singing "a": a tall open mouth, `mouth_open` 0…1
        k = clamp(pose["mouth_open"], 0.2, 1.0)
        m = Canvas.poly_mask(ellipse_pts(mx, 79.5 + 2 * k, (3.4 + 1.4 * k) * mw, 2.6 + 4.6 * k), tf)
        cv.paint(m, MOUTH)
        cv.paint(clip(Canvas.poly_mask(ellipse_pts(mx, 82 + 4.4 * k, 3.6 * mw, 2.4 * k), tf), m), TONGUE)
    elif kind == "ee":
        # singing "e": a wide, flat open smile
        k = clamp(pose["mouth_open"], 0.2, 1.0)
        shape = chaikin([(mx - 7 * mw, 76.5), (mx + 7 * mw, 76.5), (mx + 4 * mw, 79.5 + 2.6 * k),
                         (mx - 4 * mw, 79.5 + 2.6 * k)], 3)
        m = Canvas.poly_mask(shape, tf)
        cv.paint(m, MOUTH)
        cv.paint(clip(Canvas.poly_mask(ellipse_pts(mx, 80.5 + 2.4 * k, 3 * mw, 1.6 * k), tf), m), TONGUE)
    if pose["tongue"] > 0:
        # tongue sticking out below the mouth ("bleh" / licking)
        k = clamp(pose["tongue"])
        tx = mx + pose["tongue_dx"] * mw
        cv.paint(Canvas.poly_mask(ellipse_pts(tx, 79.5 + 3.2 * k, 4.2 * mw, 1.2 + 3.8 * k), tf), MOUTH)
        cv.paint(Canvas.poly_mask(ellipse_pts(tx, 79.5 + 3.2 * k, 3.3 * mw, 0.6 + 3.2 * k), tf), TONGUE)
        if k > 0.4:
            cv.paint(Canvas.stroke_mask([(tx, 79.5 + 1.5 * k), (tx, 79.5 + 4.6 * k)], 0.9, tf), MOUTH, 0.6)


def draw_arm(cv, arm, tf, turn=None):
    """Raised front paw: an orange limb from the shoulder with a dark paw at the end (`k` = thickness)."""
    side = arm["side"]
    k = arm.get("k", 1.0)
    sx = 64 + side * 22
    if turn is not None and turn.on:
        sx = turn.x(sx, 8)
    shoulder = (sx, 96)
    paw = (arm["x"], arm["y"])
    elbow = (lerp(shoulder[0], paw[0], 0.5) + side * 6, lerp(shoulder[1], paw[1], 0.5) + 2)
    limb = bezier(shoulder, elbow, paw, 16)
    cv.paint(Canvas.stroke_mask(limb, 16 * k, tf), OUTLINE)
    cv.paint(Canvas.stroke_mask(limb, 12.5 * k, tf), ORANGE_TOP)
    cv.paint(Canvas.poly_mask(ellipse_pts(paw[0], paw[1], 8.2 * k, 7.4 * k), tf), OUTLINE)
    cv.paint(Canvas.poly_mask(ellipse_pts(paw[0], paw[1], 6.6 * k, 5.8 * k), tf), PAW)


# ---------------------------------------------------------------- props
# Objects the fox holds or uses (steering wheel, wrench, map…). They are drawn in body space, so they
# lean, squash and jump together with the body, and the paws are drawn over them (the paw closes
# around the grip). Each prop has its own thin outline, like the wrapped tail, and then becomes part
# of the character's silhouette. A prop is a dict: kind, x, y (its grip point or centre), rot, scale,
# opacity, plus kind-specific fields; `back=True` draws it behind the body (the fuel hose),
# `front=True` in front of the paw (the sponge), `ground=True` keeps it standing on the ground while
# the body leans (the wheel being checked).

STEEL = (178, 188, 206)
STEEL_DARK = (118, 128, 148)
RUBBER = (52, 52, 60)
RIM_GREY = (64, 68, 82)
PAPER = (252, 244, 214)
STRAP = (66, 76, 100)
HOSE = (44, 46, 54)
NOZZLE = (70, 176, 96)
SPONGE = (255, 214, 90)
FOAM = (250, 252, 255)
FLAG_DARK = (44, 44, 52)
POLE = (126, 84, 52)

WHEEL_R = 16.0
WHEEL_SQUASH = 0.86          # the steering wheel is seen a little from above
TIRE_R = 13.0

# Resting places of the front paws (body space): a raised paw starts and ends here.
PAW_REST = {-1: (42, 108), 1: (86, 108)}


def paw(side, target, k):
    """Front paw `side` moved from its resting place toward `target` (0 = resting … 1 = there)."""
    rx, ry = PAW_REST[side]
    return dict(side=side, x=lerp(rx, target[0], k), y=lerp(ry, target[1], k))


def raised(*arms):
    """Only the paws that are really lifted (a paw at rest is part of the body drawing)."""
    out = []
    for a in arms:
        rx, ry = PAW_REST[a["side"]]
        if math.hypot(a["x"] - rx, a["y"] - ry) > 0.3:
            out.append(a)
    return out or None


def pop_in_out(t, a, b, c, d):
    """Scale of a prop that pops in over [a, b] with a small overshoot and shrinks away over [c, d]."""
    return ease_out_back(clamp((t - a) / max(1e-6, b - a))) * (1 - smooth((t - c) / max(1e-6, d - c)))


def _local(prop):
    return (Tf.translate(prop["x"], prop["y"]) @ Tf.rotate(prop.get("rot", 0.0), 0, 0)
            @ Tf.scale(prop.get("scale", 1.0), prop.get("scale", 1.0), 0, 0))


def prop_at(prop, pt):
    """Body-space position of a point given in the prop's own coordinates (where a paw holds it)."""
    x, y = _local(prop).apply([pt])[0]
    return float(x), float(y)


def wheel_grip(cx, cy, turn, ang, scale=1.0):
    """Point on the steering-wheel rim at `ang` degrees (0 = right, y down), turned with the wheel."""
    a = math.radians(ang + turn)
    r = WHEEL_R * scale
    return cx + r * math.cos(a), cy + r * WHEEL_SQUASH * math.sin(a)


def to_canvas(pt):
    """Body-space point → canvas (extras are drawn in canvas space, the body is shrunk by FIT)."""
    x, y = FIT.apply([pt])[0]
    return float(x), float(y)


def _ring(r, a0=0.0, a1=360.0, n=90):
    return [(r * math.cos(math.radians(lerp(a0, a1, k / n))), r * math.sin(math.radians(lerp(a0, a1, k / n))))
            for k in range(n + 1)]


def _prop_wheel(cv, pr, tf, body_tf, lb):
    turn = pr.get("turn", 0.0)
    t2 = tf @ Tf.scale(1, WHEEL_SQUASH, 0, 0)
    for a in (90, 210, 330):
        r = math.radians(a + turn)
        spoke = [(4 * math.cos(r), 4 * math.sin(r)), (WHEEL_R * math.cos(r), WHEEL_R * math.sin(r))]
        cv.paint(Canvas.stroke_mask(spoke, 3.2, t2), RIM_GREY)
    cv.paint(Canvas.poly_mask(ellipse_pts(0, 0, 5.6, 5.6), t2), (80, 86, 104))
    cv.paint(Canvas.poly_mask(ellipse_pts(0, 0, 2.3, 2.3), t2), ORANGE_TOP)
    cv.paint(Canvas.stroke_mask(_ring(WHEEL_R), 4.6, t2), RIM_GREY)
    cv.paint(Canvas.stroke_mask(_ring(WHEEL_R, 196, 256, 16), 1.3, t2), (146, 154, 176))   # light from the top left
    cv.paint(Canvas.stroke_mask(_ring(WHEEL_R, -99 + turn, -81 + turn, 8), 4.6, t2), ORANGE_BOT)  # top marker turns


def _prop_wrench(cv, pr, tf, body_tf, lb):
    handle = Canvas.stroke_mask([(0, 0), (19, 0)], 4.6, tf)
    head = Canvas.poly_mask(ellipse_pts(23, 0, 6, 6), tf)
    jaw = Canvas.poly_mask([(24, -2.4), (31, -2.4), (31, 2.4), (24, 2.4)], tf)
    shape = ImageChops.lighter(handle, ImageChops.subtract(head, jaw))
    cv.paint(shape, STEEL)
    cv.paint(Canvas.stroke_mask([(4, 0.9), (17, 0.9)], 1.1, tf), STEEL_DARK)
    cv.paint(clip(Canvas.poly_mask(ellipse_pts(21.5, -2.8, 2.6, 1.4), tf), shape), WHITE, 0.65)


def _prop_map(cv, pr, tf, body_tf, lb):
    u = clamp(pr.get("unfold", 1.0), 0.15, 1.0)
    w, h = 34 * u, 22
    paper = Canvas.poly_mask(_rect_pts(0, 0, w, h, 0, 1), tf)
    cv.paint(paper, PAPER)
    cv.paint(clip(Canvas.poly_mask(ellipse_pts(-7 * u, -4, 7 * u, 5), tf), paper), (196, 228, 170))
    river = bezier((-17 * u, 6), (0, -1), (17 * u, 3), 16)
    cv.paint(clip(Canvas.stroke_mask(river, 2.2, tf), paper), (150, 196, 240))
    route = bezier((-12 * u, 7), (-2 * u, -9), (9 * u, -4.5), 24)
    for k in range(0, len(route) - 2, 4):                        # dashed red route
        cv.paint(clip(Canvas.stroke_mask(route[k:k + 3], 1.5, tf), paper), (230, 70, 60))
    for fx in (-w / 6, w / 6):
        cv.paint(Canvas.stroke_mask([(fx, -h / 2 + 1), (fx, h / 2 - 1)], 0.9, tf), (214, 196, 160))
    cv.paint(Canvas.poly_mask(ellipse_pts(9 * u, -6.5, 2.6, 2.6), tf), (230, 70, 60))
    cv.paint(Canvas.poly_mask(ellipse_pts(9 * u, -6.5, 0.9, 0.9), tf), WHITE)


def _prop_tire(cv, pr, tf, body_tf, lb):
    k = pr.get("squash", 0.0)
    spin = pr.get("spin", 0.0)
    t2 = tf @ Tf.scale(1 + 0.08 * k, 1 - 0.12 * k, 0, TIRE_R)    # squashed against the ground
    cv.paint(Canvas.poly_mask(ellipse_pts(0, 0, TIRE_R, TIRE_R), t2), RUBBER)
    for j in range(12):                                          # tread blocks show the rolling
        a = spin + 30 * j
        r = math.radians(a)
        cx, cy = (TIRE_R - 1.6) * math.cos(r), (TIRE_R - 1.6) * math.sin(r)
        cv.paint(Canvas.poly_mask(_rect_pts(cx, cy, 2.0, 3.4, a, 1), t2), (84, 84, 96))
    cv.paint(Canvas.poly_mask(ellipse_pts(0, 0, 7.6, 7.6), t2), (196, 202, 216))
    cv.paint(Canvas.stroke_mask(_ring(6.0, n=48), 0.9, t2), (146, 154, 172))
    for j in range(5):
        r = math.radians(spin + 72 * j)
        cv.paint(Canvas.poly_mask(ellipse_pts(3.8 * math.cos(r), 3.8 * math.sin(r), 1.1, 1.1), t2), (118, 124, 140))
    cv.paint(Canvas.poly_mask(ellipse_pts(0, 0, 1.8, 1.8), t2), (90, 96, 112))


def _prop_belt(cv, pr, tf, body_tf, lb):
    """Seatbelt from the shoulder anchor `a` to the paw / buckle `e`; `buckle` fades the latch in."""
    a, e = pr["a"], pr["e"]
    bk = pr.get("buckle", 0.0)
    if bk > 0:
        bx, by = pr["b"]
        cv.paint(Canvas.poly_mask(_rect_pts(bx, by, 8, 6.5, -22, 2), body_tf), (44, 44, 52), bk)
        cv.paint(Canvas.poly_mask(_rect_pts(bx + 1.2, by - 0.4, 3.2, 2.4, -22, 1), body_tf), (222, 64, 52), bk)
    if math.hypot(e[0] - a[0], e[1] - a[1]) < 1.0:
        strap = [a, e]
    else:
        bow = pr.get("bow", (0.0, 5.0))
        strap = bezier(a, ((a[0] + e[0]) / 2 + bow[0], (a[1] + e[1]) / 2 + bow[1]), e, 20)
    cv.paint(Canvas.stroke_mask(strap, 6.4, body_tf), STRAP)
    cv.paint(Canvas.stroke_mask(strap, 1.3, body_tf), (110, 122, 150))
    (x0, y0), (x1, y1) = strap[-2], strap[-1]
    ang = math.degrees(math.atan2(y1 - y0, x1 - x0)) if (x1, y1) != (x0, y0) else 30.0
    cv.paint(Canvas.poly_mask(_rect_pts(e[0], e[1], 6.5, 4.6, ang, 1), body_tf), STEEL)


def _prop_nozzle(cv, pr, tf, body_tf, lb):
    sq = pr.get("squeeze", 0.0)
    cv.paint(Canvas.poly_mask(chaikin([(-7, -10), (9, -10), (11, -6), (-7, -4)], 2), tf), NOZZLE)
    cv.paint(Canvas.poly_mask([(9, -9), (24, -7), (24, -4.4), (9, -5.6)], tf), STEEL)
    cv.paint(Canvas.stroke_mask([(1, -3.5), (9, -3.5 + 2.2 * (1 - sq))], 1.8, tf), STEEL_DARK)   # trigger lever
    cv.paint(Canvas.stroke_mask([(-5, -8.6), (7, -8.6)], 1.0, tf), (150, 220, 160), 0.8)
    cv.paint(Canvas.poly_mask(_rect_pts(0, 3, 6, 13, 0, 1), tf), (52, 120, 70))                  # handle


def _prop_hose(cv, pr, tf, body_tf, lb):
    """Fuel hose from the back of the nozzle (same x, y, rot, scale) to `to`, behind the body."""
    start = lb.apply([(-6, -7)])[0]
    end = pr["to"]
    ctrl = pr.get("ctrl", (114, 104))
    pts = bezier(tuple(start), ctrl, end, 24)
    cv.paint(Canvas.stroke_mask(pts, 4.2, body_tf), HOSE)
    cv.paint(Canvas.stroke_mask(pts[:16], 0.9, body_tf), (96, 100, 112), 0.8)


def _prop_sponge(cv, pr, tf, body_tf, lb):
    foam = pr.get("foam", 0.0)
    body = Canvas.poly_mask(_rect_pts(0, 0, 15, 10, 0, 2), tf)
    cv.paint(body, SPONGE)
    cv.paint(clip(Canvas.poly_mask(_rect_pts(0, -4.2, 16, 3.4, 0, 1), tf), body), (96, 180, 112))
    for px, py in ((-4, 1.5), (1, 3), (4.5, 0.5), (-0.8, -0.2), (-5.5, 3.4)):
        cv.paint(Canvas.poly_mask(ellipse_pts(px, py, 1.1, 0.9), tf), (226, 176, 58))
    if foam > 0.02:
        for fx, fy, r in ((-5, -7, 3.2), (0, -8, 4.0), (5, -7, 3.4), (8.5, -3, 3.0), (-8, -3, 2.6), (3, -11, 2.4)):
            cv.paint(Canvas.poly_mask(ellipse_pts(fx, fy, r * foam, r * foam), tf), FOAM)
        cv.paint(Canvas.poly_mask(ellipse_pts(1.5, -9, 1.4 * foam, 1.1 * foam), tf), (190, 220, 250))


def _prop_flag(cv, pr, tf, body_tf, lb):
    side = pr.get("side", -1)
    ph = pr.get("wave", 0.0)
    cv.paint(Canvas.stroke_mask([(0, 7), (0, -28)], 2.4, tf), POLE)
    cv.paint(Canvas.poly_mask(ellipse_pts(0, -29, 2.1, 2.1), tf), (255, 200, 70))

    def pt(u, v):
        return side * (1.2 + 18 * u), -27 + 12 * v + 2.6 * u * math.sin(ph - 4.2 * u)

    for c in range(4):
        for r in range(3):
            quad = [pt(c / 4, r / 3), pt((c + 1) / 4, r / 3), pt((c + 1) / 4, (r + 1) / 3), pt(c / 4, (r + 1) / 3)]
            cv.paint(Canvas.poly_mask(quad, tf), FLAG_DARK if (c + r) % 2 else WHITE)


MIC_HEAD = (0.0, -14.0)        # mic ball in the prop's own coordinates; the grip (paw) is the origin
MIC_DARK = (58, 60, 74)
WOOD = (238, 182, 110)
WOOD_DARK = (176, 108, 56)
NECK_WOOD = (128, 78, 44)
STRING = (252, 244, 226)


def _prop_mic(cv, pr, tf, body_tf, lb):
    """Hand-held microphone: the grip is the origin (in the paw), the ball points along -y."""
    cv.paint(Canvas.poly_mask(_rect_pts(0, 0.5, 4.6, 14, 0, 2), tf), MIC_DARK)
    cv.paint(Canvas.stroke_mask([(-1, -5), (-1, 5)], 0.8, tf), (110, 114, 132), 0.8)
    cv.paint(Canvas.poly_mask(_rect_pts(0, -8, 6.4, 3, 0, 1), tf), STEEL_DARK)
    ball = Canvas.poly_mask(ellipse_pts(*MIC_HEAD, 5.6, 5.6), tf)
    cv.paint(ball, STEEL)
    for d in (-3, 0, 3):                                          # the grille
        cv.paint(clip(Canvas.stroke_mask([(d - 4, -18), (d + 4, -10)], 0.7, tf), ball), STEEL_DARK, 0.8)
        cv.paint(clip(Canvas.stroke_mask([(d + 4, -18), (d - 4, -10)], 0.7, tf), ball), STEEL_DARK, 0.8)
    cv.paint(clip(Canvas.poly_mask(ellipse_pts(-2, -16.5, 1.8, 1.2), tf), ball), WHITE, 0.8)


GUITAR_FRETS = (-16, -21, -26, -31, -36)


def _prop_guitar(cv, pr, tf, body_tf, lb):
    """Small acoustic guitar: the sound hole is the origin, the neck runs along -x to the headstock."""
    body = Canvas.poly_mask(ellipse_pts(4, 0, 11, 9.5), tf)
    Canvas.poly_mask(ellipse_pts(-8, 0, 8, 7.4), tf, body)
    Canvas.poly_mask(ellipse_pts(-2, 0, 4, 6.2), tf, body)        # fills the waist smoothly
    cv.paint(body, WOOD_DARK)
    top = Canvas.poly_mask(ellipse_pts(4, 0, 9.6, 8.2), tf)
    Canvas.poly_mask(ellipse_pts(-8, 0, 6.7, 6.1), tf, top)
    Canvas.poly_mask(ellipse_pts(-2, 0, 4, 5), tf, top)
    cv.paint(top, WOOD)
    cv.paint(clip(Canvas.poly_mask(ellipse_pts(-6, -3.5, 5, 2), tf), top), WHITE, 0.35)
    cv.paint(Canvas.poly_mask(ellipse_pts(-3, 0, 3.6, 3.6), tf), WOOD_DARK)
    cv.paint(Canvas.poly_mask(ellipse_pts(-3, 0, 2.7, 2.7), tf), (70, 40, 24))
    cv.paint(Canvas.poly_mask(_rect_pts(8, 0, 2.4, 8, 0, 1), tf), (92, 54, 30))      # bridge
    cv.paint(Canvas.poly_mask(_rect_pts(-26, 0, 28, 4.4, 0, 1), tf), NECK_WOOD)
    for fx in GUITAR_FRETS:
        cv.paint(Canvas.stroke_mask([(fx, -2), (fx, 2)], 0.6, tf), (214, 196, 170), 0.9)
    head = chaikin([(-40, -2.6), (-49, -4.2), (-49, 4.2), (-40, 2.6)], 1)
    cv.paint(Canvas.poly_mask(head, tf), (96, 56, 32))
    for px in (-43, -46.5):
        for py in (-4.6, 4.6):
            cv.paint(Canvas.poly_mask(ellipse_pts(px, py, 1.1, 1.1), tf), STEEL)
    for sy in (-1.2, -0.4, 0.4, 1.2):
        cv.paint(Canvas.stroke_mask([(8, sy), (-41, sy * 0.8)], 0.45, tf), STRING, 0.9)


# ---- wave 5 props: bayan, balalaika, mohawk, battery

BAYAN_RED = (164, 36, 48)
BAYAN_DARK = (104, 20, 32)
BAYAN_GOLD = (240, 200, 116)
BUTTON = (252, 248, 238)
BELLOWS_DARK = (58, 52, 70)
BELLOWS_LIGHT = (232, 222, 206)
BAYAN_H = 25.0                 # height of the bellows (the blocks are a little taller)
BAYAN_BLOCK = 11.0             # width of each block
BAYAN_FOLDS = 8


def bayan_edges(pr):
    """
    Bellows of the bayan (local coords; origin = inner edge of the right, keyboard block): x of the left edge
    at the top and at the bottom. `open` 0…1 pulls it out, `fan` makes the top open wider than the bottom —
    a real bayan opens like a fan, the left (bass) hand drawing an arc.
    """
    o, fan = pr.get("open", 0.5), pr.get("fan", 0.0)
    return -(5 + 18 * o * (1 + fan)), -(5 + 18 * o * (1 - fan))


def bayan_left_grip(pr):
    """Centre of the left (bass) block in local coords: where the left paw holds it."""
    xt, xb = bayan_edges(pr)
    h = BAYAN_H / 2
    ex, ey = xt - xb, -2 * h                   # inner edge, bottom → top
    ln = math.hypot(ex, ey)
    nx, ny = ey / ln, -ex / ln                 # its normal, pointing away from the bellows (left)
    if nx > 0:
        nx, ny = -nx, -ny
    return (xt + xb) / 2 + nx * BAYAN_BLOCK / 2, ny * BAYAN_BLOCK / 2


def _prop_bayan(cv, pr, tf, body_tf, lb):
    """Button accordion: the right block (keyboard) stays, the left one (bass) moves; the folded bellows between."""
    xt, xb = bayan_edges(pr)
    h = BAYAN_H / 2
    # bellows: pleats between the two inner edges; their rims zig-zag like real folds
    for j in range(BAYAN_FOLDS):
        u0, u1 = j / BAYAN_FOLDS, (j + 1) / BAYAN_FOLDS
        bulge0 = 1.3 if j % 2 else 0.0
        bulge1 = 0.0 if j % 2 else 1.3
        quad = [(lerp(xt, 0, u0), -h - bulge0), (lerp(xt, 0, u1), -h - bulge1),
                (lerp(xb, 0, u1), h + bulge1), (lerp(xb, 0, u0), h + bulge0)]
        cv.paint(Canvas.poly_mask(quad, tf), BELLOWS_LIGHT if j % 2 else BELLOWS_DARK)
        if j % 2 == 0:                          # the shadow inside each fold
            cv.paint(Canvas.stroke_mask([(lerp(xt, 0, u1), -h - bulge1), (lerp(xb, 0, u1), h + bulge1)], 0.6, tf),
                     (30, 26, 40), 0.7)
    for y in (-h - 0.6, h + 0.6):                # corner tape along the rims
        cv.paint(Canvas.stroke_mask([(xt if y < 0 else xb, y), (0, y)], 1.0, tf), STEEL, 0.9)
    # right block with the melody buttons
    rb = _rect_pts(BAYAN_BLOCK / 2, 0, BAYAN_BLOCK, BAYAN_H + 4, 0, 1)
    cv.paint(Canvas.poly_mask(rb, tf), BAYAN_RED)
    cv.paint(Canvas.stroke_mask([(0.8, -h - 1.2), (0.8, h + 1.2)], 0.9, tf), BAYAN_GOLD)
    for col, cx in enumerate((3.2, 5.9, 8.6)):
        for r in range(6):
            cy = -10.5 + r * 4.0 + (1.4 if col == 1 else 0)
            cv.paint(Canvas.poly_mask(ellipse_pts(cx, cy, 1.0, 1.0), tf), BUTTON)
    cv.paint(Canvas.poly_mask(ellipse_pts(5, -h - 0.2, 3, 0.9), tf), WHITE, 0.4)
    # left block (bass), turned with the edge of the bellows
    gx, gy = bayan_left_grip(pr)
    ang = math.degrees(math.atan2(-(xt - xb), 2 * h))
    lb_pts = _rect_pts(gx, gy, BAYAN_BLOCK, BAYAN_H + 4, -ang, 1)
    cv.paint(Canvas.poly_mask(lb_pts, tf), BAYAN_RED)
    rot = Tf.rotate(-ang, gx, gy)
    for k in range(4):
        bx, by = rot.apply([(gx - 1.5, gy - 6 + k * 4)])[0]
        cv.paint(Canvas.poly_mask(ellipse_pts(bx, by, 0.9, 0.9), tf), BUTTON)
    strap = rot.apply([(gx + 2.6, gy - h - 1), (gx + 2.6, gy + h + 1)])
    cv.paint(Canvas.stroke_mask([tuple(strap[0]), tuple(strap[1])], 1.6, tf), BAYAN_DARK)
    edge = rot.apply([(gx + BAYAN_BLOCK / 2 - 0.8, gy - h - 1.2), (gx + BAYAN_BLOCK / 2 - 0.8, gy + h + 1.2)])
    cv.paint(Canvas.stroke_mask([tuple(edge[0]), tuple(edge[1])], 0.9, tf), BAYAN_GOLD)


BALA_TOP = (248, 216, 150)
BALA_RIB = (184, 98, 42)
BALA_GUARD = (76, 40, 28)
BALA_FRETS = (-19, -24, -29, -34, -39)


def _prop_balalaika(cv, pr, tf, body_tf, lb):
    """Balalaika: a triangular body (the sound hole is the origin), the neck runs along -x to the headstock."""
    outer = chaikin([(-17, 0), (12, -16.5), (16, 0), (12, 16.5)], 1)
    body = Canvas.poly_mask(outer, tf)
    cv.paint(body, BALA_RIB)
    for k in (-8, -2.5, 3, 8.5):                 # the staves of the back show along the rim
        cv.paint(clip(Canvas.stroke_mask([(-15, 0), (15.5, k)], 0.5, tf), body), (150, 76, 30), 0.8)
    top = Canvas.poly_mask(chaikin([(-14, 0), (11, -14), (14, 0), (11, 14)], 1), tf)
    cv.paint(top, BALA_TOP)
    cv.paint(clip(Canvas.poly_mask(ellipse_pts(-2, -5, 7, 2.2, -28), tf), top), WHITE, 0.35)
    guard = chaikin([(0.5, -5), (5.5, -6.5), (4, 0), (5.5, 6.5), (0.5, 5), (2, 0)], 2)
    cv.paint(Canvas.poly_mask(guard, tf), BALA_GUARD, 0.85)
    cv.paint(Canvas.poly_mask(ellipse_pts(-5, 0, 2.8, 2.8), tf), BALA_RIB)
    cv.paint(Canvas.poly_mask(ellipse_pts(-5, 0, 2.1, 2.1), tf), (60, 34, 20))
    cv.paint(Canvas.poly_mask(_rect_pts(10.5, 0, 1.6, 7, 0, 1), tf), (92, 54, 30))       # bridge
    cv.paint(Canvas.poly_mask(_rect_pts(-29, 0, 30, 3.8, 0, 1), tf), NECK_WOOD)
    for fx in BALA_FRETS:
        cv.paint(Canvas.stroke_mask([(fx, -1.7), (fx, 1.7)], 0.55, tf), (214, 196, 170), 0.9)
    head = chaikin([(-43, -2.2), (-52, -3.4), (-52, 3.4), (-43, 2.2)], 1)
    cv.paint(Canvas.poly_mask(head, tf), (96, 56, 32))
    for px, py in ((-46, -3.8), (-50, -3.9), (-48, 3.9)):
        cv.paint(Canvas.poly_mask(ellipse_pts(px, py, 1.1, 1.1), tf), STEEL)
    for sy in (-1.0, 0.0, 1.0):
        cv.paint(Canvas.stroke_mask([(10.5, sy * 1.2), (-44, sy * 0.7)], 0.45, tf), STRING, 0.9)


MOHAWK_TIP = (255, 120, 200)
MOHAWK_BASE = (226, 40, 150)
# Spikes of the mohawk (local coords on the head, origin = top of the head): base x, lean (deg), height.
MOHAWK_SPIKES = ((-10, -26, 15), (-5, -12, 19), (0, 0, 21.5), (5, 12, 19), (10, 26, 15))


def _prop_mohawk(cv, pr, tf, body_tf, lb):
    """Punk mohawk standing up from the top of the head; `grow` 0…1 raises the spikes one after another, `bend` sways them."""
    grow, bend = pr.get("grow", 1.0), pr.get("bend", 0.0)
    n = len(MOHAWK_SPIKES)
    mask = Canvas.new_mask()
    tips = []
    for k, (bx, lean, hgt) in enumerate(MOHAWK_SPIKES):
        g = smooth(clamp(grow * 1.6 - abs(k - n // 2) * 0.22))   # the middle first, then outward
        if g <= 0.01:
            continue
        a = math.radians(lean + bend * (0.6 + 0.4 * hgt / 21.5))
        by = 3 + 0.05 * bx * bx                  # the base follows the round top of the head
        tip = (bx + math.sin(a) * hgt * g, by - math.cos(a) * hgt * g)
        w = 4.2
        side = (math.cos(a) * w, math.sin(a) * w)
        mid = (bx + math.sin(a) * hgt * g * 0.55 - side[0] * 0.25, by - math.cos(a) * hgt * g * 0.55 - side[1] * 0.25)
        spike = chaikin([(bx - side[0], by - side[1]), mid, tip, (bx + side[0], by + side[1]), (bx, by + 4)], 2)
        Canvas.poly_mask(spike, tf, mask)
        tips.append(tip)
    if not tips:
        return
    ys = [float(tf.apply([t])[0][1]) for t in tips]
    base_y = float(tf.apply([(0, 6)])[0][1])
    cv.paint_gradient(mask, MOHAWK_TIP, MOHAWK_BASE, min(ys), base_y)
    shine = Canvas.stroke_mask([(-0.8, 4), (-0.8 + math.sin(math.radians(bend)) * 9 * grow, 4 - 11 * grow)], 1.0, tf)
    cv.paint(clip(shine, mask), WHITE, 0.45)


def _prop_battery(cv, pr, tf, body_tf, lb):
    """A battery held in the paws (the centre is the origin): the red charge bar blinks with `blink`."""
    w, h = 24.0, 12.0
    cv.paint(Canvas.poly_mask(_rect_pts(0, 0, w, h, 0, 1), tf), (60, 64, 80))
    cv.paint(Canvas.poly_mask(_rect_pts(w / 2 + 1.6, 0, 3.2, 5.4, 0, 1), tf), (60, 64, 80))
    cv.paint(Canvas.poly_mask(_rect_pts(0, 0, w - 4, h - 4, 0, 1), tf), WHITE)
    level = clamp(pr.get("level", 0.08))
    fill = max(1.4, (w - 6) * level)
    bar = _rect_pts(-(w - 6) / 2 + fill / 2, 0, fill, h - 6, 0, 1)
    cv.paint(Canvas.poly_mask(bar, tf), (236, 56, 48), 0.25 + 0.75 * clamp(pr.get("blink", 1.0)))
    cv.paint(Canvas.poly_mask(ellipse_pts(-4, -4.2, 6, 1.0), tf), WHITE, 0.5)


PROPS = {
    "wheel": _prop_wheel, "wrench": _prop_wrench, "map": _prop_map, "tire": _prop_tire,
    "belt": _prop_belt, "nozzle": _prop_nozzle, "hose": _prop_hose, "sponge": _prop_sponge,
    "flag": _prop_flag, "mic": _prop_mic, "guitar": _prop_guitar,
    "bayan": _prop_bayan, "balalaika": _prop_balalaika, "mohawk": _prop_mohawk, "battery": _prop_battery,
}


def draw_prop(cv, prop, body_tf, head_tf=None):
    op = prop.get("opacity", 1.0)
    if op <= 0.01 or prop.get("scale", 1.0) <= 0.01:
        return
    lb = _local(prop)
    base = FIT if prop.get("ground") else body_tf      # a ground prop does not lean or jump with the body
    if prop.get("head") and head_tf is not None:
        base = head_tf                                 # on the head (the mohawk): tilts and bobs with it
    layer = Canvas()
    PROPS[prop["kind"]](layer, prop, base @ lb, base, lb)
    img = layer.outlined(1.0)
    if op < 1:
        img.putalpha(img.getchannel("A").point(lambda v: int(v * op)))
    cv.img.alpha_composite(img)


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
    elif kind == "butterfly":
        layer = _butterfly(x, y, s, e.get("flap", 1.0), e.get("rot", 0.0), e.get("color", BUTTERFLY), op)
    elif kind == "whoosh":
        strokes = [([(x - 7 * s + dx, y + dy), (x + 7 * s + dx, y + dy)], 1.8)
                   for dx, dy in ((0, -7 * s), (-5 * s, 0), (2 * s, 7 * s))]
        layer = _sticker([], (150, 165, 195), strokes, opacity=op, out_px=0.8)
    elif kind == "gauge":
        layer = _gauge(x, y, s, e.get("level", 0.0), op)
    elif kind == "bubble":
        layer = _bubble(x, y, s, op)
    elif kind == "arrow":
        side = e.get("side", 1)
        pts = [(x - side * 7 * s, y - 2.2 * s), (x + side * 1 * s, y - 2.2 * s), (x + side * 1 * s, y - 6 * s),
               (x + side * 8 * s, y), (x + side * 1 * s, y + 6 * s), (x + side * 1 * s, y + 2.2 * s),
               (x - side * 7 * s, y + 2.2 * s)]
        layer = _sticker([chaikin(pts, 1)], (70, 190, 110), opacity=op, out_px=1.3)
    elif kind == "psign":
        layer = _psign(x, y, s, op)
    elif kind == "bolt":
        r = math.radians(e.get("rot", 0.0))
        c, si = math.cos(r), math.sin(r)
        pts = [(-1.5, -9), (4.5, -9), (1, -2), (5, -2), (-3.5, 9), (-1, 1), (-5, 1)]
        bolt = [(x + (px * c - py * si) * s, y + (px * si + py * c) * s) for px, py in pts]
        layer = _sticker([bolt], (255, 214, 40), opacity=op, out_px=1.2)
    else:
        return
    img.alpha_composite(layer)


def _faded(img, op):
    if op < 1:
        img.putalpha(img.getchannel("A").point(lambda v: int(v * op)))
    return img


def _gauge(x, y, s, level, op):
    """Fuel gauge: half dial from red E (left) to green F (right), the needle points at `level`."""
    cv = Canvas()
    r = 11 * s
    dial = [(x, y)] + [(x + r * math.cos(math.radians(a)), y + r * math.sin(math.radians(a))) for a in range(180, 361, 6)]
    cv.paint(Canvas.poly_mask(dial), (60, 64, 80))
    inner = [(x, y)] + [(x + (r - 2 * s) * math.cos(math.radians(a)), y + (r - 2 * s) * math.sin(math.radians(a)))
                        for a in range(180, 361, 6)]
    cv.paint(Canvas.poly_mask(inner), WHITE)
    for a0, col in ((180, (236, 70, 56)), (306, (70, 190, 96))):
        arc = [(x + (r - 3.4 * s) * math.cos(math.radians(a)), y + (r - 3.4 * s) * math.sin(math.radians(a)))
               for a in range(a0, a0 + 55, 5)]
        cv.paint(Canvas.stroke_mask(arc, 1.8 * s), col)
    a = math.radians(180 + 180 * clamp(level))
    cv.paint(Canvas.stroke_mask([(x, y), (x + (r - 3 * s) * math.cos(a), y + (r - 3 * s) * math.sin(a))], 1.6 * s),
             (220, 50, 50))
    cv.paint(Canvas.poly_mask(ellipse_pts(x, y, 1.8 * s, 1.8 * s)), (60, 64, 80))
    return _faded(cv.outlined(1.1, WHITE), op)


def _bubble(x, y, s, op):
    cv = Canvas()
    r = 3.2 * s
    cv.paint(Canvas.poly_mask(ellipse_pts(x, y, r, r)), (200, 232, 255), 0.45)
    cv.paint(Canvas.stroke_mask([(x + px, y + py) for px, py in _ring(r, n=40)], 0.8 * s),
             (120, 180, 230))
    cv.paint(Canvas.poly_mask(ellipse_pts(x - r * 0.35, y - r * 0.4, r * 0.3, r * 0.22)), WHITE)
    return _faded(cv.img, op)


def _psign(x, y, s, op):
    """Blue parking sign with a white P."""
    cv = Canvas()
    cv.paint(Canvas.poly_mask(_rect_pts(x, y, 15 * s, 15 * s, 0, 2)), (50, 110, 220))
    stem = [(x - 2.6 * s, y + 4.6 * s), (x - 2.6 * s, y - 4.6 * s)]
    bowl = bezier((x - 2.6 * s, y - 4.6 * s), (x + 6.2 * s, y - 3.2 * s), (x - 2.6 * s, y + 0.6 * s), 12)
    cv.paint(Canvas.stroke_mask(stem, 2.2 * s), WHITE)
    cv.paint(Canvas.stroke_mask(bowl, 2.2 * s), WHITE)
    return _faded(cv.outlined(1.2, WHITE), op)


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


BUTTERFLY = (150, 120, 250)


def _butterfly(x, y, s, flap, rot, color, op):
    """Butterfly seen from the front; `flap` 0..1 is how open the wings are (they fold to an edge)."""
    tf = Tf.rotate(rot, x, y)
    cv = Canvas()
    f = 0.18 + 0.82 * clamp(flap)
    light = tuple(min(255, c + 70) for c in color)
    for side in (-1, 1):
        upper = ellipse_pts(x + side * 4.6 * s * f, y - 2.4 * s, 4.6 * s * f, 3.9 * s, side * 20)
        lower = ellipse_pts(x + side * 3.2 * s * f, y + 2.6 * s, 3.2 * s * f, 2.8 * s, -side * 20)
        cv.paint(Canvas.poly_mask(upper, tf), color)
        cv.paint(Canvas.poly_mask(lower, tf), color)
        spot = ellipse_pts(x + side * 5.4 * s * f, y - 2.8 * s, 1.6 * s * f, 1.5 * s)
        cv.paint(Canvas.poly_mask(spot, tf), light)
        antenna = bezier((x, y - 3 * s), (x + side * 1.2 * s, y - 6.5 * s), (x + side * 3 * s, y - 7.5 * s), 6)
        cv.paint(Canvas.stroke_mask(antenna, 0.7 * s, tf), DARK)
    cv.paint(Canvas.poly_mask(ellipse_pts(x, y, 1.2 * s, 4.2 * s), tf), DARK)
    img = cv.outlined(1.0, WHITE)
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
        p["lean"] = -side * 6 * throw - side * 2.5 * wob
        p["dx"] = -side * 2 * throw
        p["dy"] = -3 * throw                       # lifted a little, so the paws stay in the frame
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


# ---- wave 3: little scenes and character

def _secs(n):
    return n * FRAME_MS / 1000


def tail_hide(i, n):
    """Lifts the tail in front of the face, peeks out with one eye, hides again, lowers the tail."""
    t = i / n
    secs = _secs(n)
    p = default_pose()
    breathe(p, t, 0.7)
    def wrap_at(x):
        return window(x, 0.06, 0.27, 0.76, 0.95, ease_in_out)
    w = wrap_at(t)
    peek = window(t, 0.38, 0.46, 0.6, 0.67, ease_in_out)
    p["tail_wrap"] = w
    p["tail_peek"] = peek
    p["tail"] = 3 * math.sin(2 * math.pi * t * 3) * w + 6 * lag(wrap_at, t, secs, freq=1.6)
    p["head_tilt"] = 5 * w - 7 * peek
    p["head_dy"] = 1.5 * w
    p["lean"] = -2.5 * peek
    p["dx"] = -1.2 * peek
    p["ear_out"] = 10 * w * (1 - peek)
    p["ear_l"] = -8 * peek + ear_flick(t, 0.47, amp=10)
    p["ear_r"] = ear_flick(t, 0.86, amp=12)
    p["blush"] = 0.5 + 0.45 * w
    if 0.2 < t < 0.8 and peek < 0.35:
        p["eyes"] = "closed"
    p["eye_r"] = "closed" if 0.2 < t < 0.8 else None
    p["look"] = (-0.4 * peek, 0.2 * peek)
    p["open"] = 1 - blink_shut(t, 0.53, "single", 0.06)
    if 0.8 <= t < 0.93:
        p["eyes"] = "happy"
        p["eye_r"] = None
    p["mouth"] = "smile" if 0.8 <= t < 0.95 else "w"
    ex = []
    h = _floating(t, "heart", 100, 36, rise=16, delay=0.8, life=0.18, size=0.6)
    if h:
        ex.append(h)
    p["extras"] = ex
    return p


def grooming(i, n):
    """Licks a front paw, rubs its face with it, then shakes its ears."""
    t = i / n
    p = default_pose()
    breathe(p, t, 0.6)
    up = window(t, 0.06, 0.16, 0.68, 0.77, ease_in_out)
    licks = sum(bump(t, a, a + 0.07) for a in (0.17, 0.25, 0.33))
    wipe = window(t, 0.40, 0.44, 0.62, 0.66)
    a = 2 * math.pi * 2 * clamp((t - 0.40) / 0.26)               # two circles over the cheek
    mouth_paw = (56, 86 - 2.5 * licks)
    cheek_paw = (47 + 6 * math.cos(a + 0.6), 70 - 11 * math.sin(a + 0.6) - 4)
    paw = tuple(lerp(m, c, wipe) for m, c in zip(mouth_paw, cheek_paw))
    if up > 0.02:
        p["arm"] = dict(side=-1, x=lerp(42, paw[0], up), y=lerp(108, paw[1], up))
    shake = window(t, 0.76, 0.79, 0.88, 0.93)
    wob = math.sin(2 * math.pi * t * 9)
    p["head_tilt"] = -6 * up - 5 * wipe * (0.6 + 0.4 * math.sin(a)) + 7 * wob * shake
    p["head_dy"] = 1.5 * up - 1.0 * licks
    p["lean"] = -2 * up + 2 * wob * shake
    p["look"] = (-0.6 * up, 0.8 * up * (1 - wipe))
    # the ears flick one after the other, not as a pair
    p["ear_l"] = 16 * math.sin(2 * math.pi * t * 9) * shake + 4 * wipe
    p["ear_r"] = 16 * math.sin(2 * math.pi * t * 9 + 1.4) * shake
    p["ear_out"] = 6 * up
    p["tongue"] = clamp(licks * 1.3)
    p["tongue_dx"] = -2.5
    p["eyes"] = "closed" if 0.15 < t < 0.92 else "open"
    if 0.92 <= t < 0.98:
        p["eyes"] = "happy"
    p["mouth"] = "w"
    p["blush"] = 0.5 + 0.25 * up
    p["tail"] = 5 * math.sin(2 * math.pi * t * 2)
    ex = []
    for sx_, sy_, d in ((30, 46, 0.66), (100, 34, 0.9)):
        f = _floating(t, "sparkle", sx_, sy_, rise=6, delay=d, life=0.1, size=0.8, drift=0, rot=90 * t)
        if f:
            ex.append(f)
    p["extras"] = ex
    return p


BUTTERFLY_PATH = [
    (0.00, (12, 34)), (0.08, (24, 20)), (0.17, (50, 16)), (0.26, (88, 24)), (0.34, (108, 44)),
    (0.42, (84, 54)), (0.48, (64, 70)), (0.58, (64, 70)), (0.64, (82, 30)), (0.72, (100, 20)),
    (0.80, (86, 16)), (0.88, (100, 14)), (0.97, (108, 16)), (1.00, (110, 16)),
]


def butterfly(i, n):
    """Follows a butterfly with its eyes, it lands on the nose, a swipe misses, it flies away."""
    t = i / n
    secs = _secs(n)
    p = default_pose()
    breathe(p, t, 0.6)
    bx, by = spline(t, BUTTERFLY_PATH)
    perched = window(t, 0.47, 0.49, 0.58, 0.6)
    flutter = 1 - perched
    bx += 2.0 * math.sin(2 * math.pi * t * 7) * flutter
    by += 2.5 * math.sin(2 * math.pi * t * 11) * flutter
    # Gaze follows with a slight lag (spring), the head turns a little after the eyes.
    def gx(x):
        return clamp((spline(x, BUTTERFLY_PATH)[0] - 64) / 26, -1.5, 1.5)
    def gy(x):
        return clamp((spline(x, BUTTERFLY_PATH)[1] - 60) / 22, -1.3, 1.0)
    noticed = ramp(t, 0.04, 0.1) * (1 - ramp(t, 0.84, 0.96))
    lx = spring(gx, t, secs, freq=3.0, zeta=0.7) * noticed
    ly = spring(gy, t, secs, freq=3.0, zeta=0.7) * noticed
    p["look"] = (lx * (1 - perched), ly * (1 - perched) + 0.7 * perched)
    p["cross"] = perched
    p["head_tilt"] = 6 * spring(gx, t, secs, freq=1.4, zeta=0.8) * noticed * (1 - perched)
    p["head_dy"] = -1.5 * max(0.0, -ly)
    p["ear_l"] = -8 * noticed * clamp(-lx) + ear_flick(t, 0.2, amp=8)
    p["ear_r"] = -8 * noticed * clamp(lx) + ear_flick(t, 0.3, amp=8)
    p["ear_out"] = -4 * noticed
    # swipe: crouch, little hop up after it, paw reaching toward where it flew
    hop(p, t, 0.6, 0.72, 4)
    reach = window(t, 0.6, 0.65, 0.72, 0.8, ease_out)
    if reach > 0.02:
        p["arm"] = dict(side=1, x=lerp(86, 96, reach), y=lerp(108, 46, reach))
    surprised = window(t, 0.66, 0.7, 0.8, 0.86)
    p["ear_scale"] = 1 + 0.1 * surprised
    p["eyes"] = "wide" if surprised > 0.5 else "open"
    if 0.86 < t < 0.97:
        p["eyes"] = "happy"
    p["open"] = 1 - blink_shut(t, 0.36, "single", 0.05)
    p["mouth"] = "o" if surprised > 0.5 or 0.48 < t < 0.58 else ("smile" if t > 0.86 else "w")
    def dyf(x):
        return hop_dy(x, 0.6, 0.72, 4)
    p["tail"] = (6 + 10 * noticed) * math.sin(2 * math.pi * t * (3 + 2 * noticed)) * (1 - surprised) \
        + 2.5 * lag(dyf, t, secs, freq=2.0)
    p["blush"] = 0.5 + 0.3 * perched
    op = ramp(t, 0.0, 0.05) * (1 - ramp(t, 0.84, 0.94))
    flap = abs(math.cos(2 * math.pi * t * (16 * flutter + 3 * perched)))
    p["extras"] = [dict(kind="butterfly", x=bx, y=by, scale=1.0, flap=flap,
                        rot=12 * math.sin(2 * math.pi * t * 3) * flutter, opacity=op)]
    sp = _floating(t, "sparkle", 30, 34, rise=6, delay=0.86, life=0.12, size=0.8, drift=0)
    if sp:
        p["extras"].append(sp)
    return p


def curled(p, k):
    """Lying down curled up, k = 0 (sitting) … 1 (curled): flat wide body, head resting low."""
    p["body_sy"] *= 1 - 0.2 * k
    p["body_sx"] *= 1 + 0.1 * k
    p["head_dy"] += 12 * k
    p["head_tilt"] += 5 * k
    p["dx"] += 1.5 * k
    p["ear_out"] += 18 * k
    p["ear_scale"] *= 1 - 0.07 * k
    p["tail_style"] = "curl"


def curl_up(i, n):
    """Sleepy: yawns, lies down, wraps the tail over its nose and dozes off (→ sleep_curled)."""
    t = i / n
    p = default_pose()
    breathe(p, t, 0.8 + 0.8 * ramp(t, 0.6, 0.9), cycles=2)
    yawn_ = bump(t, 0.06, 0.3)
    k = keys(t, [(0.0, 0.0), (0.24, 0.0), (0.29, -0.06), (0.62, 1.0), (1.0, 1.0)])
    curled(p, max(0.0, k))
    if k < 0:                                        # rises a little before lying down
        p["sy"] *= 1 - 0.8 * k
    p["tail_wrap"] = keys(t, [(0.0, 0.0), (0.5, 0.0), (0.8, 1.0), (1.0, 1.0)])
    p["tail"] = 2 * math.sin(2 * math.pi * t)
    p["mouth"] = "yawn" if 0.08 < t < 0.28 else "w"
    p["mouth_open"] = yawn_
    p["sy"] *= 1 + 0.04 * yawn_
    shut = ramp(t, 0.55, 0.82)
    p["eyes"] = "closed" if (shut > 0.8 or 0.1 < t < 0.27) else "open"
    p["open"] = (0.62 - 0.3 * shut) * (1 - blink_shut(t, 0.36, "slow", 0.05))
    p["ear_r"] = ear_flick(t, 0.9, amp=6)
    p["blush"] = 0.45
    p["extras"] = [dict(z, opacity=z["opacity"] * ramp(t, 0.84, 0.98)) for z in _zzz(t, cycles=1)] if t > 0.84 else []
    return p


def sleep_curled(i, n):
    """Sleeping curled up with the tail over the nose (loop). Pairs with curl_up / wake_curled."""
    t = i / n
    p = default_pose()
    breathe(p, t, 2.0, cycles=2)
    curled(p, 1.0)
    p["tail_wrap"] = 1.0
    p["tail"] = 2 * math.sin(2 * math.pi * t)
    p["ear_r"] = ear_flick(t, 0.9, amp=6) + ear_flick(t, 0.6, amp=5)
    p["ear_l"] = ear_flick(t, 0.25, amp=4)
    p["eyes"] = "closed"
    p["blush"] = 0.45
    p["extras"] = _zzz(t)
    return p


def wake_curled(i, n):
    """Wakes up from sleep_curled: opens its eyes, unwraps the tail, sits up and stretches."""
    t = i / n
    p = default_pose()
    k = keys(t, [(0.0, 1.0), (0.18, 1.0), (0.5, 0.0), (1.0, 0.0)])
    curled(p, k)
    p["tail_wrap"] = keys(t, [(0.0, 1.0), (0.1, 1.0), (0.42, 0.0), (1.0, 0.0)])
    stretch_ = bump(t, 0.5, 0.82)
    p["sy"] *= 1 + 0.07 * stretch_
    p["sx"] *= 1 - 0.03 * stretch_
    p["ear_out"] -= 6 * bump(t, 0.5, 0.7)
    p["ear_r"] = ear_flick(t, 0.04, amp=8)
    p["ear_l"] = ear_flick(t, 0.12, amp=8)
    p["eyes"] = "closed" if t < 0.12 or 0.55 < t < 0.8 else "open"
    p["open"] = (0.45 + 0.55 * ramp(t, 0.12, 0.45)) * (1 - blink_shut(t, 0.86, "double", 0.05))
    p["mouth"] = "yawn" if 0.52 < t < 0.8 else "w"
    p["mouth_open"] = 0.7 * stretch_
    p["tail"] = 8 * math.sin(2 * math.pi * t * 2) * ramp(t, 0.45, 0.6) * (1 - ramp(t, 0.9, 1.0))
    p["blush"] = 0.45 + 0.1 * ramp(t, 0.3, 0.6)
    z = [dict(zz, opacity=zz["opacity"] * (1 - ramp(t, 0.0, 0.12))) for zz in _zzz(t)] if t < 0.12 else []
    sp = _floating(t, "sparkle", 104, 34, rise=6, delay=0.82, life=0.14, size=0.9, drift=0)
    p["extras"] = z + ([sp] if sp else [])
    return p


def tease(i, n):
    """Winks, sticks its tongue out with a little bounce, then smiles smugly."""
    t = i / n
    secs = _secs(n)
    p = default_pose()
    breathe(p, t, 0.6)
    tilt = window(t, 0.04, 0.16, 0.8, 0.94, ease_out)
    bleh = window(t, 0.16, 0.22, 0.66, 0.72)
    hop(p, t, 0.3, 0.42, 3)
    p["head_tilt"] = 9 * tilt + 3 * math.sin(2 * math.pi * t * 4) * bleh
    p["lean"] = 2 * tilt
    p["ear_l"] = -6 * tilt
    p["ear_r"] = 8 * tilt + ear_flick(t, 0.5, amp=8)
    p["eye_l"] = "happy" if 0.12 < t < 0.7 else None   # the wink
    p["open"] = 1 + 0.05 * tilt
    p["tongue"] = bleh
    p["tongue_dx"] = 1.5 * math.sin(2 * math.pi * t * 6) * bleh
    p["mouth"] = "smile" if 0.7 < t < 0.92 else "w"
    if 0.72 < t < 0.9:
        p["eyes"] = "happy"
    def dyf(x):
        return hop_dy(x, 0.3, 0.42, 3)
    p["tail"] = 18 * math.sin(2 * math.pi * t * 5) * tilt + 3 * lag(dyf, t, secs)
    p["blush"] = 0.6 + 0.3 * tilt
    ex = []
    sp = _floating(t, "sparkle", 30, 44, rise=6, delay=0.12, life=0.22, size=0.9, drift=0, rot=90 * t)
    if sp:
        ex.append(sp)
    sp = _floating(t, "sparkle", 102, 30, rise=6, delay=0.7, life=0.2, size=0.8, drift=0, rot=90 * t)
    if sp:
        ex.append(sp)
    p["extras"] = ex
    return p


def sneeze(i, n):
    """Nose twitches and scrunches, a sharp sneeze, shakes it off, blinks and recovers."""
    t = i / n
    secs = _secs(n)
    p = default_pose()
    build = keys(t, [(0.0, 0.0), (0.08, 0.0), (0.36, 1.0), (0.4, 0.0)], ease_in)
    twitch = 0.25 * math.sin(2 * math.pi * t * 14) * window(t, 0.08, 0.12, 0.2, 0.26)
    achoo = keys(t, [(0.0, 0.0), (0.36, 0.0), (0.385, 1.0), (0.5, 0.25), (0.62, 0.0)], ease_out)
    shake = window(t, 0.44, 0.47, 0.58, 0.64)
    wob = math.sin(2 * math.pi * t * 10)
    def head_y(x):
        return -2.5 * keys(x, [(0.0, 0.0), (0.08, 0.0), (0.36, 1.0), (0.4, 0.0)], ease_in) \
            + 4 * keys(x, [(0.0, 0.0), (0.36, 0.0), (0.385, 1.0), (0.5, 0.25), (0.62, 0.0)], ease_out)
    p["head_dy"] = head_y(t)
    p["head_tilt"] = -4 * build + 8 * wob * shake
    p["sy"] = 1 + 0.04 * build - 0.09 * achoo
    p["sx"] = 1 - 0.02 * build + 0.06 * achoo
    p["lean"] = 2 * wob * shake
    p["nose_wrinkle"] = clamp(build * 1.1 + twitch + 0.6 * achoo)
    p["ear_out"] = 6 * build - 6 * achoo
    p["ear_l"] = 14 * math.sin(2 * math.pi * t * 10) * shake
    p["ear_r"] = 14 * math.sin(2 * math.pi * t * 10 + 1.2) * shake
    if achoo > 0.3 or shake > 0.3:
        p["eyes"] = "squeeze"
    p["open"] = (1 - 0.6 * build) * (1 - blink_shut(t, 0.7, "double", 0.06))
    p["look"] = (0.0, -0.4 * build)
    p["mouth"] = "yawn" if build > 0.15 else ("open" if achoo > 0.3 else "w")
    p["mouth_open"] = 0.15 + 0.3 * build if build > 0.15 else 1.0
    p["tail"] = 4 * math.sin(2 * math.pi * t) - 3 * lag(head_y, t, secs, freq=2.0, zeta=0.25)
    p["blush"] = 0.5 + 0.3 * achoo
    ex = []
    for k, (dx_, dy_) in enumerate(((-30, 4), (30, 2), (-18, 14), (20, 16))):
        ph = (t - 0.38 - 0.02 * k) / 0.22
        if 0 < ph < 1:
            ex.append(dict(kind="puff", x=64 + dx_ * (0.6 + 0.8 * ease_out(ph)), y=90 + dy_ * ph,
                           scale=0.5 + 0.35 * ph, opacity=1 - ramp(ph, 0.5, 1.0)))
    for k, ang in enumerate((-160, -20, -120, -60)):
        ph = (t - 0.38) / 0.18
        if 0 < ph < 1:
            r = math.radians(ang)
            ex.append(dict(kind="drop", x=64 + (22 + 18 * ph) * math.cos(r), y=78 + (22 + 18 * ph) * math.sin(r),
                           scale=0.6, rot=ang + 90, opacity=1 - ramp(ph, 0.6, 1.0)))
    if 0.37 < t < 0.5:
        ex.append(dict(kind="shock", x=26, y=30, scale=0.7, opacity=1 - ramp(t, 0.44, 0.5)))
    p["extras"] = ex
    return p


def listen(i, n):
    """Ears go up, one ear and the eyes turn toward a sound on the right, then to the left."""
    t = i / n
    p = default_pose()
    breathe(p, t, 0.7)
    alert_ = window(t, 0.08, 0.18, 0.8, 0.92, ease_in_out)
    right = window(t, 0.2, 0.28, 0.46, 0.54, ease_in_out)
    left = window(t, 0.52, 0.6, 0.72, 0.8, ease_in_out)
    p["ear_scale"] = 1 + 0.1 * alert_
    p["ear_out"] = -6 * alert_
    p["ear_r"] = 12 * right - 4 * left + ear_flick(t, 0.36, amp=6)
    p["ear_l"] = -12 * left + 4 * right + ear_flick(t, 0.64, amp=6)
    p["ear_scale_r"] = 1 + 0.06 * right
    p["ear_scale_l"] = 1 + 0.06 * left
    p["look"] = gaze(t, [(0.0, (0.0, 0.0)), (0.24, (1.3, -0.3)), (0.4, (1.1, -0.5)),
                         (0.56, (-1.2, -0.3)), (0.76, (0.0, 0.0))])
    p["head_tilt"] = 7 * right - 6 * left
    p["head_dy"] = -1.2 * alert_
    p["open"] = (1 + 0.08 * alert_) * (1 - blink_shut(t, 0.84, "single", 0.06))
    p["mouth"] = "w"
    p["tail"] = 3 * math.sin(2 * math.pi * t) - 4 * alert_
    p["blush"] = 0.45
    ex = []
    if right > 0.05:
        ex.append(dict(kind="waves", x=112, y=40, scale=0.8, side=1,
                       opacity=right * (0.45 + 0.35 * math.sin(2 * math.pi * t * 6))))
    if left > 0.05:
        ex.append(dict(kind="waves", x=16, y=40, scale=0.8, side=-1,
                       opacity=left * (0.45 + 0.35 * math.sin(2 * math.pi * t * 6))))
    p["extras"] = ex
    return p


def paw_wave(i, n):
    """Raises a front paw and waves hello (no pop-in, unlike greeting)."""
    t = i / n
    p = default_pose()
    breathe(p, t, 0.6)
    raise_ = window(t, 0.06, 0.2, 0.78, 0.92, ease_out_back)
    waving = window(t, 0.2, 0.26, 0.7, 0.78)
    wave = math.sin(2 * math.pi * (t - 0.2) * 4.5) * waving
    if raise_ > 0.02:
        p["arm"] = dict(side=-1, x=lerp(42, 22 + 6 * wave, raise_), y=lerp(108, 58 - 3 * abs(wave), raise_))
    p["lean"] = 2.5 * wave * raise_ - 1.5 * raise_
    p["head_tilt"] = -7 * raise_ + 2 * wave
    p["ear_l"] = -6 * raise_ + ear_flick(t, 0.3, amp=8)
    p["ear_r"] = 5 * raise_
    p["eyes"] = "happy" if 0.18 < t < 0.82 else "open"
    p["open"] = 1 - blink_shut(t, 0.9, "single", 0.05)
    p["mouth"] = "open" if 0.18 < t < 0.82 else "w"
    p["mouth_open"] = 0.7
    p["blush"] = 0.65
    p["tail"] = 14 * math.sin(2 * math.pi * t * 4) * raise_ + 3 * math.sin(2 * math.pi * t)
    ex = []
    for sx_, sy_, d in ((16, 34, 0.3), (30, 22, 0.5)):
        f = _floating(t, "sparkle", sx_, sy_, rise=5, delay=d, life=0.2, size=0.8, drift=0, rot=60 * t)
        if f:
            ex.append(f)
    p["extras"] = ex
    return p


def confused(i, n):
    """Tilts its head, looks around, scratches its cheek under a question mark."""
    t = i / n
    p = default_pose()
    breathe(p, t, 0.6)
    tilt = keys(t, [(0.0, 0.0), (0.06, 0.0), (0.18, 1.0), (0.5, 1.0), (0.62, -0.8), (0.76, -0.8), (0.92, 0.0)])
    q = window(t, 0.12, 0.2, 0.8, 0.9, ease_out_back)
    think = window(t, 0.56, 0.64, 0.76, 0.84, ease_in_out)
    p["head_tilt"] = 13 * tilt
    p["ear_l"] = -10 * max(0.0, tilt) + 10 * max(0.0, -tilt)
    p["ear_r"] = 10 * max(0.0, tilt) - 8 * max(0.0, -tilt)
    p["ear_scale_r"] = 1 - 0.08 * max(0.0, tilt)
    p["look"] = gaze(t, [(0.0, (0.0, 0.0)), (0.22, (0.4, -0.6)), (0.3, (-1.4, 0.0)), (0.42, (1.4, 0.0)),
                         (0.54, (0.0, -0.8)), (0.8, (0.0, 0.0))])
    p["open"] = 1 - blink_shut(t, 0.48, "double", 0.05)
    p["mouth"] = "o" if 0.28 < t < 0.5 else "w"
    if think > 0.02:
        scratch = math.sin(2 * math.pi * t * 10) * window(t, 0.62, 0.64, 0.74, 0.76)
        p["arm"] = dict(side=1, x=lerp(86, 92 + 1.5 * scratch, think), y=lerp(108, 78 + 2 * scratch, think))
    p["tail"] = 5 * math.sin(2 * math.pi * t * 1.5)
    p["blush"] = 0.45
    bob = 2 * math.sin(2 * math.pi * t * 2)
    p["extras"] = [dict(kind="question", x=104 - 2 * think, y=26 + bob, scale=q,
                        opacity=min(1.0, q * 1.5))] if q > 0.02 else []
    return p


def proud(i, n):
    """Sits up straight, chin up, smug closed eyes, slow sweeping tail wag, sparkles."""
    t = i / n
    p = default_pose()
    breathe(p, t, 0.8, cycles=2)
    up = window(t, 0.04, 0.2, 0.8, 0.94, ease_out_back)
    nod = bump(t, 0.5, 0.6)
    p["sy"] *= 1 + 0.06 * up
    p["sx"] *= 1 - 0.025 * up
    p["head_dy"] = -3 * up + 1.5 * nod
    p["head_tilt"] = -4 * up + 2 * math.sin(2 * math.pi * t * 2) * up
    p["ear_out"] = -6 * up
    p["ear_scale"] = 1 + 0.06 * up
    p["ear_l"] = ear_flick(t, 0.66, amp=8)
    p["eyes"] = "happy" if up > 0.5 else "open"
    p["look"] = (0.0, -0.5 * up)
    p["open"] = 1 - blink_shut(t, 0.95, "single", 0.04)
    p["mouth"] = "smile" if up > 0.3 else "w"
    p["blush"] = 0.55 + 0.25 * up
    p["tail"] = 18 * math.sin(2 * math.pi * t * 2.5) * up + 3 * math.sin(2 * math.pi * t)
    ex = []
    for sx_, sy_, d in ((22, 30, 0.16), (106, 26, 0.3), (100, 70, 0.46), (28, 72, 0.6)):
        f = _floating(t, "sparkle", sx_, sy_, rise=7, delay=d, life=0.28, size=1.0, drift=0, rot=80 * t)
        if f:
            ex.append(f)
    p["extras"] = ex
    return p


# ---- car companion: driving, care for the car, getting ready, arriving
# Every one starts and ends in the calm pose: props pop in while the paws rise and go away
# while they come back down, so CALM / one-shot transitions do not jump.

WHEEL_AT = (64, 104)


def _wheel_hands(p, t, s, hold, turn, cx=WHEEL_AT[0], cy=WHEEL_AT[1]):
    """Steering wheel in front of the belly, both paws on the rim at ten to two, turning with it."""
    if s > 0.01:
        p["props"] = p["props"] + [dict(kind="wheel", x=cx, y=cy, turn=turn, scale=s)]
    left = wheel_grip(cx, cy, turn, 200, max(s, 0.0))
    right = wheel_grip(cx, cy, turn, -20, max(s, 0.0))
    p["arm"] = raised(paw(-1, left, hold), paw(1, right, hold))


def driving(i, n):
    """Takes the steering wheel with both paws and steers calmly, eyes on the road, small corrections."""
    t = i / n
    p = default_pose()
    breathe(p, t, 0.6, cycles=2)
    hold = window(t, 0.02, 0.13, 0.87, 0.98, ease_in_out)
    s = pop_in_out(t, 0.03, 0.13, 0.87, 0.96)
    ph = 2 * math.pi * clamp((t - 0.13) / 0.74)
    steer = (8 * math.sin(2 * ph) + 3 * math.sin(4 * ph + 0.8)) * window(t, 0.13, 0.2, 0.8, 0.87)
    _wheel_hands(p, t, s, hold, steer)
    p["lean"] = 0.3 * steer
    p["head_tilt"] = 0.25 * steer
    p["look"] = (0.05 * steer, -0.35 * hold)
    p["open"] = 1 - blink_shut(t, 0.56, "single", 0.05)
    p["ear_l"] = ear_flick(t, 0.36, amp=9) - 0.4 * steer
    p["ear_r"] = -0.4 * steer
    p["tail"] = 6 * math.sin(2 * math.pi * t * 2) - 0.5 * steer
    p["mouth"] = "smile" if 0.25 < t < 0.75 else "w"
    p["blush"] = 0.5
    return p


BELT_ANCHOR = (27, 86)
BELT_BUCKLE = (92, 113)


def seatbelt(i, n):
    """Pulls the seatbelt from over its shoulder, passes it to the other paw, clicks it in and pats it: ready."""
    t = i / n
    p = default_pose()
    breathe(p, t, 0.6)
    mid = (62, 106)
    # left paw: up to the anchor, pulls the belt to the middle, lets go; later pats the strap
    reach = keys(t, [(0.04, 0.0), (0.18, 1.0)])
    pull = keys(t, [(0.2, 0.0), (0.36, 1.0)])
    left_back = keys(t, [(0.4, 0.0), (0.5, 1.0)])
    left = tuple(lerp(a_, b_, pull) for a_, b_ in zip(BELT_ANCHOR, mid))
    pat = window(t, 0.62, 0.66, 0.78, 0.82, ease_in_out)
    tap = 2.0 * abs(math.sin(2 * math.pi * (t - 0.66) * 4)) * window(t, 0.66, 0.68, 0.76, 0.78)
    pat_at = (58, 101 + tap)
    if t < 0.55:
        arm_l = paw(-1, left, reach * (1 - left_back))
    else:
        arm_l = paw(-1, pat_at, pat)
    # right paw: takes the belt in the middle and carries it to the buckle at its hip
    meet = keys(t, [(0.28, 0.0), (0.38, 1.0)])
    carry = keys(t, [(0.38, 0.0), (0.52, 1.0)])
    right_back = keys(t, [(0.58, 0.0), (0.68, 1.0)])
    right = tuple(lerp(a_, b_, carry) for a_, b_ in zip(mid, BELT_BUCKLE))
    arm_r = paw(1, right, meet * (1 - right_back))
    p["arm"] = raised(arm_l, arm_r)
    grip = left if t < 0.38 else right
    strap_on = ramp(t, 0.15, 0.2) * (1 - ramp(t, 0.84, 0.96))
    if strap_on > 0.01:
        end = BELT_ANCHOR if t < 0.2 else (grip if t < 0.52 else BELT_BUCKLE)
        p["props"] = [dict(kind="belt", x=0, y=0, a=BELT_ANCHOR, e=end, b=BELT_BUCKLE, opacity=strap_on,
                           buckle=ramp(t, 0.16, 0.26))]
    click = bump(t, 0.52, 0.6)
    p["sy"] *= 1 - 0.05 * click
    p["sx"] *= 1 + 0.03 * click
    p["look"] = gaze(t, [(0.0, (0.0, 0.0)), (0.06, (-1.3, -0.5)), (0.3, (-0.3, 0.9)), (0.44, (0.9, 1.0)),
                         (0.62, (0.4, 0.8)), (0.82, (0.0, 0.0))])
    p["head_tilt"] = -8 * window(t, 0.04, 0.16, 0.24, 0.36) + 5 * window(t, 0.36, 0.46, 0.6, 0.72)
    p["head_dy"] = 1.5 * window(t, 0.3, 0.4, 0.74, 0.84)
    p["ear_l"] = ear_flick(t, 0.53, amp=12)
    p["ear_r"] = ear_flick(t, 0.55, amp=12)
    happy = 0.55 < t < 0.84
    p["eyes"] = "happy" if happy else "open"
    p["open"] = 1 - blink_shut(t, 0.9, "single", 0.05)
    p["mouth"] = "smile" if happy else ("o" if 0.3 < t < 0.5 else "w")
    p["blush"] = 0.5 + 0.2 * happy
    p["tail"] = 4 * math.sin(2 * math.pi * t) + 12 * math.sin(2 * math.pi * t * 4) * window(t, 0.55, 0.6, 0.8, 0.88)
    ex = []
    bx, by = to_canvas(BELT_BUCKLE)
    for dx, dy, d in ((6, -8, 0.53), (-4, -12, 0.56)):
        f = _floating(t, "sparkle", bx + dx, by + dy, rise=5, delay=d, life=0.16, size=0.7, drift=0, rot=90 * t)
        if f:
            ex.append(f)
    p["extras"] = ex
    return p


def navigator(i, n):
    """Unfolds a map, studies it and turns it around, has an idea and points the way, folds the map."""
    t = i / n
    p = default_pose()
    breathe(p, t, 0.6)
    hold = window(t, 0.03, 0.14, 0.84, 0.95, ease_in_out)
    s = pop_in_out(t, 0.05, 0.15, 0.84, 0.93)
    unfold = keys(t, [(0.08, 0.3), (0.2, 1.0), (0.56, 1.0), (0.66, 0.5)])
    point = window(t, 0.58, 0.66, 0.78, 0.86, ease_in_out)
    rot = keys(t, [(0.26, 0.0), (0.33, 22.0), (0.4, 22.0), (0.47, -8.0), (0.53, 0.0)]) - 10 * point
    mp = dict(kind="map", x=lerp(64, 58, point), y=lerp(100, 103, point), rot=rot, unfold=unfold, scale=s)
    if s > 0.01:
        p["props"] = [mp]
    half = 17 * clamp(unfold, 0.15, 1.0)
    left, right = prop_at(mp, (-half, 0)), prop_at(mp, (half, 0))
    right = (lerp(right[0], 110, point), lerp(right[1], 70, point))
    p["arm"] = raised(paw(-1, left, hold), paw(1, right, hold))
    study = window(t, 0.16, 0.22, 0.52, 0.58)
    p["look"] = gaze(t, [(0.0, (0.0, 0.0)), (0.16, (0.0, 0.9)), (0.24, (-0.7, 0.9)), (0.32, (0.6, 0.8)),
                         (0.4, (-0.2, 1.0)), (0.46, (0.5, 0.9)), (0.53, (0.0, 0.0)), (0.6, (1.4, -0.4)),
                         (0.82, (0.0, 0.0))])
    p["head_tilt"] = -0.25 * rot * study - 4 * study + 7 * point
    p["head_dy"] = 2.0 * study
    aha = bump(t, 0.52, 0.6)
    p["ear_scale"] = 1 + 0.1 * aha
    p["ear_out"] = -6 * aha + 4 * study
    p["ear_l"] = ear_flick(t, 0.3, amp=8)
    p["open"] = 1 - blink_shut(t, 0.44, "single", 0.05)
    p["mouth"] = "open" if 0.56 < t < 0.8 else ("o" if 0.34 < t < 0.44 else "w")
    p["mouth_open"] = 0.7
    p["blush"] = 0.5 + 0.2 * point
    p["tail"] = 4 * math.sin(2 * math.pi * t) + 12 * math.sin(2 * math.pi * t * 4) * point
    ex = []
    q = window(t, 0.34, 0.38, 0.48, 0.52, ease_out_back)
    if q > 0.02:
        ex.append(dict(kind="question", x=104, y=26 + 2 * math.sin(2 * math.pi * t * 3), scale=q * 0.9,
                       opacity=min(1.0, q * 1.5)))
    f = _floating(t, "sparkle", 102, 28, rise=6, delay=0.53, life=0.16, size=1.0, drift=0, rot=90 * t)
    if f:
        ex.append(f)
    ar = window(t, 0.62, 0.68, 0.78, 0.84, ease_out_back)
    if ar > 0.02:
        ex.append(dict(kind="arrow", x=106 + 2.5 * math.sin(2 * math.pi * t * 6), y=58, scale=ar, side=1,
                       opacity=min(1.0, ar * 1.5)))
    p["extras"] = ex
    return p


BOLT = (110, 104)
WRENCH_HEAD = 23


def mechanic(i, n):
    """Takes out a wrench, tightens a bolt with a few ratcheting turns (tongue out), phew, twirls the wrench."""
    t = i / n
    p = default_pose()
    breathe(p, t, 0.6)
    hold = window(t, 0.03, 0.12, 0.88, 0.97, ease_in_out)
    s = pop_in_out(t, 0.05, 0.13, 0.87, 0.95)
    work = window(t, 0.24, 0.3, 0.56, 0.62, ease_in_out)
    ratchet = 18 + 16 * math.sin(2 * math.pi * (t - 0.3) * 11)
    twirl = 360 * ease_in_out(clamp((t - 0.74) / 0.1))
    rot = lerp(-55 + twirl, ratchet, work)
    r = math.radians(ratchet)
    on_bolt = (BOLT[0] - WRENCH_HEAD * math.cos(r), BOLT[1] - WRENCH_HEAD * math.sin(r))
    grip = (lerp(92, on_bolt[0], work), lerp(90, on_bolt[1], work))
    hand = paw(1, grip, hold)
    if s > 0.01:
        p["props"] = [dict(kind="wrench", x=hand["x"], y=hand["y"], rot=rot, scale=s)]
    phew = window(t, 0.6, 0.64, 0.7, 0.74, ease_in_out)
    p["arm"] = raised(hand)
    p["lean"] = 3 * work - 1.5 * phew
    p["head_tilt"] = 6 * work - 4 * phew
    p["sy"] *= 1 - 0.03 * phew
    p["tongue"] = 0.55 * work
    p["tongue_dx"] = 2.5
    p["look"] = gaze(t, [(0.0, (0.0, 0.0)), (0.13, (0.9, -0.6)), (0.26, (1.3, 0.7)), (0.6, (0.0, 0.0)),
                         (0.76, (0.8, -0.4)), (0.86, (0.0, 0.0))])
    proud = 0.84 < t < 0.95
    if phew > 0.5:
        p["eyes"] = "closed"
    elif proud:
        p["eyes"] = "happy"
    p["open"] = 1 - blink_shut(t, 0.2, "single", 0.05)
    p["mouth"] = "smile" if proud else ("open" if phew > 0.5 else "w")
    p["mouth_open"] = 0.5
    p["ear_r"] = ear_flick(t, 0.34, amp=8) + ear_flick(t, 0.46, amp=8)
    p["blush"] = 0.5 + 0.2 * proud
    p["tail"] = 5 * math.sin(2 * math.pi * t * 1.5) + 10 * math.sin(2 * math.pi * t * 4) * window(t, 0.82, 0.86, 0.92, 0.97)
    ex = []
    bx, by = to_canvas(BOLT)
    for d in (0.33, 0.42, 0.51):
        f = _floating(t, "sparkle", bx - 2, by - 4, rise=4, delay=d, life=0.08, size=0.55, drift=0, rot=120 * t)
        if f:
            ex.append(f)
    for f in (_floating(t, "sweat", 26, 38, rise=-10, delay=0.6, life=0.16, size=0.9, drift=0),
              _floating(t, "puff", 42, 86, rise=8, delay=0.62, life=0.16, size=0.7, drift=-2)):
        if f:
            ex.append(f)
    hx, hy = to_canvas(prop_at(dict(x=grip[0], y=grip[1], rot=rot), (WRENCH_HEAD, 0)))
    f = _floating(t, "sparkle", hx, hy - 4, rise=5, delay=0.84, life=0.12, size=0.8, drift=0, rot=90 * t)
    if f:
        ex.append(f)
    p["extras"] = ex
    return p


def tire_check(i, n):
    """A wheel rolls up; the fox leans over, pokes the tyre twice, nods approvingly, and the wheel rolls away."""
    t = i / n
    p = default_pose()
    breathe(p, t, 0.6)
    xin = lerp(10, 24, ease_out(clamp((t - 0.04) / 0.16)))
    xout = lerp(24, 10, ease_in(clamp((t - 0.8) / 0.14)))
    x = xin if t < 0.5 else xout
    op = ramp(t, 0.04, 0.1) * (1 - ramp(t, 0.86, 0.94))
    pokes = bump(t, 0.42, 0.48) + bump(t, 0.52, 0.58)
    if op > 0.01:
        p["props"] = [dict(kind="tire", x=x, y=GROUND[1] - TIRE_R, spin=(x - 10) / TIRE_R * 180 / math.pi,
                           squash=0.8 * pokes, opacity=op, ground=True)]
    lean = window(t, 0.2, 0.3, 0.7, 0.8, ease_in_out)
    poke = window(t, 0.36, 0.42, 0.6, 0.66, ease_in_out)
    p["arm"] = raised(paw(-1, (31, 93 + 3.5 * pokes), poke))
    p["lean"] = -6 * lean
    p["dx"] = -2.5 * lean
    p["dy"] = -2.5 * lean                     # lifted a little, so the paws stay in the frame
    p["head_tilt"] = -9 * lean
    p["head_dy"] = 2 * bump(t, 0.62, 0.68) + 2 * bump(t, 0.68, 0.74)
    p["look"] = gaze(t, [(0.0, (0.0, 0.0)), (0.06, (-1.3, 0.8)), (0.24, (-1.1, 1.0)), (0.62, (-0.6, 0.4)),
                         (0.8, (-1.4, 0.6)), (0.94, (0.0, 0.0))])
    p["ear_l"] = ear_flick(t, 0.44, amp=10) + ear_flick(t, 0.54, amp=10) - 6 * lean
    p["ear_r"] = -4 * lean
    good = 0.62 < t < 0.78
    p["eyes"] = "happy" if good else "open"
    p["open"] = 1 - blink_shut(t, 0.3, "single", 0.05)
    p["mouth"] = "smile" if good else ("o" if 0.42 < t < 0.6 else "w")
    p["blush"] = 0.5
    p["tail"] = 5 * math.sin(2 * math.pi * t * 1.5) + 10 * math.sin(2 * math.pi * t * 4) * window(t, 0.62, 0.66, 0.76, 0.8)
    ex = []
    sx_, sy_ = to_canvas((30, 98))
    f = _floating(t, "sparkle", sx_, sy_, rise=6, delay=0.64, life=0.16, size=0.9, drift=0, rot=90 * t)
    if f:
        ex.append(f)
    p["extras"] = ex
    return p


def refuel(i, n):
    """Fills up: holds the fuel nozzle to the car and watches the gauge needle climb from E to F, click — full."""
    t = i / n
    p = default_pose()
    breathe(p, t, 0.6)
    hold = window(t, 0.04, 0.14, 0.8, 0.9, ease_in_out)
    s = pop_in_out(t, 0.06, 0.15, 0.8, 0.88)
    flow = window(t, 0.22, 0.26, 0.66, 0.7)
    buzz = 0.5 * math.sin(i * 2.1) * flow
    hand = paw(1, (94 + buzz, 92 + 0.6 * buzz), hold)
    if s > 0.01:
        noz = dict(x=hand["x"], y=hand["y"], rot=8, scale=1.15 * s)
        p["props"] = [dict(noz, kind="hose", back=True, to=(82, 118), ctrl=(120, 124)),
                      dict(noz, kind="nozzle", squeeze=flow)]
    p["arm"] = raised(hand)
    level = lerp(0.08, 1.0, ease_in_out(clamp((t - 0.25) / 0.42)))
    full = 0.68 < t < 0.86
    hop(p, t, 0.69, 0.77, 3, prep=0.04, settle=0.08)
    p["look"] = gaze(t, [(0.0, (0.0, 0.0)), (0.1, (1.0, 0.3)), (0.22, (1.0, -1.0)), (0.6, (0.8, -1.1)),
                         (0.86, (0.0, 0.0))])
    p["head_tilt"] = 5 * window(t, 0.2, 0.28, 0.66, 0.74)
    p["ear_l"] = ear_flick(t, 0.4, amp=8) - 5 * flow
    p["ear_r"] = ear_flick(t, 0.69, amp=12)
    p["eyes"] = "happy" if full else "open"
    p["open"] = 1 - blink_shut(t, 0.46, "single", 0.05)
    p["mouth"] = "smile" if full else ("o" if 0.3 < t < 0.6 else "w")
    p["blush"] = 0.5 + 0.2 * full
    p["tail"] = 6 * math.sin(2 * math.pi * t * 3) * flow + 14 * math.sin(2 * math.pi * t * 4) * window(t, 0.68, 0.72, 0.82, 0.88)
    gop = window(t, 0.06, 0.12, 0.86, 0.94)
    ex = []
    if gop > 0.01:
        ex.append(dict(kind="gauge", x=100, y=26, scale=pop_in_out(t, 0.06, 0.14, 0.86, 0.95) * (1 + 0.12 * bump(t, 0.67, 0.75)),
                       level=level, opacity=gop))
    for dx, d in ((-10, 0.68), (10, 0.71)):
        f = _floating(t, "sparkle", 100 + dx, 18, rise=5, delay=d, life=0.14, size=0.8, drift=0, rot=90 * t)
        if f:
            ex.append(f)
    nt = _floating(t, "note", 30, 44, rise=14, delay=0.36, life=0.22, size=0.8, color=NOTE_COLORS[1])
    if nt:
        ex.append(nt)
    p["extras"] = ex
    return p


def car_wash(i, n):
    """Scrubs the car with a foamy sponge in circles while bubbles float up, then admires the shine."""
    t = i / n
    p = default_pose()
    breathe(p, t, 0.6)
    hold = window(t, 0.04, 0.13, 0.84, 0.94, ease_in_out)
    s = pop_in_out(t, 0.05, 0.14, 0.84, 0.92)
    scrub = window(t, 0.16, 0.22, 0.66, 0.72, ease_in_out)
    a = 2 * math.pi * 3 * clamp((t - 0.16) / 0.56)
    spot = (100 + 7 * math.cos(a), 82 + 6 * math.sin(a))
    hand = paw(1, (lerp(92, spot[0], scrub), lerp(96, spot[1], scrub)), hold)
    if s > 0.01:
        p["props"] = [dict(kind="sponge", x=hand["x"] + 4 * s, y=hand["y"] - 4 * s, rot=10 * math.sin(a) * scrub,
                           scale=s, foam=ramp(t, 0.18, 0.4), front=True)]
    p["arm"] = raised(hand)
    p["lean"] = 2.5 * math.cos(a) * scrub + 1.5 * scrub
    p["dx"] = 0.8 * math.cos(a) * scrub
    p["head_tilt"] = 5 * scrub + 2 * math.sin(a) * scrub
    admire = window(t, 0.72, 0.76, 0.82, 0.86)
    p["sy"] *= 1 + 0.03 * admire
    p["eyes"] = "happy" if scrub > 0.5 else "open"
    p["look"] = (1.2 * admire + 0.6 * (1 - scrub) * hold, -0.3 * admire)
    p["open"] = 1 - blink_shut(t, 0.9, "single", 0.05)
    p["mouth"] = "smile" if scrub > 0.5 or admire > 0.5 else "w"
    p["blush"] = 0.5 + 0.25 * scrub
    p["tail"] = 4 * math.sin(2 * math.pi * t) + 12 * math.sin(2 * math.pi * t * 3) * scrub
    p["ear_l"] = 4 * math.sin(a) * scrub
    ex = []
    for k in range(9):
        d = 0.2 + 0.055 * k
        bx, by = to_canvas((100 + 7 * math.cos(2 * math.pi * 3 * clamp((d - 0.16) / 0.56)), 76))
        f = _floating(t, "bubble", bx + (k % 3 - 1) * 4, by, rise=22, delay=d, life=0.22, size=0.6 + 0.15 * (k % 3), drift=2.5)
        if f:
            ex.append(f)
    for sx_, sy_, d in ((112, 64, 0.72), (98, 50, 0.76), (114, 90, 0.8)):
        f = _floating(t, "sparkle", sx_, sy_, rise=4, delay=d, life=0.16, size=0.9, drift=0, rot=90 * t)
        if f:
            ex.append(f)
    p["extras"] = ex
    return p


def arrival(i, n):
    """The trip is over: hops twice waving a chequered flag, sparkles all around."""
    t = i / n
    p = default_pose()
    hold = window(t, 0.04, 0.16, 0.8, 0.92, ease_in_out)
    s = pop_in_out(t, 0.06, 0.16, 0.8, 0.9)
    wave = math.sin(2 * math.pi * (t - 0.16) * 4) * window(t, 0.16, 0.22, 0.72, 0.8)
    hand = paw(-1, (28 + 4 * wave, 64 - 2 * abs(wave)), hold)
    if s > 0.01:
        p["props"] = [dict(kind="flag", x=hand["x"], y=hand["y"], rot=-12 * wave, scale=s, wave=2 * math.pi * t * 5, side=-1)]
    p["arm"] = raised(hand)
    hop(p, t, 0.3, 0.44, 8)
    hop(p, t, 0.52, 0.66, 6)
    p["lean"] += 2.5 * wave
    p["head_tilt"] = -5 * hold + 2 * wave
    p["ear_out"] += 4 * hold
    party = 0.14 < t < 0.84
    p["eyes"] = "happy" if party else "open"
    p["mouth"] = "open" if party else "w"
    p["mouth_open"] = 0.9
    p["blush"] = 0.5 + 0.3 * hold
    p["tail"] = 18 * math.sin(2 * math.pi * t * 4) * hold + 3 * math.sin(2 * math.pi * t)
    ex = []
    for sx_, sy_, d in ((104, 28, 0.2), (100, 70, 0.32), (20, 84, 0.44), (108, 46, 0.56), (88, 18, 0.66)):
        f = _floating(t, "sparkle", sx_, sy_, rise=7, delay=d, life=0.22, size=1.0, drift=0, rot=90 * t)
        if f:
            ex.append(f)
    h = _floating(t, "heart", 100, 40, rise=18, delay=0.6, life=0.24, size=0.7)
    if h:
        ex.append(h)
    p["extras"] = ex
    return p


def pothole(i, n):
    """A bump in the road: jolted up with ears flapping and round eyes, lands with a wobble, then a sigh of relief."""
    t = i / n
    p = default_pose()
    hop(p, t, 0.1, 0.3, 9, prep=0.04, settle=0.16)
    wob = math.sin(2 * math.pi * (t - 0.3) * 3.5) * math.exp(-4 * max(0.0, t - 0.3)) * ramp(t, 0.3, 0.34)
    wob *= 1 - ramp(t, 0.8, 0.95)
    p["lean"] = 5 * wob
    p["dx"] = 1.2 * wob
    flap = bump(t, 0.1, 0.3)
    p["ear_l"] = -16 * flap + 6 * wob
    p["ear_r"] = 16 * flap + 6 * wob
    p["ear_scale"] = 1 + 0.08 * flap
    scared = ramp(t, 0.06, 0.1) * (1 - ramp(t, 0.62, 0.7))
    p["tail"] = -14 * scared + 4 * wob
    p["tail_curl"] = 0.6 * scared
    p["eyes"] = "wide" if 0.08 < t < 0.6 else "open"
    p["open"] = 1 - 0.3 * ramp(t, 0.66, 0.74) * (1 - ramp(t, 0.86, 0.96))
    p["mouth"] = "o" if 0.08 < t < 0.6 else "w"
    p["blush"] = 0.3
    ex = []
    sh = bump(t, 0.06, 0.3)
    if sh > 0:
        ex.append(dict(kind="shock", x=26, y=112, scale=0.6 + 0.5 * sh, opacity=min(1.0, sh * 2), angles=(150, 180, 210)))
        ex.append(dict(kind="shock", x=100, y=112, scale=0.6 + 0.5 * sh, opacity=min(1.0, sh * 2), angles=(-30, 0, 30)))
    sw = clamp((t - 0.3) / 0.45)
    if 0 < sw < 1:
        ex.append(dict(kind="sweat", x=24, y=36 + 10 * sw, scale=0.9, opacity=ramp(sw, 0, 0.1) * (1 - ramp(sw, 0.75, 1.0))))
    pf = _floating(t, "puff", 40, 84, rise=8, delay=0.68, life=0.26, size=0.8, drift=-2)
    if pf:
        ex.append(pf)
    p["extras"] = ex
    return p


def parking(i, n):
    """Parks: looks back over its shoulder, turns the wheel hard while the parking sensor beeps, stops — P."""
    t = i / n
    p = default_pose()
    breathe(p, t, 0.6)
    hold = window(t, 0.03, 0.12, 0.86, 0.96, ease_in_out)
    s = pop_in_out(t, 0.04, 0.12, 0.86, 0.94)
    turn = keys(t, [(0.16, 0.0), (0.26, -40.0), (0.38, -40.0), (0.48, 32.0), (0.58, 32.0), (0.66, 0.0)])
    _wheel_hands(p, t, s, hold, turn)
    back = window(t, 0.12, 0.2, 0.58, 0.66, ease_in_out)
    p["head_tilt"] = 9 * back + 0.06 * turn
    p["look"] = (1.6 * back, -0.3 * back - 0.3 * hold * (1 - back))
    p["lean"] = 0.06 * turn + 2 * back
    p["ear_r"] = -10 * back
    p["ear_l"] = 6 * back
    stop = bump(t, 0.6, 0.66)
    p["sy"] *= 1 - 0.04 * stop
    p["head_dy"] = 2 * bump(t, 0.68, 0.76)
    done = 0.66 < t < 0.86
    p["eyes"] = "happy" if done else "open"
    p["open"] = 1 - blink_shut(t, 0.1, "single", 0.04)
    p["mouth"] = "smile" if done else ("o" if 0.44 < t < 0.6 else "w")
    p["blush"] = 0.5 + 0.2 * done
    p["tail"] = 4 * math.sin(2 * math.pi * t) + 10 * math.sin(2 * math.pi * t * 4) * window(t, 0.66, 0.7, 0.8, 0.86)
    ex = []
    u = clamp((t - 0.3) / 0.3)
    beep = max(0.0, math.sin(2 * math.pi * (3 * u + 5 * u * u))) if 0 < u < 1 else 0.0
    if beep > 0.05:
        ex.append(dict(kind="waves", x=110, y=72, scale=0.8 + 0.3 * u, opacity=beep, side=1))
    ps = pop_in_out(t, 0.62, 0.68, 0.86, 0.94)
    if ps > 0.01:
        ex.append(dict(kind="psign", x=24, y=26, scale=ps, opacity=min(1.0, ps * 1.5)))
    f = _floating(t, "sparkle", 34, 16, rise=5, delay=0.66, life=0.16, size=0.8, drift=0, rot=90 * t)
    if f:
        ex.append(f)
    p["extras"] = ex
    return p


# ---- wave 4: heart, music shows, turning around
# The fox turns about its vertical axis (pose `yaw`, see Turn) instead of being squeezed or mirrored, and
# morphs into a heart (pose `heart`, see draw_heart_body). Every one starts and ends in the calm pose.

# When the heart is complete in heart_transform (ms from the start); the app gives a short haptic tick then
# (AnimationPools.HEART_MOMENT_MS in the app must match).
HEART_MOMENT_MS = 2000


def _mouth_at(p, pt):
    """Body-space position of a head point (the head tilts and bobs on the neck)."""
    x, y = Tf.rotate(p["head_tilt"], *NECK).apply([pt])[0]
    return float(x), float(y) + p["head_dy"]


def heart_transform(i, n):
    """Closes its eyes, hugs itself and turns into a big orange-pink heart that beats, then opens back into the fox."""
    t = i / n
    p = default_pose()
    breathe(p, t, 0.6)
    full = HEART_MOMENT_MS / (n * FRAME_MS)
    m = keys(t, [(0.2, 0.0), (full, 1.0), (0.72, 1.0), (0.9, 0.0)])
    hug = window(t, 0.06, 0.2, 0.84, 0.95, ease_in_out)
    beat = sum(a * bump(t, s, s + 0.06) for s, a in ((0.48, 1.0), (0.55, 0.7), (0.62, 1.0), (0.69, 0.7)))
    p["heart"] = m
    p["sx"] *= 1 + 0.06 * beat
    p["sy"] *= 1 + 0.06 * beat
    k = 1 - ramp(m, 0.1, 0.55)
    if hug > 0.02 and k > 0.03:
        arms = raised(paw(-1, (52, 93), hug), paw(1, (76, 93), hug)) or []
        p["arm"] = [dict(a, k=k, x=lerp(64, a["x"], k), y=lerp(96, a["y"], k)) for a in arms] or None
    p["head_tilt"] = 7 * window(t, 0.06, 0.16, 0.26, 0.4, ease_in_out) - 5 * window(t, 0.8, 0.86, 0.9, 0.97)
    p["head_dy"] = 1.5 * hug * (1 - m)
    shut = 0.07 < t < 0.88
    p["eyes"] = "closed" if shut else ("happy" if 0.88 <= t < 0.95 else "open")
    p["mouth"] = "smile" if 0.07 < t < 0.95 else "w"
    p["blush"] = 0.5 + 0.45 * hug
    p["ear_r"] = ear_flick(t, 0.93, amp=10)
    p["tail"] = 4 * math.sin(2 * math.pi * t) + 10 * math.sin(2 * math.pi * t * 5) * window(t, 0.86, 0.9, 0.94, 0.99)
    ex = []
    for hx, hy, d, s_ in ((22, 52, 0.45, 0.7), (106, 40, 0.5, 0.8), (30, 26, 0.57, 0.6), (100, 74, 0.62, 0.65),
                          (64, 30, 0.66, 0.6)):
        f = _floating(t, "heart", hx, hy, rise=16, delay=d, life=0.2, size=s_, drift=2)
        if f:
            ex.append(f)
    for sx_, sy_, d in ((24, 30, full - 0.02), (104, 22, full)):
        f = _floating(t, "sparkle", sx_, sy_, rise=4, delay=d, life=0.12, size=0.8, drift=0, rot=90 * t)
        if f:
            ex.append(f)
    p["extras"] = ex
    return p


def dance_side(i, n):
    """Turns sideways and dances in profile: sways, steps from paw to paw, wags; glances back at you once, turns back."""
    t = i / n
    beat = (i % 15) / 15
    p = default_pose()
    breathe(p, t, 0.5)
    turn = keys(t, [(0.03, 0.0), (0.15, 1.0), (0.85, 1.0), (0.97, 0.0)])
    p["yaw"] = 72 * turn
    dance = window(t, 0.13, 0.2, 0.8, 0.87)
    pulse = math.exp(-beat * 6) * dance
    step = math.sin(math.pi * beat) * dance
    lead = 1 if (i // 15) % 2 == 0 else -1
    p["feet_dy"] = (-4.5 * step if lead < 0 else 0.0, -4.5 * step if lead > 0 else 0.0)
    sway = math.sin(2 * math.pi * i / 30) * dance
    p["lean"] = 6 * sway
    p["dx"] = 2 * sway
    p["dy"] = -1.5 * step
    p["sy"] *= 1 - 0.05 * pulse
    p["sx"] *= 1 + 0.03 * pulse
    glance = window(t, 0.44, 0.5, 0.6, 0.66, ease_in_out)
    p["head_yaw"] = -66 * glance                       # looks at you over its shoulder
    p["head_tilt"] = -4 * sway + 4 * glance
    p["ear_l"] = 6 * sway
    p["ear_r"] = 6 * sway
    p["tail"] = 16 * math.sin(2 * math.pi * i / 15) * dance - 6 * turn
    happy = dance > 0.4 and glance < 0.4
    p["eyes"] = "happy" if happy else "open"
    p["open"] = 1 - blink_shut(t, 0.55, "single", 0.04)
    p["mouth"] = "open" if happy else ("smile" if glance > 0.4 else "w")
    p["mouth_open"] = 0.5 + 0.4 * pulse
    p["blush"] = 0.5 + 0.2 * dance
    ex = [e for e in _notes(t, 3, 24, 14, -1, NOTE_COLORS) if dance > 0.05]
    for e in ex:
        e["opacity"] *= dance
        e["y"] += 12
    p["extras"] = ex
    return p


def microphone(i, n):
    """Pulls out a microphone, sings into it with its eyes shut and swaying, a big gesture with the free paw, puts it away."""
    t = i / n
    secs = _secs(n)
    p = default_pose()
    breathe(p, t, 0.5)
    hold = window(t, 0.03, 0.15, 0.84, 0.97, ease_in_out)
    s = pop_in_out(t, 0.05, 0.12, 0.86, 0.93)
    sing = window(t, 0.2, 0.24, 0.76, 0.8)
    sway = math.sin(2 * math.pi * (t - 0.2) * secs / 1.6) * sing
    p["lean"] = 4 * sway
    p["head_tilt"] = -6 * sway + 4 * sing
    p["head_dy"] = 1.0 * sing
    # the mic ball stays just below the mouth while the head sways; the paw holds the grip
    rot = -28 + 6 * sway
    mouth = _mouth_at(p, (66, 83))
    r = math.radians(rot)
    grip_sing = (mouth[0] + 4 - 16 * math.sin(r), mouth[1] + 5 + 16 * math.cos(r))
    to_mouth = keys(t, [(0.12, 0.0), (0.22, 1.0), (0.76, 1.0), (0.88, 0.0)])
    grip = (lerp(92, grip_sing[0], to_mouth), lerp(100, grip_sing[1], to_mouth))
    hand = paw(1, grip, hold)
    if s > 0.01:
        p["props"] = [dict(kind="mic", x=hand["x"], y=hand["y"], rot=lerp(-8, rot, to_mouth), scale=1.15 * s)]
    flourish = window(t, 0.62, 0.68, 0.74, 0.8, ease_in_out)
    free = paw(-1, (20 + 3 * math.sin(2 * math.pi * t * 6), 54), flourish)
    p["arm"] = raised(free, hand)
    # syllables: a new mouth shape every 0.24 s
    syl = int(max(0.0, t - 0.22) * secs / 0.24)
    shapes = ("aa", "o", "ee", "aa", "open", "o", "aa", "ee")
    ph = (max(0.0, t - 0.22) * secs / 0.24) % 1
    if sing > 0.5:
        p["mouth"] = shapes[syl % len(shapes)]
        p["mouth_open"] = 0.45 + 0.55 * math.sin(math.pi * ph) + 0.3 * flourish
    else:
        p["mouth"] = "smile" if hold > 0.5 else "w"
    p["eyes"] = "closed" if 0.3 < t < 0.6 else ("happy" if 0.6 <= t < 0.8 else "open")
    p["open"] = 1 - blink_shut(t, 0.88, "single", 0.04)
    p["ear_out"] = 4 * sing - 4 * flourish
    p["ear_l"] = 5 * sway
    p["ear_r"] = 5 * sway + ear_flick(t, 0.16, amp=8)
    p["blush"] = 0.5 + 0.3 * sing
    p["tail"] = 6 * math.sin(2 * math.pi * t * 3) * (0.4 + 0.6 * sing) + 8 * flourish
    ex = [e for e in _notes(t * 2 % 1, 2, 98, 112, 1, NOTE_COLORS)] if sing > 0.05 else []
    for e in ex:
        e["opacity"] *= sing
        e["y"] += 12
    f = _floating(t, "sparkle", 18, 40, rise=6, delay=0.66, life=0.14, size=0.9, drift=0, rot=90 * t)
    if f:
        ex.append(f)
    p["extras"] = ex
    return p


GUITAR_AT = (67, 103)
GUITAR_ROT = 26
GUITAR_CHORDS = (-27, -32, -23, -29)


def guitar(i, n):
    """A small guitar appears; holds the neck with one paw and strums with the other, bobbing its head; a final chord, puts it away."""
    t = i / n
    secs = _secs(n)
    p = default_pose()
    breathe(p, t, 0.5)
    hold = window(t, 0.04, 0.14, 0.84, 0.95, ease_in_out)
    s = pop_in_out(t, 0.03, 0.1, 0.86, 0.93)
    play = window(t, 0.16, 0.2, 0.7, 0.74)
    final = window(t, 0.74, 0.77, 0.8, 0.85, ease_in_out)
    lift = keys(t, [(0.06, 0.0), (0.16, 1.0), (0.84, 1.0), (0.92, 0.0)])
    rock = math.sin(2 * math.pi * (t - 0.16) * secs / 1.6) * play      # the guitar rocks gently with the tune
    g_ = dict(kind="guitar", x=GUITAR_AT[0] + 1.2 * rock, y=lerp(106, GUITAR_AT[1], lift) - 1.5 * final,
              rot=GUITAR_ROT + 4 * final + 3 * rock, scale=1.15 * s)
    if s > 0.01:
        p["props"] = [g_]
    # fretting paw moves between chord positions along the neck
    ci = int(max(0.0, t - 0.16) * secs / 0.6)
    cph = (max(0.0, t - 0.16) * secs / 0.6) % 1
    fret = lerp(GUITAR_CHORDS[ci % 4], GUITAR_CHORDS[(ci + 1) % 4], smooth((cph - 0.82) / 0.18))
    neck = prop_at(g_, (fret, 0.5))
    strum = math.sin(2 * math.pi * (t - 0.16) * secs * 2.5) * play
    body_pt = prop_at(g_, (4, -1.5 + 5 * strum - 9 * final))
    up = window(t, 0.79, 0.81, 0.84, 0.88, ease_out)
    strummer = (lerp(body_pt[0], 102, up), lerp(body_pt[1], 66, up))
    p["arm"] = raised(paw(-1, neck, hold), paw(1, strummer, hold))
    beat = (i % 10) / 10
    bob = math.exp(-beat * 5) * play
    p["head_dy"] = 1.5 * bob
    p["head_tilt"] = 5 * math.sin(2 * math.pi * (t - 0.16) * secs / 1.6) * play - 6 * final
    p["lean"] = -3 * final
    shut = (0.3 < t < 0.48) or (0.58 < t < 0.68)
    p["eyes"] = "closed" if shut else ("happy" if final > 0.3 else "open")
    p["look"] = (-0.4 * hold * (1 - play), 0.9 * hold * (1 - final))
    p["open"] = 1 - blink_shut(t, 0.9, "single", 0.04)
    p["mouth"] = "open" if final > 0.3 else ("smile" if play > 0.3 else "w")
    p["mouth_open"] = 0.9
    p["ear_out"] = 3 * play - 6 * final
    p["ear_l"] = 4 * bob
    p["ear_r"] = 4 * bob
    p["blush"] = 0.5 + 0.25 * play + 0.2 * final
    p["tail"] = 5 * math.sin(2 * math.pi * t * 4) * (0.3 + 0.7 * play) + 10 * final
    ex = []
    for d, x0, col in ((0.24, 104, NOTE_COLORS[0]), (0.4, 110, NOTE_COLORS[1]), (0.56, 102, NOTE_COLORS[2])):
        f = _floating(t, "note", x0, 50, rise=30, delay=d, life=0.22, size=0.85, drift=3, color=col)
        if f:
            ex.append(f)
    for sx_, sy_, d in ((108, 50, 0.79), (96, 30, 0.81), (116, 70, 0.83)):
        f = _floating(t, "sparkle", sx_, sy_, rise=5, delay=d, life=0.12, size=1.0, drift=0, rot=90 * t)
        if f:
            ex.append(f)
    p["extras"] = ex
    return p


def tail_back(i, n):
    """Gets up, turns around to show its back and wags its big fluffy tail, peeks over the shoulder, turns back and sits."""
    t = i / n
    secs = _secs(n)
    p = default_pose()
    breathe(p, t, 0.5)
    up = window(t, 0.02, 0.1, 0.88, 0.97, ease_in_out)
    p["sy"] *= 1 + 0.05 * up
    p["sx"] *= 1 - 0.03 * up
    p["dy"] = -2.5 * up
    yaw = keys(t, [(0.07, 0.0), (0.3, 180.0), (0.74, 180.0), (0.95, 360.0)])
    p["yaw"] = yaw % 360
    turning = bump(t, 0.07, 0.3) + bump(t, 0.74, 0.95)
    p["feet_dy"] = (-2.5 * abs(math.sin(2 * math.pi * t * secs * 1.6)) * turning, -2.5 * abs(math.cos(2 * math.pi * t * secs * 1.6)) * turning)
    wag = window(t, 0.32, 0.37, 0.68, 0.73)
    back = window(t, 0.2, 0.28, 0.76, 0.84)
    # seen from behind the tail points up-left; +28° brings it upright, then it swishes side to side
    p["tail"] = 28 * back + 30 * math.sin(2 * math.pi * (t - 0.32) * secs * 1.4) * wag - 8 * turning
    p["tail_curl"] = 0.3 * wag
    peek = window(t, 0.47, 0.52, 0.6, 0.65, ease_in_out)
    p["head_yaw"] = -78 * peek                         # looks back over the shoulder
    p["head_tilt"] = 5 * peek
    p["lean"] = 3 * math.sin(2 * math.pi * (t - 0.32) * secs * 1.4) * wag
    p["ear_l"] = ear_flick(t, 0.4, amp=10) + 6 * wag * math.sin(2 * math.pi * t * 7)
    p["ear_r"] = ear_flick(t, 0.44, amp=10)
    p["eyes"] = "happy" if peek > 0.5 else "open"
    p["open"] = 1 - blink_shut(t, 0.06, "single", 0.04)
    p["mouth"] = "smile" if peek > 0.5 or t > 0.9 else "w"
    p["blush"] = 0.5 + 0.2 * peek
    ex = []
    for sx_, sy_, d in ((22, 40, 0.4), (104, 34, 0.5), (26, 70, 0.6)):
        f = _floating(t, "sparkle", sx_, sy_, rise=5, delay=d, life=0.12, size=0.7, drift=0, rot=90 * t)
        if f:
            ex.append(f)
    p["extras"] = ex
    return p


# ---- wave 5: music shows (bayan, balalaika, punk), the battery, the car leans
# Same rules as wave 4: start and end in the calm pose, props pop in and out while the paws lift and lower.

BAYAN_AT = (80, 98)            # inner edge of the keyboard block, body space
BAYAN_TUNE = ((0, 0), (1, 1), (2, 2), (1, 3), (0, 2), (2, 1), (1, 0), (2, 3))   # (column, row) of the buttons


def bayan(i, n):
    """A bayan appears; the left paw draws the bellows out and in like a fan, the right paw plays the buttons; sways and sings, puts it away."""
    t = i / n
    secs = _secs(n)
    p = default_pose()
    breathe(p, t, 0.5)
    hold = window(t, 0.03, 0.14, 0.86, 0.97, ease_in_out)
    s = pop_in_out(t, 0.03, 0.11, 0.87, 0.94)
    play = window(t, 0.15, 0.21, 0.78, 0.85, ease_in_out)
    # One bar of a waltz per bellows stroke: out for 1.4 s, in for 1.4 s; it opens wider at the top.
    ph = 2 * math.pi * max(0.0, t - 0.15) * secs / 2.8
    stroke = -math.cos(ph)                               # -1 closed … 1 drawn out
    lift = keys(t, [(0.05, 0.0), (0.15, 1.0), (0.85, 1.0), (0.93, 0.0)])
    sway = math.sin(ph) * play
    b = dict(kind="bayan", x=BAYAN_AT[0] - 1.0 * sway, y=lerp(101, BAYAN_AT[1], lift),
             rot=-2.5 * sway, scale=1.25 * s, open=0.12 + play * (0.4 + 0.33 * stroke),
             fan=play * (0.18 + 0.16 * stroke))
    if s > 0.01:
        p["props"] = [b]
    # the right paw walks over the buttons, one per 0.35 s
    step = max(0.0, t - 0.2) * secs / 0.35
    c0, c1 = BAYAN_TUNE[int(step) % len(BAYAN_TUNE)], BAYAN_TUNE[(int(step) + 1) % len(BAYAN_TUNE)]
    k = smooth((step % 1 - 0.75) / 0.25)
    col, row = lerp(c0[0], c1[0], k), lerp(c0[1], c1[1], k)
    press = 0.6 * math.exp(-(step % 1) * 6) * play
    # the paw wraps round the outer edge of the keyboard; the fingers walk up and down the button rows
    keys_pt = prop_at(b, (BAYAN_BLOCK - 2 + 0.6 * col * play, -5.5 + 3.4 * row * play + press))
    p["arm"] = raised(paw(-1, prop_at(b, bayan_left_grip(b)), hold), paw(1, keys_pt, hold))
    p["lean"] = -3 * sway
    p["dx"] = -1.2 * sway
    p["head_tilt"] = 6 * math.sin(ph + 0.6) * play
    p["head_dy"] = 1.2 * play
    p["ear_l"] = 5 * math.sin(ph + 1.0) * play
    p["ear_r"] = 5 * math.sin(ph + 1.0) * play + ear_flick(t, 0.12, amp=8)
    singing = 0.3 < t < 0.72
    syl = int(max(0.0, t - 0.3) * secs / 0.42)
    p["eyes"] = "closed" if 0.22 < t < 0.76 else ("happy" if 0.76 <= t < 0.84 else "open")
    p["open"] = 1 - blink_shut(t, 0.9, "single", 0.04)
    if singing:
        p["mouth"] = ("o", "aa", "o", "smile")[syl % 4]
        p["mouth_open"] = 0.5 + 0.4 * math.sin(math.pi * ((max(0.0, t - 0.3) * secs / 0.42) % 1))
    else:
        p["mouth"] = "smile" if hold > 0.5 else "w"
    p["blush"] = 0.5 + 0.3 * play
    p["tail"] = 6 * math.sin(ph * 2) * play + 3 * math.sin(2 * math.pi * t)
    ex = []
    for d, x0, col_ in ((0.26, 110, NOTE_COLORS[2]), (0.42, 104, NOTE_COLORS[0]), (0.58, 112, NOTE_COLORS[1])):
        f = _floating(t, "note", x0, 46, rise=26, delay=d, life=0.22, size=0.8, drift=3, color=col_)
        if f:
            ex.append(f)
    for sx_, sy_, d in ((20, 44, 0.77), (108, 30, 0.79)):
        f = _floating(t, "sparkle", sx_, sy_, rise=5, delay=d, life=0.12, size=0.9, drift=0, rot=90 * t)
        if f:
            ex.append(f)
    p["extras"] = ex
    return p


BALA_AT = (68, 100)
BALA_ROT = 28
BALA_CHORDS = (-22, -27, -31, -36)


def balalaika(i, n):
    """A balalaika appears; one paw moves along the neck, the other plucks and trembles over the strings; bounces to the tune, a final flourish."""
    t = i / n
    secs = _secs(n)
    p = default_pose()
    breathe(p, t, 0.5)
    hold = window(t, 0.04, 0.14, 0.84, 0.95, ease_in_out)
    s = pop_in_out(t, 0.03, 0.1, 0.86, 0.93)
    play = window(t, 0.16, 0.2, 0.72, 0.76)
    final = window(t, 0.75, 0.78, 0.81, 0.86, ease_in_out)
    lift = keys(t, [(0.06, 0.0), (0.16, 1.0), (0.84, 1.0), (0.92, 0.0)])
    beat = (max(0.0, t - 0.16) * secs / 0.5) % 1                # a beat every 0.5 s
    bounce = math.sin(math.pi * beat) * play
    bl = dict(kind="balalaika", x=BALA_AT[0], y=lerp(101, BALA_AT[1], lift) - 1.2 * bounce - 1.5 * final,
              rot=BALA_ROT + 4 * final + 2 * math.sin(2 * math.pi * max(0.0, t - 0.16) * secs / 2), scale=1.2 * s)
    if s > 0.01:
        p["props"] = [bl]
    ci = int(max(0.0, t - 0.16) * secs / 0.5)
    cph = (max(0.0, t - 0.16) * secs / 0.5) % 1
    fret = lerp(BALA_CHORDS[ci % 4], BALA_CHORDS[(ci + 1) % 4], smooth((cph - 0.8) / 0.2))
    neck = prop_at(bl, (fret, 0.4))
    # Plucking: a single pluck on the beat, then a fast tremolo over the strings (the balalaika's sound).
    tremolo = math.sin(2 * math.pi * max(0.0, t - 0.16) * secs * 4.5) * window(cph, 0.35, 0.45, 0.85, 0.95)
    pluck = -2.5 * math.exp(-cph * 7)
    string_pt = prop_at(bl, (7.5 + 0.6 * tremolo, -0.5 + (1.8 * tremolo + pluck) * play - 7 * final))
    up = window(t, 0.8, 0.82, 0.85, 0.89, ease_out)
    picker = (lerp(string_pt[0], 104, up), lerp(string_pt[1], 64, up))
    p["arm"] = raised(paw(-1, neck, hold), paw(1, picker, hold))
    p["dy"] = -1.6 * bounce
    p["sy"] *= 1 - 0.03 * math.exp(-beat * 6) * play
    p["head_dy"] = 1.2 * math.exp(-beat * 5) * play
    p["head_tilt"] = 6 * math.sin(2 * math.pi * max(0.0, t - 0.16) * secs / 2) * play - 6 * final
    p["lean"] = 2 * math.sin(2 * math.pi * max(0.0, t - 0.16) * secs / 2) * play - 3 * final
    wink = window(t, 0.46, 0.48, 0.54, 0.56)
    p["eyes"] = "happy" if (play > 0.5 and not 0.44 < t < 0.58) or final > 0.3 else "open"
    p["eye_l"] = "happy" if wink > 0.5 else None
    p["look"] = (-0.5 * hold * (1 - play), 0.8 * hold * (1 - final) * (1 - play))
    p["open"] = 1 - blink_shut(t, 0.9, "single", 0.04)
    p["mouth"] = "open" if final > 0.3 or wink > 0.5 else ("smile" if play > 0.3 else "w")
    p["mouth_open"] = 0.8
    p["ear_out"] = 3 * play - 6 * final
    p["ear_l"] = 4 * bounce
    p["ear_r"] = 4 * bounce
    p["blush"] = 0.5 + 0.25 * play + 0.2 * final
    p["tail"] = 7 * math.sin(2 * math.pi * t * 5) * (0.3 + 0.7 * play) + 10 * final
    ex = []
    for d, x0, col in ((0.22, 106, NOTE_COLORS[1]), (0.36, 112, NOTE_COLORS[0]), (0.5, 104, NOTE_COLORS[2]),
                       (0.62, 110, NOTE_COLORS[1])):
        f = _floating(t, "note", x0, 50, rise=28, delay=d, life=0.2, size=0.75, drift=3, color=col)
        if f:
            ex.append(f)
    for sx_, sy_, d in ((110, 48, 0.8), (98, 28, 0.82), (118, 70, 0.84)):
        f = _floating(t, "sparkle", sx_, sy_, rise=5, delay=d, life=0.12, size=1.0, drift=0, rot=90 * t)
        if f:
            ex.append(f)
    p["extras"] = ex
    return p


PUNK_BEAT = 0.48               # s per beat of the rock tune


def punk(i, n):
    """A pink mohawk shoots up, a rock dance: head-banging on the beat, a raised paw, two jumps; the mohawk sinks back and it calms down."""
    t = i / n
    secs = _secs(n)
    p = default_pose()
    breathe(p, t, 0.5)
    grow = keys(t, [(0.04, 0.0), (0.16, 1.0), (0.84, 1.0), (0.95, 0.0)], ease_out)
    rock = window(t, 0.17, 0.22, 0.78, 0.84, ease_in_out)
    tt = max(0.0, t - 0.17) * secs / PUNK_BEAT
    beat = tt % 1
    hit = smooth(beat / 0.25) * math.exp(-max(0.0, beat - 0.25) * 5)   # a quick drop on the beat, a slower rise
    bang = hit * rock                                    # the head drops on the beat and comes back up
    side = math.sin(math.pi * tt / 2) * rock             # sways left / right every two beats
    p["head_dy"] = 3 * bang
    p["head_tilt"] = 7 * side
    p["lean"] = 3 * side
    p["dx"] = -1.5 * max(0.0, side)                     # leaning right, the tail would leave the frame
    p["sy"] *= 1 - 0.035 * bang
    p["sx"] *= 1 + 0.02 * bang
    for a, b_ in ((0.41, 0.51), (0.59, 0.69)):
        soft_hop(p, t, a, b_, 5)
    p["dy"] -= 1.0 * rock                                 # stamping and swaying: keep the paws in the frame
    # the right paw goes up in a "rock on" and pumps on the beat; the left one plays air guitar
    up = window(t, 0.2, 0.26, 0.74, 0.8, ease_in_out)
    pump = 5 * hit * rock
    air = math.sin(2 * math.pi * tt * 2) * rock
    p["arm"] = raised(paw(1, (100, 58 + pump), up), paw(-1, (52 + 3 * air, 100 + 2 * air), up))
    # the mohawk lags behind the head and springs back
    head_x = lambda u: 7 * math.sin(math.pi * max(0.0, u - 0.17) * secs / PUNK_BEAT / 2) * window(u, 0.17, 0.22, 0.78, 0.84, ease_in_out)
    bend = -1.2 * lag(head_x, t, secs, freq=2.6, zeta=0.3)
    if grow > 0.01:
        p["props"] = [dict(kind="mohawk", head=True, x=64, y=27, grow=grow, bend=bend + 4 * side, scale=1.0)]
    p["eyes"] = ("squeeze" if 0.12 < beat < 0.45 else "grumpy") if rock > 0.5 else ("wide" if 0.06 < t < 0.16 else "open")
    p["open"] = 1 - blink_shut(t, 0.9, "single", 0.04)
    p["mouth"] = "open" if rock > 0.5 and 0.12 < beat < 0.6 else ("smile" if rock > 0.3 or 0.84 < t < 0.92 else ("o" if 0.06 < t < 0.16 else "w"))
    p["mouth_open"] = 0.6 + 0.4 * bang
    p["tongue"] = 0.7 * window(t, 0.3, 0.32, 0.36, 0.38)
    p["ear_out"] = 8 * bang - 3 * rock
    p["ear_l"] = 6 * side + ear_flick(t, 0.05, amp=10)
    p["ear_r"] = 6 * side
    p["blush"] = 0.4 + 0.2 * rock
    p["tail"] = 11 * math.sin(math.pi * tt) * rock + 4 * math.sin(2 * math.pi * t)
    ex = []
    for k in range(int(0.6 * secs / PUNK_BEAT) + 1):
        d = 0.22 + k * PUNK_BEAT / secs
        x0 = 18 if k % 2 else 110
        f = _floating(t, "bolt", x0, 40 + 8 * (k % 3), rise=6, delay=d, life=0.1, size=0.9, drift=0, rot=12 if k % 2 else -12)
        if f:
            ex.append(f)
    for sx_, sy_, d in ((40, 18, 0.12), (88, 16, 0.14)):
        f = _floating(t, "sparkle", sx_, sy_, rise=4, delay=d, life=0.1, size=0.8, drift=0, rot=90 * t)
        if f:
            ex.append(f)
    for d, x0, col in ((0.3, 22, NOTE_COLORS[2]), (0.55, 108, NOTE_COLORS[0])):
        f = _floating(t, "note", x0, 70, rise=24, delay=d, life=0.2, size=0.8, drift=3, color=col)
        if f:
            ex.append(f)
    p["extras"] = ex
    return p


def battery_sad(i, n):
    """Ears drooping, holds an almost empty battery and looks at it; the red bar blinks, a gentle shake does not help, a sigh, a tear; puts it away."""
    t = i / n
    p = default_pose()
    breathe(p, t, 0.6)
    sad = window(t, 0.04, 0.16, 0.86, 0.97, ease_in_out)
    hold = window(t, 0.05, 0.15, 0.84, 0.95, ease_in_out)
    s = pop_in_out(t, 0.05, 0.13, 0.85, 0.92)
    look = window(t, 0.16, 0.26, 0.5, 0.58, ease_in_out)
    shake = math.sin(2 * math.pi * t * 18) * window(t, 0.36, 0.39, 0.47, 0.5)
    sigh = window(t, 0.56, 0.62, 0.7, 0.8, ease_in_out)
    hug = window(t, 0.7, 0.76, 0.84, 0.9, ease_in_out)
    cx = 64 + 2.2 * shake
    cy = 99 - 8 * look + 2 * hug + 2 * sigh
    bat = dict(kind="battery", x=cx, y=cy, rot=7 * shake - 3 * look, scale=1.4 * s, level=0.12,
               blink=1.0 if (i // 5) % 2 == 0 else 0.0)
    if s > 0.01:
        p["props"] = [bat]
    # held from below, so the whole battery and its nearly empty bar stay in sight
    p["arm"] = raised(paw(-1, prop_at(bat, (-9.5, 9.5)), hold), paw(1, prop_at(bat, (9.5, 9.5)), hold))
    p["sy"] *= 1 - 0.04 * sad - 0.04 * sigh
    p["sx"] *= 1 + 0.02 * sad
    p["head_dy"] = 2.5 * sad + 2 * sigh
    p["head_tilt"] = -4 * sad + 3 * look
    p["ear_out"] = 20 * sad
    p["ear_scale"] = 1 - 0.14 * sad
    p["ear_l"] = -4 * sigh
    p["ear_r"] = 4 * sigh
    p["tail"] = -12 * sad
    p["tail_curl"] = 0.35 * sad
    p["eyes"] = "closed" if 0.6 < t < 0.7 else "open"
    p["open"] = 1 - 0.3 * sad - blink_shut(t, 0.32, "slow", 0.04) - 0.3 * blink_shut(t, 0.8, "half", 0.05)
    p["look"] = (0.0, 1.0 * look + 0.4 * hug)
    p["mouth"] = "frown" if sad > 0.3 else "w"
    p["blush"] = 0.25
    ex = []
    tear = clamp((t - 0.62) / 0.2)
    if 0 < tear < 1:
        ex.append(dict(kind="drop", x=43 - 1.5 * tear, y=77 + 9 * tear, scale=0.7,
                       opacity=ramp(tear, 0, 0.15) * (1 - ramp(tear, 0.75, 1.0))))
    sw = clamp((t - 0.36) / 0.16)
    if 0 < sw < 1:
        ex.append(dict(kind="sweat", x=100, y=34 + 6 * sw, scale=0.8, opacity=ramp(sw, 0, 0.2) * (1 - ramp(sw, 0.7, 1.0))))
    p["extras"] = ex
    return p


def cloud_farewell(i, n):
    """Closes its eyes and curls up, turns into a fluffy cloud with a sleepy smile, floats up a little and melts away."""
    t = i / n
    p = default_pose()
    breathe(p, t, 0.5)
    curl = window(t, 0.04, 0.18, 2.0, 3.0, ease_in_out)          # holds till the end: the fox is gone
    m = keys(t, [(0.16, 0.0), (0.5, 1.0)])
    hug = window(t, 0.05, 0.17, 2.0, 3.0, ease_in_out)
    p["morph"] = "cloud"
    p["heart"] = m
    p["sy"] *= 1 - 0.07 * curl
    p["sx"] *= 1 + 0.03 * curl
    k = 1 - ramp(m, 0.1, 0.55)
    if hug > 0.02 and k > 0.03:
        arms = raised(paw(-1, (52, 96), hug), paw(1, (76, 96), hug)) or []
        p["arm"] = [dict(a, k=k, x=lerp(64, a["x"], k), y=lerp(98, a["y"], k)) for a in arms] or None
    p["head_dy"] = 3 * curl + 7 * m                              # the face settles into the middle of the cloud
    p["head_tilt"] = -5 * curl * (1 - m)
    p["ear_out"] = 10 * curl
    p["tail_curl"] = 0.6 * curl
    p["tail"] = -10 * curl
    float_up = keys(t, [(0.5, 0.0), (0.97, 1.0)], ease_in)
    bob = math.sin(2 * math.pi * (t - 0.5) * 2) * window(t, 0.5, 0.58, 0.8, 0.9)
    p["dy"] = -12 * float_up - 1.5 * bob
    # gone exactly on the last frame: identical empty frames would be merged by the WebP encoder
    p["fade"] = keys(t, [(0.66, 0.0), (0.99, 1.0)], ease_in)
    p["eyes"] = "closed" if t > 0.06 else "open"
    p["open"] = 1 - blink_shut(t, 0.02, "slow", 0.02)
    p["mouth"] = "smile" if t > 0.1 else "w"
    p["blush"] = 0.5 + 0.25 * m
    ex = []
    for x0, y0, d, sz in ((26, 96, 0.55, 0.9), (102, 92, 0.6, 1.0), (40, 70, 0.68, 0.8), (90, 64, 0.72, 0.9),
                          (60, 50, 0.78, 0.8), (76, 84, 0.82, 0.7)):
        f = _floating(t, "puff", x0, y0, rise=18, delay=d, life=0.14, size=sz, drift=4)
        if f:
            ex.append(f)
    p["extras"] = ex
    return p


def _inertia(kind):
    """
    The car pulls the fox one way and it leans with a little delay, then settles back (wave 7: the INERTIA_* states,
    driven by the car physics in the app). The body follows the push on a spring, the head lags more, the ears
    and the tail most — so they trail behind and overshoot a little on the way back.
    """
    def pose(i, n):
        t = i / n
        secs = _secs(n)
        p = default_pose()
        breathe(p, t, 0.5)
        push = lambda u: window(u, 0.08, 0.22, 0.5, 0.72, ease_in_out)
        body = spring(push, t, secs, freq=1.5, zeta=0.55)
        head = spring(push, t, secs, freq=1.1, zeta=0.45)
        soft = spring(push, t, secs, freq=0.85, zeta=0.3)
        trail = body - soft                                       # > 0 while the soft parts lag behind
        onset = bump(t, 0.08, 0.3)
        if kind in ("left", "right"):
            sd = -1 if kind == "left" else 1
            p["lean"] = 9 * sd * body
            # leaning right, the big tail would swing out of the frame: the body shifts back a little instead
            p["dx"] = (2.5 if sd < 0 else -1.0) * body
            p["dy"] = -3 * abs(body)                              # lifted a little: the leaning paws stay in the frame
            p["head_tilt"] = 3 * sd * head - 5 * sd * (body - head)
            p["ear_l"] = p["ear_r"] = -16 * sd * trail
            p["tail"] = -14 * sd * trail
            p["look"] = (0.7 * sd * head, 0.0)
            p["eyes"] = "open"
            p["mouth"] = "o" if onset > 0.4 else "w"
        elif kind == "forward":                                   # braking: pitches toward you
            p["dy"] = 0.5 * body
            p["sy"] *= 1 - 0.07 * body
            p["sx"] *= 1 + 0.06 * body
            p["head_dy"] = 6 * head
            p["ear_scale"] = 1 - 0.16 * soft
            p["ear_out"] = -3 * soft
            p["ear_l"] = -6 * trail
            p["ear_r"] = 6 * trail
            p["tail"] = -16 * trail - 4 * body
            p["tail_curl"] = 0.3 * soft
            p["look"] = (0.0, 0.8 * head)
            p["eyes"] = "wide" if onset > 0.5 else "open"
            p["mouth"] = "o" if onset > 0.3 else "w"
        else:                                                     # speeding up: pressed back, enjoys it
            p["dy"] = -1.5 * body
            p["sy"] *= 1 - 0.01 * body
            p["sx"] *= 1 - 0.035 * body
            p["head_dy"] = -3 * head
            p["ear_out"] = 14 * soft
            p["ear_scale"] = 1 - 0.1 * soft
            p["tail"] = 12 * trail - 5 * body
            p["look"] = (0.0, -0.5 * head)
            p["eyes"] = "happy" if 0.3 < t < 0.6 else "open"
            p["mouth"] = "open" if 0.3 < t < 0.6 else "w"
            p["mouth_open"] = 0.6
        p["open"] = 1 - blink_shut(t, 0.86, "single", 0.04)
        p["blush"] = 0.5
        return p
    pose.__doc__ = {
        "left": "Leans left with the car, a little late; ears and tail trail behind, then it settles back with a small sway.",
        "right": "Leans right with the car, a little late; ears and tail trail behind, then it settles back with a small sway.",
        "forward": "Braking: pitches forward toward you, head lags and dips, ears tip forward, tail swings up; settles back.",
        "back": "Speeding up: pressed back, head tips up, ears blown back, a happy face; settles back.",
    }[kind]
    return pose


inertia_left = _inertia("left")
inertia_right = _inertia("right")
inertia_forward = _inertia("forward")
inertia_back = _inertia("back")


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
    # wave 3
    "tail_hide": (tail_hide, 80),
    "grooming": (grooming, 100),
    "butterfly": (butterfly, 125),
    "curl_up": (curl_up, 100),
    "sleep_curled": (sleep_curled, 75),
    "wake_curled": (wake_curled, 60),
    "tease": (tease, 60),
    "sneeze": (sneeze, 60),
    "listen": (listen, 75),
    "paw_wave": (paw_wave, 60),
    "confused": (confused, 75),
    "proud": (proud, 75),
    # car companion
    "driving": (driving, 100),
    "seatbelt": (seatbelt, 90),
    "navigator": (navigator, 150),
    "mechanic": (mechanic, 150),
    "tire_check": (tire_check, 150),
    "refuel": (refuel, 150),
    "car_wash": (car_wash, 150),
    "arrival": (arrival, 100),
    "pothole": (pothole, 60),
    "parking": (parking, 150),
    # wave 4
    "heart_transform": (heart_transform, 115),
    "dance_side": (dance_side, 125),
    "microphone": (microphone, 130),
    "guitar": (guitar, 140),
    "tail_back": (tail_back, 140),
    # wave 5
    "bayan": (bayan, 140),
    "balalaika": (balalaika, 140),
    "punk": (punk, 150),
    "battery_sad": (battery_sad, 110),
    "cloud_farewell": (cloud_farewell, 100),
    "inertia_left": (inertia_left, 60),
    "inertia_right": (inertia_right, 60),
    "inertia_forward": (inertia_forward, 60),
    "inertia_back": (inertia_back, 60),
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


# Animations added in wave 3 (shown first in the gallery, checked by test_generate.py).
WAVE3 = ["tail_hide", "grooming", "butterfly", "curl_up", "sleep_curled", "wake_curled",
         "tease", "sneeze", "listen", "paw_wave", "confused", "proud"]

# Car companion animations (shown first in the gallery, checked by test_generate.py).
CAR = ["driving", "seatbelt", "navigator", "mechanic", "tire_check", "refuel", "car_wash",
       "arrival", "pothole", "parking"]

# Wave 4: the heart, music shows, turning around (shown first in the gallery, checked by test_generate.py).
WAVE4 = ["heart_transform", "dance_side", "microphone", "guitar", "tail_back"]

# Wave 5: music shows, the battery, the car leans (shown first in the gallery, checked by test_generate.py).
WAVE5 = ["bayan", "balalaika", "punk", "battery_sad", "cloud_farewell",
         "inertia_left", "inertia_right", "inertia_forward", "inertia_back"]

# Wave 5 animations that do not end in the calm pose: the fox is gone at the end (the service stops after it).
ENDS_GONE = ["cloud_farewell"]

# Where each animation is used (see AnimationPools in the app); shown in the gallery.
USAGE = {
    "driving": "AUTO · CALM while driving", "seatbelt": "AUTO · TRIP_PREPARE",
    "navigator": "AUTO · TRIP_PREPARE, CALM parked", "mechanic": "AUTO · CALM parked, rare",
    "tire_check": "AUTO · CALM parked", "refuel": "AUTO · CALM parked", "car_wash": "AUTO · CALM parked, rare",
    "arrival": "AUTO · ARRIVAL", "pothole": "preview only (no trigger yet)",
    "parking": "AUTO · ARRIVAL, CALM parked",
    "tail_hide": "CALM · scene", "grooming": "CALM · scene", "butterfly": "CALM · scene",
    "sneeze": "CALM, FIDGET", "listen": "CALM, FIDGET", "confused": "CALM",
    "tease": "TAP", "paw_wave": "TAP", "proud": "TAP, HEARTS",
    "curl_up": "FALL_ASLEEP → sleep_curled", "sleep_curled": "SLEEP after curl_up",
    "wake_curled": "WAKE after sleep_curled",
    "heart_transform": "HEARTS after petting, FAREWELL", "dance_side": "MUSIC",
    "microphone": "MUSIC · rare scene", "guitar": "MUSIC · rare scene", "tail_back": "CALM · scene, not while driving",
    "bayan": "MUSIC · rare scene", "balalaika": "MUSIC · rare scene", "punk": "MUSIC · rare scene, not while driving",
    "battery_sad": "BATTERY_CRITICAL (10 %)", "cloud_farewell": "BATTERY_SHUTDOWN (1 %, then Mimio stops)",
    "inertia_left": "AUTO · INERTIA_LEFT (the car turns right)", "inertia_right": "AUTO · INERTIA_RIGHT (the car turns left)",
    "inertia_forward": "AUTO · INERTIA_FORWARD (braking)", "inertia_back": "AUTO · INERTIA_BACK (speeding up)",
}

EDGE_ALPHA_MAX = 8       # opacity allowed in the outermost pixel row/column (anti-aliasing only)


def edge_problems(frames):
    """Frames whose silhouette touches the border of the image (ears, paws or tail would be cut off)."""
    out = []
    for i, f in enumerate(frames):
        a = np.asarray(f.getchannel("A"))
        border = max(a[0].max(), a[-1].max(), a[:, 0].max(), a[:, -1].max())
        if border > EDGE_ALPHA_MAX:
            out.append((i, int(border)))
    return out


def set_size(size):
    """Output resolution; supersampling grows with it so all geometry scales proportionally."""
    global SIZE, SS, W
    SIZE = size
    SS = 4 * size // 128
    W = 128 * SS


def describe(name):
    doc = (EMOTIONS[name][0].__doc__ or "").strip()
    return " ".join(doc.split())


def write_gallery(path, names):
    """HTML gallery that plays the generated WebP files straight from res/raw."""
    rel = os.path.relpath(OUT_DIR, os.path.dirname(os.path.abspath(path)))
    cards = []
    for name in names:
        fn, n = EMOTIONS[name]
        f = os.path.join(OUT_DIR, f"fox_{name}.webp")
        kb = os.path.getsize(f) // 1024 if os.path.exists(f) else 0
        group = ("wave5" if name in WAVE5 else "wave4" if name in WAVE4 else "car" if name in CAR
                 else "wave3" if name in WAVE3 else "")
        cards.append(dict(name=name, src=f"{rel}/fox_{name}.webp", frames=n, ms=n * FRAME_MS, kb=kb,
                          desc=describe(name), use=USAGE.get(name, ""), group=group))
    import json
    html = GALLERY_HTML.replace("__CARDS__", json.dumps(cards, ensure_ascii=False))
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(html)


GALLERY_HTML = """<!doctype html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Fox animations</title>
<style>
:root{--bg:#f4f1ec;--card:#fff;--ink:#2b211c;--muted:#7a6a60;--accent:#e8641e;--line:#e6ded5}
@media (prefers-color-scheme:dark){:root{--bg:#1d1916;--card:#2a2420;--ink:#f3ebe4;--muted:#b3a399;--line:#3a322c}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.45 system-ui,sans-serif}
header{padding:20px 16px 8px;max-width:1100px;margin:auto}h1{margin:0 0 4px;font-size:22px}
.bar{display:flex;gap:8px;flex-wrap:wrap;margin:10px 0}.bar button{border:1px solid var(--line);background:var(--card);
color:var(--ink);border-radius:999px;padding:6px 12px;cursor:pointer}.bar button[aria-pressed=true]{background:var(--accent);color:#fff;border-color:var(--accent)}
main{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:12px;padding:8px 16px 32px;max-width:1100px;margin:auto}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:10px;cursor:pointer;text-align:left;color:inherit;font:inherit}
.card img,.big img{width:100%;aspect-ratio:1;image-rendering:auto;border-radius:10px}
.stage{background:var(--stage,#eef0f4);border-radius:10px}.checker{background:repeating-conic-gradient(#ddd 0 25%,#fff 0 50%) 0 0/16px 16px}
.dark{background:#30343c}.name{font-weight:600;margin-top:6px}.meta{color:var(--muted);font-size:12.5px}
.badge{display:inline-block;background:var(--accent);color:#fff;border-radius:6px;font-size:11px;padding:0 6px;margin-left:6px;vertical-align:2px}
dialog{border:none;border-radius:16px;padding:16px;max-width:min(560px,94vw);width:100%;background:var(--card);color:var(--ink)}
dialog::backdrop{background:#0008}.big{max-width:384px;margin:auto}.row{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}
.row button{border:1px solid var(--line);background:var(--bg);color:var(--ink);border-radius:8px;padding:6px 10px;cursor:pointer}
</style></head><body>
<header><h1>Лисёнок — анимации</h1>
<div class="meta">Animated WebP прямо из <code>app/src/main/res/raw</code>. Нажмите на карточку, чтобы посмотреть отдельно.</div>
<div class="bar" id="filters"><button data-f="wave5" aria-pressed="true">Волна 5</button><button data-f="wave4" aria-pressed="false">Волна 4</button><button data-f="car" aria-pressed="false">Автомобиль</button><button data-f="wave3" aria-pressed="false">Волна 3</button><button data-f="all" aria-pressed="false">Все</button>
<span style="flex:1"></span><button data-bg="" aria-pressed="true">Светлый фон</button><button data-bg="dark" aria-pressed="false">Тёмный</button><button data-bg="checker" aria-pressed="false">Прозрачность</button></div>
</header>
<main id="grid"></main>
<dialog id="dlg"><div class="big stage" id="bigstage"><img id="bigimg" alt=""></div>
<div class="name" id="bigname"></div><div class="meta" id="bigmeta"></div><p id="bigdesc"></p>
<div class="row"><button id="replay">↻ Сначала</button><button id="prev">← Пред.</button><button id="next">След. →</button><span style="flex:1"></span><button id="close">Закрыть</button></div></dialog>
<script>
const CARDS = __CARDS__;
let filter = "wave5", bg = "", shown = [], cur = 0;
const grid = document.getElementById("grid"), dlg = document.getElementById("dlg");
function render() {
  shown = CARDS.filter(c => filter === "all" || c.group === filter);
  grid.innerHTML = "";
  shown.forEach((c, k) => {
    const b = document.createElement("button"); b.className = "card";
    b.innerHTML = `<div class="stage ${bg}"><img loading="lazy" src="${c.src}" alt="${c.name}"></div>
      <div class="name">${c.name}${c.group === "wave5" ? '<span class="badge">new</span>' : ""}</div>
      <div class="meta">${c.use || "&nbsp;"}</div><div class="meta">${(c.ms / 1000).toFixed(1)} с · ${c.frames} кадров · ${c.kb} KB</div>`;
    b.onclick = () => open(k); grid.appendChild(b);
  });
}
function open(k) {
  cur = (k + shown.length) % shown.length; const c = shown[cur];
  document.getElementById("bigstage").className = "big stage " + bg;
  const img = document.getElementById("bigimg"); img.src = ""; img.src = c.src + "#" + Date.now();
  document.getElementById("bigname").textContent = c.name;
  document.getElementById("bigmeta").textContent = `${c.use} · ${(c.ms / 1000).toFixed(1)} с · ${c.frames} кадров · ${c.kb} KB`;
  document.getElementById("bigdesc").textContent = c.desc;
  if (!dlg.open) dlg.showModal();
}
document.getElementById("replay").onclick = () => open(cur);
document.getElementById("prev").onclick = () => open(cur - 1);
document.getElementById("next").onclick = () => open(cur + 1);
document.getElementById("close").onclick = () => dlg.close();
document.querySelectorAll("[data-f]").forEach(b => b.onclick = () => {
  filter = b.dataset.f; document.querySelectorAll("[data-f]").forEach(x => x.setAttribute("aria-pressed", x === b)); render(); });
document.querySelectorAll("[data-bg]").forEach(b => b.onclick = () => {
  bg = b.dataset.bg; document.querySelectorAll("[data-bg]").forEach(x => x.setAttribute("aria-pressed", x === b)); render(); });
render();
</script></body></html>
"""


def main():
    args = sys.argv[1:]

    def option(flag, default=None):
        if flag in args:
            idx = args.index(flag)
            return args[idx + 1] if len(args) > idx + 1 and not args[idx + 1].startswith("--") else default
        return None

    preview_dir = option("--preview", os.path.dirname(__file__))
    default_gallery = os.path.join(os.path.dirname(os.path.abspath(__file__)), "gallery.html")
    gallery = option("--gallery", default_gallery) or (default_gallery if "--gallery-only" in args else None)
    out_dir = option("--out") or OUT_DIR
    size = option("--size")
    if size:
        set_size(int(size))
    only = [a for a in args if a in EMOTIONS]
    if "--wave3" in args:
        only += WAVE3
    if "--car" in args:
        only += CAR
    if "--wave4" in args:
        only += WAVE4
    if "--wave5" in args:
        only += WAVE5
    all_frames = {}
    for name, (fn, n) in EMOTIONS.items():
        if only and name not in only:
            continue
        if "--gallery-only" in args:
            continue
        frames = render(fn, n)
        all_frames[name] = frames
        os.makedirs(out_dir, exist_ok=True)
        path = os.path.join(out_dir, f"fox_{name}.webp")
        save_webp(frames, path)
        print(f"{path}: {n} frames, {os.path.getsize(path) // 1024} KB")
        cut = edge_problems(frames)
        if cut:
            print(f"  WARNING: touches the image edge in {len(cut)} frame(s), e.g. frame {cut[0][0]}")
    if preview_dir and all_frames:
        os.makedirs(preview_dir, exist_ok=True)
        save_preview(all_frames, preview_dir)
    if gallery:
        names = WAVE5 + WAVE4 + CAR + WAVE3 + [k for k in EMOTIONS if k not in WAVE3 + CAR + WAVE4 + WAVE5]
        write_gallery(gallery, names)
        print(f"gallery: {gallery}")


if __name__ == "__main__":
    main()
