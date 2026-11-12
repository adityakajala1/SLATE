"""
SLATE icon generator.
Renders a charcoal writing slate with a single white chalk stick resting diagonally
against it. Procedural (numpy + Pillow) so it is reproducible and crisp at all sizes.

Outputs:
  out/foreground_1024.png   adaptive-icon foreground (transparent, 108dp canvas)
  out/background_1024.png   adaptive-icon background
  out/full_1024.png         flattened square icon
  out/playstore_512.png     Play Store icon
  + density specific mipmaps written straight into the Android res folder.
"""
import os, math
import numpy as np
from PIL import Image, ImageFilter, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
RES = os.path.join(HERE, "..", "app", "src", "main", "res")
os.makedirs(OUT, exist_ok=True)

S = 2048  # supersampled canvas
rng = np.random.default_rng(7)
yy, xx = np.mgrid[0:S, 0:S].astype(np.float32)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def rrect_sdf(cx, cy, hw, hh, r):
    qx = np.abs(xx - cx) - (hw - r)
    qy = np.abs(yy - cy) - (hh - r)
    outside = np.sqrt(np.maximum(qx, 0) ** 2 + np.maximum(qy, 0) ** 2)
    inside = np.minimum(np.maximum(qx, qy), 0)
    return outside + inside - r


def noise(scale, octaves=3):
    """Value-noise via upsampled random grids."""
    acc = np.zeros((S, S), np.float32)
    amp, tot = 1.0, 0.0
    for o in range(octaves):
        n = max(2, int(S / scale * (2 ** o)))
        g = rng.random((n, n)).astype(np.float32)
        im = Image.fromarray((g * 255).astype(np.uint8)).resize((S, S), Image.BICUBIC)
        acc += amp * (np.asarray(im, np.float32) / 255.0)
        tot += amp
        amp *= 0.5
    return acc / tot


def blur(a, r):
    im = Image.fromarray(np.clip(a * 255, 0, 255).astype(np.uint8))
    return np.asarray(im.filter(ImageFilter.GaussianBlur(r)), np.float32) / 255.0


def over(dst_rgb, dst_a, src_rgb, src_a):
    """Porter-Duff source-over on float arrays."""
    out_a = src_a + dst_a * (1 - src_a)
    safe = np.where(out_a > 1e-6, out_a, 1)
    out_rgb = (src_rgb * src_a[..., None] + dst_rgb * dst_a[..., None] * (1 - src_a[..., None])) / safe[..., None]
    return out_rgb, out_a


C = S / 2
# ---------------------------------------------------------------- background
def make_background():
    d = np.sqrt((xx - C) ** 2 + (yy - C * 0.82) ** 2) / S
    base = np.array([0.105, 0.105, 0.11]) * (1 - 0.55 * smoothstep(0.05, 0.85, d))[..., None]
    base += (noise(6, 1)[..., None] - 0.5) * 0.012
    return np.clip(base, 0, 1)


