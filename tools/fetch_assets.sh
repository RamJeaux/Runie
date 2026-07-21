#!/usr/bin/env bash
# fetch_assets.sh — installs the approved roster art from the platform into
# the §5.1 resource tree, replacing the bundled placeholder tiles.
#
#   creatures/<id>/art/<style>/stage<N>/idle/frame_000.png
#
# AUTH REQUIRED: the manifest URLs are behind platform session auth. This
# script works in a real dev environment / CI where a valid session exists;
# in an unauthenticated sandbox every download 401s (by design — just run it
# where you have a session). Provide ONE of:
#
#   RUNIE_SESSION_COOKIE   e.g. 'session=abc123...'   (sent as Cookie:)
#   RUNIE_AUTH_HEADER      e.g. 'Authorization: Bearer ...'
#
# One-liner for a developer with a session:
#
#   RUNIE_SESSION_COOKIE='session=<your cookie>' ./tools/fetch_assets.sh
#
# Behavior:
#   * Reads src/main/resources/com/runie/assets_manifest.json (252 entries).
#   * IDEMPOTENT + REAL-ART-SAFE: a target file is only (re)written when it
#     is missing or is a marked placeholder (PNG tEXt "runie-placeholder",
#     written by tools/gen_placeholders.py). Real art — e.g. Grubnak's
#     hand-finished frames — is NEVER overwritten. FORCE=1 overrides.
#   * Background -> alpha cutout (robust, per sprite): if ImageMagick
#     (magick/convert) is on PATH, each downloaded PNG is processed as:
#       1. SKIP if the image already carries transparency (pre-cut or
#          hand-finished art) — only the <=256px size cap is applied.
#       2. SAMPLE all 4 corner pixels; a corner qualifies only when it is
#          LIGHT and NEUTRAL (max channel >= 0.78, chroma spread <= 0.18 —
#          the flat studio background). If fewer than 3 corners qualify the
#          sprite likely bleeds into the frame: install opaque + warn
#          rather than eat art.
#       3. FLOODFILL to alpha from every qualifying corner at FUZZ (default
#          12%) — floodfill (not global -transparent) so enclosed light
#          areas inside the sprite (eyes, teeth, highlights) survive.
#       4. TRIM to content, then cap the largest edge at 256px.
#       5. VERIFY: if the trim collapsed the image (<8px a side = overcut),
#          the opaque original is kept (size-capped) + a warning printed.
#     Without ImageMagick the PNG is installed opaque/full-size with a
#     warning (drop-in still works; the overlay just shows a rectangular
#     card until a cutout pass runs). Tune with FUZZ=<percent>.
#   * Downloads are validated as real PNGs (magic bytes) before install —
#     an auth wall serving HTML with HTTP 200 can never land in resources.
#   * Optional filters: CREATURE=<id> STYLE=<kawaii|pixel> to fetch a subset.
#
# DROP-IN (authed dev env / CI, from the repo root):
#
#   RUNIE_SESSION_COOKIE='session=<cookie>' ./tools/fetch_assets.sh
#
# Re-run any time: already-installed real art is never touched, so a rerun
# only fetches what is missing or still a marked placeholder.
#
# Requires: bash, curl, awk, python3 (manifest parsing; jq used when present).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RES="$ROOT/src/main/resources"
MANIFEST="$RES/com/runie/assets_manifest.json"
MARKER="runie-placeholder"
MAX_PX=256
FORCE="${FORCE:-0}"
CREATURE_FILTER="${CREATURE:-}"
STYLE_FILTER="${STYLE:-}"

[ -f "$MANIFEST" ] || { echo "ERROR: manifest not found: $MANIFEST" >&2; exit 1; }

AUTH_ARGS=()
if [ -n "${RUNIE_SESSION_COOKIE:-}" ]; then
    AUTH_ARGS=(-H "Cookie: ${RUNIE_SESSION_COOKIE}")
elif [ -n "${RUNIE_AUTH_HEADER:-}" ]; then
    AUTH_ARGS=(-H "${RUNIE_AUTH_HEADER}")
else
    echo "WARNING: neither RUNIE_SESSION_COOKIE nor RUNIE_AUTH_HEADER set —" >&2
    echo "         downloads will 401 unless the URLs are otherwise reachable." >&2
fi

MAGICK=""
if command -v magick >/dev/null 2>&1; then MAGICK="magick";
elif command -v convert >/dev/null 2>&1; then MAGICK="convert"; fi
[ -n "$MAGICK" ] || echo "NOTE: ImageMagick not found — installing sprites opaque (no alpha cutout)." >&2

# entries as TSV: url \t resourcePath \t creatureId \t style
entries() {
    if command -v jq >/dev/null 2>&1; then
        jq -r '.entries[] | [.url, .resourcePath, .creatureId, .style] | @tsv' "$MANIFEST"
    else
        python3 -c '
import json,sys
m=json.load(open(sys.argv[1]))
for e in m["entries"]:
    print("\t".join([e["url"],e["resourcePath"],e["creatureId"],e["style"]]))' "$MANIFEST"
    fi
}

is_placeholder() { grep -aq "$MARKER" "$1" 2>/dev/null; }

is_png() { # PNG magic bytes — reject auth-wall HTML served with HTTP 200
    [ "$(head -c 8 "$1" 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "89504e470d0a1a0a" ]
}

# corner_light_neutral <r> <g> <b>  (channels normalized 0..1)
# true when the pixel reads as the flat light-neutral studio background
corner_light_neutral() {
    awk -v r="$1" -v g="$2" -v b="$3" 'BEGIN {
        max = r; if (g > max) max = g; if (b > max) max = b
        min = r; if (g < min) min = g; if (b < min) min = b
        exit !(max >= 0.78 && (max - min) <= 0.18)
    }'
}

