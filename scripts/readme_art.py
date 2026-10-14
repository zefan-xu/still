#!/usr/bin/env python3
"""Draws the README hero, download button and screenshot showcase in the app's palette and type.

Text becomes SVG paths from the app's own Source Serif 4 files, since GitHub images cannot load
fonts. The showcase is composited from `scripts/test.py screenshots` output.

    python3 -m pip install pillow fonttools uharfbuzz
    python3 scripts/test.py screenshots --serial emulator-5554
    python3 scripts/readme_art.py artifacts/test-runs/<run>/screenshots
"""
import argparse
from pathlib import Path
import re

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import uharfbuzz as hb

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "readme"
FONTS = ROOT / "app" / "src" / "main" / "assets" / "fonts"
DISPLAY = FONTS / "SourceSerif4Display-Regular.ttf"
SEMIBOLD = FONTS / "SourceSerif4-Semibold.ttf"
TRAY = ROOT / "tools" / "figures" / "out"

# Theme.java
PALETTE = {
    "light": {"bg": "#FAF9F5", "raised": "#F0EEE6", "ink": "#141413", "secondary": "#5E5D59",
              "tertiary": "#737269", "hairline": "#E8E6DC", "accent": "#D97757", "onInk": "#FAF9F5",
              "bezel": "#141413"},
    "dark": {"bg": "#1F1E1D", "raised": "#30302E", "ink": "#FAF9F5", "secondary": "#B7B5A9",
             "tertiary": "#9C9A90", "hairline": "#3D3D3A", "accent": "#D97757", "onInk": "#1F1E1D",
             "bezel": "#0B0B0A"},
}
PLATFORMS = ["X", "Instagram", "TikTok", "Facebook", "Pinterest", "Threads", "Bluesky", "YouTube"]
SHOWCASE = [
    ("library", "Your library", "Everything you saved, by day"),
    ("save", "Save from any app", "Share a post, keep what you want"),
    ("actions", "Long-press for more", "Share, make a GIF or delete"),
    ("settings", "Quiet settings", "Quality, folder, Wi-Fi only"),
]


class Face:
    def __init__(self, path):
        self.font = TTFont(path)
        self.glyphs = self.font.getGlyphSet()
        self.upem = self.font["head"].unitsPerEm
        blob = hb.Blob.from_file_path(str(path))
        self.hb = hb.Font(hb.Face(blob))

    def shape(self, text, size, tracking=0.0):
        buffer = hb.Buffer()
        buffer.add_str(text)
        buffer.guess_segment_properties()
        hb.shape(self.hb, buffer, {"kern": True, "liga": True})
        scale = size / self.upem
        x, placed = 0.0, []
        order = self.font.getGlyphOrder()
        for info, pos in zip(buffer.glyph_infos, buffer.glyph_positions):
            placed.append((order[info.codepoint], x + pos.x_offset * scale, pos.y_offset * scale))
            x += pos.x_advance * scale + tracking
        return placed, x - tracking if placed else 0.0

    def width(self, text, size, tracking=0.0):
        return self.shape(text, size, tracking)[1]

    def path(self, text, size, x, y, tracking=0.0):
        """SVG path data for text with its baseline starting at (x, y)."""
        scale = size / self.upem
        placed, _ = self.shape(text, size, tracking)
        data = []
        for name, dx, dy in placed:
            pen = SVGPathPen(self.glyphs)
            self.glyphs[name].draw(TransformPen(pen, (scale, 0, 0, -scale, x + dx, y - dy)))
            data.append(pen.getCommands())
        return " ".join(d for d in data if d)

    def text(self, text, size, x, y, fill, tracking=0.0, anchor="start"):
        if anchor == "middle":
            x -= self.width(text, size, tracking) / 2
        return f'<path fill="{fill}" d="{self.path(text, size, x, y, tracking)}"/>'


def tray(theme):
    """The app's assembled tray figure (third frame), 240×234 at the origin."""
    svg = (TRAY / f"fig_tray-{theme}.svg").read_text()
    body = re.sub(r"^<svg[^>]*>|</svg>$", "", svg.strip())
    body = re.sub(r'^<rect width="100%" height="100%"[^>]*/>', "", body)
    for frame in ("0 0", "256 0"):
        body = re.sub(rf'<g transform="translate\({frame}\)">.*?</g>', "", body, count=1)
    return f'<g transform="translate(-512 0)">{body}</g>'


def hero(theme, display, semibold):
    c = PALETTE[theme]
    w, h, left = 1280, 520, 96
    word = display.text("Still", 168, left - 6, 236, c["ink"])
    period_x = left - 6 + display.width("Still", 168) + 4
    period = f'<circle cx="{period_x + 13:.1f}" cy="224" r="13" fill="{c["accent"]}"/>'
    lines = ["Save the photos and videos", "from public posts."]
    tagline = "".join(display.text(line, 44, left, 318 + i * 54, c["secondary"]) for i, line in enumerate(lines))
    plain = semibold.text("No ads, no account, no tracking.", 22, left, 446, c["tertiary"], tracking=0.4)
    figure = f'<g transform="translate(760 92) scale(1.72)">{tray(theme)}</g>'
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}" role="img">'
            f'<title>Still: save the photos and videos from public posts. No ads, no account, no tracking.</title>'
            f'<rect x="1" y="1" width="{w - 2}" height="{h - 2}" rx="28" fill="{c["bg"]}" stroke="{c["hairline"]}" stroke-width="2"/>'
            f'{word}{period}{tagline}{plain}{figure}</svg>\n')


