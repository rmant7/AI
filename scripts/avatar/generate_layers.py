#!/usr/bin/env python3
"""Builds the layered avatar PNGs (app/src/main/assets/avatar/) from avatar_face.jpg.

Classical image processing only (Pillow + numpy) — no ML. Every output is a
full-size RGBA PNG in the source photo's own pixel coordinates, so at runtime
one Matrix registers all of them. Each layer only differs from the original
photo inside a small footprint rectangle; everywhere else it is transparent
(feature layers) or identical to the photo (head).

The coordinates below were measured on this specific photo (640x640) from
zoomed crops — if the photo changes they must be re-measured.

Usage: python3 scripts/avatar/generate_layers.py [--preview DIR]
"""
import argparse
import math
import os

import numpy as np
from PIL import Image, ImageFilter

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SRC = os.path.join(ROOT, "app/src/main/res/drawable-nodpi/avatar_face.jpg")
OUT = os.path.join(ROOT, "app/src/main/assets/avatar")

# xc, yc: centre of the eye opening; a: half-width; h_top/h_bot: max half-height
# above/below yc. Measured from 5x zoomed crops of the photo.
LEFT_EYE = dict(xc=245.0, yc=344.5, a=41.0, h_top=8.5, h_bot=8.5)
RIGHT_EYE = dict(xc=393.0, yc=337.5, a=36.0, h_top=9.5, h_bot=9.0)

# Mouth: lip line row, horizontal centre, and the footprint of everything the
# mouth layers may touch.
MOUTH_XC = 318.0
MOUTH_YS = 513.0
MOUTH_H_UP = 14.0
MOUTH_H_LOW = 28.0

# name -> (half_width, jaw_drop, upper_lift, teeth_fraction, tongue, rounded)
VISEMES = {
    "a": (32.0, 15.0, 2.0, 0.42, True, False),
    "e": (42.0, 8.0, 1.0, 0.70, False, False),
    "i": (38.0, 6.0, 1.0, 0.80, False, False),
    "o": (23.0, 13.0, 2.0, 0.0, False, True),
    "u": (17.0, 9.0, 1.0, 0.0, False, True),
    "smile": (46.0, 5.0, 1.0, 0.85, False, False),
}


def smoothstep(x):
    x = np.clip(x, 0.0, 1.0)
    return x * x * (3.0 - 2.0 * x)


def rect_mask(h, w, x0, y0, x1, y1, feather):
    """1 inside the rectangle, smoothly falling to 0 over `feather` px outside it."""
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    dx = np.maximum(np.maximum(x0 - xs, xs - x1), 0.0)
    dy = np.maximum(np.maximum(y0 - ys, ys - y1), 0.0)
    dist = np.sqrt(dx * dx + dy * dy)
    return smoothstep(1.0 - dist / feather)


def sample_rows(img, x, ys):
    """Linear-interpolated column sample: img[ys, x] for fractional row coordinates."""
    ys = np.clip(ys, 0, img.shape[0] - 1.001)
    y0 = np.floor(ys).astype(int)
    f = (ys - y0)[..., None]
    return img[y0, x] * (1 - f) + img[y0 + 1, x] * f


def eye_profile(x, eye):
    t = (x - eye["xc"]) / eye["a"]
    return math.sqrt(max(0.0, 1.0 - t * t))


def close_eye(rgb, eye):
    """Returns (skin_rgb, lash_alpha): the eye region re-filled with lid skin, and a lash-line mask."""
    out = rgb.copy()
    lash = np.zeros(rgb.shape[:2], np.float32)
    x_lo = int(eye["xc"] - eye["a"] - 8)
    x_hi = int(eye["xc"] + eye["a"] + 9)
    m_up, m_dn = 2.5, 2.5
    ramp_up, ramp_dn = 22.0, 12.0
    for x in range(x_lo, x_hi):
        env = eye_profile(x, eye)
        top = eye["yc"] - eye["h_top"] * env - m_up
        bot = eye["yc"] + eye["h_bot"] * env + m_dn
        y_close = eye["yc"] + 0.85 * eye["h_bot"] * env + 0.5
        d_up = y_close - top
        d_dn = bot - y_close
        ys = np.arange(int(top - ramp_up), int(bot + ramp_dn) + 1, dtype=np.float32)
        src = ys.copy()
        # upper lid skin slides down over the opening (smooth ramp avoids folding)
        up_sel = ys <= y_close
        r = smoothstep((ys[up_sel] - (top - ramp_up)) / (ramp_up + d_up))
        src[up_sel] = ys[up_sel] - d_up * r
        # lower lid skin slides up
        dn_sel = ys > y_close
        r = smoothstep(((bot + ramp_dn) - ys[dn_sel]) / (ramp_dn + d_dn))
        src[dn_sel] = ys[dn_sel] + d_dn * r
        out[ys.astype(int), x] = sample_rows(rgb, x, src)
        # lash line: thin dark curve along the lid seam, thicker mid-eye
        width = 1.0 + 2.0 * env
        yy = np.arange(int(y_close) - 4, int(y_close) + 6)
        a = np.clip(1.0 - np.abs(yy - y_close) / (width / 2.0 + 0.6), 0.0, 1.0)
        lash[yy, x] = np.maximum(lash[yy, x], a * float(smoothstep(env * 3.5)))
    return out, lash


