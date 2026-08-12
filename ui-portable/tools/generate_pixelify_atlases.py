#!/usr/bin/env python3
"""Rasterize the portable Pixelify Sans atlases from a local OFL font source."""

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .:/&-_%?+()[]!,'"
MODULE = Path(__file__).resolve().parents[1]
OUTPUT = MODULE / "src/main/resources/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/overlay"


def atlas(font_path: Path, size: int, cell_width: int, cell_height: int) -> Image.Image:
    font = ImageFont.truetype(str(font_path), size)
    result = Image.new("RGBA", (cell_width * 16,
                                 cell_height * ((len(CHARACTERS) + 15) // 16)),
                       (255, 255, 255, 0))
    draw = ImageDraw.Draw(result)
    for index, character in enumerate(CHARACTERS):
        if character == " ":
            continue
        left = (index % 16) * cell_width
        top = (index // 16) * cell_height
        bounds = font.getbbox(character, anchor="lt")
        glyph_width = bounds[2] - bounds[0]
        glyph_height = bounds[3] - bounds[1]
        x = left + max(0, (cell_width - glyph_width) // 2) - bounds[0]
        y = top + max(0, (cell_height - glyph_height) // 2) - bounds[1]
        # The transparent atlas is a mask.  Pixelify Sans' pixel contours are retained exactly;
        # hosts supply ink/paper color while compositing.
        draw.text((x, y), character, font=font, anchor="lt",
                  fill=(255, 255, 255, 255), stroke_width=0)
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("regular", type=Path)
    parser.add_argument("medium", type=Path)
    parser.add_argument("semibold", type=Path)
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    recipes = [
        (args.medium, 36, 36, 36, "pixelify-sans-medium-atlas.png"),
        (args.semibold, 48, 48, 48, "pixelify-sans-semibold-atlas.png"),
    ]
    for source, size, width, height, filename in recipes:
        atlas(source, size, width, height).save(OUTPUT / filename, optimize=True,
                                                compress_level=9)
    display = atlas(args.semibold, 48, 48, 48)
    display = display.resize((36 * 16, display.height), Image.Resampling.NEAREST)
    display.save(OUTPUT / "pixelify-sans-display-atlas.png", optimize=True, compress_level=9)
    compact = atlas(args.regular, 36, 36, 36)
    compact = compact.resize((22 * 16, compact.height), Image.Resampling.NEAREST)
    compact.save(OUTPUT / "pixelify-sans-small-atlas.png", optimize=True, compress_level=9)
    notice = atlas(args.medium, 36, 36, 36)
    notice = notice.resize((28 * 16, notice.height), Image.Resampling.NEAREST)
    notice.save(OUTPUT / "pixelify-sans-notice-atlas.png", optimize=True, compress_level=9)


if __name__ == "__main__":
    main()
