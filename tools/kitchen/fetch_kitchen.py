"""
Builds the animal editions from Emoji Kitchen, using the same data as https://emojikitchen.dev
(github.com/xsalazar/emoji-kitchen-backend).

For every animal Emoji Kitchen has, it writes an index of all that animal's combinations
(partner emoji, name, search keywords, gstatic image URL) to java/assets/kitchen/<animal>.json,
plus java/assets/kitchen/animals.json listing the animals. The app downloads an animal's
images from gstatic when someone picks it; the cow's images are built into the app.

    python tools/kitchen/fetch_kitchen.py                     # all indexes
    python tools/kitchen/fetch_kitchen.py --images cow        # also the built-in cow images
    python tools/kitchen/fetch_kitchen.py --metadata metadata.json   # reuse a downloaded copy
"""

import argparse
import concurrent.futures
import json
import os
import sys
import unicodedata
import urllib.request

METADATA_URL = "https://raw.githubusercontent.com/xsalazar/emoji-kitchen-backend/main/app/metadata.json"

REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
INDEX_DIR = os.path.join(REPO, "java", "assets", "kitchen")
GEMOJI = os.path.join(REPO, "java", "res", "raw", "gemoji.json")

# Emoji Kitchen's animals: (codepoint, key, display name), in Gboard order
ANIMALS = [
    ("1f435", "monkey", "Monkey"), ("1f981", "lion", "Lion"), ("1f42f", "tiger", "Tiger"),
    ("1f431", "cat", "Cat"), ("1f436", "dog", "Dog"), ("1f43a", "wolf", "Wolf"),
    ("1f43b", "bear", "Bear"), ("1f428", "koala", "Koala"), ("1f43c", "panda", "Panda"),
    ("1f42d", "mouse", "Mouse"), ("1f430", "rabbit", "Rabbit"), ("1f98a", "fox", "Fox"),
    ("1f99d", "raccoon", "Raccoon"), ("1f42e", "cow", "Cow"), ("1f437", "pig", "Pig"),
    ("1f984", "unicorn", "Unicorn"), ("1f409", "dragon", "Dragon"), ("1f422", "turtle", "Turtle"),
    ("1f40d", "snake", "Snake"), ("1f438", "frog", "Frog"), ("1f429", "poodle", "Poodle"),
    ("1f40e", "horse", "Horse"), ("1f410", "goat", "Goat"), ("1f98c", "deer", "Deer"),
    ("1f999", "llama", "Llama"), ("1f9a5", "sloth", "Sloth"), ("1f994", "hedgehog", "Hedgehog"),
    ("1f987", "bat", "Bat"), ("1f989", "owl", "Owl"), ("1f426", "bird", "Bird"),
    ("1f414", "chicken", "Chicken"), ("1f99a", "peacock", "Peacock"), ("1fabf", "goose", "Goose"),
    ("1f54a-fe0f", "dove", "Dove"), ("1f9a9", "flamingo", "Flamingo"), ("1f9a8", "skunk", "Skunk"),
    ("1f427", "penguin", "Penguin"), ("1f988", "shark", "Shark"), ("1f433", "whale", "Whale"),
    ("1f41f", "fish", "Fish"), ("1f99e", "lobster", "Lobster"), ("1f980", "crab", "Crab"),
    ("1f419", "octopus", "Octopus"), ("1f982", "scorpion", "Scorpion"), ("1f577-fe0f", "spider", "Spider"),
    ("1f40c", "snail", "Snail"), ("1f997", "cricket", "Cricket"), ("1fab2", "beetle", "Beetle"),
    ("1fab3", "cockroach", "Cockroach"), ("1f41d", "bee", "Bee"), ("1f41e", "ladybug", "Ladybug"),
    ("1f98b", "butterfly", "Butterfly"),
]
BUILT_IN = {"cow"}  # images shipped inside the app; the rest download on demand


def load_metadata(path):
    if path and os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    print("Downloading metadata.json (~100 MB)...")
    with urllib.request.urlopen(METADATA_URL) as r:
        return json.loads(r.read().decode("utf-8"))


def codepoints_to_str(cp):
    return "".join(chr(int(p, 16)) for p in cp.split("-"))


def strip_fe0f(s):
    return s.replace("️", "")


