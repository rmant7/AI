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

try:
    import cv2  # only for the background matte (classical GrabCut, no ML)
except ImportError:  # pragma: no cover
    cv2 = None

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

# name -> (half_width, jaw_drop, upper_lift, teeth_fraction, tongue, rounded, corner_lift)
# corner_lift raises the mouth corners (px), which is what makes "smile" read as a smile.
VISEMES = {
    "a": (32.0, 15.0, 2.0, 0.42, True, False, 0.0),
    "e": (42.0, 8.0, 1.0, 0.70, False, False, 0.0),
    "i": (38.0, 6.0, 1.0, 0.80, False, False, 0.0),
    "o": (27.0, 16.0, 2.0, 0.0, False, True, 0.0),
    "u": (17.0, 9.0, 1.0, 0.0, False, True, 0.0),
    "smile": (58.0, 14.0, 2.0, 0.55, False, False, 11.0),
    # the "surprised" mouth: a big round opening, much larger than the speech "o"
    "wow": (34.0, 34.0, 5.0, 0.0, True, True, 0.0),
    # clenched teeth, corners pulled down
    "anger": (48.0, 12.0, 1.0, 1.0, False, False, -9.0),
}
BITE_LINE = {"anger"}

# Eyebrows (box in photo px) and which end is the inner one (nearer the nose).
LEFT_BROW = dict(box=(190, 302, 294, 333), pivot_x=192, angle=-7.0)   # inner end = right
RIGHT_BROW = dict(box=(343, 287, 449, 319), pivot_x=447, angle=7.0)   # inner end = left
BROW_DROP = 5.0


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


def angry_eye(rgb, eye, inner_sign):
    """Upper lid pulled down over the top ~45% of the opening, lower at the inner end (a glare)."""
    out = rgb.copy()
    lash = np.zeros(rgb.shape[:2], np.float32)
    ramp_up = 18.0
    for x in range(int(eye["xc"] - eye["a"] - 8), int(eye["xc"] + eye["a"] + 9)):
        env = eye_profile(x, eye)
        top = eye["yc"] - eye["h_top"] * env - 2.5
        bot = eye["yc"] + eye["h_bot"] * env + 2.5
        slant = 5.0 * inner_sign * (x - eye["xc"]) / eye["a"]
        y_lid = min(bot - 1.5, top + (bot - top) * 0.45 + slant * env)
        d_up = max(0.0, y_lid - top)
        ys = np.arange(int(top - ramp_up), int(y_lid) + 1, dtype=np.float32)
        r = smoothstep((ys - (top - ramp_up)) / (ramp_up + d_up))
        out[ys.astype(int), x] = sample_rows(rgb, x, ys - d_up * r)
        yy = np.arange(int(y_lid) - 4, int(y_lid) + 6)
        a = np.clip(1.0 - np.abs(yy - y_lid) / (1.3 + 1.6 * env), 0.0, 1.0)
        lash[yy, x] = np.maximum(lash[yy, x], a * float(smoothstep(env * 3.5)))
    return out, lash


def brow_hair_mask(rgb, brow):
    """Soft mask of the brow hairs only (local deviation from the smoothed skin), inside the brow box."""
    h, w = rgb.shape[:2]
    lum = rgb.mean(axis=2)
    blur = np.asarray(Image.fromarray(lum.astype(np.uint8)).filter(ImageFilter.GaussianBlur(6)), np.float32)
    dev = np.abs(lum - blur)
    m = np.clip((dev - 3.0) / 10.0, 0.0, 1.0)
    m = np.asarray(Image.fromarray((m * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.3)), np.float32) / 255.0
    x0, y0, x1, y1 = brow["box"]
    return np.clip(m * 1.6, 0.0, 1.0) * rect_mask(h, w, x0, y0, x1, y1, 5.0)


