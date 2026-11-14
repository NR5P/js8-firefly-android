"""Render the Google Play feature graphic (1024 x 500) from the JS8 Firefly artwork.

Run: python3 tools/play_graphics.py

Uses the drawing code of the iPhone repo's icon
(../js8calliphone/Branding/firefly/generate.py; set JS8_BRANDING if it lives
elsewhere) and writes Docs/PlayStore/graphics/feature-graphic.png.
"""
import importlib.util
import os
import random

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
BRANDING = os.environ.get(
    "JS8_BRANDING", os.path.join(ROOT, "..", "js8calliphone", "Branding", "firefly", "generate.py"))

spec = importlib.util.spec_from_file_location("firefly_art", BRANDING)
art = importlib.util.module_from_spec(spec)
spec.loader.exec_module(art)

S = art.S                       # the artwork's square canvas (2048)
W, H = 2048, 1000               # the graphic at 2x, cut from the middle of that canvas
TOP = (S - H) // 2


def feature_graphic():
    img = art.background()
    wf = art.layer()
    d = ImageDraw.Draw(wf)
    random.seed(7)
    for k in range(11):
        x0 = 90 + k * 180 + random.randint(-30, 30)
        w = random.randint(24, 46)
        for y in range(0, S, 14):
            if random.random() < 0.5:
                d.rectangle([x0, y, x0 + w, y + 8], fill=art.CYAN + (int(30 + 40 * random.random()),))
    img = Image.alpha_composite(img, wf)

    trail = art.layer()
    art.tone_trail(ImageDraw.Draw(trail), (1020, 1480), (72, -47), art.TONES[:6], art.GLOW, 11, 27)
    img = art.glow_paste(img, trail, 22)
    img = art.firefly(img, 1640, 1090, 0.8, -35)

    text = art.layer()
    d = ImageDraw.Draw(text)
    d.text((120, TOP + 250), "JS8 Firefly", font=art.font(210), fill=art.WHITE + (255,))
    img = art.glow_paste(img, text, 20)
    d = ImageDraw.Draw(img)
    d.text((128, TOP + 520), "JS8 messaging for ham radio", font=art.font(74, bold=False), fill=(170, 185, 220, 255))
    return img.crop((0, TOP, W, TOP + H)).convert("RGB").resize((1024, 500), Image.LANCZOS)


def main():
    out = os.path.join(ROOT, "Docs", "PlayStore", "graphics")
    os.makedirs(out, exist_ok=True)
    feature_graphic().save(os.path.join(out, "feature-graphic.png"), optimize=True)


if __name__ == "__main__":
    main()
