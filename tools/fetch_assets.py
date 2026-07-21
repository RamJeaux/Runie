#!/usr/bin/env python3
"""fetch_assets.py — cross-platform installer for the approved roster art.

Pure Python 3 + Pillow (+ numpy if present). No bash / awk / jq / ImageMagick.
Downloads each sprite in src/main/resources/com/runie/assets_manifest.json,
cuts its flat studio background to transparency, trims + caps to <=256px, and
installs it over the bundled placeholder tile at the manifest resourcePath.

AUTH: the manifest URLs live behind your Hyperagent session. Provide the
cookie via env (name=value form), e.g. on Windows PowerShell:

    $env:RUNIE_SESSION_COOKIE='__Host-hyperagent_session=<value>'
    python tools\\fetch_assets.py

or bash/zsh:

    RUNIE_SESSION_COOKIE='__Host-hyperagent_session=<value>' python3 tools/fetch_assets.py

Behaviour (mirrors tools/fetch_assets.sh):
  * IDEMPOTENT + REAL-ART-SAFE: only writes a target that is missing or is a
    marked placeholder (PNG tEXt "runie-placeholder"). FORCE=1 overrides.
  * Background -> alpha: samples the 4 corners; a corner qualifies only if it
    is LIGHT + NEUTRAL (max>=0.78, chroma<=0.18). With >=3 qualifying corners
    it floodfills to transparency from each (so enclosed lights — eyes, teeth
    — survive), trims to content, caps the long edge at 256px. Fewer than 3
    qualifying corners, or an overcut, installs the sprite OPAQUE + warns
    (drop-in still works; overlay just shows a rectangular card).
  * Validates every download is a real image before install.
  * Optional filters: CREATURE=<id> STYLE=<kawaii|pixel>.
"""
import io
import os
import sys
import json
import time
import urllib.request

from PIL import Image, ImageDraw

try:
    import numpy as np
    HAVE_NUMPY = True
except Exception:
    HAVE_NUMPY = False

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src", "main", "resources")
MANIFEST = os.path.join(RES, "com", "runie", "assets_manifest.json")
MARKER = b"runie-placeholder"
MAX_PX = 256
SENT = (255, 0, 255)  # magenta floodfill sentinel
THRESH = int(os.environ.get("FUZZ_THRESH", "40"))  # 0-255; JPEG-noise tolerant
FORCE = os.environ.get("FORCE", "0") == "1"
CREATURE_FILTER = os.environ.get("CREATURE", "")
STYLE_FILTER = os.environ.get("STYLE", "")


UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/125 Safari/537.36")
DELAY = float(os.environ.get("REQ_DELAY", "0.4"))   # polite gap between requests
MAX_RETRY = int(os.environ.get("MAX_RETRY", "5"))   # backoff on 403/429 throttling


def auth_headers():
    headers = {"User-Agent": UA}
    ck = os.environ.get("RUNIE_SESSION_COOKIE")
    if ck:
        headers["Cookie"] = ck
        return headers
    hdr = os.environ.get("RUNIE_AUTH_HEADER")
    if hdr and ":" in hdr:
        k, v = hdr.split(":", 1)
        headers[k.strip()] = v.strip()
        return headers
    sys.stderr.write("WARNING: no RUNIE_SESSION_COOKIE / RUNIE_AUTH_HEADER — downloads will fail.\n")
    return headers


def download(url, headers):
    """GET with backoff on throttling (403/429). Returns bytes or raises."""
    last = None
    for attempt in range(MAX_RETRY):
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=60) as resp:
                return resp.read()
        except urllib.error.HTTPError as ex:
            last = ex
            if ex.code in (403, 429) and attempt < MAX_RETRY - 1:
                time.sleep(2 ** attempt)  # 1,2,4,8,16s
                continue
            raise
    raise last


def is_placeholder(path):
    try:
        with open(path, "rb") as f:
            return MARKER in f.read()
    except OSError:
        return False


def light_neutral(px):
    r, g, b = px[0] / 255.0, px[1] / 255.0, px[2] / 255.0
    mx, mn = max(r, g, b), min(r, g, b)
    return mx >= 0.78 and (mx - mn) <= 0.18


def cutout(img):
    """Return (rgba_image, opaque_bool). Cuts flat light-neutral bg to alpha."""
    im = img.convert("RGB")
    w, h = im.size
    corners = [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]
    seeds = [c for c in corners if light_neutral(im.getpixel(c))]
    if len(seeds) < 3:
        return _cap(im.convert("RGBA")), True  # opaque

    work = im.copy()
    for s in seeds:
        ImageDraw.floodfill(work, s, SENT, thresh=THRESH)

    if HAVE_NUMPY:
        warr = np.asarray(work)
        mask = np.all(warr == SENT, axis=-1)
        base = np.asarray(im).astype("uint8")
        alpha = np.where(mask, 0, 255).astype("uint8")
        rgba = np.dstack([base, alpha])
        out = Image.fromarray(rgba, "RGBA")
    else:
        rgba = im.convert("RGBA")
        wpx, opx = work.load(), rgba.load()
        for y in range(h):
            for x in range(w):
                if wpx[x, y] == SENT:
                    opx[x, y] = (0, 0, 0, 0)
        out = rgba

    bbox = out.getbbox()
    if not bbox:
        return _cap(im.convert("RGBA")), True  # overcut -> opaque
    cropped = out.crop(bbox)
    cw, ch = cropped.size
    if cw < 8 or ch < 8:
        return _cap(im.convert("RGBA")), True  # overcut -> opaque
    return _cap(cropped), False


def _cap(rgba):
    rgba.thumbnail((MAX_PX, MAX_PX), Image.LANCZOS)
    return rgba


def main():
    if not os.path.isfile(MANIFEST):
        sys.exit("ERROR: manifest not found: %s" % MANIFEST)
    manifest = json.load(open(MANIFEST, encoding="utf-8"))
    entries = manifest["entries"]
    headers = auth_headers()

    installed = skipped_real = skipped_filter = failed = warned = 0
    for e in entries:
        cid, style, url, rpath = e["creatureId"], e["style"], e["url"], e["resourcePath"]
        if (CREATURE_FILTER and cid != CREATURE_FILTER) or (STYLE_FILTER and style != STYLE_FILTER):
            skipped_filter += 1
            continue
        target = os.path.join(RES, *rpath.split("/"))
        if not FORCE and os.path.isfile(target) and not is_placeholder(target):
            skipped_real += 1
            continue
        try:
            data = download(url, headers)
            img = Image.open(io.BytesIO(data))
            img.load()
        except Exception as ex:
            sys.stderr.write("  FAIL %s/%s: %s\n" % (cid, style, ex))
            failed += 1
            continue
        time.sleep(DELAY)
        rgba, opaque = cutout(img)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        rgba.save(target, "PNG")
        if opaque:
            warned += 1
            sys.stderr.write("  WARN installed OPAQUE (bg not clean): %s\n" % rpath)
        else:
            print("  installed %s" % rpath)
        installed += 1

    print("done: %d installed (%d opaque-warn), %d real-art skips, %d filtered, %d failed."
          % (installed, warned, skipped_real, skipped_filter, failed))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
