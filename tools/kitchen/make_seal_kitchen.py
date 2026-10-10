"""
Generates the seal 🦭 "Emoji Kitchen" set. Google's Emoji Kitchen has no seal, so the mixes are
built here from Google's Noto Color Emoji artwork (Apache 2.0) plus Google's cow mixes, copying
the technique Google's artists used for the cow with each partner (kitchen_techniques.json):

  smiley     the seal takes the face's eyes, mouth and extras (tears, hearts, glasses, hands) and
             keeps its own nose and whiskers; coloured faces (🥵 🥶 🤢) tint the seal
  accessory  the cow wears or holds the partner (🎧 👑 🍳): the partner is lifted off the cow mix
             and put on the seal, lined up by the eyes
  print      the partner drawn in cow print (🍄 ⚽ ♈): Google's drawing, recoloured to seal print
  material   the cow made of the partner (🍓 🐯 👾): the seal's shape filled with the partner
  icon       a small cow face printed on the partner (🛑 🥇 📦): a small seal face instead
  scene      cows in a scene (🌅 🛸): a little seal in the scene

The partners are the same as the cow set's, so names and search keywords are reused.

    python tools/kitchen/make_seal_kitchen.py              # writes tools/kitchen/out/seal_kitchen/
    python tools/kitchen/make_seal_kitchen.py --install    # writes java/assets/seal_kitchen/
    python tools/kitchen/make_seal_kitchen.py --only 1f602 1f3a7   # just these partners

Preview pages go to tools/kitchen/preview/. Noto SVGs are cached in tools/kitchen/noto/.
"""
import argparse
import colorsys
import concurrent.futures
import copy
import io
import json
import os
import re
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

import numpy as np
import resvg_py
from PIL import Image, ImageDraw, ImageFilter, ImageFont
from scipy import ndimage as nd

import cow_face

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
COW_DIR = os.path.join(REPO, "java", "assets", "cow_kitchen")
PARTNERS = os.path.join(REPO, "java", "assets", "kitchen", "cow.json")
TECHNIQUES = os.path.join(HERE, "kitchen_techniques.json")
NOTO_DIR = os.path.join(HERE, "noto")
PREVIEW_DIR = os.path.join(HERE, "preview")
NOTO_URL = "https://raw.githubusercontent.com/googlefonts/noto-emoji/main/2D/svg/emoji_u{}.svg"

SEAL = "1f9ad"
SIZE = 534  # same as Emoji Kitchen's images

NS = "http://www.w3.org/2000/svg"
ET.register_namespace("", NS)
ET.register_namespace("xlink", "http://www.w3.org/1999/xlink")
GRADIENTS = {f"{{{NS}}}{t}" for t in ("defs", "radialGradient", "linearGradient")}

# Landmarks on Noto's 128-unit grid
SEAL_EYES = ((28.8, 22.66), (50.08, 27.46))
SMILEY_EYES = ((38.0, 60.0), (90.0, 60.0))  # Noto smileys share one face grid
SEAL_FACE_CENTRE = (37.0, 30.0)

# Parts of the seal SVG, by position in its flattened element list
SEAL_EYES_AND_MOUTH = [3, 4, 7]
SEAL_ALL_BUT_FACE = [1, 24, 25, 26, 29, 30, 31, 32, 33, 34]
SEAL_COLOURS = ["#CAD8DD", "#A8BAC1", "#78909C", "#728E9C"]

# Cow print -> seal print: (cow colour, seal colour, match radius)
PRINT_ANCHORS = [
    ("#DFDFDF", "#CAD8DD", 24), ("#C6C6C6", "#B3C4CB", 16), ("#ADADB8", "#9DB1BA", 16),
    ("#4D4D4D", "#7D97A3", 20), ("#606060", "#8CA3AE", 14), ("#2F2F2F", "#68838F", 12),
]