# ---------------------------------------------------------------- slate board
def make_foreground():
    rgb = np.zeros((S, S, 3), np.float32)
    a = np.zeros((S, S), np.float32)
    px = 1.5  # anti-alias width

    bw, bh = 508, 420            # half sizes of frame (fits the 66dp safe zone)
    cx, cy = C, C - 30
    frame_r = 104

    # drop shadow of the board
    sd = rrect_sdf(cx, cy + 46, bw - 6, bh - 6, frame_r)
    sh = blur((sd < 0).astype(np.float32), 46) * 0.62
    rgb, a = over(rgb, a, np.zeros_like(rgb), sh)

    # frame (dark graphite with bevel)
    fd = rrect_sdf(cx, cy, bw, bh, frame_r)
    fa = 1 - smoothstep(-px, px, fd)
    vert = (yy - (cy - bh)) / (2 * bh)
    frame_col = np.array([0.20, 0.20, 0.205]) * (1.12 - 0.35 * vert)[..., None]
    # bevel: bright top-left rim, darker bottom-right rim
    rim = smoothstep(-26, -2, fd) * (1 - smoothstep(-2, px, fd))
    light = np.clip(((cx - xx) * 0.4 + (cy - yy)) / (bh * 1.4), -1, 1)
    frame_col = frame_col + (rim * light * 0.16)[..., None]
    frame_col += (noise(10, 2)[..., None] - 0.5) * 0.035
    rgb, a = over(rgb, a, np.clip(frame_col, 0, 1), fa)

    # inner slate surface
    inset = 58
    sd2 = rrect_sdf(cx, cy, bw - inset, bh - inset, frame_r - inset + 12)
    sa = 1 - smoothstep(-px, px, sd2)
    grain = noise(3, 2)
    smudge = blur(noise(260, 3), 30)
    v2 = (yy - (cy - bh)) / (2 * bh)
    slate = np.array([0.155, 0.16, 0.168])[None, None, :] * (1.08 - 0.28 * v2)[..., None]
    slate = slate + ((grain - 0.5) * 0.045)[..., None]
    # faint erased-chalk haze (subtle realism)
    slate = slate + (smoothstep(0.52, 0.75, smudge) * 0.05)[..., None]
    # soft top-light glow
    glow = np.exp(-(((xx - (cx - 140)) / 520) ** 2 + ((yy - (cy - 260)) / 300) ** 2))
    slate = slate + (glow * 0.05)[..., None]
    # inner shadow cast by the frame onto the slate
    ish = (1 - smoothstep(-34, 0, sd2)) * 0 + smoothstep(-34, 0, sd2)
    ish_dir = np.clip((yy - cy) / bh * -1, 0, 1) * 0.6 + 0.4
    slate = slate * (1 - (ish * 0.55 * ish_dir))[..., None]
    rgb, a = over(rgb, a, np.clip(slate, 0, 1), sa)

    # ---------------------------------------------------------- chalk stick
    # capsule from p0 (tip, lower-left) to p1 (upper-right), resting across the board's lower edge
    p0 = np.array([C - 430, C + 330])
    p1 = np.array([C + 405, C + 80])
    rad = 52.0
    d = p1 - p0
    L = np.linalg.norm(d)
    ux, uy = d / L
    nx, ny = -uy, ux
    rx, ry = xx - p0[0], yy - p0[1]
    u = rx * ux + ry * uy           # along axis
    v = rx * nx + ry * ny           # across axis

    # slight taper at the used (left) end -> worn tip
    taper = 1 - 0.18 * (1 - smoothstep(0, 140, u))
    r_here = rad * taper
    uc = np.clip(u, 0, L)
    cxp = p0[0] + uc * ux
    cyp = p0[1] + uc * uy
    dist = np.sqrt((xx - cxp) ** 2 + (yy - cyp) ** 2) - r_here
    # flatten the right end a bit (snapped end)
    end_cut = u - (L + rad * 0.55)
    dist = np.maximum(dist, end_cut)

    # chalk shadow on the slate/frame
    shadow_mask = (np.roll(np.roll(dist, 34, axis=0), 14, axis=1) < 0).astype(np.float32)
    csh = blur(shadow_mask, 22) * 0.7
    rgb, a = over(rgb, a, np.zeros_like(rgb), csh)
    contact = (np.roll(dist, 10, axis=0) < 6).astype(np.float32)
    rgb, a = over(rgb, a, np.zeros_like(rgb), blur(contact, 6) * 0.35)

    ca = 1 - smoothstep(-px, px, dist)
    # cylindrical shading across v
    t = np.clip(v / np.maximum(r_here, 1), -1, 1)       # -1 .. 1 across the stick
    nz = np.sqrt(np.clip(1 - t * t, 0, 1))
    # light from upper-left
    lx, ly, lz = -0.45, -0.65, 0.62
    # normal in screen space: n = t*(nx,ny) + nz*z
    ndl = np.clip(t * (nx * lx + ny * ly) + nz * lz, 0, 1)
    shade = 0.62 + 0.38 * ndl
    spec = np.power(np.clip(ndl, 0, 1), 18) * 0.12
    chalk_grain = noise(2.2, 2)
    pits = smoothstep(0.80, 0.95, noise(5, 1)) * 0.10
    base = 0.955 * shade + spec - (chalk_grain - 0.5) * 0.10 - pits
    # end caps slightly darker / dusty
    cap = smoothstep(L - 20, L + rad, u) * 0.10 + (1 - smoothstep(-rad, 30, u)) * 0.05
    base = base - cap
    chalk = np.stack([base * 0.995, base * 0.99, base * 0.975], -1)
    rgb, a = over(rgb, a, np.clip(chalk, 0, 1), ca)

    # a whisper of chalk dust near the worn tip
    dust = np.exp(-(((xx - (p0[0] + 40)) / 120) ** 2 + ((yy - (p0[1] - 80)) / 60) ** 2))
    speck = smoothstep(0.86, 0.97, noise(1.6, 1)) * dust * 0.55 * sa
    rgb, a = over(rgb, a, np.ones_like(rgb) * 0.9, speck)

    return rgb, a


