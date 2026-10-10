"""
Finds the cow's face in a cow Emoji Kitchen image. Google draws the cow face three slightly
different ways, so each has a profile of landmarks (eyes, nostrils, horns, face outline) measured
on one example. A similarity transform from that profile to the image maps any point of the
canonical face onto the actual face, even when it is tilted or scaled.
"""
import math

import numpy as np
from PIL import Image
from scipy import ndimage as nd


def _rgb(s):
    return np.array([int(s[i:i + 2], 16) for i in (1, 3, 5)], dtype=np.float32)


class Profile:
    """One of the cow face drawings Emoji Kitchen uses, with landmarks (x, y) measured on a
    canonical image. `unit` converts its pixels to profile A's."""

    def __init__(self, eye, nostril, eye_l, eye_r, nose_l, nose_r, eye_area, nose_area, horns, face, muzzle, eye_tol, nose_tol):
        self.eye, self.nostril = _rgb(eye), _rgb(nostril)
        self.eye_l, self.eye_r = np.array(eye_l), np.array(eye_r)
        self.nose_l, self.nose_r = np.array(nose_l), np.array(nose_r)
        self.eye_area, self.nose_area = eye_area, nose_area
        self.horns = [np.array(h) for h in horns]
        self.face = [np.array(p) for p in face]  # head outline without the ears
        self.muzzle = np.array(muzzle)  # a point well inside the muzzle
        self.eye_tol, self.nose_tol = eye_tol, nose_tol
        self.unit = float(np.hypot(*(self.eye_r - self.eye_l))) / 149.0


PROFILES = [
    # A: cow as the first emoji (brown eyes), measured on u1f42e_u1f615.png
    Profile("#422B0D", "#996A3F", (197.0, 231.0), (346.0, 231.0), (206.0, 339.0), (341.0, 344.0),
            2460.0, 2520.0, [(186.0, 65.0), (359.0, 69.0)],
            [(150, 92), (230, 80), (330, 80), (400, 95), (440, 190), (450, 310), (95, 310), (118, 190)],
            (270.0, 310.0), 60, 40),
    # B: cow as the second emoji (black eyes, grey nostrils), measured on u1f9ea_u1f42e.png
    Profile("#0A0A0A", "#2E2F31", (174.0, 221.0), (278.0, 222.0), (174.0, 326.0), (266.0, 327.0),
            1042.0, 1494.0, [(161.0, 107.0), (282.0, 110.0)],
            [(155, 125), (200, 117), (300, 122), (325, 140), (345, 230), (345, 300), (95, 300), (118, 195)],
            (220.0, 290.0), 30, 30),
    # C: longer face with black eyes and brown nostrils, measured on u1f42e_u1f338.png
    Profile("#0A0A0A", "#996A3F", (205.0, 238.0), (350.0, 240.0), (205.0, 384.0), (333.0, 385.0),
            2030.0, 2944.0, [(195.0, 85.0), (355.0, 85.0)],
            [(150, 100), (230, 92), (330, 92), (400, 105), (440, 200), (445, 330), (95, 330), (115, 200)],
            (270.0, 400.0), 30, 40),
]
FUR = [_rgb(c) for c in ("#DFDFDF", "#C6C6C6", "#ADADB8", "#BC8954")]
MUZZLE = [_rgb(c) for c in ("#FFD3B0", "#FEEBDA")]
HORN = _rgb("#FFB903")


def _mask_near(a, c, tol):
    return (np.abs(a[..., :3] - c).sum(-1) < tol) & (a[..., 3] > 200)


def _blobs(mask):
    lab, n = nd.label(mask)
    if n == 0:
        return []
    idx = range(1, n + 1)
    areas = nd.sum(mask, lab, idx)
    cms = nd.center_of_mass(mask, lab, idx)
    return [(float(ar), np.array([cm[1], cm[0]]), lab == i) for i, ar, cm in zip(idx, areas, cms) if ar >= 60]


