"""
Builds the extra keyboard themes bundled with the cow build (Half-Life, Portal, Blue Lantern).

Each theme is a FUTO Keyboard zip theme (theme.txt + background + font) written to
java/cow/assets/themes/, where the theme picker finds it automatically. A mock-up of each
keyboard is rendered to tools/themes/preview/ so you can check a change before building.

    python tools/themes/make_themes.py

Fonts are open-licence (SIL OFL) fonts from Google Fonts, downloaded once into
tools/themes/fonts/ and bundled in each zip together with their licence.
"""
import io
import math
import os
import urllib.request
import zipfile

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
OUT_DIR = os.path.join(REPO, "java", "cow", "assets", "themes")
FONT_DIR = os.path.join(HERE, "fonts")
PREVIEW_DIR = os.path.join(HERE, "preview")

W, H = 1600, 1200  # backgrounds are drawn "cover"-style, so keep the interesting part central
FONTS_URL = "https://raw.githubusercontent.com/google/fonts/main/ofl"


# ---- small helpers ------------------------------------------------------------------------------

def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def mix(a, b, t):
    """Blend hex colour a towards b by t (0..1)."""
    ca, cb = rgb(a), rgb(b)
    return "#" + "".join(f"{round(x + (y - x) * t):02X}" for x, y in zip(ca, cb))


def alpha(h, a):
    """Hex colour with an alpha byte (theme.txt uses #RRGGBBAA)."""
    return f"{h}{round(a * 255):02X}"


def font_file(folder, file):
    os.makedirs(FONT_DIR, exist_ok=True)
    path = os.path.join(FONT_DIR, file)
    lic = os.path.join(FONT_DIR, f"{folder}-OFL.txt")
    for url, dest in ((f"{FONTS_URL}/{folder}/{file}", path), (f"{FONTS_URL}/{folder}/OFL.txt", lic)):
        if not os.path.exists(dest):
            print("downloading", url)
            urllib.request.urlretrieve(url, dest)
    return path, lic


def glow(layer, radius):
    """Adds a soft bloom around the bright parts of an RGBA layer."""
    blurred = layer.filter(ImageFilter.GaussianBlur(radius))
    return Image.alpha_composite(blurred, layer)


def vertical_gradient(top, bottom):
    t = np.linspace(0, 1, H)[:, None, None]
    img = np.array(rgb(top)) * (1 - t) + np.array(rgb(bottom)) * t
    return Image.fromarray(np.repeat(img, W, axis=1).astype(np.uint8), "RGB").convert("RGBA")


# ---- backgrounds --------------------------------------------------------------------------------

