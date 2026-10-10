"""
Generates the manatee "Emoji Kitchen" set. There is no manatee emoji, but Google drew one for the
cow + 🐟 mix (a "sea cow"); that drawing is the base. For every partner the manatee copies the
technique Google's artists used for the cow (kitchen_techniques.json), the same as the seal:

  smiley     the manatee takes the face's eyes, mouth and extras; coloured faces tint it
  accessory  the partner is lifted off Google's cow mix and put on the manatee's head
  print      Google's cow-print drawing of the partner, recoloured to manatee blue
  material   the manatee's shape filled with the partner, keeping its face
  icon       a small manatee printed on the partner
  scene      a little manatee in the partner's scene

    python tools/kitchen/make_manatee_kitchen.py              # writes tools/kitchen/out/manatee_kitchen/
    python tools/kitchen/make_manatee_kitchen.py --install    # writes java/assets/manatee_kitchen/
    python tools/kitchen/make_manatee_kitchen.py --only 1f602 1f3a7
"""
import argparse
import colorsys
import concurrent.futures
import json
import os
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage as nd

import make_seal_kitchen as K
from make_seal_kitchen import NS, SIZE, fetch_svg, fit_square, render, rgb, solid_colour, to_text

NAME = "manatee"
BASE = os.path.join(K.COW_DIR, "u1f42e_u1f41f.png")  # Google's cow + fish "sea cow"

# Landmarks on the manatee drawing (534 px)
EYE = (82, 130)
MOUTH = (78, 180)
FACE_EYES = ((50, 126), (124, 118))  # where a pair of eyes goes on its head (for expressions)
BODY = "#37A0D0"                     # mid body blue
DARK = "#1B65A6"                     # flippers, tail and face lines
HEAD_BOX = (0, 70, 210, 205)         # x0, y0, x1, y1 of the head

# Cow print -> manatee print
PRINT_ANCHORS = [
    ("#DFDFDF", "#4AAFDB", 24), ("#C6C6C6", "#3AA0D1", 16), ("#ADADB8", "#2E93C8", 16),
    ("#4D4D4D", "#1E74B2", 20), ("#606060", "#2580BB", 14), ("#2F2F2F", "#18609E", 12),
]


def base():
    return Image.open(BASE).convert("RGBA")


def lum(a):
    return a[..., :3] @ np.array([0.299, 0.587, 0.114], np.float32)


def face_marks():
    """Mask of the manatee's eye and mouth (the parts an expression replaces)."""
    a = np.asarray(base()).astype(np.float32)
    h, w = a.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w]
    eye = (lum(a) < 60) & (np.hypot(xx - EYE[0], yy - EYE[1]) < 22) & (a[..., 3] > 0)
    dark = np.abs(a[..., :3] - rgb(DARK)).sum(-1) < 70
    mouth = dark & (np.abs(xx - MOUTH[0]) < 18) & (np.abs(yy - MOUTH[1]) < 22)
    return nd.binary_dilation(eye | mouth, iterations=2)


def without_face():
    """The manatee with its eye and mouth painted over in the surrounding body colour."""
    img = base()
    a = np.asarray(img).copy()
    hole = face_marks() & (a[..., 3] > 0)
    _, (iy, ix) = nd.distance_transform_edt(hole, return_indices=True)
    a[hole] = a[iy[hole], ix[hole]]
    return Image.fromarray(a, "RGBA")


def tinted(img, colour, amount=0.75):
    """Shifts the manatee's blues towards a colour, keeping its shading."""
    a = np.asarray(img).astype(np.float32)
    l = lum(a)[..., None] / lum(rgb(BODY)[None])[0]
    target = np.clip(colour[None, None] * l, 0, 255)
    a[..., :3] = a[..., :3] * (1 - amount) + target * amount
    return Image.fromarray(a.astype(np.uint8), "RGBA")


# ---- techniques ---------------------------------------------------------------------------------

