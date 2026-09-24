"""
Fetch every Emoji Kitchen combination that uses the cow face (U+1F42E) as one
side, using the same data source as https://emojikitchen.dev
(github.com/xsalazar/emoji-kitchen-backend).

Writes straight into the keyboard's assets:
  java/assets/cow_kitchen/cow_kitchen.json   index of all combinations (one entry per partner emoji)
  java/assets/cow_kitchen/*.png              the combination images downloaded from gstatic

Usage:
  python tools/cow_kitchen/fetch_cow_kitchen.py [--metadata path/to/metadata.json]
"""

import argparse
import concurrent.futures
import json
import os
import sys
import unicodedata
import urllib.request

METADATA_URL = "https://raw.githubusercontent.com/xsalazar/emoji-kitchen-backend/main/app/metadata.json"
BASE_EMOJI = "1f42e"  # cow face

REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
OUT_DIR = os.path.join(REPO, "java", "assets", "cow_kitchen")
OUT_JSON = os.path.join(OUT_DIR, "cow_kitchen.json")
GEMOJI = os.path.join(REPO, "java", "res", "raw", "gemoji.json")


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


def download(entry):
    dest = os.path.join(OUT_DIR, entry["file"])
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return entry["file"], False
    req = urllib.request.Request(entry["url"], headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=60) as r:
        data = r.read()
    with open(dest, "wb") as f:
        f.write(data)
    return entry["file"], True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--metadata", help="local copy of metadata.json")
    args = ap.parse_args()

    meta = load_metadata(args.metadata)
    data = meta["data"]
    combos = data[BASE_EMOJI]["combinations"]

    gemoji = load_gemoji()

    entries = []
    for partner_cp, variants in combos.items():
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
            "date": latest["date"],
            "url": latest["gStaticUrl"],
            "file": latest["gStaticUrl"].rsplit("/", 1)[-1],
        })

    entries.sort(key=lambda e: (e["order"], e["codepoint"]))

    os.makedirs(OUT_DIR, exist_ok=True)
    failed = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
        futures = {pool.submit(download, e): e for e in entries}
        for i, fut in enumerate(concurrent.futures.as_completed(futures), 1):
            e = futures[fut]
            try:
                fut.result()
            except Exception as ex:  # noqa: BLE001
                failed.append((e["file"], str(ex)))
            if i % 50 == 0 or i == len(entries):
                print(f"  {i}/{len(entries)}")

    if failed:
        print("Failed downloads:", *failed, sep="\n  ")
        failed_files = {f for f, _ in failed}
        entries = [e for e in entries if e["file"] not in failed_files]

    # Google sometimes redraws a combination under a new file name; drop the old images
    keep = {e["file"] for e in entries}
    for f in os.listdir(OUT_DIR):
        if f.endswith(".png") and f not in keep:
            os.remove(os.path.join(OUT_DIR, f))

    with open(OUT_JSON, "w", encoding="utf-8") as f:
        json.dump(entries, f, ensure_ascii=False, indent=1)
    print(f"Wrote {len(entries)} combinations to {OUT_JSON}")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
