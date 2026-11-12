"""
Slate modern aesthetic icon generator.
Generates an ultra-clean, minimalist, aesthetic dark luxury icon for "Slate".
- Deep Obsidian / Charcoal titanium background with subtle radial glow and fine border rim
- Clean, refined slate tablet slate slab with frosted glass / obsidian bevel
- Elegant stylus / chalk accent resting at an angle with a luminous minimalist glow
- Crisp, super-sampled, exports to all Android mipmap densities and Play Store 512.
"""
import os, math
import numpy as np
from PIL import Image, ImageFilter, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
RES = os.path.join(HERE, "..", "app", "src", "main", "res")
os.makedirs(OUT, exist_ok=True)

S = 2048  # supersampled canvas
yy, xx = np.mgrid[0:S, 0:S].astype(np.float32)
C = S / 2.0

def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)

def rrect_sdf(cx, cy, hw, hh, r):
    qx = np.abs(xx - cx) - (hw - r)
    qy = np.abs(yy - cy) - (hh - r)
    outside = np.sqrt(np.maximum(qx, 0) ** 2 + np.maximum(qy, 0) ** 2)
    inside = np.minimum(np.maximum(qx, qy), 0)
    return outside + inside - r

def make_aesthetic_background():
    # Deep obsidian midnight gradient (#0B0F14 -> #121820) with subtle warm indigo-slate glow at center-top
    d = np.sqrt((xx - C) ** 2 + (yy - C * 0.75) ** 2) / S
    glow = smoothstep(0.7, 0.0, d)
    
    # Base dark gradient
    y_grad = yy / S
    top_col = np.array([0.08, 0.09, 0.11], dtype=np.float32)
    bot_col = np.array([0.04, 0.045, 0.055], dtype=np.float32)
    bg = top_col[None, None, :] * (1.0 - y_grad[..., None]) + bot_col[None, None, :] * y_grad[..., None]
    
    # Add subtle luxury indigo / slate glow in the upper center
    accent_glow = np.array([0.15, 0.20, 0.28], dtype=np.float32) * 0.35
    bg += glow[..., None] * accent_glow
    
    return np.clip(bg, 0.0, 1.0)

def make_aesthetic_foreground():
    rgb = np.zeros((S, S, 3), np.float32)
    a = np.zeros((S, S), np.float32)
    
    # 1. Main Slate Tablet (Smooth rounded floating obsidian tablet)
    # Dimensions: 520 x 680, radius: 100
    hw, hh, r = 380.0, 480.0, 88.0
    cy = C - 20.0
    cx = C
    
    d_slate = rrect_sdf(cx, cy, hw, hh, r)
    slate_mask = smoothstep(1.5, -1.5, d_slate)
    
    # Slate surface coloring: refined matte slate-gray with soft vertical sheen
    slate_grad = smoothstep(cy - hh, cy + hh, yy)
    slate_top = np.array([0.18, 0.19, 0.22], dtype=np.float32)
    slate_bot = np.array([0.10, 0.105, 0.12], dtype=np.float32)
    slate_body = slate_top[None, None, :] * (1.0 - slate_grad[..., None]) + slate_bot[None, None, :] * slate_grad[..., None]
    
    # Inner border / chamfer highlight on top rim
    rim_top = smoothstep(4.0, 0.0, np.abs(d_slate + 4.0)) * smoothstep(cy + hh, cy - hh * 0.5, yy) * 0.35
    slate_body += rim_top[..., None] * np.array([1.0, 1.0, 1.0], dtype=np.float32)
    
    # Drop shadow behind slate
    shadow_d = rrect_sdf(cx, cy + 32.0, hw + 10.0, hh + 10.0, r + 8.0)
    shadow_mask = smoothstep(60.0, -10.0, shadow_d) * 0.55
    
    # Apply shadow to canvas
    rgb += np.array([0.0, 0.0, 0.0], dtype=np.float32) * shadow_mask[..., None]
    a = np.maximum(a, shadow_mask)
    
    # Composite slate body
    rgb = rgb * (1.0 - slate_mask[..., None]) + slate_body * slate_mask[..., None]
    a = np.maximum(a, slate_mask)
    
    # 2. Add an aesthetic stylized "S" or minimal notebook lines / grid on the slate
    # Three sleek horizontal document lines in frosted translucent silver
    lines_y = [cy - 120.0, cy, cy + 120.0]
    lines_w = [360.0, 440.0, 260.0]
    for ly, lw in zip(lines_y, lines_w):
        line_d = rrect_sdf(cx - (440.0 - lw) / 2.0, ly, lw / 2.0, 10.0, 10.0)
        line_mask = smoothstep(1.5, -1.5, line_d) * 0.28
        line_col = np.array([0.65, 0.72, 0.85], dtype=np.float32)
        rgb = rgb * (1.0 - line_mask[..., None]) + line_col * line_mask[..., None]
    
    # 3. Modern Minimalist Stylus / Pen (Diagonal, sleek silver/titanium with luminous tip)
    # Angled from bottom-left (cx - 260, cy + 340) to top-right (cx + 280, cy - 200)
    theta = math.radians(-38.0)
    cos_t, sin_t = math.cos(theta), math.sin(theta)
    
    # Rotate coords centered at pen mid
    px0, py0 = cx + 20.0, cy + 60.0
    xr = (xx - px0) * cos_t - (yy - py0) * sin_t
    yr = (xx - px0) * sin_t + (yy - py0) * cos_t
    
    pen_hw, pen_hh = 420.0, 22.0
    pen_d = np.sqrt(np.maximum(np.abs(xr) - (pen_hw - 22.0), 0)**2 + np.maximum(np.abs(yr) - (pen_hh - 22.0), 0)**2) - 22.0
    pen_mask = smoothstep(1.5, -1.5, pen_d)
    
    # Pen shadow
    pen_shadow_d = np.sqrt(np.maximum(np.abs(xr - 15.0) - (pen_hw - 22.0), 0)**2 + np.maximum(np.abs(yr - 25.0) - (pen_hh - 22.0), 0)**2) - 22.0
    pen_shadow = smoothstep(40.0, -5.0, pen_shadow_d) * 0.45
    
    rgb = rgb * (1.0 - pen_shadow[..., None])
    a = np.maximum(a, pen_shadow)
    
    # Stylus Body gradient (sleek ceramic pearl white / silver titanium)
    pen_grad = (yr + pen_hh) / (pen_hh * 2.0)
    pen_col = np.array([0.96, 0.97, 0.99], dtype=np.float32)[None, None, :] * (1.0 - 0.22 * pen_grad[..., None])
    
    # Stylus Nib accent (accent teal/cyan glow at the tip: xr < -340)
    tip_glow = smoothstep(-280.0, -400.0, xr)
    tip_col = np.array([0.35, 0.80, 0.95], dtype=np.float32)
    pen_col = pen_col * (1.0 - tip_glow[..., None] * 0.7) + tip_col * (tip_glow[..., None] * 0.7)
    
    # Stylus tip luminous dot glow
    tip_pt_d = np.sqrt((xx - (px0 + (-400.0) * cos_t - (0) * sin_t)) ** 2 + (yy - (py0 + (-400.0) * sin_t + (0) * cos_t)) ** 2)
    glow_corona = smoothstep(120.0, 0.0, tip_pt_d) * 0.6
    glow_col = np.array([0.30, 0.85, 1.0], dtype=np.float32)
    
    # Composite pen onto slate
    rgb = rgb * (1.0 - pen_mask[..., None]) + pen_col * pen_mask[..., None]
    rgb += glow_col * glow_corona[..., None]
    a = np.maximum(a, np.maximum(pen_mask, glow_corona))
    
    return rgb, a

