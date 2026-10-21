#!/usr/bin/env python3
"""
Checks for the fox generator: python3 -m unittest discover -s tools/fox

- new animations stay inside the frame (no cut-off ears, paws or tail);
- one-shots start and end close to the calm pose, so CALM continues without a jump;
- the curled-up sleep sequence joins up (curl_up → sleep_curled loop → wake_curled → calm);
- rendering is deterministic (no randomly changing details between runs);
- car animations: inside the frame, start/end in the calm pose (props pop in and out), the props
  follow the paws and are not cut off, and the transparent background stays transparent;
- wave 4: inside the frame, calm at both ends, the turn and the heart change the drawing itself (no
  squeezing or mirroring), the front view is unchanged by the new pose fields, the props follow the paws;
- wave 5: inside the frame, calm at both ends (the cloud goodbye ends fully transparent), no frame jumps,
  128×128 at 25 fps, the instruments follow the paws (the bayan's bellows really move), the mohawk is
  temporary, the leans lag behind the push and settle back.
"""

import os
import sys
import unittest

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import generate as g  # noqa: E402

# Mean absolute RGBA difference (0..255) between two frames that still reads as "the same pose".
SAME_POSE = 3.0


def frame(name, i):
    fn, n = g.EMOTIONS[name]
    big = g.draw_character(fn(i % n, n))
    return big.convert("RGBa").resize((g.SIZE, g.SIZE), Image.LANCZOS).convert("RGBA")


def diff(a, b):
    return float(np.abs(np.asarray(a, dtype=float) - np.asarray(b, dtype=float)).mean())


def visible_diff(a, b):
    """Like diff(), but the colour of a fully transparent pixel does not count (fading out to nothing)."""
    def pm(img):
        x = np.asarray(img, dtype=float)
        return np.concatenate([x[..., :3] * x[..., 3:] / 255, x[..., 3:]], axis=-1)
    return float(np.abs(pm(a) - pm(b)).mean())


class FoxGeneratorTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.calm = frame("idle_small", 0)

    def test_new_animations_stay_inside_the_frame(self):
        for name in g.WAVE3:
            n = g.EMOTIONS[name][1]
            frames = [frame(name, i) for i in range(0, n, 2)]
            self.assertEqual([], g.edge_problems(frames), name)

    def test_one_shots_return_to_calm(self):
        for name in ("tail_hide", "grooming", "butterfly", "tease", "sneeze", "listen",
                     "paw_wave", "confused", "proud", "wake_curled"):
            n = g.EMOTIONS[name][1]
            if name != "wake_curled":
                self.assertLess(diff(frame(name, 0), self.calm), SAME_POSE, f"{name} start")
            self.assertLess(diff(frame(name, n - 1), self.calm), SAME_POSE, f"{name} end")

    def test_curled_sleep_sequence_joins_up(self):
        n_curl = g.EMOTIONS["curl_up"][1]
        n_sleep = g.EMOTIONS["sleep_curled"][1]
        sleep0 = frame("sleep_curled", 0)
        self.assertLess(diff(frame("curl_up", 0), self.calm), SAME_POSE, "curl_up starts calm")
        self.assertLess(diff(frame("curl_up", n_curl - 1), sleep0), SAME_POSE, "curl_up → sleep_curled")
        # The loop wraps around like any other frame step (the floating zzz keep moving).
        step = max(diff(frame("sleep_curled", i), frame("sleep_curled", i + 1)) for i in range(0, n_sleep - 1, 8))
        self.assertLessEqual(diff(frame("sleep_curled", n_sleep - 1), sleep0), step * 1.2, "sleep_curled loops")
        self.assertLess(diff(frame("wake_curled", 0), sleep0), SAME_POSE, "sleep_curled → wake_curled")

    def test_rendering_is_deterministic(self):
        for name in ("butterfly", "sneeze"):
            a, b = frame(name, 37), frame(name, 37)
            self.assertEqual(a.tobytes(), b.tobytes(), name)

    def test_new_animations_are_described_for_the_gallery(self):
        for name in g.WAVE3:
            self.assertTrue(g.describe(name), name)
            self.assertIn(name, g.USAGE)

    def test_other_resolutions_keep_proportions(self):
        try:
            g.set_size(256)
            big = frame("idle_small", 0)
            self.assertEqual((256, 256), big.size)
            self.assertEqual([], g.edge_problems([big]))
            g.set_size(128)
            self.assertLess(diff(big.resize((128, 128), Image.LANCZOS), frame("idle_small", 0)), SAME_POSE)
        finally:
            g.set_size(128)

    def test_car_animations_stay_inside_the_frame(self):
        for name in g.CAR:
            n = g.EMOTIONS[name][1]
            frames = [frame(name, i) for i in range(0, n, 2)]
            self.assertEqual([], g.edge_problems(frames), name)

    def test_car_animations_start_and_end_calm(self):
        for name in g.CAR:
            n = g.EMOTIONS[name][1]
            self.assertLess(diff(frame(name, 0), self.calm), SAME_POSE, f"{name} start")
            self.assertLess(diff(frame(name, n - 1), self.calm), SAME_POSE, f"{name} end")

    def test_car_animations_keep_a_transparent_background(self):
        for name in g.CAR:
            n = g.EMOTIONS[name][1]
            a = np.asarray(frame(name, n // 2).getchannel("A"))
            self.assertGreater((a == 0).mean(), 0.35, name)          # most of the square is see-through
            self.assertEqual(0, a[:4, :4].max(), name)               # corners are empty

    def test_props_move_with_the_paws(self):
        # The paw holding a prop is at the prop's grip in every frame of the hold.
        for name, kind in (("mechanic", "wrench"), ("car_wash", "sponge"), ("arrival", "flag"), ("refuel", "nozzle")):
            fn, n = g.EMOTIONS[name]
            grips = []
            for i in range(0, n, 5):
                p = fn(i, n)
                props = [pr for pr in p["props"] if pr["kind"] == kind and pr.get("scale", 1) > 0.01]
                if not props:
                    continue
                arms = p["arm"] if isinstance(p["arm"], list) else [p["arm"]]
                pr = props[0]
                gx, gy = pr["x"], pr["y"]
                if kind == "sponge":                                  # held from behind, a little offset
                    gx, gy = gx - 4 * pr["scale"], gy + 4 * pr["scale"]
                near = min(abs(a["x"] - gx) + abs(a["y"] - gy) for a in arms if a)
                self.assertLess(near, 0.01, f"{name} frame {i}")
                grips.append((round(gx), round(gy)))
            self.assertGreater(len(set(grips)), 3, f"{name}: the prop moves")

    def test_steering_wheel_turns_under_the_paws(self):
        fn, n = g.EMOTIONS["driving"]
        p = fn(n // 3, n)
        wheel = p["props"][0]
        left, right = p["arm"]
        self.assertAlmostEqual(left["x"], g.wheel_grip(wheel["x"], wheel["y"], wheel["turn"], 200, wheel["scale"])[0], 3)
        self.assertAlmostEqual(right["y"], g.wheel_grip(wheel["x"], wheel["y"], wheel["turn"], -20, wheel["scale"])[1], 3)
        turns = {round(fn(i, n)["props"][0]["turn"]) for i in range(20, 80, 6)}
        self.assertGreater(len(turns), 4, "steers")

    def test_props_do_not_change_old_animations(self):
        # No props → the drawing code path of every older animation is unchanged.
        for name in ("idle_small", "tease", "phone_call"):
            fn, n = g.EMOTIONS[name]
            self.assertEqual([], fn(n // 2, n)["props"], name)

    def test_car_animations_are_described_for_the_gallery(self):
        for name in g.CAR:
            self.assertTrue(g.describe(name), name)
            self.assertIn(name, g.USAGE)

    # ---- wave 4

    def test_wave4_stays_inside_the_frame(self):
        for name in g.WAVE4:
            n = g.EMOTIONS[name][1]
            frames = [frame(name, i) for i in range(0, n, 2)]
            self.assertEqual([], g.edge_problems(frames), name)

    def test_wave4_starts_and_ends_calm(self):
        for name in g.WAVE4:
            n = g.EMOTIONS[name][1]
            self.assertLess(diff(frame(name, 0), self.calm), SAME_POSE, f"{name} start")
            self.assertLess(diff(frame(name, n - 1), self.calm), SAME_POSE, f"{name} end")

    def test_wave4_moves_smoothly(self):
        # No jumps between neighbouring frames: never a bigger step than the existing dances take on the
        # beat (music / music_paws ≈ 19; music_spin reaches 30).
        for name in g.WAVE4:
            n = g.EMOTIONS[name][1]
            frames = [frame(name, i) for i in range(n)]
            worst = max(diff(a, b) for a, b in zip(frames, frames[1:]))
            self.assertLess(worst, 19.0, name)

    def test_wave4_keeps_a_transparent_background(self):
        for name in g.WAVE4:
            n = g.EMOTIONS[name][1]
            a = np.asarray(frame(name, n // 2).getchannel("A"))
            self.assertGreater((a == 0).mean(), 0.35, name)
            self.assertEqual(0, a[:4, :4].max(), name)

    def test_new_pose_fields_keep_the_front_view(self):
        # yaw = 0 and heart = 0 draw exactly what the original code drew.
        p = g.default_pose()
        plain = g.draw_character(p).tobytes()
        p.update(yaw=0.0, head_yaw=0.0, heart=0.0, tail_scale=1.0, feet_dy=(0.0, 0.0))
        self.assertEqual(plain, g.draw_character(p).tobytes())

    def test_turning_is_drawn_not_squeezed(self):
        # A squeezed front view keeps both eyes; the real profile hides the far eye and shows the snout
        # beyond the head, and the back view has no face at all.
        def dark_pixels(p):
            img = np.asarray(g.draw_character(p).resize((128, 128)))
            return ((img[..., 0] < 80) & (img[..., 1] < 50) & (img[..., 3] > 200)).sum()
        front, side, back = g.default_pose(), g.default_pose(), g.default_pose()
        side["yaw"], back["yaw"] = 90.0, 180.0
        self.assertLess(dark_pixels(back), dark_pixels(side))
        self.assertLess(dark_pixels(side), dark_pixels(front))
        a_side = np.asarray(g.draw_character(side).getchannel("A")) > 128
        a_front = np.asarray(g.draw_character(front).getchannel("A")) > 128
        # the profile is not the front view squashed: its outline is not symmetric
        self.assertNotEqual(a_side.tolist(), a_side[:, ::-1].tolist())
        self.assertGreater(abs(int(a_front.sum()) - int(a_side.sum())), 0)

    def test_heart_transform_really_morphs(self):
        # The silhouette goes through shapes between fox and heart (no fade of one picture into another),
        # and the face stays on it.
        fn, n = g.EMOTIONS["heart_transform"]
        full = round(g.HEART_MOMENT_MS / g.FRAME_MS)
        self.assertAlmostEqual(1.0, fn(full, n)["heart"], places=2)
        heart = np.asarray(frame("heart_transform", full).getchannel("A")) > 128
        fox = np.asarray(self.calm.getchannel("A")) > 128
        mid_i = next(i for i in range(n) if 0.4 < fn(i, n)["heart"] < 0.6)
        mid = np.asarray(frame("heart_transform", mid_i).getchannel("A")) > 128
        self.assertGreater((mid != fox).sum(), 0)
        self.assertGreater((mid != heart).sum(), 0)
        # every frame stays opaque inside (no translucent cross-fade)
        for i in range(0, n, 6):
            a = np.asarray(frame("heart_transform", i).getchannel("A"))
            self.assertLess(((a > 30) & (a < 200)).mean(), 0.08, f"frame {i}")
        self.assertEqual("closed", fn(full, n)["eyes"])

    def test_heart_moment_matches_the_app(self):
        kt = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "java", "com", "damitrya",
                          "mimio", "state", "AnimationPool.kt")
        with open(kt, encoding="utf-8") as fh:
            self.assertIn(f"HEART_MOMENT_MS = {g.HEART_MOMENT_MS}L", fh.read())

    def test_music_props_move_with_the_paws(self):
        for name, kind, side in (("microphone", "mic", 1), ("guitar", "guitar", -1)):
            fn, n = g.EMOTIONS[name]
            seen = []
            for i in range(0, n, 4):
                p = fn(i, n)
                props = [pr for pr in p["props"] if pr["kind"] == kind and pr.get("scale", 1) > 0.3]
                if not props or (kind == "guitar" and not 0.2 < i / n < 0.7):   # held, not being picked up
                    continue
                pr = props[0]
                arms = p["arm"] if isinstance(p["arm"], list) else [p["arm"]]
                hand = next(a for a in arms if a and a["side"] == side)
                if kind == "mic":
                    self.assertLess(abs(hand["x"] - pr["x"]) + abs(hand["y"] - pr["y"]), 0.01, f"{name} {i}")
                else:   # the fretting paw stays on the neck (local y ≈ 0, between body and headstock)
                    inv = np.linalg.inv(g._local(pr).m)
                    lx, ly, _ = inv @ np.array([hand["x"], hand["y"], 1.0])
                    self.assertTrue(-40 < lx < -14 and abs(ly) < 1.5, f"{name} {i}: {lx:.1f}, {ly:.1f}")
                seen.append((round(pr["x"]), round(pr["y"]), round(pr.get("rot", 0))))
            self.assertGreater(len(set(seen)), 3, f"{name}: the prop moves")
            # ...and never jumps from one frame to the next
            for i in range(n - 1):
                a, b = fn(i, n)["props"], fn(i + 1, n)["props"]
                if a and b:
                    self.assertLess(abs(a[0]["x"] - b[0]["x"]) + abs(a[0]["y"] - b[0]["y"]), 4.0, f"{name} {i}")
                    self.assertLess(abs(a[0].get("rot", 0) - b[0].get("rot", 0)), 8.0, f"{name} {i}")

    def test_wave4_is_described_for_the_gallery(self):
        for name in g.WAVE4:
            self.assertTrue(g.describe(name), name)
            self.assertIn(name, g.USAGE)

    # ---- wave 5

    def test_wave5_stays_inside_the_frame(self):
        for name in g.WAVE5:
            n = g.EMOTIONS[name][1]
            frames = [frame(name, i) for i in range(n)]
            self.assertEqual([], g.edge_problems(frames), name)

    def test_wave5_starts_and_ends_calm(self):
        for name in g.WAVE5:
            n = g.EMOTIONS[name][1]
            self.assertLess(diff(frame(name, 0), self.calm), SAME_POSE, f"{name} start")
            if name not in g.ENDS_GONE:
                self.assertLess(diff(frame(name, n - 1), self.calm), SAME_POSE, f"{name} end")

    def test_wave5_moves_smoothly(self):
        # The same bound as wave 4: never a bigger step than the existing dances take on the beat.
        for name in g.WAVE5:
            n = g.EMOTIONS[name][1]
            frames = [frame(name, i) for i in range(n)]
            worst = max(visible_diff(a, b) for a, b in zip(frames, frames[1:]))
            self.assertLess(worst, 19.0, name)

    def test_wave5_keeps_a_transparent_background(self):
        for name in g.WAVE5:
            n = g.EMOTIONS[name][1]
            a = np.asarray(frame(name, n // 2).getchannel("A"))
            self.assertGreater((a == 0).mean(), 0.35, name)
            self.assertEqual(0, a[:4, :4].max(), name)

    def test_wave5_is_described_for_the_gallery(self):
        for name in g.WAVE5:
            self.assertTrue(g.describe(name), name)
            self.assertIn(name, g.USAGE)
            self.assertEqual(128, frame(name, 0).size[0])
        self.assertEqual(40, g.FRAME_MS, "25 fps")

    def test_wave5_pose_fields_keep_the_old_drawing(self):
        p = g.default_pose()
        plain = g.draw_character(p).tobytes()
        p.update(morph="heart", fade=0.0)
        self.assertEqual(plain, g.draw_character(p).tobytes())
        heart = g.default_pose()
        heart["heart"] = 0.6
        self.assertEqual(g.draw_character(heart).tobytes(), g.draw_character(dict(heart, morph="heart")).tobytes())

    def test_cloud_farewell_morphs_and_vanishes(self):
        fn, n = g.EMOTIONS["cloud_farewell"]
        last = np.asarray(frame("cloud_farewell", n - 1).getchannel("A"))
        self.assertEqual(0, last.max(), "nothing is left at the end: the overlay goes away after it")
        full = next(i for i in range(n) if fn(i, n)["heart"] >= 0.999)
        self.assertEqual("cloud", fn(full, n)["morph"])
        cloud = np.asarray(frame("cloud_farewell", full).getchannel("A")) > 128
        fox = np.asarray(self.calm.getchannel("A")) > 128
        self.assertGreater((cloud != fox).sum(), 500, "a different shape, not the fox")
        # a real morph: the half-way frame is opaque inside (no cross-fade of two pictures)
        mid = next(i for i in range(n) if 0.4 < fn(i, n)["heart"] < 0.6)
        a = np.asarray(frame("cloud_farewell", mid).getchannel("A"))
        self.assertLess(((a > 30) & (a < 200)).mean(), 0.08)
        # it fades out only after it has become a cloud
        fades = [fn(i, n)["fade"] for i in range(n)]
        self.assertEqual(0.0, max(fades[:full + 1]))
        self.assertTrue(all(b >= a_ for a_, b in zip(fades, fades[1:])))
        # the ears melt into the cloud instead of floating above it
        top = np.asarray(frame("cloud_farewell", full).getchannel("A"))[:40]
        self.assertEqual(0, (top > 128).sum())

    def test_instruments_move_with_the_paws(self):
        def arms(p):
            a = p["arm"]
            return {x["side"]: x for x in (a if isinstance(a, list) else [a]) if x}

        # bayan: the left paw holds the bass block, the right one the keyboard block, in every held frame
        fn, n = g.EMOTIONS["bayan"]
        opens, rights, lefts = [], [], []
        for i in range(0, n, 3):
            p = fn(i, n)
            pr = next((x for x in p["props"] if x["kind"] == "bayan"), None)
            if not pr or not 0.25 < i / n < 0.75:
                continue
            hand = arms(p)
            lx, ly = g.prop_at(pr, g.bayan_left_grip(pr))
            self.assertLess(abs(hand[-1]["x"] - lx) + abs(hand[-1]["y"] - ly), 0.01, f"bayan {i}")
            inv = np.linalg.inv(g._local(pr).m)
            rx, ry, _ = inv @ np.array([hand[1]["x"], hand[1]["y"], 1.0])
            self.assertTrue(0 <= rx <= g.BAYAN_BLOCK and abs(ry) <= g.BAYAN_H / 2, f"bayan {i}: {rx:.1f}, {ry:.1f}")
            opens.append(pr["open"])
            rights.append(pr["x"])
            lefts.append(lx)
        self.assertGreater(max(opens) - min(opens), 0.4, "the bellows go out and in")
        self.assertGreater(max(lefts) - min(lefts), 3 * (max(rights) - min(rights)),
                           "the bass hand works the bellows, the keyboard side stays")
        xt, xb = g.bayan_edges(dict(open=0.8, fan=0.3))
        self.assertLess(xt, xb, "the bellows open like a fan: wider at the top")

        # balalaika: the fretting paw stays on the neck, the plucking one moves over the strings
        fn, n = g.EMOTIONS["balalaika"]
        plucks = []
        for i in range(0, n, 2):
            p = fn(i, n)
            pr = next((x for x in p["props"] if x["kind"] == "balalaika" and x["scale"] > 0.3), None)
            if not pr or not 0.22 < i / n < 0.7:
                continue
            inv = np.linalg.inv(g._local(pr).m)
            hand = arms(p)
            lx, ly, _ = inv @ np.array([hand[-1]["x"], hand[-1]["y"], 1.0])
            self.assertTrue(-44 < lx < -14 and abs(ly) < 1.5, f"balalaika {i}: {lx:.1f}, {ly:.1f}")
            px, py, _ = inv @ np.array([hand[1]["x"], hand[1]["y"], 1.0])
            self.assertTrue(4 < px < 12, f"balalaika {i}: plucks over the strings ({px:.1f})")
            plucks.append(round(py, 1))
        self.assertGreater(len(set(plucks)), 6, "the plucking paw trembles over the strings")

        # battery: held in both paws while it is out
        fn, n = g.EMOTIONS["battery_sad"]
        for i in range(0, n, 5):
            p = fn(i, n)
            pr = next((x for x in p["props"] if x["kind"] == "battery" and x["scale"] > 1.0), None)
            if pr:
                self.assertEqual({-1, 1}, set(arms(p)), f"battery_sad {i}")
                self.assertLess(pr["level"], 0.2, "almost empty")

        # no prop jumps from one frame to the next
        for name in ("bayan", "balalaika", "battery_sad"):
            fn, n = g.EMOTIONS[name]
            for i in range(n - 1):
                a, b = fn(i, n)["props"], fn(i + 1, n)["props"]
                if a and b:
                    self.assertLess(abs(a[0]["x"] - b[0]["x"]) + abs(a[0]["y"] - b[0]["y"]), 4.0, f"{name} {i}")
                    self.assertLess(abs(a[0].get("rot", 0) - b[0].get("rot", 0)), 8.0, f"{name} {i}")

    def test_mohawk_is_temporary(self):
        fn, n = g.EMOTIONS["punk"]
        grows = [next((pr["grow"] for pr in fn(i, n)["props"] if pr["kind"] == "mohawk"), 0.0) for i in range(n)]
        self.assertEqual(0.0, grows[0])
        self.assertEqual(0.0, grows[-1])
        self.assertAlmostEqual(1.0, max(grows), places=3)
        self.assertTrue(all(pr.get("head") for i in range(n) for pr in fn(i, n)["props"]), "it sits on the head")
        # it really shows: pink pixels above the head in the middle of the dance
        img = np.asarray(frame("punk", n // 2))
        pink = (img[..., 0] > 200) & (img[..., 1] < 130) & (img[..., 2] > 120) & (img[..., 3] > 200)
        self.assertGreater(pink[:40].sum(), 30)

    def test_leans_lag_and_settle(self):
        for name, field, sign in (("inertia_left", "lean", -1), ("inertia_right", "lean", 1),
                                  ("inertia_forward", "head_dy", 1), ("inertia_back", "head_dy", -1)):
            fn, n = g.EMOTIONS[name]
            values = [sign * fn(i, n)[field] for i in range(n)]
            peak = max(range(n), key=lambda i: values[i])
            self.assertGreater(values[peak], 2.0, name)
            self.assertGreater(peak / n, 0.2, f"{name}: follows the push with a delay")
            self.assertLess(abs(values[-1]), 0.6, f"{name}: back in the calm pose")
        # the soft parts trail: the ears lean against the motion while the body leans into it
        fn, n = g.EMOTIONS["inertia_left"]
        early = fn(n // 6, n)
        self.assertLess(early["lean"], 0)
        self.assertGreater(early["ear_l"], 0)

    def test_inertia_is_preview_only(self):
        kt = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "java", "com", "damitrya",
                          "mimio", "state", "AnimationPool.kt")
        with open(kt, encoding="utf-8") as fh:
            src = fh.read()
        line = next(ln for ln in src.splitlines() if "val PREVIEW_ONLY" in ln)
        for name in ("inertia_left", "inertia_right", "inertia_forward", "inertia_back"):
            self.assertIn(f'"{name}"', line)


if __name__ == "__main__":
    unittest.main()
