#!/usr/bin/env python3
"""Snapshot the Care tab's curated product searches so the app opens instantly (and works offline).

The app searches UPCitemdb live (the trial endpoint: no key, 100 requests a day per IP) and caches each
answer for a week. This tool saves the same answers, trimmed to the fields the app reads, into
proto-app/assets/care/ as that cache's starting point, and each product's photo (shrunk) into
proto-app/assets/care/img/ so shelves have pictures offline too (the photos are the stores', so that folder
stays out of git: run this before building a demo phone). Keep QUERIES in step with CareData.java.

Usage: tools/care_seed.py [--force]     (skips answers younger than a week unless --force)
"""
import hashlib
import io
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

QUERIES = [
    "tylenol extra strength", "advil ibuprofen", "digital thermometer",   # pain & fever
    "dayquil", "mucinex",                                                  # cold & cough
    "claritin", "zyrtec",                                                  # allergy
    "pepto bismol", "tums",                                                # stomach
    "pedialyte", "liquid iv",                                              # electrolytes
    "vitamin d3", "emergen-c",                                             # vitamins
    "melatonin",                                                           # sleep
    "band-aid", "systane eye drops",                                       # first aid
]
OUT = Path(__file__).resolve().parent.parent / "proto-app" / "assets" / "care"
UA = "ApolloProto/0.2 (UW DubHacks health prototype)"
PHOTO_HOSTS = ["pics.walgreens.com", "target.scene7.com", "i5.walmartimages.com", "media.officedepot.com"]
DEAD_HOSTS = ["pics.drugstore.com"]   # drugstore.com closed; its photos 404
WEEK = 7 * 86400


def slug(q):
    """The cache name, as CareApi.slug() makes it in the app."""
    return re.sub(r"[^a-z0-9]+", "_", q.lower()).strip("_")


def trim(answer):
    """Only what the app reads: https photos, the newest priced offers."""
    items = []
    for it in answer.get("items", []):
        images = [u for u in it.get("images", []) if u.startswith("https://")][:3]
        offers = sorted((o for o in it.get("offers", []) if (o.get("price") or 0) > 0),
                        key=lambda o: o.get("updated_t") or 0, reverse=True)[:4]
        items.append({
            "title": it.get("title", ""), "brand": it.get("brand", ""), "size": it.get("size", ""),
            "upc": it.get("upc", ""), "category": it.get("category", ""), "images": images,
            "offers": [{"merchant": o.get("merchant", ""), "price": o.get("price"), "updated_t": o.get("updated_t")} for o in offers],
        })
    return {"code": answer.get("code", "OK"), "items": items, "fetched_ms": int(time.time() * 1000)}


def main():
    force = "--force" in sys.argv
    OUT.mkdir(parents=True, exist_ok=True)
    for i, q in enumerate(QUERIES):
        f = OUT / f"upc_{slug(q)}.json"
        if f.exists() and not force and time.time() - json.loads(f.read_text()).get("fetched_ms", 0) / 1000 < WEEK:
            print(f"{q!r}: fresh, skipped")
            continue
        url = "https://api.upcitemdb.com/prod/trial/search?" + urllib.parse.urlencode({"s": q, "match_mode": 0, "type": "product"})
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
        answer = None
        for attempt in range(3):
            try:
                with urllib.request.urlopen(req, timeout=30) as r:
                    left = r.headers.get("X-RateLimit-Remaining")
                    answer = json.load(r)
                break
            except urllib.error.HTTPError as e:
                if e.code != 429 or attempt == 2:
                    print(f"{q!r}: failed ({e})")
                    break
                time.sleep(70)   # the trial also limits bursts: wait out its minute window
            except Exception as e:   # network miss: keep any older snapshot
                print(f"{q!r}: failed ({e})")
                break
        if answer is None:
            continue
        data = trim(answer)
        f.write_text(json.dumps(data, separators=(",", ":")))
        usable = sum(1 for it in data["items"] if it["images"])
        print(f"{q!r}: {len(data['items'])} items, {usable} with photos -> {f.name} (quota left today: {left})")
        if i < len(QUERIES) - 1:
            time.sleep(20)   # stay under the trial's burst limit


def photo(images):
    """The photo the app shows: a known store host first, else the first https one (CareApi.photo())."""
    live = [u for u in images if urllib.parse.urlparse(u).hostname not in DEAD_HOSTS]
    for u in live:
        if urllib.parse.urlparse(u).hostname in PHOTO_HOSTS:
            return u
    return live[0] if live else None


def save_photos():
    """Each snapshot product's photo, 360 px at most, named by the SHA-1 of its URL as the app looks it up."""
    from PIL import Image
    out = OUT / "img"
    out.mkdir(exist_ok=True)
    got = have = failed = 0
    for f in sorted(OUT.glob("upc_*.json")):
        for it in json.loads(f.read_text())["items"]:
            u = photo(it["images"])
            if not u:
                continue
            dest = out / (hashlib.sha1(u.encode()).hexdigest() + ".jpg")
            if dest.exists():
                have += 1
                continue
            try:
                req = urllib.request.Request(u, headers={"User-Agent": UA})
                with urllib.request.urlopen(req, timeout=30) as r:
                    im = Image.open(io.BytesIO(r.read())).convert("RGB")
                im.thumbnail((360, 360))
                im.save(dest, "JPEG", quality=85)
                got += 1
            except Exception as e:
                print(f"photo failed: {u} ({e})")
                failed += 1
    size = sum(p.stat().st_size for p in out.glob("*.jpg")) / 1e6
    print(f"photos: {got} new, {have} already saved, {failed} failed; {size:.1f} MB in {out}")


if __name__ == "__main__":
    main()
    save_photos()