# Partners with faces of their own: made-of-the-partner seals only take their colours, not
# their eyes and mouths. Emoji Kitchen's animals, plus a few faces that aren't called "face".
HAS_FACE = {"1f435", "1f981", "1f42f", "1f431", "1f436", "1f43a", "1f43b", "1f428", "1f43c",
            "1f42d", "1f430", "1f98a", "1f99d", "1f42e", "1f437", "1f984", "1f409", "1f422",
            "1f40d", "1f438", "1f429", "1f40e", "1f410", "1f98c", "1f999", "1f9a5", "1f994",
            "1f987", "1f989", "1f426", "1f414", "1f99a", "1fabf", "1f54a-fe0f", "1f9a9", "1f9a8",
            "1f427", "1f988", "1f433", "1f41f", "1f99e", "1f980", "1f419", "1f982", "1f577-fe0f",
            "1f40c", "1f997", "1fab2", "1fab3", "1f41d", "1f41e", "1f98b", "1f480", "1f47b",
            "1f47d", "1f47e", "1f916", "1f4a9", "1f383", "1f479", "1f47a", "1f9a0", "2603-fe0f", "26c4"}

# Everything that is cow in a cow mix, for lifting the partner off it
COW_COLOURS = ["#DFDFDF", "#C6C6C6", "#ADADB8", "#BC8954", "#996A3F", "#FFD3B0", "#FEEBDA",
               "#FFB903", "#FFBD16", "#896024", "#422B0D", "#6D4938", "#0A0A0A", "#2E2F31",
               "#ACADB6", "#E0E0E0"]


def rgb(h):
    return np.array([int(h[i:i + 2], 16) for i in (1, 3, 5)], np.float32)


# ---- svg helpers --------------------------------------------------------------------------------

def noto_names(codepoint):
    """Noto's file name for an emoji: no FE0F, each part at least 4 hex digits (© is 00a9)."""
    parts = codepoint.split("-")
    stripped = [p.zfill(4) for p in parts if p != "fe0f"]
    return ["_".join(stripped), "_".join(p.zfill(4) for p in parts)]


def fetch_svg(codepoint):
    """Path of the cached Noto SVG, downloading it if needed; None if Noto has no such file."""
    os.makedirs(NOTO_DIR, exist_ok=True)
    for name in noto_names(codepoint):
        path = os.path.join(NOTO_DIR, f"{name}.svg")
        if os.path.exists(path):
            return path
        try:
            urllib.request.urlretrieve(NOTO_URL.format(name), path)
            return path
        except urllib.error.HTTPError:
            continue
    return None


def render(svg_text, size=SIZE):
    png = resvg_py.svg_to_bytes(svg_string=svg_text, width=size, height=size)
    return Image.open(io.BytesIO(bytes(png))).convert("RGBA")


def to_text(root):
    return ET.tostring(root, encoding="unicode")


def load(path, id_prefix=""):
    text = open(path, encoding="utf-8").read()
    if id_prefix:
        for i in set(re.findall(r'id="([^"]+)"', text)):
            text = (text.replace(f'id="{i}"', f'id="{id_prefix}{i}"')
                    .replace(f"#{i})", f"#{id_prefix}{i})")
                    .replace(f'"#{i}"', f'"#{id_prefix}{i}"'))
    return ET.fromstring(text)


def without(root, indices):
    flat = list(root.iter())[1:]
    drop = {id(flat[i]) for i in indices}
    for parent in root.iter():
        for c in list(parent):
            if id(c) in drop:
                parent.remove(c)
    return root


def unwrap(root):
    """Lifts the contents of a lone wrapper group up to the top level (many Noto SVGs have one)."""
    while True:
        drawn = [c for c in root if c.tag not in GRADIENTS]
        if len(drawn) != 1 or drawn[0].tag != f"{{{NS}}}g":
            return root
        g = drawn[0]
        attrs = dict(g.attrib)
        attrs.pop("id", None)
        if attrs and len(g) == 1:
            child = g[0]
            if child.tag != f"{{{NS}}}g":
                return root
            # push the wrapper's settings down into the group inside it, then keep unwrapping
            for k, v in attrs.items():
                if k == "transform":
                    child.set(k, f"{v} {child.get(k, '')}".strip())
                elif k not in child.attrib:
                    child.set(k, v)
            at = list(root).index(g)
            root.remove(g)
            root.insert(at, child)
            continue
        at = list(root).index(g)
        root.remove(g)
        for n, c in enumerate(list(g)):
            if attrs:
                wrapper = ET.Element(g.tag, attrs)
                wrapper.append(c)
                c = wrapper
            root.insert(at + n, c)


