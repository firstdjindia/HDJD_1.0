#!/usr/bin/env python3
"""Installs a logo and regenerates every raster asset derived from it.

    python3 tools/generate_assets.py path/to/logo.png

Writes (relative to app/src/main/res):
  drawable-nodpi/hdjd_logo.png     the logo itself (used by splash + adaptive icon)
  mipmap-*/ic_launcher*.png        legacy launcher icons for Android 7.0/7.1 (API 24-25)
  drawable-xhdpi/tv_banner.png     640x360 Android TV banner

Needs Pillow (pip install pillow). Prefer a transparent PNG, ideally square and >= 512 px.
"""
import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"


def brand_background():
    xml = (RES / "values/colors.xml").read_text()
    m = re.search(r'name="hdjd_background">#([0-9A-Fa-f]{6})<', xml)
    h = m.group(1) if m else "0B1220"
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def fit(logo, box):
    """Scale logo to fit inside a square/rect box (w, h) keeping aspect ratio."""
    w, h = logo.size
    s = min(box[0] / w, box[1] / h)
    return logo.resize((max(1, round(w * s)), max(1, round(h * s))), Image.LANCZOS)


def paste_center(canvas, img):
    x = (canvas.width - img.width) // 2
    y = (canvas.height - img.height) // 2
    canvas.alpha_composite(img, (x, y))


def main(src):
    bg = brand_background()
    logo = Image.open(src)
    logo.load()
    logo = logo.convert("RGBA")
    if logo.getchannel("A").getextrema()[0] == 255:
        print("note: logo has no transparency; it will appear as a solid rectangle on the "
              "splash screen. A transparent PNG is recommended.", file=sys.stderr)
    if max(logo.size) > 1024:
        logo = fit(logo, (1024, 1024))
    out = RES / "drawable-nodpi"
    out.mkdir(parents=True, exist_ok=True)
    logo.save(out / "hdjd_logo.png", optimize=True)

    # Legacy launcher icons (square-with-rounded-corners and round).
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for dpi, px in sizes.items():
        scale = 4  # supersample for smooth edges
        big = px * scale
        for name, round_icon in (("ic_launcher.png", False), ("ic_launcher_round.png", True)):
            canvas = Image.new("RGBA", (big, big), (0, 0, 0, 0))
            mask = Image.new("L", (big, big), 0)
            d = ImageDraw.Draw(mask)
            if round_icon:
                d.ellipse((0, 0, big - 1, big - 1), fill=255)
            else:
                d.rounded_rectangle((0, 0, big - 1, big - 1), radius=big // 5, fill=255)
            plate = Image.new("RGBA", (big, big), bg)
            canvas.paste(plate, (0, 0), mask)
            paste_center(canvas, fit(logo, (int(big * 0.66), int(big * 0.66))))
            # clip logo to the plate shape too
            final = Image.new("RGBA", (big, big), (0, 0, 0, 0))
            final.paste(canvas, (0, 0), mask)
            final = final.resize((px, px), Image.LANCZOS)
            d_out = RES / f"mipmap-{dpi}"
            d_out.mkdir(parents=True, exist_ok=True)
            final.save(d_out / name, optimize=True)

    # Android TV banner, 320x180 dp at xhdpi.
    banner = Image.new("RGBA", (640, 360), bg)
    paste_center(banner, fit(logo, (300, 240)))
    d_out = RES / "drawable-xhdpi"
    d_out.mkdir(parents=True, exist_ok=True)
    banner.convert("RGB").save(d_out / "tv_banner.png", optimize=True)
    print("assets generated from", src)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