def lipless(rgb):
    """The photo with the closed-lip line painted out: the band around it is refilled
    from the lower-lip skin a few rows below (real texture, not an interpolation)."""
    out = rgb.copy()
    y0, y1 = int(MOUTH_YS) - 3, int(MOUTH_YS) + 6
    shift = 12
    x0, x1 = int(MOUTH_XC) - 62, int(MOUTH_XC) + 63
    for y in range(y0, y1 + 1):
        t = smoothstep((y - y0) / 3.0)
        out[y, x0:x1] = rgb[y, x0:x1] * (1 - t) + rgb[y + shift, x0:x1] * t
    return out


def mouth_variant(rgb, base, name):
    half, drop, lift, teeth_frac, tongue, rounded = VISEMES[name]
    src = base if rounded else rgb
    h, w = rgb.shape[:2]
    out = src.copy()
    cav = np.zeros((h, w), np.float32)
    y_s = MOUTH_YS
    for x in range(int(MOUTH_XC - half - 2), int(MOUTH_XC + half + 3)):
        t = abs(x - MOUTH_XC) / half
        wgt = max(0.0, 1.0 - t * t)
        if wgt <= 0.0:
            continue
        for y in range(int(y_s - MOUTH_H_UP) - 1, int(y_s + MOUTH_H_LOW + drop) + 2):
            if y >= y_s:
                s = round(drop * wgt * max(0.0, 1.0 - (y - y_s) / MOUTH_H_LOW))
                y_src = y - s
                if y_src < y_s:
                    cav[y, x] = 1.0
                else:
                    out[y, x] = src[y_src, x]
            else:
                s = round(lift * wgt * max(0.0, 1.0 - (y_s - y) / MOUTH_H_UP))
                y_src = y + s
                if y_src >= y_s:
                    cav[y, x] = 1.0
                else:
                    out[y, x] = src[y_src, x]

    cav_img = Image.fromarray((cav * 255).astype(np.uint8))
    cav_a = np.asarray(cav_img.filter(ImageFilter.GaussianBlur(0.7)), np.float32) / 255.0

    paint = np.zeros_like(out)
    ys, xs = np.mgrid[0:h, 0:w]
    for x in range(int(MOUTH_XC - half - 2), int(MOUTH_XC + half + 3)):
        rows = np.nonzero(cav[:, x] > 0.5)[0]
        if rows.size == 0:
            continue
        top, bot = rows[0], rows[-1] + 1
        height = float(bot - top)
        v = (ys[top:bot, x] - top + 0.5) / height
        col = np.array([34, 15, 17], np.float32) * (1 - v)[:, None] + np.array([78, 34, 36], np.float32) * v[:, None]
        if tongue:
            tt = smoothstep((v - 0.45) / 0.4)
            col = col * (1 - tt[:, None] * 0.75) + np.array([132, 64, 64], np.float32) * tt[:, None] * 0.75
        if teeth_frac > 0.0:
            edge = smoothstep((half - abs(x - MOUTH_XC)) / (half * 0.45))
            tv = np.clip(1.0 - v / teeth_frac, 0.0, 1.0)
            tooth = smoothstep(tv * 3.0) * edge
            # faint gaps between teeth and warm shading near the gum line
            shade = 1.0 - 0.10 * (0.5 + 0.5 * math.cos((x - MOUTH_XC) * 2 * math.pi / 9.0))
            tcol = np.array([214, 204, 188], np.float32) * shade * (0.82 + 0.18 * (1 - v))[:, None]
            col = col * (1 - tooth[:, None]) + tcol * tooth[:, None]
        # soft shadow under the moustache along the top edge of the opening
        top_shadow = np.clip(1.0 - (ys[top:bot, x] - top) / 2.5, 0.0, 1.0)
        col = col * (1 - 0.45 * top_shadow[:, None])
        paint[top:bot, x] = col
    out = out * (1 - cav_a[..., None]) + paint * cav_a[..., None]

    if rounded:
        dil = np.asarray(cav_img.filter(ImageFilter.MaxFilter(7)).filter(ImageFilter.GaussianBlur(1.0)), np.float32) / 255.0
        ring = np.clip(dil - cav_a, 0.0, 1.0) * 0.9
        out = out * (1 - ring[..., None]) + np.array([170, 100, 98], np.float32) * ring[..., None]
    return out