def _best_pair(blobs, ref_area, ref_dist):
    """Two similar blobs side by side whose size fits their spacing."""
    best = None
    for i in range(len(blobs)):
        for j in range(i + 1, len(blobs)):
            (a1, p1, _), (a2, p2, _) = blobs[i], blobs[j]
            if max(a1, a2) / min(a1, a2) > 1.8:
                continue
            v = p2 - p1
            d = float(np.hypot(*v))
            if d < 20:
                continue
            ang = abs(math.degrees(math.atan2(v[1], v[0])))
            ang = min(ang, 180 - ang)
            if ang > 60 or not 0.3 < d / ref_dist < 1.8:
                continue
            expect = ref_area * (d / ref_dist) ** 2
            ratio = (a1 + a2) / 2 / expect
            if not 0.45 < ratio < 2.2:
                continue
            score = math.sqrt(a1 + a2) * math.exp(-2 * abs(math.log(ratio)))
            if best is None or score > best[0]:
                left, right = (p1, p2) if p1[0] < p2[0] else (p2, p1)
                best = (score, left, right)
    return best


def _similarity(src_a, src_b, dst_a, dst_b):
    """Returns f(points) mapping canonical -> image, and the scale."""
    s = complex(*(src_b - src_a))
    d = complex(*(dst_b - dst_a))
    m = d / s

    def f(p):
        z = complex(*(p - src_a)) * m + complex(*dst_a)
        return np.array([z.real, z.imag])
    return f, abs(m), m


def _fraction(a, centre, r, colours, tol):
    """Share of the pixels within r of centre that are near one of the colours."""
    x, y = np.round(centre).astype(int)
    r = max(3, int(r))
    win = a[max(0, y - r):y + r + 1, max(0, x - r):x + r + 1]
    if win.size == 0:
        return 0.0
    d = np.min(np.stack([np.abs(win[..., :3] - c).sum(-1) for c in colours]), axis=0)
    return float(((d < tol) & (win[..., 3] > 200)).mean())


def _locate(a, pr):
    nostrils = _blobs(_mask_near(a, pr.nostril, pr.nose_tol))
    pair = _best_pair(nostrils, pr.nose_area, float(np.hypot(*(pr.nose_r - pr.nose_l))))
    eyes = _blobs(_mask_near(a, pr.eye, pr.eye_tol))
    by_nostrils = pair is not None
    if by_nostrils:
        f, scale, rot = _similarity(pr.nose_l, pr.nose_r, pair[1], pair[2])
    else:
        pair = _best_pair(eyes, pr.eye_area, float(np.hypot(*(pr.eye_r - pr.eye_l))))
        if not pair:
            return None
        f, scale, rot = _similarity(pr.eye_l, pr.eye_r, pair[1], pair[2])
    if not 0.2 < scale < 2.0:
        return None
    # It must really be the cow: peach muzzle below the eyes and grey/brown fur around them
    u = scale * pr.unit
    if _fraction(a, f(pr.muzzle), 40 * u, MUZZLE[:1], 45) < 0.2:
        return None
    if _fraction(a, (f(pr.eye_l) + f(pr.eye_r)) / 2, 45 * u, FUR, 60) < 0.25:
        return None
    # ...and a second landmark must back it up, or cow-print lions/monkeys pass too
    horn_ok = max(_fraction(a, f(p), 30 * u, [HORN], 90) for p in pr.horns) > 0.05
    if by_nostrils:
        other_ok = max(_fraction(a, f(p), 15 * u, [pr.eye], pr.eye_tol) for p in (pr.eye_l, pr.eye_r)) > 0.1
    else:
        other_ok = max(_fraction(a, f(p), 25 * u, [pr.nostril], pr.nose_tol) for p in (pr.nose_l, pr.nose_r)) > 0.02
    if not (horn_ok or other_ok):
        return None
    centre = f(pr.eye_l)
    # Snap to the real left eye when there is a plausible blob close by
    for area, p, _ in eyes:
        if np.hypot(*(p - centre)) < 30 * scale * pr.unit and 0.25 < area / (pr.eye_area * scale ** 2) < 3:
            centre = p
            break
    # how well the predicted landmarks land on this profile's eye/nostril colours
    fit = sum(_fraction(a, f(p), 12 * u, [pr.nostril], pr.nose_tol) for p in (pr.nose_l, pr.nose_r)) + sum(_fraction(a, f(p), 12 * u, [pr.eye], pr.eye_tol) for p in (pr.eye_l, pr.eye_r))
    return centre, scale * pr.unit, rot, f, pr.horns, [f(p) for p in pr.face], pr, fit


def locate(orig):
    """Returns (eye_centre_xy, scale, rotation complex, canonical->image fn, canonical horns)
    or None if no cow face. `scale` is in profile A units."""
    a = np.asarray(orig.convert("RGBA")).astype(np.float32)
    found = [loc for loc in (_locate(a, pr) for pr in PROFILES) if loc]
    return max(found, key=lambda loc: loc[7]) if found else None