def bbox_alone(root, index):
    """Bounding box (0..128 units) and mean colour of one top-level drawable, rendered alone."""
    r = copy.deepcopy(root)
    for n, c in enumerate(list(r)):
        if n != index and c.tag not in GRADIENTS:
            r.remove(c)
    a = np.asarray(render(to_text(r), 128)).astype(np.float32)
    mask = a[..., 3] > 10
    if not mask.any():
        return None, None
    ys, xs = np.nonzero(mask)
    return (xs.min(), ys.min(), xs.max(), ys.max()), a[mask][:, :3].mean(0)


def similarity(src, dst):
    (sx1, sy1), (sx2, sy2) = src
    (dx1, dy1), (dx2, dy2) = dst
    m = complex(dx2 - dx1, dy2 - dy1) / complex(sx2 - sx1, sy2 - sy1)
    a, b = m.real, m.imag
    e = dx1 - (a * sx1 - b * sy1)
    f = dy1 - (b * sx1 + a * sy1)
    return a, b, e, f


def svg_matrix(src, dst):
    a, b, e, f = similarity(src, dst)
    return f"matrix({a:.5f},{b:.5f},{-b:.5f},{a:.5f},{e:.4f},{f:.4f})"


def hexmix(h, colour, t):
    c = rgb(h)
    return "#" + "".join(f"{round(x + (y - x) * t):02X}" for x, y in zip(c, colour))


def seal_svg(tint=None, amount=0.45):
    text = open(fetch_svg(SEAL), encoding="utf-8").read()
    if tint is not None:
        for c in SEAL_COLOURS:
            text = re.sub(c, hexmix(c, tint, amount), text, flags=re.IGNORECASE)
    return ET.fromstring(text)


def seal_face(ink=None):
    """Just the seal's eyes, nose, mouth and whiskers, optionally in another colour."""
    face = without(seal_svg(), SEAL_ALL_BUT_FACE)
    if ink:
        face = ET.fromstring(re.sub("#212121", ink, to_text(face), flags=re.IGNORECASE))
    return face