def move_brow(rgb, brow, out, alpha, angle, drop):
    """Paints the old brow hairs out of `out` (forehead skin) and re-draws them lower and tilted.
    `alpha` accumulates exactly the pixels this changes, so the layer never covers the eyes."""
    h, w = rgb.shape[:2]
    mask = brow_hair_mask(rgb, brow)
    x0, y0, x1, y1 = brow["box"]
    # skin to hide the old brow: forehead just above, softened
    skin = rgb.copy()
    skin[y0:y1, x0:x1] = rgb[y0 - 26:y1 - 26, x0:x1]
    skin = np.asarray(Image.fromarray(skin.astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.2)), np.float32)
    grow = np.asarray(Image.fromarray((mask * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.GaussianBlur(2)), np.float32) / 255.0
    out = out * (1 - grow[..., None]) + skin * grow[..., None]
    # the same hairs, rotated about the outer end and dropped
    rgba = np.dstack([rgb * (0.84 if drop > 0 else 0.95), mask * 255]).astype(np.uint8)
    rot = Image.fromarray(rgba, "RGBA").rotate(angle, resample=Image.BICUBIC, center=(brow["pivot_x"], (y0 + y1) / 2))
    rot = np.asarray(rot, np.float32)
    rmask = np.roll(rot[..., 3] / 255.0, drop, axis=0)
    rcol = np.roll(rot[..., :3], drop, axis=0)
    # soft shadow just under the lowered brow so the frown reads
    shadow = np.asarray(Image.fromarray((np.roll(rmask, 5 if drop > 0 else 0, axis=0) * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(3.5)), np.float32) / 255.0
    out = out * (1 - 0.14 * shadow[..., None])
    touched = np.maximum(np.maximum(grow, rmask), shadow * 0.9)
    touched = np.asarray(Image.fromarray((touched * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(3)), np.float32) / 255.0
    return out * (1 - rmask[..., None]) + rcol * rmask[..., None], np.maximum(alpha, touched)


def wide_eye(rgb, eye, k=1.32):
    """The eye stretched vertically about its centre (fading out with distance): a startled, wide-open look."""
    out = rgb.copy()
    r2 = (eye["h_top"] + eye["h_bot"]) * 1.35
    for x in range(int(eye["xc"] - eye["a"] - 8), int(eye["xc"] + eye["a"] + 9)):
        env = eye_profile(x, eye)
        ys = np.arange(int(eye["yc"] - r2 - 2), int(eye["yc"] + r2 + 3), dtype=np.float32)
        d = ys - eye["yc"]
        kk = 1.0 + (k - 1.0) * (1.0 - smoothstep(np.abs(d) / r2)) * min(1.0, 0.35 + env)
        out[ys.astype(int), x] = sample_rows(rgb, x, eye["yc"] + d / kk)
    return out


def compute_matte(rgb):
    """Person/background alpha via GrabCut seeded from hand-placed regions, then edge clean-up.
    Returns (alpha 0..1, rgb with background colour bleed removed from the edge)."""
    if cv2 is None:
        raise SystemExit("background removal needs opencv-python-headless (pip install opencv-python-headless)")
    h, w = rgb.shape[:2]
    bgr = np.ascontiguousarray(rgb[..., ::-1].astype(np.uint8))
    mask = np.full((h, w), cv2.GC_PR_BGD, np.uint8)
    # hand-placed regions (photo px): head+hair+shoulders outline, face/torso core, known background
    outline = np.array([(60, 330), (80, 160), (190, 40), (330, 8), (455, 35), (525, 190), (535, 330),
                        (500, 470), (560, 500), (640, 635), (0, 640), (0, 510), (140, 480)], np.int32)
    cv2.fillPoly(mask, [outline], cv2.GC_PR_FGD)
    cv2.ellipse(mask, (320, 340), (140, 215), 0, 0, 360, cv2.GC_FGD, -1)
    mask[545:, :540] = cv2.GC_FGD
    mask[:480, :15] = cv2.GC_BGD
    mask[:480, 625:] = cv2.GC_BGD
    mask[:5, :120] = cv2.GC_BGD
    cv2.fillPoly(mask, [np.array([(560, 440), (640, 440), (640, 600), (575, 495)], np.int32)], cv2.GC_BGD)
    bgm, fgm = np.zeros((1, 65), np.float64), np.zeros((1, 65), np.float64)
    cv2.grabCut(bgr, mask, None, bgm, fgm, 8, cv2.GC_INIT_WITH_MASK)
    hard = ((mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD)).astype(np.uint8)

    core = cv2.erode(hard, np.ones((3, 3), np.uint8), iterations=3)
    alpha = cv2.GaussianBlur(cv2.erode(hard, np.ones((3, 3), np.uint8)).astype(np.float32), (0, 0), 1.3)
    # colour of the person just inside the edge, spread outward, replaces the bleed at the rim
    cf = core.astype(np.float32)
    num = cv2.GaussianBlur(rgb * cf[..., None], (0, 0), 5)
    den = cv2.GaussianBlur(cf, (0, 0), 5)[..., None]
    inner = num / np.maximum(den, 1e-3)
    edge = np.clip(1.0 - cv2.GaussianBlur(cf, (0, 0), 1.5), 0.0, 1.0)[..., None]
    clean = np.where(den > 0.02, rgb * (1 - edge) + inner * edge, rgb)
    # the photo is cropped through the shoulders: fade those cut edges instead of a hard line
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    fade = 34.0
    f = smoothstep((h - 1 - ys) / fade)
    f = np.where(ys > 430, np.minimum(f, np.minimum(smoothstep(xs / fade), smoothstep((w - 1 - xs) / fade))), f)
    f = np.minimum(f, smoothstep(ys / 22.0))  # the hair tuft cut by the top edge
    return np.clip(alpha * f, 0.0, 1.0), clean


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


def lift_corners(rgb, half, amount):
    """Raises the outer parts of the mouth band (columns beyond ~35% of `half`), so the corners turn up."""
    out = rgb.copy()
    band = 26.0
    y_mid = MOUTH_YS + 3.0
    for x in range(int(MOUTH_XC - half * 1.5), int(MOUTH_XC + half * 1.5) + 1):
        t = abs(x - MOUTH_XC) / half
        s = abs(amount) * float(smoothstep((t - 0.35) / 0.65)) * float(smoothstep((1.6 - t) / 0.6))
        if s < 0.5:
            continue
        s = round(s) * (1 if amount > 0 else -1)
        for y in range(int(y_mid - band), int(y_mid + band) + 1):
            decay = max(0.0, 1.0 - abs(y - y_mid) / band)
            out[y, x] = rgb[min(len(rgb) - 1, y + round(s * decay)), x]
    return out


def mouth_variant(rgb, base, name):
    half, drop, lift, teeth_frac, tongue, rounded, corner_lift = VISEMES[name]
    src = base if rounded else rgb
    if corner_lift != 0.0:
        src = lift_corners(src, half, corner_lift)
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
        if name in BITE_LINE:
            bite = np.clip(1.0 - np.abs(v - 0.5) / 0.09, 0.0, 1.0)
            col = col * (1 - 0.5 * bite[:, None])
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
    matte, clean = compute_matte(rgb)
    head_out = np.where(matte[..., None] > 0.99, head, head * 0.0 + np.where(np.abs(head - rgb).max(axis=2, keepdims=True) > 0.5, head, clean))
    save_rgba(os.path.join(OUT, "avatar_head.png"), head_out, matte)

    lash_col = np.array([62, 44, 40], np.float32)
    layers = {}
    for side, eye, fp, closed, lash in (("left", LEFT_EYE, fl, closed_l, lash_l), ("right", RIGHT_EYE, fr, closed_r, lash_r)):
        layers[f"eyes/{side}_open.png"] = (rgb, fp)
        soft = np.asarray(Image.fromarray((lash * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.6)), np.float32) / 255.0
        soft = np.clip(soft * 1.25, 0.0, 1.0) * 0.72
        skin_with_lash = closed * (1 - soft[..., None]) + lash_col * soft[..., None]
        layers[f"eyes/{side}_closed.png"] = (skin_with_lash, fp)
    lash_soft = lambda l: np.clip(np.asarray(Image.fromarray((l * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.6)), np.float32) / 255.0 * 1.25, 0, 1) * 0.8
    for side, eye, fp, inner in (("left", LEFT_EYE, fl, 1.0), ("right", RIGHT_EYE, fr, -1.0)):
        ang, lash = angry_eye(rgb, eye, inner)
        soft = lash_soft(lash)
        layers[f"eyes/{side}_angry.png"] = (ang * (1 - soft[..., None]) + lash_col * soft[..., None], fp)
    brows_rgb, brow_alpha = move_brow(rgb, LEFT_BROW, rgb.copy(), np.zeros((h, w), np.float32), LEFT_BROW["angle"], int(BROW_DROP))
    brows_rgb, brow_alpha = move_brow(rgb, RIGHT_BROW, brows_rgb, brow_alpha, RIGHT_BROW["angle"], int(BROW_DROP))
    layers["eyebrows/angry.png"] = (brows_rgb, brow_alpha)
    up_rgb, up_alpha = move_brow(rgb, LEFT_BROW, rgb.copy(), np.zeros((h, w), np.float32), -LEFT_BROW["angle"] * 0.6, -13)
    up_rgb, up_alpha = move_brow(rgb, RIGHT_BROW, up_rgb, up_alpha, -RIGHT_BROW["angle"] * 0.6, -13)
    layers["eyebrows/raised.png"] = (up_rgb, up_alpha)
    layers["eyes/left_wide.png"] = (wide_eye(rgb, LEFT_EYE), fl)
    layers["eyes/right_wide.png"] = (wide_eye(rgb, RIGHT_EYE), fr)
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
            bg = Image.new("RGBA", im.size, (28, 24, 34, 255))
            return Image.alpha_composite(bg, im).convert("RGB")

        combos = {
            "static": ["eyes/left_open.png", "eyes/right_open.png", "mouth/neutral.png"],
            "blink": ["eyes/left_closed.png", "eyes/right_closed.png", "mouth/neutral.png"],
            "wink_l": ["eyes/left_closed.png", "eyes/right_open.png", "mouth/neutral.png"],
            "wink_r": ["eyes/left_open.png", "eyes/right_closed.png", "mouth/neutral.png"],
        }
        for name in VISEMES:
            combos[f"mouth_{name}"] = ["eyes/left_open.png", "eyes/right_open.png", f"mouth/{name}.png"]
        def combo(parts, weights=None):
            im = Image.open(os.path.join(OUT, "avatar_head.png")).convert("RGBA")
            for i, n in enumerate(parts):
                layer = Image.open(os.path.join(OUT, n)).convert("RGBA")
                if weights and weights[i] < 1.0:
                    a = layer.getchannel("A").point(lambda v, k=weights[i]: int(v * k))
                    layer.putalpha(a)
                im = Image.alpha_composite(im, layer)
            bg = Image.new("RGBA", im.size, (28, 24, 34, 255))
            return Image.alpha_composite(bg, im).convert("RGB")
        E = ["eyes/left_open.png", "eyes/right_open.png"]
        AE = ["eyes/left_angry.png", "eyes/right_angry.png"]
        combos_w = {
            "anger": (E + AE + ["eyebrows/angry.png", "mouth/neutral.png", "mouth/anger.png"], None),
            "anger_a": (E + AE + ["eyebrows/angry.png", "mouth/neutral.png", "mouth/anger.png", "mouth/a.png"], [1, 1, 1, 1, 1, 1, 1, .6]),
            "anger_blink": (E + AE + ["eyes/left_closed.png", "eyes/right_closed.png", "eyebrows/angry.png", "mouth/neutral.png", "mouth/anger.png"], None),
            "anger_wink_l": (E + AE + ["eyes/left_closed.png", "eyebrows/angry.png", "mouth/neutral.png", "mouth/anger.png"], None),
            "wow": (["eyes/left_open.png", "eyes/right_open.png", "eyes/left_wide.png", "eyes/right_wide.png", "eyebrows/raised.png", "mouth/neutral.png", "mouth/wow.png"], None),
            "smile_a": (E + ["mouth/neutral.png", "mouth/smile.png", "mouth/a.png"], [1, 1, 1, 1, .6]),
            "wow_o": (E + ["mouth/neutral.png", "mouth/wow.png", "mouth/o.png"], [1, 1, 1, 1, .6]),
            "smile_wink": (["eyes/left_closed.png", "eyes/right_open.png", "mouth/neutral.png", "mouth/smile.png"], None),
        }
        for name, (parts, wts) in combos_w.items():
            combo(parts, wts).save(os.path.join(args.preview, name + ".png"))
        for name, parts in combos.items():
            composite(*parts).save(os.path.join(args.preview, name + ".png"))
        inside = matte > 0.99
        diff = np.abs(np.asarray(composite(*combos["static"]), np.float32) - rgb)[inside].max()
        print("static composite max abs diff vs original (inside the matte):", diff)


if __name__ == "__main__":
    main()