resize_cap() { # $1 = png path: cap largest edge at MAX_PX, in place
    [ -n "$MAGICK" ] || return 0
    local f="$1" tmp="$1.cap.png"
    if "$MAGICK" "$f" -resize "${MAX_PX}x${MAX_PX}>" "$tmp" 2>/dev/null; then
        mv "$tmp" "$f"
    else
        rm -f "$tmp"
    fi
}

cutout() { # $1 = png path (in place), $2 = label for warnings
    [ -n "$MAGICK" ] || return 0
    local f="$1" label="${2:-$1}" tmp="$1.cut.png"

    # 1. already has alpha (pre-cut / hand-finished) -> size cap only
    local opaque
    opaque=$("$MAGICK" "$f" -format '%[opaque]' info: 2>/dev/null || echo True)
    case "$opaque" in
        [Ff]alse) resize_cap "$f"; return 0 ;;
    esac

    # 2. sample the 4 corners; keep only light-neutral (background) ones
    local w h corners
    w=$("$MAGICK" "$f" -format '%w' info: 2>/dev/null) || { resize_cap "$f"; return 0; }
    h=$("$MAGICK" "$f" -format '%h' info: 2>/dev/null) || { resize_cap "$f"; return 0; }
    corners=""
    local n_ok=0 cx cy rgb
    for corner in "0,0" "$((w-1)),0" "0,$((h-1))" "$((w-1)),$((h-1))"; do
        cx="${corner%,*}"; cy="${corner#*,}"
        rgb=$("$MAGICK" "$f" -format \
            "%[fx:p{$cx,$cy}.r] %[fx:p{$cx,$cy}.g] %[fx:p{$cx,$cy}.b]" info: 2>/dev/null) || continue
        # shellcheck disable=SC2086
        if corner_light_neutral $rgb; then
            corners="$corners $corner"
            n_ok=$((n_ok+1))
        fi
    done
    if [ "$n_ok" -lt 3 ]; then
        echo "  WARN: corners not light-neutral ($n_ok/4) — installed OPAQUE: $label" >&2
        resize_cap "$f"
        return 0
    fi

    # 3+4. floodfill->alpha from each qualifying corner, trim, cap size
    local draw_args=()
    for corner in $corners; do
        draw_args+=(-fill none -draw "color $corner floodfill")
    done
    if ! "$MAGICK" "$f" -alpha set -fuzz "${FUZZ:-12}%" "${draw_args[@]}" \
        -trim +repage -resize "${MAX_PX}x${MAX_PX}>" "$tmp" 2>/dev/null; then
        rm -f "$tmp"
        echo "  WARN: cutout failed — installed OPAQUE: $label" >&2
        resize_cap "$f"
        return 0
    fi

    # 5. sanity: an overcut collapses the trim box — keep the opaque original
    local tw th
    tw=$("$MAGICK" "$tmp" -format '%w' info: 2>/dev/null || echo 0)
    th=$("$MAGICK" "$tmp" -format '%h' info: 2>/dev/null || echo 0)
    if [ "${tw:-0}" -lt 8 ] || [ "${th:-0}" -lt 8 ]; then
        rm -f "$tmp"
        echo "  WARN: cutout overcut (${tw}x${th}) — installed OPAQUE: $label" >&2
        resize_cap "$f"
        return 0
    fi
    mv "$tmp" "$f"
}

ENTRIES_TSV="$(mktemp)"
trap 'rm -f "$ENTRIES_TSV"' EXIT
entries > "$ENTRIES_TSV"

fetched=0 skipped_real=0 skipped_filter=0 failed=0
while IFS=$'\t' read -r url rpath cid style; do
    if { [ -n "$CREATURE_FILTER" ] && [ "$cid" != "$CREATURE_FILTER" ]; } ||
       { [ -n "$STYLE_FILTER" ] && [ "$style" != "$STYLE_FILTER" ]; }; then
        skipped_filter=$((skipped_filter+1)); continue
    fi
    target="$RES/$rpath"
    if [ "$FORCE" != "1" ] && [ -f "$target" ] && ! is_placeholder "$target"; then
        skipped_real=$((skipped_real+1)); continue   # real art — never clobber
    fi
    mkdir -p "$(dirname "$target")"
    tmp="$(mktemp --suffix=.png)"
    code=$(curl -sS -o "$tmp" -w '%{http_code}' "${AUTH_ARGS[@]}" "$url" || echo 000)
    if [ "$code" != "200" ]; then
        echo "  FAIL [$code] $cid/$style: $url" >&2
        rm -f "$tmp"; failed=$((failed+1)); continue
    fi
    if ! is_png "$tmp"; then
        echo "  FAIL [not a PNG — auth wall?] $cid/$style: $url" >&2
        rm -f "$tmp"; failed=$((failed+1)); continue
    fi
    cutout "$tmp" "$rpath"
    mv "$tmp" "$target"
    echo "  installed $rpath"
    fetched=$((fetched+1))
done < "$ENTRIES_TSV"

echo "done: $fetched installed, $skipped_real real-art skips, $skipped_filter filtered, $failed failed."
if [ "$failed" -gt 0 ]; then
    echo "Some downloads failed. 401s mean no/expired session — set RUNIE_SESSION_COOKIE and re-run;" >&2
    echo "already-installed files are skipped, so re-running only fetches what's missing." >&2
    exit 1
fi