def smiley(entry, path):
    split = K.split_face(path)
    if split is None:
        return None
    base_colour, defs, features = split
    hue, _, sat = colorsys.rgb_to_hls(*(base_colour / 255).tolist())
    yellow = 0.10 <= hue <= 0.17 and sat > 0.5
    img = without_face()
    if not yellow:
        img = tinted(img, base_colour)
    svg = ET.Element(f"{{{NS}}}svg", {"viewBox": "0 0 128 128"})
    for d in defs:
        svg.append(d)
    eyes128 = [(x / SIZE * 128, y / SIZE * 128) for x, y in FACE_EYES]
    g = ET.SubElement(svg, f"{{{NS}}}g", {"transform": K.svg_matrix(K.SMILEY_EYES, eyes128)})
    for f in features:
        g.append(f)
    img.alpha_composite(render(to_text(svg)))
    return img


def accessory(entry, path):
    lifted = K.lift_partner(entry, path)
    if lifted is None:
        return None
    layer, cow_eyes = lifted
    big, off = SIZE * 3, SIZE
    moved = K.place(layer, cow_eyes, [(x + off, y + off) for x, y in FACE_EYES], big)
    canvas = Image.new("RGBA", (big, big))
    canvas.alpha_composite(base(), (off, off))
    canvas.alpha_composite(moved)
    return fit_square(canvas)


def print_recolour(entry, path):
    return K.print_recolour(entry, path, PRINT_ANCHORS)


def material(entry, path):
    """The manatee's shape filled with the partner, keeping its shading and face."""
    m = np.asarray(base()).astype(np.float32)
    shade = np.clip(lum(m) / lum(rgb(BODY)[None])[0], 0.5, 1.25)[..., None]

    partner = render(open(path, encoding="utf-8").read())
    p = np.asarray(partner)
    ys, xs = np.nonzero(p[..., 3] > 30)
    crop = partner.crop((xs.min(), ys.min(), xs.max() + 1, ys.max() + 1))
    fill = Image.new("RGBA", crop.size, tuple(int(v) for v in solid_colour(partner)) + (255,))
    fill.alpha_composite(crop)
    if entry["codepoint"] in K.HAS_FACE or "face" in entry["name"]:
        fill = fill.filter(ImageFilter.GaussianBlur(fill.width * 0.06))
    sy, sx = np.nonzero(m[..., 3] > 30)
    bw, bh = sx.max() - sx.min() + 1, sy.max() - sy.min() + 1
    scale = max(bw / fill.width, bh / fill.height)
    tex = fill.resize((int(fill.width * scale) + 1, int(fill.height * scale) + 1), Image.LANCZOS)
    tx, ty = sx.min() - (tex.width - bw) // 2, sy.min() - (tex.height - bh) // 2
    canvas = Image.new("RGBA", (SIZE, SIZE))
    canvas.alpha_composite(tex, (max(0, tx), max(0, ty)), (max(0, -tx), max(0, -ty)))
    t = np.asarray(canvas).astype(np.float32)[..., :3]

    out = np.zeros_like(m)
    out[..., :3] = np.clip(t * shade, 0, 255)
    out[..., 3] = m[..., 3]
    # put the face back on top: eye, mouth, nostril and wrinkles, light on dark materials
    marks = face_marks()
    x0, y0, x1, y1 = HEAD_BOX
    head = np.zeros(marks.shape, bool)
    head[y0:y1, x0:x1] = True
    lines = head & (np.abs(m[..., :3] - rgb(DARK)).sum(-1) < 70)
    around = t[y0:y1, x0:x1].mean()
    ink = np.array([245, 245, 245], np.float32) if around < 90 else np.array([20, 40, 60], np.float32)
    for mask, strength in ((lines, 0.55), (marks, 1.0)):
        out[..., :3] = np.where(mask[..., None], out[..., :3] * (1 - strength) + ink * strength, out[..., :3])
    return Image.fromarray(out.astype(np.uint8), "RGBA")