def platforms(theme, semibold):
    c = PALETTE[theme]
    size, tracking, gap = 21, 2.4, 44
    labels = [p.upper() for p in PLATFORMS]
    widths = [semibold.width(label, size, tracking) for label in labels]
    w = sum(widths) + gap * (len(labels) - 1) + 8
    x, parts = 4.0, []
    for i, (label, width) in enumerate(zip(labels, widths)):
        parts.append(semibold.text(label, size, x, 30, c["tertiary"], tracking))
        x += width
        if i < len(labels) - 1:
            parts.append(f'<circle cx="{x + gap / 2:.1f}" cy="22.5" r="2.6" fill="{c["accent"]}"/>')
            x += gap
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w:.0f} 44" width="{w:.0f}" height="44" role="img">'
            f'<title>Works with {", ".join(PLATFORMS[:-1])} and {PLATFORMS[-1]}</title>{"".join(parts)}</svg>\n')


def button(theme, semibold):
    c = PALETTE[theme]
    label, size = "Download for Android", 26
    text_w = semibold.width(label, size)
    h, pad, icon = 72, 34, 22
    w = pad + icon + 16 + text_w + pad
    cx, cy = pad + icon / 2, h / 2
    arrow = (f'<path d="M{cx} {cy - 11}V{cy + 7}M{cx - 7.5} {cy - 0.5}L{cx} {cy + 7}L{cx + 7.5} {cy - 0.5}" '
             f'fill="none" stroke="{c["accent"]}" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>'
             f'<path d="M{cx - 10} {cy + 13}H{cx + 10}" stroke="{c["onInk"]}" stroke-width="3" stroke-linecap="round"/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w:.0f} {h}" width="{w:.0f}" height="{h}" role="img">'
            f'<title>{label}</title><rect width="{w:.0f}" height="{h}" rx="{h / 2}" fill="{c["ink"]}"/>{arrow}'
            f'{semibold.text(label, size, pad + icon + 16, cy + 9, c["onInk"])}</svg>\n')


def rounded(size, radius):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return mask


def rgb(value):
    return tuple(int(value[i:i + 2], 16) for i in (1, 3, 5))


def showcase(theme, shots):
    """Four phones on the app background, captioned in Source Serif, at 2× for sharp text."""
    c = PALETTE[theme]
    screen_w, bezel, gap, margin, top = 500, 14, 64, 88, 88
    screen_h = round(screen_w * 2340 / 1080)
    phone_w, phone_h = screen_w + 2 * bezel, screen_h + 2 * bezel
    caption_h = 150
    w = margin * 2 + phone_w * 4 + gap * 3
    h = top + phone_h + caption_h + 40
    canvas = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(canvas).rounded_rectangle((0, 0, w - 1, h - 1), 56, fill=c["bg"], outline=c["hairline"], width=4)

    title_font = ImageFont.truetype(str(DISPLAY), 46)
    line_font = ImageFont.truetype(str(SEMIBOLD), 28)
    draw = ImageDraw.Draw(canvas)
    for i, (name, title, line) in enumerate(SHOWCASE):
        x = margin + i * (phone_w + gap)
        shadow = Image.new("L", (phone_w + 120, phone_h + 120), 0)
        ImageDraw.Draw(shadow).rounded_rectangle((60, 76, 60 + phone_w, 76 + phone_h), 70, fill=70 if theme == "light" else 150)
        shadow = shadow.filter(ImageFilter.GaussianBlur(26))
        canvas.paste(Image.new("RGBA", shadow.size, (20, 20, 19, 255)), (x - 60, top - 60), shadow)
        body = Image.new("RGBA", (phone_w, phone_h), rgb(c["bezel"]) + (255,))
        canvas.paste(body, (x, top), rounded((phone_w, phone_h), 70))
        if theme == "dark":
            ring = Image.new("L", (phone_w, phone_h), 0)
            ImageDraw.Draw(ring).rounded_rectangle((0, 0, phone_w - 1, phone_h - 1), 70, outline=255, width=2)
            canvas.paste(Image.new("RGBA", (phone_w, phone_h), rgb(c["hairline"]) + (255,)), (x, top), ring)
        screen = Image.open(shots / f"{name}-{theme}.png").convert("RGBA").resize((screen_w, screen_h), Image.LANCZOS)
        canvas.paste(screen, (x + bezel, top + bezel), rounded((screen_w, screen_h), 56))
        centre = x + phone_w / 2
        baseline = top + phone_h + 78
        draw.text((centre, baseline), title, font=title_font, fill=c["ink"], anchor="ms")
        draw.text((centre, baseline + 46), line, font=line_font, fill=c["tertiary"], anchor="ms")
    return canvas


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("screenshots", type=Path, help="Directory written by scripts/test.py screenshots")
    args = parser.parse_args()
    display, semibold = Face(DISPLAY), Face(SEMIBOLD)
    OUT.mkdir(parents=True, exist_ok=True)
    for theme in PALETTE:
        (OUT / f"hero-{theme}.svg").write_text(hero(theme, display, semibold))
        (OUT / f"platforms-{theme}.svg").write_text(platforms(theme, semibold))
        (OUT / f"download-{theme}.svg").write_text(button(theme, semibold))
        showcase(theme, args.screenshots).save(OUT / f"screens-{theme}.png", optimize=True)
    print(f"Wrote {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