def to_img(rgb, a=None):
    rgb8 = (np.clip(rgb, 0, 1) * 255 + 0.5).astype(np.uint8)
    if a is None:
        return Image.fromarray(rgb8, "RGB")
    a8 = (np.clip(a, 0, 1) * 255 + 0.5).astype(np.uint8)
    return Image.fromarray(np.dstack([rgb8, a8]), "RGBA")


def main():
    bg = make_background()
    fg_rgb, fg_a = make_foreground()
    fg = to_img(fg_rgb, fg_a).resize((1024, 1024), Image.LANCZOS)
    bgi = to_img(bg).resize((1024, 1024), Image.LANCZOS)
    fg.save(os.path.join(OUT, "foreground_1024.png"))
    bgi.save(os.path.join(OUT, "background_1024.png"))
    full = bgi.convert("RGBA")
    full.alpha_composite(fg)
    full.save(os.path.join(OUT, "full_1024.png"))

    # Play Store icon 512 (full bleed, square; Play applies its own mask)
    full.convert("RGB").resize((512, 512), Image.LANCZOS).save(os.path.join(OUT, "playstore_512.png"))

    # Legacy icons are the 108dp canvas cropped to its central 72dp-ish area, then masked.
    crop = int(1024 * (18 / 108))
    legacy_sq = full.crop((crop, crop, 1024 - crop, 1024 - crop))

    def rounded(img, radius_frac):
        n = img.size[0]
        m = Image.new("L", (n * 4, n * 4), 0)
        ImageDraw.Draw(m).rounded_rectangle((0, 0, n * 4 - 1, n * 4 - 1), radius=int(n * 4 * radius_frac), fill=255)
        m = m.resize((n, n), Image.LANCZOS)
        o = img.copy(); o.putalpha(m); return o

    def circle(img):
        n = img.size[0]
        m = Image.new("L", (n * 4, n * 4), 0)
        ImageDraw.Draw(m).ellipse((0, 0, n * 4 - 1, n * 4 - 1), fill=255)
        m = m.resize((n, n), Image.LANCZOS)
        o = img.copy(); o.putalpha(m); return o

    dens = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
    for name, f in dens.items():
        d = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        fgs = int(108 * f)
        fg.resize((fgs, fgs), Image.LANCZOS).save(os.path.join(d, "ic_launcher_foreground.png"))
        bgi.resize((fgs, fgs), Image.LANCZOS).save(os.path.join(d, "ic_launcher_background.png"))
        ls = int(48 * f)
        sq = legacy_sq.resize((ls, ls), Image.LANCZOS)
        rounded(sq, 0.22).save(os.path.join(d, "ic_launcher.png"))
        circle(sq).save(os.path.join(d, "ic_launcher_round.png"))

    # in-app brand mark (used in header / about / splash)
    dn = os.path.join(RES, "drawable-nodpi")
    os.makedirs(dn, exist_ok=True)
    rounded(legacy_sq.resize((384, 384), Image.LANCZOS), 0.24).save(os.path.join(dn, "slate_mark.png"))
    print("icons written")


if __name__ == "__main__":
    main()

