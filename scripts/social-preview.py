#!/usr/bin/env python3
"""Build docs/social-preview.png, the card GitHub shows above the repo in search and links.

    python3 scripts/social-preview.py

Derived from docs/demo.gif rather than drawn by hand, so re-recording the demo and re-running
this keeps the card honest: it shows the real app, not an illustration of one.

It shows the *challenge*, not the idle screen. The draining countdown bar is the only
saturated colour anywhere in Halotap, so on a dark card it does all the work of drawing the
eye with nothing added, and it is the moment that explains why the app exists. The idle screen
is calmer and says far less.

Both watches are taken at their native size from the demo and scaled down only. Upscaling a
754px-wide source to fill a 1280px card would soften the watch faces, and the type inside them
is small enough that it would show.

GitHub wants 1280x640 and under 1MB. Upload it by hand: Settings, General, Social preview.
A repo cannot be pointed at a file in its own tree, so the PNG lives here only so that it can
be regenerated and reviewed in a diff.
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

WIDTH, HEIGHT = 1280, 640
MARGIN = 80

# Frame 46 of 125. Chosen by scanning every frame for amber pixels inside the Wear OS face and
# taking the one where the bar is about half drained: full reads as "nothing is happening yet"
# and nearly empty reads as "too late", while half says a countdown is running.
FRAME = 46

# The demo's own geometry, from scripts/record-demo.sh. Kept in step with it by hand; if the
# canvas there changes, these move too.
WEAR_BOX = (26, 24, 26 + 360, 24 + 360)
APPLE_BOX = (426, 24, 426 + 302, 24 + 360)

WATCH_HEIGHT = 330
WATCH_GAP = 44

AMBER = (0xFF, 0xB3, 0x00)  # Ui.kt Amber
DIM = (0x8A, 0x94, 0xA6)  # Ui.kt Dim, and the demo's caption colour
TEXT = (0xE6, 0xEA, 0xF0)

BOLD = "/System/Library/Fonts/Supplemental/Arial Bold.ttf"
REGULAR = "/System/Library/Fonts/Supplemental/Arial.ttf"

NAME = "HaloTap"

# Broken into clauses by hand rather than wrapped, so no line ends mid-thought. "Let go to
# disarm" would be wrong: releasing starts the challenge, and the PIN is what disarms.
TAGLINE = [
    "Hold to arm.",
    "Let go when you arrive.",
    "Enter your PIN",
    "to mark yourself safe.",
]


def main() -> None:
    root = Path(__file__).resolve().parent.parent
    gif = Image.open(root / "docs/demo.gif")
    gif.seek(FRAME)
    frame = gif.convert("RGB")

    wear = frame.crop(WEAR_BOX)
    apple = frame.crop(APPLE_BOX)

    # Sampled from the crop rather than hardcoded. The gif's palette quantised the demo's
    # #151A21 to something one value off, and a one-value step across a 330px edge reads as a
    # visible rectangle around each watch.
    background = wear.getpixel((2, 2))

    card = Image.new("RGB", (WIDTH, HEIGHT), background)
    draw = ImageDraw.Draw(card)

    scale = WATCH_HEIGHT / wear.height
    wear = wear.resize((round(wear.width * scale), WATCH_HEIGHT), Image.LANCZOS)
    apple = apple.resize((round(apple.width * scale), WATCH_HEIGHT), Image.LANCZOS)

    caption_font = ImageFont.truetype(REGULAR, 21)
    pair_width = wear.width + WATCH_GAP + apple.width
    pair_left = WIDTH - MARGIN - pair_width
    # The watches and their captions are centred as one block, not the watches alone.
    pair_top = (HEIGHT - (WATCH_HEIGHT + 34)) // 2

    card.paste(wear, (pair_left, pair_top))
    card.paste(apple, (pair_left + wear.width + WATCH_GAP, pair_top))

    # Named once, under the watch it belongs to, where the label maps onto a device shape.
    caption_y = pair_top + WATCH_HEIGHT + 12
    for label, centre_x in (
        ("Wear OS (Android)", pair_left + wear.width // 2),
        ("watchOS (Apple)", pair_left + wear.width + WATCH_GAP + apple.width // 2),
    ):
        width = draw.textbbox((0, 0), label, font=caption_font)[2]
        draw.text((centre_x - width // 2, caption_y), label, font=caption_font, fill=DIM)

    title_font = ImageFont.truetype(BOLD, 74)
    body_font = ImageFont.truetype(REGULAR, 32)
    leading = 44

    block_height = 74 + 26 + len(TAGLINE) * leading
    top = (HEIGHT - block_height) // 2
    draw.text((MARGIN, top), NAME, font=title_font, fill=AMBER)
    for index, line in enumerate(TAGLINE):
        draw.text((MARGIN + 2, top + 74 + 26 + index * leading), line, font=body_font, fill=TEXT)

    out = root / "docs/social-preview.png"
    card.save(out, optimize=True)
    size_kb = out.stat().st_size // 1024
    print(f"wrote {out.relative_to(root)} ({size_kb} KB, {card.size[0]}x{card.size[1]})")


if __name__ == "__main__":
    main()
