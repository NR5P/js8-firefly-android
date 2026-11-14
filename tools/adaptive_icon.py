"""Render the Android adaptive launcher icon layers from the JS8 Firefly artwork.

Run: python3 tools/adaptive_icon.py [preview.png]

Reuses the drawing code of the iPhone repo's "classic" icon
(../js8calliphone/Branding/firefly/generate.py; set JS8_BRANDING if it lives
elsewhere) and writes ic_launcher_foreground.png / ic_launcher_background.png
into app/src/main/res/mipmap-*.

An adaptive icon is a 108dp canvas of which launchers show the middle 72dp,
cut to their own shape. The original square artwork is scaled to ART_DP so the
firefly, trail and wordmark stay inside every mask, and the sky and waterfall
are extended to fill the rest of the canvas.
"""
import importlib.util
import os
import random
import sys

from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
BRANDING = os.environ.get(
    "JS8_BRANDING", os.path.join(ROOT, "..", "js8calliphone", "Branding", "firefly", "generate.py"))

spec = importlib.util.spec_from_file_location("firefly_art", BRANDING)
art = importlib.util.module_from_spec(spec)
spec.loader.exec_module(art)

CANVAS_DP = 108
ART_DP = 62                                # width the original square artwork gets
S = art.S                                  # artwork units (2048)
B = round(S * CANVAS_DP / ART_DP)          # full canvas in artwork units
O = (B - S) // 2                           # artwork origin inside the canvas

DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def foreground():
    """Tone trail, firefly and wordmark exactly as in the classic icon, on transparency."""
    img = art.layer()
    trail = art.layer()
    art.tone_trail(ImageDraw.Draw(trail), (300, 1500), (95, -62), art.TONES[:9], art.GLOW)
    img = art.glow_paste(img, trail, 26)
    img = art.firefly(img, 1400, 760, 1.05, -35)
    img = art.wordmark(img)
    out = Image.new("RGBA", (B, B), (0, 0, 0, 0))
    out.paste(img, (O, O))
    return out


def background():
    """The classic icon's night sky and faint waterfall, extended to the whole canvas."""
    top, bottom = (9, 11, 19), (15, 21, 38)
    img = Image.new("RGBA", (B, B))
    d = ImageDraw.Draw(img)
    for y in range(B):
        t = min(1.0, max(0.0, (y - O) / S))
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        d.line([(0, y), (B, y)], fill=c + (255,))
    g = Image.new("RGBA", (B, B), (0, 0, 0, 0))
    ImageDraw.Draw(g).ellipse([O + S * 0.15, O + S * 0.12, O + S * 0.85, O + S * 0.82], fill=(40, 70, 140, 70))
    img = Image.alpha_composite(img, g.filter(ImageFilter.GaussianBlur(220)))
    wf = Image.new("RGBA", (B, B), (0, 0, 0, 0))
    d = ImageDraw.Draw(wf)
    random.seed(7)
    for k in range(-5, 16):
        x0 = O + 90 + k * 180 + random.randint(-30, 30)
        w = random.randint(24, 46)
        for y in range(0, B, 14):
            if random.random() < 0.5:
                d.rectangle([x0, y, x0 + w, y + 8], fill=art.CYAN + (int(30 + 40 * random.random()),))
    return Image.alpha_composite(img, wf).convert("RGB")


def preview(fg, bg, path):
    """What launchers show: the middle 72dp as a circle, a squircle and a square."""
    n = 432
    full = Image.alpha_composite(bg.convert("RGBA"), fg).resize((n, n), Image.LANCZOS)
    v = n * 72 // CANVAS_DP
    shown = full.crop(((n - v) // 2, (n - v) // 2, (n + v) // 2, (n + v) // 2))
    sheet = Image.new("RGB", (3 * v + 80, v + 40), (60, 60, 60))
    for i, radius in enumerate([v // 2, v // 4, 0]):
        mask = Image.new("L", (v * 4, v * 4), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, v * 4 - 1, v * 4 - 1], radius=radius * 4, fill=255)
        sheet.paste(shown, (20 + i * (v + 20), 20), mask.resize((v, v), Image.LANCZOS))
    sheet.save(path)


def main():
    fg, bg = foreground(), background()
    for name, px in DENSITIES.items():
        out = os.path.join(ROOT, "app", "src", "main", "res", f"mipmap-{name}")
        os.makedirs(out, exist_ok=True)
        fg.resize((px, px), Image.LANCZOS).save(os.path.join(out, "ic_launcher_foreground.png"), optimize=True)
        bg.resize((px, px), Image.LANCZOS).save(os.path.join(out, "ic_launcher_background.png"), optimize=True)
    if len(sys.argv) > 1:
        preview(fg, bg, sys.argv[1])


if __name__ == "__main__":
    main()