def icon(entry, path):
    """The partner with a small manatee silhouette printed on it."""
    partner = render(open(path, encoding="utf-8").read())
    p = np.asarray(partner).astype(np.float32)
    ys, xs = np.nonzero(p[..., 3] > 40)
    cx, cy = xs.mean(), ys.mean()
    span = min(xs.max() - xs.min(), ys.max() - ys.min())
    r = int(span * 0.2)
    patch = p[max(0, int(cy) - r):int(cy) + r, max(0, int(cx) - r):int(cx) + r]
    under = patch[patch[..., 3] > 100][:, :3].mean(0) if (patch[..., 3] > 100).any() else np.array([255.0] * 3)
    light = under @ np.array([0.299, 0.587, 0.114]) > 140
    ink = (58, 74, 82) if light else (255, 255, 255)

    m = np.asarray(base())
    sil = np.zeros_like(m)
    sil[..., :3] = ink
    sil[..., 3] = m[..., 3]
    eye = face_marks()
    sil[..., 3] = np.where(eye, 0, sil[..., 3])  # the eye shows through
    sil = fit_square(Image.fromarray(sil, "RGBA"))
    size = int(span * 0.55)
    sil = sil.resize((size, size), Image.LANCZOS)
    partner.alpha_composite(sil, (int(cx - size / 2), int(cy - size / 2)))
    return partner


def scene(entry, path):
    partner = render(open(path, encoding="utf-8").read())
    m = fit_square(base()).resize((int(SIZE * 0.46),) * 2, Image.LANCZOS)
    partner.alpha_composite(m, (int(SIZE * 0.52), int(SIZE * 0.52)))
    return partner


TECHNIQUE = {"smiley": smiley, "accessory": accessory, "print": print_recolour,
             "material": material, "icon": icon, "scene": scene}


def make(entry, technique):
    path = fetch_svg(entry["codepoint"])
    if path is None:
        raise FileNotFoundError(f"no Noto artwork for {entry['codepoint']}")
    for t in [technique] + ([] if technique == "scene" else ["scene"]):
        img = TECHNIQUE[t](entry, path)
        if img is not None:
            return img, t
    raise RuntimeError("no technique worked")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--install", action="store_true", help=f"write into java/assets/{NAME}_kitchen/")
    ap.add_argument("--only", nargs="*", help="codepoints to generate (default: all)")
    args = ap.parse_args()
    out_dir = os.path.join(K.REPO, "java", "assets", f"{NAME}_kitchen") if args.install \
        else os.path.join(K.HERE, "out", f"{NAME}_kitchen")
    os.makedirs(out_dir, exist_ok=True)
    os.makedirs(K.PREVIEW_DIR, exist_ok=True)

    partners = json.load(open(K.PARTNERS, encoding="utf-8"))
    techniques = json.load(open(K.TECHNIQUES, encoding="utf-8"))
    if args.only:
        partners = [e for e in partners if e["codepoint"] in args.only]
    with concurrent.futures.ThreadPoolExecutor(16) as pool:
        list(pool.map(lambda e: fetch_svg(e["codepoint"]), partners))

    entries, previews, failed = [], [], []
    for n, e in enumerate(partners, 1):
        try:
            img, used = make(e, techniques.get(e["codepoint"], "scene"))
        except Exception as ex:  # noqa: BLE001
            failed.append((e["emoji"], str(ex)))
            continue
        file = f"{NAME}_u{e['codepoint']}.png"
        img.save(os.path.join(out_dir, file), optimize=True)
        entries.append({k: e[k] for k in ("emoji", "codepoint", "name", "description", "keywords", "category", "order")}
                       | {"alt": f"{NAME}-{e['description']}", "file": file})
        previews.append((f"{n} {used}", img))
        if n % 50 == 0:
            print(f"  {n}/{len(partners)}")

    if not args.only:
        with open(os.path.join(out_dir, f"{NAME}_kitchen.json"), "w", encoding="utf-8") as f:
            json.dump(entries, f, ensure_ascii=False, indent=1)
    prefix = f"{NAME}_only" if args.only else NAME
    for p in range(0, len(previews), 100):
        K.sheet(previews[p:p + 100], os.path.join(K.PREVIEW_DIR, f"{prefix}_{p // 100 + 1}.png"))
    print(f"made {len(entries)} {NAME} mixes in {out_dir}; failed: {failed}")


if __name__ == "__main__":
    main()