def load_gemoji():
    """The keyboard's own emoji list: official names plus aliases and tags, keyed without FE0F."""
    if not os.path.exists(GEMOJI):
        print("Keyboard gemoji.json not found, names will come from emojikitchen.dev only")
        return {}
    with open(GEMOJI, encoding="utf-8") as f:
        return {strip_fe0f(e["emoji"]): e for e in json.load(f)}


def emoji_names(emoji, partner, gemoji):
    """Returns (official name, search tags) for the partner emoji, e.g. ('face with tears of joy', [...])."""
    g = gemoji.get(strip_fe0f(emoji), {})

    unicode_names = []
    for ch in strip_fe0f(emoji):
        try:
            unicode_names.append(unicodedata.name(ch).lower())
        except ValueError:
            pass

    name = g.get("description") or (unicode_names[0] if len(unicode_names) == 1 else "") \
        or partner.get("alt", "").replace("_", " ")

    raw = [name, partner.get("alt", "")] + g.get("aliases", []) + g.get("tags", []) \
        + partner.get("keywords", []) + unicode_names
    tags = []
    for t in raw:
        t = t.replace("_", " ").strip().lower()
        if t and t not in tags:
            tags.append(t)
    return name, tags


def animal_entries(data, codepoint, gemoji):
    entries = []
    for partner_cp, variants in data[codepoint]["combinations"].items():
        latest = next((v for v in variants if v.get("isLatest")), None) or max(variants, key=lambda v: v["date"])
        partner = data.get(partner_cp, {})
        emoji = codepoints_to_str(partner_cp)
        name, tags = emoji_names(emoji, partner, gemoji)
        entries.append({
            "emoji": emoji,
            "codepoint": partner_cp,
            "name": name,
            "alt": latest["alt"],
            "description": partner.get("alt", ""),
            "keywords": tags,
            "category": partner.get("category", ""),
            "order": partner.get("gBoardOrder", 1_000_000),
            "url": latest["gStaticUrl"],
            "file": latest["gStaticUrl"].rsplit("/", 1)[-1],
        })
    entries.sort(key=lambda e: (e["order"], e["codepoint"]))
    return entries


def download_images(entries, out_dir):
    os.makedirs(out_dir, exist_ok=True)

    def get(e):
        dest = os.path.join(out_dir, e["file"])
        if os.path.exists(dest) and os.path.getsize(dest) > 0:
            return
        req = urllib.request.Request(e["url"], headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=60) as r:
            data = r.read()
        with open(dest, "wb") as f:
            f.write(data)

    failed = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
        futures = {pool.submit(get, e): e for e in entries}
        for fut in concurrent.futures.as_completed(futures):
            try:
                fut.result()
            except Exception as ex:  # noqa: BLE001
                failed.append((futures[fut]["file"], str(ex)))
    keep = {e["file"] for e in entries}
    for f in os.listdir(out_dir):
        if f.endswith(".png") and f not in keep:
            os.remove(os.path.join(out_dir, f))
    return failed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--metadata", help="local copy of metadata.json")
    ap.add_argument("--images", nargs="*", default=[], help="animals whose images go into the app")
    args = ap.parse_args()

    data = load_metadata(args.metadata)["data"]
    gemoji = load_gemoji()
    os.makedirs(INDEX_DIR, exist_ok=True)

    animals = []
    for codepoint, key, name in ANIMALS:
        if codepoint not in data:
            print(f"Emoji Kitchen has no {name}, skipping")
            continue
        entries = animal_entries(data, codepoint, gemoji)
        with open(os.path.join(INDEX_DIR, f"{key}.json"), "w", encoding="utf-8") as f:
            json.dump(entries, f, ensure_ascii=False, separators=(",", ":"))
        animals.append({"key": key, "name": name, "emoji": codepoints_to_str(codepoint),
                        "codepoint": codepoint, "count": len(entries), "builtIn": key in BUILT_IN})
        print(f"{name}: {len(entries)} combinations")

        if key in args.images:
            out_dir = os.path.join(REPO, "java", "assets", f"{key}_kitchen")
            failed = download_images(entries, out_dir)
            print(f"  images in {out_dir}; failed: {failed}")

    with open(os.path.join(INDEX_DIR, "animals.json"), "w", encoding="utf-8") as f:
        json.dump(animals, f, ensure_ascii=False, indent=1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