def save_rgba(path, rgb, alpha):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    # Colour under fully transparent pixels is zeroed: it is invisible, and a whole
    # photo hidden behind alpha=0 would make every feature layer as large as the head.
    rgb = np.where(alpha[..., None] > 0, np.clip(rgb, 0, 255), 0)
    rgba = np.dstack([rgb, np.clip(alpha, 0, 1) * 255]).astype(np.uint8)
    Image.fromarray(rgba, "RGBA").save(path, optimize=True)


def eye_footprint(h, w, eye):
    return rect_mask(
        h, w,
        eye["xc"] - eye["a"] - 9, eye["yc"] - eye["h_top"] - 12,
        eye["xc"] + eye["a"] + 9, eye["yc"] + eye["h_bot"] + 10,
        4.0,
    )


def mouth_footprint(h, w):
    half = max(v[0] for v in VISEMES.values())
    return rect_mask(
        h, w,
        MOUTH_XC - half - 6, MOUTH_YS - MOUTH_H_UP - 4,
        MOUTH_XC + half + 6, MOUTH_YS + MOUTH_H_LOW + 4,
        4.0,
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", help="also write composited preview PNGs into this directory")
    args = ap.parse_args()

    src = Image.open(SRC).convert("RGB")
    rgb = np.asarray(src, np.float32)
    h, w = rgb.shape[:2]
    print("source", w, "x", h)

    closed_l, lash_l = close_eye(rgb, LEFT_EYE)
    closed_r, lash_r = close_eye(rgb, RIGHT_EYE)
    lipless_rgb = lipless(rgb)

    # Head: original photo with both eyes replaced by lid skin and the lip line removed.
    head = rgb.copy()
    fl, fr, fm = eye_footprint(h, w, LEFT_EYE), eye_footprint(h, w, RIGHT_EYE), mouth_footprint(h, w)
    head = head * (1 - fl[..., None]) + closed_l * fl[..., None]
    head = head * (1 - fr[..., None]) + closed_r * fr[..., None]
    head = head * (1 - fm[..., None]) + lipless_rgb * fm[..., None]
    save_rgba(os.path.join(OUT, "avatar_head.png"), head, np.ones((h, w), np.float32))

    lash_col = np.array([62, 44, 40], np.float32)
    layers = {}
    for side, eye, fp, closed, lash in (("left", LEFT_EYE, fl, closed_l, lash_l), ("right", RIGHT_EYE, fr, closed_r, lash_r)):
        layers[f"eyes/{side}_open.png"] = (rgb, fp)
        soft = np.asarray(Image.fromarray((lash * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.6)), np.float32) / 255.0
        soft = np.clip(soft * 1.25, 0.0, 1.0) * 0.72
        skin_with_lash = closed * (1 - soft[..., None]) + lash_col * soft[..., None]
        layers[f"eyes/{side}_closed.png"] = (skin_with_lash, fp)
    layers["mouth/neutral.png"] = (rgb, fm)
    for name in VISEMES:
        layers[f"mouth/{name}.png"] = (mouth_variant(rgb, lipless_rgb, name), fm)
    for rel, (img, alpha) in layers.items():
        save_rgba(os.path.join(OUT, rel), img, alpha)
        print("wrote", rel)

    if args.preview:
        os.makedirs(args.preview, exist_ok=True)

        def composite(*names):
            im = Image.open(os.path.join(OUT, "avatar_head.png")).convert("RGBA")
            for n in names:
                im = Image.alpha_composite(im, Image.open(os.path.join(OUT, n)).convert("RGBA"))
            return im.convert("RGB")

        combos = {
            "static": ["eyes/left_open.png", "eyes/right_open.png", "mouth/neutral.png"],
            "blink": ["eyes/left_closed.png", "eyes/right_closed.png", "mouth/neutral.png"],
            "wink_l": ["eyes/left_closed.png", "eyes/right_open.png", "mouth/neutral.png"],
            "wink_r": ["eyes/left_open.png", "eyes/right_closed.png", "mouth/neutral.png"],
        }
        for name in VISEMES:
            combos[f"mouth_{name}"] = ["eyes/left_open.png", "eyes/right_open.png", f"mouth/{name}.png"]
        for name, parts in combos.items():
            composite(*parts).save(os.path.join(args.preview, name + ".png"))
        diff = np.abs(np.asarray(composite(*combos["static"]), np.float32) - rgb).max()
        print("static composite max abs diff vs original:", diff)


if __name__ == "__main__":
    main()