def to_img(rgb, a=None):
    rgb8 = (np.clip(rgb, 0, 1) * 255 + 0.5).astype(np.uint8)
    if a is None:
        return Image.fromarray(rgb8, "RGB")
    a8 = (np.clip(a, 0, 1) * 255 + 0.5).astype(np.uint8)
    return Image.fromarray(np.dstack([rgb8, a8]), "RGBA")

def main():
    bg = make_aesthetic_background()
    fg_rgb, fg_a = make_aesthetic_foreground()
    
    fg = to_img(fg_rgb, fg_a).resize((1024, 1024), Image.LANCZOS)
    bgi = to_img(bg).resize((1024, 1024), Image.LANCZOS)
    
    fg.save(os.path.join(OUT, "foreground_1024.png"))
    bgi.save(os.path.join(OUT, "background_1024.png"))
    
    full = bgi.convert("RGBA")
    full.alpha_composite(fg)
    full.save(os.path.join(OUT, "full_1024.png"))
    full.convert("RGB").resize((512, 512), Image.LANCZOS).save(os.path.join(OUT, "playstore_512.png"))
    
    # Legacy / round icons
    crop = int(1024 * (18 / 108))
    legacy_sq = full.crop((crop, crop, 1024 - crop, 1024 - crop))
    
    def rounded(img, radius_frac):
        n = img.size[0]
        m = Image.new("L", (n * 4, n * 4), 0)
        ImageDraw.Draw(m).rounded_rectangle((0, 0, n * 4 - 1, n * 4 - 1), radius=int(n * 4 * radius_frac), fill=255)
        m = m.resize((n, n), Image.LANCZOS)
        o = img.copy()
        o.putalpha(m)
        return o

    def circle(img):
        n = img.size[0]
        m = Image.new("L", (n * 4, n * 4), 0)
        ImageDraw.Draw(m).ellipse((0, 0, n * 4 - 1, n * 4 - 1), fill=255)
        m = m.resize((n, n), Image.LANCZOS)
        o = img.copy()
        o.putalpha(m)
        return o

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

    dn = os.path.join(RES, "drawable-nodpi")
    os.makedirs(dn, exist_ok=True)
    rounded(legacy_sq.resize((384, 384), Image.LANCZOS), 0.24).save(os.path.join(dn, "slate_mark.png"))
    print("Aesthetic icons successfully generated and saved across all Android densities!")

if __name__ == "__main__":
    main()
