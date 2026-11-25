"""Builds docs/showcase-strip.svg: an endlessly scrolling strip of app screenshots for the README.

GitHub renders SVGs through <img>, which blocks external files but keeps CSS animation,
so every screenshot is embedded as a small base64 JPEG.

    python scripts/readme/make_marquee.py
"""
import base64
import io
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
SHOTS = ROOT / "docs" / "screenshots"
OUT = ROOT / "docs" / "showcase-strip.svg"

FILES = [
    "new_player.jpg", "artist.jpg", "listen_together.jpg",
    "new_player_lyrics.jpg", "library.jpg", "../readme/phone-classic.jpg", "recognition.jpg",
    "../readme/phone-immersive.jpg", "search.jpg", "stats.jpg", "wrapped.jpg",
]

H = 400          # phone height in the strip
W = 180          # phone width
GAP = 22
PAD_Y = 30
SECONDS_PER_PHONE = 3.2


def encode(path: Path) -> str:
    im = Image.open(path).convert("RGB")
    # Crop to the phone aspect, keeping the top (status bar + content).
    target = W / H
    w, h = im.size
    if w / h > target:
        nw = int(h * target)
        im = im.crop(((w - nw) // 2, 0, (w - nw) // 2 + nw, h))
    else:
        im = im.crop((0, 0, w, int(w / target)))
    im = im.resize((W * 2, H * 2), Image.LANCZOS)
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=72, optimize=True, progressive=True)
    return base64.b64encode(buf.getvalue()).decode()


def main() -> None:
    step = W + GAP
    set_w = step * len(FILES)
    view_w = 1000
    view_h = H + PAD_Y * 2
    dur = SECONDS_PER_PHONE * len(FILES)

    defs, uses = [], []
    for i, f in enumerate(FILES):
        defs.append(
            f'<image id="s{i}" width="{W}" height="{H}" preserveAspectRatio="xMidYMid slice" '
            f'href="data:image/jpeg;base64,{encode(SHOTS / f)}"/>'
        )
    for copy in range(2):
        for i in range(len(FILES)):
            x = copy * set_w + i * step
            uses.append(
                f'<g transform="translate({x},{PAD_Y})">'
                f'<rect x="-3" y="-3" width="{W + 6}" height="{H + 6}" rx="27" fill="#0b0807" stroke="#ffffff22"/>'
                f'<g clip-path="url(#phone)"><use href="#s{i}"/></g></g>'
            )

    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {view_w} {view_h}" width="{view_w}" height="{view_h}">
<style>
.track{{animation:slide {dur:.1f}s linear infinite}}
@keyframes slide{{from{{transform:translateX(0)}}to{{transform:translateX(-{set_w}px)}}}}
@media (prefers-reduced-motion:reduce){{.track{{animation:none}}}}
</style>
<defs>
<clipPath id="phone"><rect width="{W}" height="{H}" rx="24"/></clipPath>
<linearGradient id="fade" x1="0" x2="1">
<stop offset="0" stop-color="#fff" stop-opacity="0"/><stop offset=".08" stop-color="#fff"/>
<stop offset=".92" stop-color="#fff"/><stop offset="1" stop-color="#fff" stop-opacity="0"/>
</linearGradient>
<mask id="edges"><rect width="{view_w}" height="{view_h}" fill="url(#fade)"/></mask>
{''.join(defs)}
</defs>
<g mask="url(#edges)"><g class="track">{''.join(uses)}</g></g>
</svg>'''
    OUT.write_text(svg, encoding="utf-8")
    print(OUT, round(OUT.stat().st_size / 1024), "KB")


if __name__ == "__main__":
    main()