def half_life_background():
    img = vertical_gradient("#1C1D20", "#0C0D0F")
    rng = np.random.default_rng(3)

    # brushed-metal grain
    grain = rng.normal(0, 5, (H, W // 4)).repeat(4, axis=1)
    arr = np.asarray(img).astype(np.float32)
    arr[..., :3] += grain[..., None]
    img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGBA")

    # faint lambda in a ring, centre stage
    mark = Image.new("RGBA", (W, H))
    d = ImageDraw.Draw(mark)
    cx, cy, r = W // 2, int(H * 0.52), 330
    orange = rgb("#F28C28") + (60,)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=orange, width=36)
    d.line([(cx - 120, cy - 230), (cx + 130, cy + 235)], fill=orange, width=58)  # long stroke
    d.line([(cx + 5, cy + 5), (cx - 140, cy + 235)], fill=orange, width=58)      # short leg
    d.line([(cx - 185, cy - 230), (cx - 95, cy - 230)], fill=orange, width=46)   # top serif
    img = Image.alpha_composite(img, glow(mark, 18))

    # hazard stripes along the bottom edge
    band = Image.new("RGBA", (W, H))
    d = ImageDraw.Draw(band)
    top = H - 70
    for x in range(-200, W + 200, 90):
        d.polygon([(x, H), (x + 45, H), (x + 45 + 70, top), (x + 70, top)], fill=rgb("#F2A51C") + (150,))
    d.rectangle([0, top - 6, W, top - 2], fill=rgb("#F2A51C") + (150,))
    img = Image.alpha_composite(img, band)
    return img.convert("RGB")


def portal_ring(size, colour):
    """A glowing elliptical portal, returned as an RGBA layer of the given (w, h)."""
    w, h = size
    layer = Image.new("RGBA", (w, h))
    d = ImageDraw.Draw(layer)
    c = rgb(colour)
    d.ellipse([w * 0.18, h * 0.06, w * 0.82, h * 0.94], fill=mix(colour, "#FFFFFF", 0.35) + "50")
    for k, a in ((0, 255), (10, 160), (22, 70)):
        d.ellipse([w * 0.18 - k, h * 0.06 - k, w * 0.82 + k, h * 0.94 + k], outline=c + (a,), width=14)
    return glow(layer, 28)


def portal_background():
    img = vertical_gradient("#F3F5F7", "#D9DDE2")
    rng = np.random.default_rng(7)

    # test-chamber wall: off-white square panels with grey seams
    panels = Image.new("RGBA", (W, H))
    d = ImageDraw.Draw(panels)
    size = 200
    for y in range(0, H, size):
        for x in range(0, W, size):
            shade = int(rng.integers(-10, 8))
            base = 238 + shade
            d.rectangle([x + 4, y + 4, x + size - 4, y + size - 4], fill=(base, base + 1, base + 3, 120))
            d.rectangle([x, y, x + size, y + size], outline=(178, 184, 191, 200), width=4)
    img = Image.alpha_composite(img, panels)

    # blue portal on the left edge, orange on the right
    ph = int(H * 0.72)
    pw = int(ph * 0.62)
    blue = portal_ring((pw, ph), "#2A93FF")
    orange = portal_ring((pw, ph), "#FF8A1C")
    img.alpha_composite(blue, (-pw // 3, (H - ph) // 2))
    img.alpha_composite(orange, (W - pw + pw // 3, (H - ph) // 2))
    return img.convert("RGB")


def blue_lantern_background():
    img = vertical_gradient("#07112B", "#02050F")
    rng = np.random.default_rng(11)

    # stars
    stars = Image.new("RGBA", (W, H))
    d = ImageDraw.Draw(stars)
    for _ in range(420):
        x, y = rng.integers(0, W), rng.integers(0, H)
        r = rng.choice([1, 1, 1, 2, 2, 3])
        a = int(rng.integers(90, 255))
        d.ellipse([x - r, y - r, x + r, y + r], fill=(200, 225, 255, a))
    img = Image.alpha_composite(img, glow(stars, 2))

    # soft blue light from the centre
    yy, xx = np.mgrid[0:H, 0:W]
    cx, cy = W / 2, H * 0.52
    dist = np.hypot(xx - cx, yy - cy) / (H * 0.6)
    light = np.clip(1 - dist, 0, 1) ** 2.2
    rays = 0.5 + 0.5 * np.cos(np.arctan2(yy - cy, xx - cx) * 16)
    light = light * (0.75 + 0.25 * rays)
    layer = np.zeros((H, W, 4), np.float32)
    layer[..., :3] = rgb("#2F8FFF")
    layer[..., 3] = light * 170
    img = Image.alpha_composite(img, Image.fromarray(layer.astype(np.uint8), "RGBA"))

    symbol = os.path.join(HERE, "art", "blue_lantern_symbol.png")
    if os.path.exists(symbol):
        # The Blue Lantern symbol (DC's trademark, so it's only in the private build): its blue
        # shapes glow and the gaps between them show the stars behind
        size = 600
        art = np.asarray(Image.open(symbol).convert("RGBA").resize((size, size), Image.LANCZOS)).astype(np.float32)
        visible = art[..., 3] / 255
        blue = np.clip((art[..., 2] - art[..., 0] - 60) / 80, 0, 1) * visible
        emblem = np.zeros((size, size, 4), np.float32)
        emblem[..., :3] = rgb("#4FB4FF")
        emblem[..., 3] = blue * 235
        layer = Image.new("RGBA", (W, H))
        layer.alpha_composite(Image.fromarray(emblem.astype(np.uint8), "RGBA"), (int(cx - size / 2), int(cy - size / 2)))
        img = Image.alpha_composite(img, glow(layer, 10))
    else:
        # the ring of light
        ring = Image.new("RGBA", (W, H))
        d = ImageDraw.Draw(ring)
        r = 250
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=rgb("#9FD8FF") + (255,), width=26)
        d.ellipse([cx - r + 40, cy - r + 40, cx + r - 40, cy + r - 40], outline=rgb("#3FA9F5") + (150,), width=10)
        img = Image.alpha_composite(img, glow(ring, 30))
    return img.convert("RGB")


# ---- themes -------------------------------------------------------------------------------------

THEMES = [
    {
        "id": "cow_half_life",
        "name": "Half-Life",
        "dark": True,
        "font": ("sharetech", "ShareTech-Regular.ttf"),
        "background": half_life_background,
        "opacity": 0.9,
        "roundedness": 0.6,
        "surface": "#141517",       # behind the keys
        "key": "#1E1F22",           # letter keys
        "key_alpha": 0.82,
        "key_variant": "#2B2C30",   # shift, delete, ?123
        "text": "#FFA21F",          # key labels
        "hint": "#A8701E",
        "accent": "#F28C28",        # enter key
        "on_accent": "#141517",
        "accent2": "#FFC14D",
        "press": "#F28C28",
        "on_press": "#141517",
    },
    {
        "id": "cow_portal",
        "name": "Portal",
        "dark": False,
        "font": ("barlow", "Barlow-Medium.ttf"),
        "background": portal_background,
        "opacity": 0.85,
        "roundedness": 0.4,
        "surface": "#E3E6EA",
        "key": "#FFFFFF",
        "key_alpha": 0.88,
        "key_variant": "#E8EBEF",
        "text": "#2B3036",
        "hint": "#7A828B",
        "accent": "#1E8CFF",        # blue portal
        "on_accent": "#FFFFFF",
        "accent2": "#FF8A1C",       # orange portal
        "press": "#FF8A1C",
        "on_press": "#FFFFFF",
    },
    {
        "id": "cow_blue_lantern",
        "name": "Blue Lantern",
        "dark": True,
        "font": ("audiowide", "Audiowide-Regular.ttf"),
        "background": blue_lantern_background,
        "opacity": 0.95,
        "roundedness": 1.0,
        "scale_text": 0.9,
        "surface": "#050A1A",
        "key": "#0B1836",
        "key_alpha": 0.72,
        "key_variant": "#12275A",
        "text": "#BFE6FF",
        "hint": "#5FA8E0",
        "accent": "#2F8FFF",
        "on_accent": "#00122E",
        "accent2": "#9FD8FF",
        "press": "#3FA9F5",
        "on_press": "#00122E",
    },
]


def colours(t):
    """Expands a theme's small palette into every colour FUTO Keyboard asks for."""
    dark = t["dark"]
    s, text = t["surface"], t["text"]
    ui_bg = s
    ui_fg = mix(text, "#FFFFFF" if dark else "#000000", 0.3)
    lift = "#FFFFFF" if dark else "#000000"
    return {
        "primary": t["accent"], "on_primary": t["on_accent"],
        "primary_container": mix(t["accent"], s, 0.6), "on_primary_container": ui_fg,
        "inverse_primary": mix(t["accent"], s, 0.3),
        "secondary": t["accent2"], "on_secondary": t["on_accent"],
        "secondary_container": mix(t["accent2"], s, 0.65), "on_secondary_container": ui_fg,
        "tertiary": t["accent2"], "on_tertiary": t["on_accent"],
        "tertiary_container": mix(t["accent2"], s, 0.65), "on_tertiary_container": ui_fg,
        "background": ui_bg, "on_background": ui_fg,
        "surface": ui_bg, "on_surface": ui_fg,
        "surface_variant": mix(s, lift, 0.12), "on_surface_variant": t["hint"],
        "surface_tint": t["accent"],
        "inverse_surface": ui_fg, "inverse_on_surface": ui_bg,
        "error": "#FF5449" if dark else "#BA1A1A", "on_error": "#690005" if dark else "#FFFFFF",
        "error_container": "#93000A" if dark else "#FFDAD6", "on_error_container": "#FFDAD6" if dark else "#410002",
        "outline": mix(text, s, 0.5), "outline_variant": mix(text, s, 0.75),
        "scrim": "#000000",
        "surface_bright": mix(s, lift, 0.16), "surface_dim": mix(s, "#000000", 0.1),
        "surface_container": mix(s, lift, 0.06), "surface_container_high": mix(s, lift, 0.1),
        "surface_container_highest": mix(s, lift, 0.14), "surface_container_low": mix(s, lift, 0.04),
        "surface_container_lowest": mix(s, "#000000", 0.05),
        "keyboard_surface": s, "keyboard_surface_dim": mix(s, "#000000", 0.08),
        "keyboard_container": alpha(t["key"], t["key_alpha"]),
        "keyboard_container_variant": alpha(t["key_variant"], t["key_alpha"]),
        "on_keyboard_container": text,
        "keyboard_press": alpha(t["press"], 0.6),
        "keyboard_container_pressed": t["press"], "on_keyboard_container_pressed": t["on_press"],
    }


def theme_txt(t, font_name):
    lines = [
        "# Format version: 1",
        f'name = "{t["name"]}"',
        'author = "Cowmoji Keyboard"',
        f'id = "{t["id"]}"',
        "version = 1",
        f'description = "{t["name"]} theme for Cowmoji Keyboard."',
        "",
        "[options]",
        "auto_borders = true",
        f'roundedness = {t["roundedness"]}',
        f'scale_text = {t.get("scale_text", 1.0)}',
        "",
        "[options.font]",
        f'font = "{font_name}"',
        "",
        "[options.background]",
        'image = "background.jpg"',
        f'opacity = {t["opacity"]}',
        "action_bar_opacity = 0.6",
        "",
        "[colors]",
    ]
    lines += [f'{k} = "{v}"' for k, v in colours(t).items()]
    return "\n".join(lines) + "\n"


# ---- preview ------------------------------------------------------------------------------------

def rgba(h):
    h = h.lstrip("#")
    a = int(h[6:8], 16) if len(h) == 8 else 255
    return rgb(h[:6]) + (a,)


def preview(t, bg, font_path):
    """Rough mock-up of the keyboard: background, action bar, four rows of keys."""
    pw, ph = 1080, 820
    scale = max(pw / W, ph / H)
    img = bg.convert("RGBA").resize((round(W * scale), round(H * scale)), Image.LANCZOS)
    img = img.crop(((img.width - pw) // 2, (img.height - ph) // 2, (img.width + pw) // 2, (img.height + ph) // 2))
    c = colours(t)
    overlay = Image.new("RGBA", (pw, ph), rgb(c["keyboard_surface"]) + (round((1 - t["opacity"]) * 255),))
    img = Image.alpha_composite(img, overlay)

    keys = Image.new("RGBA", (pw, ph))
    d = ImageDraw.Draw(keys)
    font = ImageFont.truetype(font_path, round(46 * t.get("scale_text", 1.0)))
    small = ImageFont.truetype(font_path, 22)
    rows = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
    top, kh, gap = 130, 150, 14
    kw = (pw - gap) / 10 - gap
    radius = round(26 * t["roundedness"])

    def key(x, y, w, label, fill, fg):
        d.rounded_rectangle([x, y, x + w, y + kh - gap], radius=radius, fill=rgba(fill))
        d.text((x + w / 2, y + (kh - gap) / 2), label, font=font, fill=rgba(fg), anchor="mm")

    for r, row in enumerate(rows):
        offset = (pw - len(row) * (kw + gap) + gap) / 2
        if r == 2:
            offset = gap + 1.5 * (kw + gap)
            key(gap, top + r * kh, 1.5 * kw + 0.5 * gap, "⇧", c["keyboard_container_variant"], c["on_keyboard_container"])
            key(pw - gap - 1.5 * kw - 0.5 * gap, top + r * kh, 1.5 * kw + 0.5 * gap, "⌫",
                c["keyboard_container_variant"], c["on_keyboard_container"])
        for i, ch in enumerate(row):
            x = offset + i * (kw + gap)
            key(x, top + r * kh, kw, ch, c["keyboard_container"], c["on_keyboard_container"])
            d.text((x + kw - 14, top + r * kh + 12), str((i + 1) % 10) if r == 0 else "",
                   font=small, fill=rgba(c["on_surface_variant"]), anchor="ra")
    y = top + 3 * kh
    key(gap, y, 1.5 * kw, "?123", c["keyboard_container_variant"], c["on_keyboard_container"])
    key(gap * 2 + 1.5 * kw, y, kw, ",", c["keyboard_container_variant"], c["on_keyboard_container"])
    key(gap * 3 + 2.5 * kw, y, 5 * kw + 4 * gap, "", c["keyboard_container"], c["on_keyboard_container"])
    key(pw - gap * 2 - 2.5 * kw, y, kw, ".", c["keyboard_container_variant"], c["on_keyboard_container"])
    key(pw - gap - 1.5 * kw, y, 1.5 * kw, "↵", c["primary"], c["on_primary"])

    # action bar icons
    for i in range(5):
        d.ellipse([40 + i * 110, 40, 90 + i * 110, 90], outline=rgba(c["on_keyboard_container"]), width=4)
    return Image.alpha_composite(img, keys).convert("RGB")


# ---- main ---------------------------------------------------------------------------------------

def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    sheet = []
    for t in THEMES:
        font_path, licence = font_file(*t["font"])
        font_name = os.path.basename(font_path)
        bg = t["background"]()
        jpg = io.BytesIO()
        bg.save(jpg, "JPEG", quality=90)

        out = os.path.join(OUT_DIR, f'{t["id"]}.zip')
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("theme.txt", theme_txt(t, font_name))
            z.writestr("background.jpg", jpg.getvalue())
            z.write(font_path, font_name)
            z.write(licence, "OFL.txt")
        print(f'{t["name"]}: {out} ({os.path.getsize(out) // 1024} KB)')

        p = preview(t, bg, font_path)
        p.save(os.path.join(PREVIEW_DIR, f'{t["id"]}.png'))
        sheet.append(p)

    s = Image.new("RGB", (sum(p.width for p in sheet) + 20 * (len(sheet) - 1), sheet[0].height), (40, 40, 40))
    x = 0
    for p in sheet:
        s.paste(p, (x, 0))
        x += p.width + 20
    s.save(os.path.join(PREVIEW_DIR, "all.png"))


if __name__ == "__main__":
    main()