def fit_square(img):
    """Crops to the drawing and pads it back to a centred square of SIZE."""
    a = np.asarray(img)[..., 3]
    ys, xs = np.nonzero(a > 8)
    box = img.crop((xs.min(), ys.min(), xs.max() + 1, ys.max() + 1))
    side = int(max(box.size) * 1.04)
    sq = Image.new("RGBA", (side, side))
    sq.alpha_composite(box, ((side - box.width) // 2, (side - box.height) // 2))
    return sq.resize((SIZE, SIZE), Image.LANCZOS)


def solid_colour(img):
    a = np.asarray(img).astype(np.float32)
    px = a[a[..., 3] > 200][:, :3]
    sat = px.max(1) - px.min(1)
    return (px[sat > 40] if (sat > 40).sum() > 400 else px).mean(0)


# ---- techniques ---------------------------------------------------------------------------------

def split_face(path):
    """For a smiley: (face base colour, gradient defs, feature elements), or None if it isn't one."""
    root = unwrap(load(path, "p_"))
    items = list(root)
    base_colour, features, in_base = None, [], True
    for n, el in enumerate(items):
        if el.tag in GRADIENTS:
            continue
        bb, colour = bbox_alone(root, n)
        if bb is None:
            continue
        w, h = bb[2] - bb[0], bb[3] - bb[1]
        if in_base and w >= 100 and abs(w - h) <= 14:
            if base_colour is None:
                base_colour = colour
            continue
        in_base = False
        features.append(el)
    if base_colour is None or not features:
        return None
    return base_colour, [c for c in items if c.tag in GRADIENTS], features


def smiley(entry, path):
    split = split_face(path)
    if split is None:
        return None
    base_colour, defs, features = split
    hue, _, sat = colorsys.rgb_to_hls(*(base_colour / 255).tolist())
    yellow = 0.10 <= hue <= 0.17 and sat > 0.5
    seal = without(seal_svg(None if yellow else base_colour), SEAL_EYES_AND_MOUTH)
    for d in defs:
        seal.insert(0, d)
    g = ET.SubElement(seal, f"{{{NS}}}g", {"transform": svg_matrix(SMILEY_EYES, SEAL_EYES)})
    for f in features:
        g.append(f)
    return render(to_text(seal))


def lift_partner(entry, path):
    """Google's cow mix with the cow removed: (partner layer, cow eye positions), or None."""
    cow = Image.open(os.path.join(COW_DIR, entry["file"])).convert("RGBA")
    loc = cow_face.locate(cow)
    if loc is None:
        return None
    a = np.asarray(cow).astype(np.float32)
    dist = np.min(np.stack([np.abs(a[..., :3] - rgb(c)).sum(-1) for c in COW_COLOURS]), axis=0)
    cowish = dist < 45
    # the cow is the cow-coloured region connected to its face; anything else is the partner
    lab, _ = nd.label(cowish)
    # the cow's head area: its face outline grown by half, to take in the ears and horns
    poly = np.array(loc[5])
    centre = poly.mean(0)
    face_poly = Image.new("L", cow.size)
    ImageDraw.Draw(face_poly).polygon([tuple(centre + (p - centre) * 1.5) for p in poly], fill=255)
    touching = np.unique(lab[(np.asarray(face_poly) > 0) & cowish])
    cow_mask = np.isin(lab, touching[touching > 0])
    # ...and any other little cow faces in the picture (cow-coloured blobs with fur and muzzle)
    idx = np.arange(lab.max() + 1)
    muzzle = (np.abs(a[..., :3] - rgb("#FFD3B0")).sum(-1) < 45)
    fur = np.min(np.stack([np.abs(a[..., :3] - rgb(c)).sum(-1) for c in ("#DFDFDF", "#BC8954")]), axis=0) < 45
    small_cows = (nd.sum(muzzle, lab, idx) > 120) & (nd.sum(fur, lab, idx) > 120)
    small_cows[0] = False
    cow_mask |= small_cows[lab]
    cow_mask = nd.binary_dilation(cow_mask, iterations=2) & (dist < 120)  # take the soft edges too
    layer = a.copy()
    layer[..., 3] = np.where(cow_mask, 0, layer[..., 3])
    # if hardly anything of the partner survives, this technique doesn't suit it
    partner_area = (np.asarray(render(open(path, encoding="utf-8").read()))[..., 3] > 40).sum()
    if (layer[..., 3] > 40).sum() < 0.3 * partner_area * (loc[1] * 0.7) ** 2:
        return None
    layer = Image.fromarray(layer.astype(np.uint8), "RGBA")
    f, pr = loc[3], loc[6]
    return layer, [tuple(f(pr.eye_l)), tuple(f(pr.eye_r))]


def place(layer, src_eyes, dst_eyes, big):
    """Moves a layer so src_eyes land on dst_eyes, on a big x big canvas."""
    a_, b_, e_, f_ = similarity(src_eyes, dst_eyes)
    # PIL wants the inverse map (output -> input)
    det = a_ * a_ + b_ * b_
    ia, ib = a_ / det, b_ / det
    inv = (ia, ib, -(ia * e_ + ib * f_), -ib, ia, -(-ib * e_ + ia * f_))
    return layer.transform((big, big), Image.AFFINE, inv, resample=Image.BICUBIC)


def accessory(entry, path):
    """Lifts the partner off Google's cow mix and puts it on the seal, lined up by the eyes."""
    lifted = lift_partner(entry, path)
    if lifted is None:
        return None
    layer, cow_eyes = lifted
    # on a big canvas so nothing gets cut off
    big = SIZE * 3
    off = SIZE
    seal_eyes = [(x / 128 * SIZE + off, y / 128 * SIZE + off) for x, y in SEAL_EYES]
    moved = place(layer, cow_eyes, seal_eyes, big)

    canvas = Image.new("RGBA", (big, big))
    canvas.alpha_composite(render(to_text(seal_svg())), (int(off), int(off)))
    canvas.alpha_composite(moved)
    return fit_square(canvas)


def print_recolour(entry, path, anchors=PRINT_ANCHORS):
    """Google's cow-print drawing of the partner, with the cow print turned into seal print."""
    cow = Image.open(os.path.join(COW_DIR, entry["file"])).convert("RGBA")
    a = np.asarray(cow).astype(np.float32)
    flat = a[..., :3].reshape(-1, 3)
    cols, inv = np.unique(flat, axis=0, return_inverse=True)
    src = np.stack([rgb(s) for s, _, _ in anchors])
    dst = np.stack([rgb(d) for _, d, _ in anchors])
    rad = np.array([r for _, _, r in anchors], np.float32)
    d = np.linalg.norm(cols[:, None] - src[None], axis=2)
    k = d.argmin(1)
    w = np.clip((2 * rad[k] - d[np.arange(len(cols)), k]) / rad[k], 0, 1)[:, None]
    out = np.clip(cols * (1 - w) + (dst[k] + cols - src[k]) * w, 0, 255)
    a[..., :3] = out[inv.reshape(-1)].reshape(a.shape[0], a.shape[1], 3)
    return Image.fromarray(a.astype(np.uint8), "RGBA")


def material(entry, path):
    """The seal's shape filled with the partner, keeping the seal's shading and face."""
    seal = render(to_text(seal_svg()))
    s = np.asarray(seal).astype(np.float32)
    alpha = s[..., 3:4] / 255
    lum = s[..., :3] @ np.array([0.299, 0.587, 0.114], np.float32)
    shade = np.clip(lum / (rgb("#CAD8DD") @ np.array([0.299, 0.587, 0.114])), 0.45, 1.15)[..., None]

    partner = render(open(path, encoding="utf-8").read())
    p = np.asarray(partner)
    ys, xs = np.nonzero(p[..., 3] > 30)
    crop = partner.crop((xs.min(), ys.min(), xs.max() + 1, ys.max() + 1))
    # fill the gaps in the partner with its main colour so the seal has no holes
    base = Image.new("RGBA", crop.size, tuple(int(v) for v in solid_colour(partner)) + (255,))
    base.alpha_composite(crop)
    if entry["codepoint"] in HAS_FACE or "face" in entry["name"]:
        # keep the partner's colours but not its eyes and mouth
        base = base.filter(ImageFilter.GaussianBlur(base.width * 0.06))
    sy, sx = np.nonzero(s[..., 3] > 30)
    bw, bh = sx.max() - sx.min() + 1, sy.max() - sy.min() + 1
    scale = max(bw / base.width, bh / base.height)
    tex = base.resize((int(base.width * scale) + 1, int(base.height * scale) + 1), Image.LANCZOS)
    tx = sx.min() - (tex.width - bw) // 2
    ty = sy.min() - (tex.height - bh) // 2
    canvas = Image.new("RGBA", seal.size)
    canvas.alpha_composite(tex, (max(0, tx), max(0, ty)), (max(0, -tx), max(0, -ty)))
    t = np.asarray(canvas).astype(np.float32)[..., :3]

    out = np.zeros_like(s)
    out[..., :3] = np.clip(t * shade, 0, 255)
    out[..., 3] = s[..., 3]
    img = Image.fromarray(out.astype(np.uint8), "RGBA")

    # dark materials get a light face so it still shows
    fx, fy = (int(v / 128 * SIZE) for v in SEAL_FACE_CENTRE)
    around = t[fy - 40:fy + 40, fx - 60:fx + 60].mean() if t.size else 128
    img.alpha_composite(render(to_text(seal_face("#F5F5F5" if around < 90 else None))))
    return img


def icon(entry, path):
    """The partner with a small seal face printed on it, in a colour that stands out."""
    partner = render(open(path, encoding="utf-8").read())
    p = np.asarray(partner).astype(np.float32)
    ys, xs = np.nonzero(p[..., 3] > 40)
    cx, cy = xs.mean(), ys.mean()
    span = min(xs.max() - xs.min(), ys.max() - ys.min())
    r = int(span * 0.18)
    patch = p[max(0, int(cy) - r):int(cy) + r, max(0, int(cx) - r):int(cx) + r]
    under = patch[patch[..., 3] > 100][:, :3].mean(0) if (patch[..., 3] > 100).any() else np.array([255.0] * 3)
    light = under @ np.array([0.299, 0.587, 0.114]) > 140
    ink = "#3A4A52" if light else "#FFFFFF"

    face = seal_face(ink)
    ring = ET.SubElement(face, f"{{{NS}}}ellipse", {
        "cx": str(SEAL_FACE_CENTRE[0]), "cy": str(SEAL_FACE_CENTRE[1] - 2), "rx": "27", "ry": "21",
        "fill": "none", "stroke": ink, "stroke-width": "3"})
    face.remove(ring)
    face.insert(0, ring)
    k = span / SIZE * 128 * 0.42 / 54  # icon about 42% of the object's size
    g = ET.Element(f"{{{NS}}}g", {"transform": f"translate({cx / SIZE * 128:.2f},{cy / SIZE * 128:.2f}) "
                                               f"scale({k:.3f}) translate({-SEAL_FACE_CENTRE[0]},{-SEAL_FACE_CENTRE[1] + 2})"})
    for c in list(face):
        face.remove(c)
        g.append(c)
    face.append(g)
    partner.alpha_composite(render(to_text(face)))
    return partner


def scene(entry, path):
    """A little seal joining the partner's scene."""
    partner = render(open(path, encoding="utf-8").read())
    seal = render(to_text(seal_svg()), int(SIZE * 0.42))
    partner.alpha_composite(seal, (int(SIZE * 0.55), int(SIZE * 0.56)))
    return partner


TECHNIQUE = {"smiley": smiley, "accessory": accessory, "print": print_recolour,
             "material": material, "icon": icon, "scene": scene}


def make(entry, technique):
    path = fetch_svg(entry["codepoint"])
    if path is None:
        raise FileNotFoundError(f"no Noto artwork for {entry['codepoint']}")
    # scene works for anything, so it is the fallback (material would show a partner's face)
    order = [technique] + ([] if technique == "scene" else ["scene"])
    for t in order:
        img = TECHNIQUE[t](entry, path)
        if img is not None:
            return img, t
    raise RuntimeError("no technique worked")


# ---- main ---------------------------------------------------------------------------------------

def sheet(items, out_path, cols=10, cell=150):
    rows = (len(items) + cols - 1) // cols
    font = ImageFont.load_default()
    s = Image.new("RGBA", (cols * cell, rows * (cell + 14)), (60, 60, 64, 255))
    d = ImageDraw.Draw(s)
    for n, (label, img) in enumerate(items):
        x, y = (n % cols) * cell, (n // cols) * (cell + 14)
        s.alpha_composite(img.resize((cell - 10, cell - 10), Image.LANCZOS), (x + 5, y + 5))
        d.text((x + 4, y + cell - 4), label, fill=(220, 220, 220, 255), font=font)
    s.convert("RGB").save(out_path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--install", action="store_true", help="write into java/assets/seal_kitchen/")
    ap.add_argument("--only", nargs="*", help="codepoints to generate (default: all)")
    args = ap.parse_args()
    out_dir = os.path.join(REPO, "java", "assets", "seal_kitchen") if args.install \
        else os.path.join(HERE, "out", "seal_kitchen")
    os.makedirs(out_dir, exist_ok=True)
    os.makedirs(PREVIEW_DIR, exist_ok=True)

    partners = json.load(open(PARTNERS, encoding="utf-8"))
    techniques = json.load(open(TECHNIQUES, encoding="utf-8"))
    if args.only:
        partners = [e for e in partners if e["codepoint"] in args.only]
    with concurrent.futures.ThreadPoolExecutor(16) as pool:
        list(pool.map(lambda e: fetch_svg(e["codepoint"]), partners))
    fetch_svg(SEAL)

    entries, previews, failed = [], [], []
    for n, e in enumerate(partners, 1):
        try:
            img, used = make(e, techniques.get(e["codepoint"], "material"))
        except Exception as ex:  # noqa: BLE001
            failed.append((e["emoji"], str(ex)))
            continue
        file = f"u{SEAL}_u{e['codepoint']}.png"
        img.save(os.path.join(out_dir, file), optimize=True)
        entries.append({k: e[k] for k in ("emoji", "codepoint", "name", "description", "keywords", "category", "order")}
                       | {"alt": e["alt"], "file": file})
        previews.append((f"{n} {used}", img))
        if n % 50 == 0:
            print(f"  {n}/{len(partners)}")

    if not args.only:
        with open(os.path.join(out_dir, "seal_kitchen.json"), "w", encoding="utf-8") as f:
            json.dump(entries, f, ensure_ascii=False, indent=1)
    prefix = "seal_only" if args.only else "seal"
    for p in range(0, len(previews), 100):
        sheet(previews[p:p + 100], os.path.join(PREVIEW_DIR, f"{prefix}_{p // 100 + 1}.png"))
    print(f"made {len(entries)} seal mixes in {out_dir}; failed: {failed}")


if __name__ == "__main__":
    main()
