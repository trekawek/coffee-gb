#!/usr/bin/env python3
"""Rasterize the portable ByteBounce glyph atlases from a licensed local TTF."""

import argparse
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .:/&-_%?+()[]!,'\""
MODULE = Path(__file__).resolve().parents[1]
OUTPUT = MODULE / "src/main/resources/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/overlay"


@dataclass(frozen=True)
class Recipe:
    font_size: int
    cell_width: int
    cell_height: int
    horizontal_scale: float
    filename: str

    @property
    def dash_offset(self) -> int:
        """Align ByteBounce's short dash with the cap-height glyphs in its cell."""
        return 6 if self.cell_height == 36 else 8


def atlas(font_path: Path, recipe: Recipe) -> Image.Image:
    font = ImageFont.truetype(str(font_path), recipe.font_size)
    result = Image.new("RGBA", (recipe.cell_width * 16,
                                 recipe.cell_height * ((len(CHARACTERS) + 15) // 16)),
                       (255, 255, 255, 0))
    for index, character in enumerate(CHARACTERS):
        if character == " ":
            continue
        bounds = font.getbbox(character, anchor="lt")
        glyph_width = bounds[2] - bounds[0]
        glyph_height = bounds[3] - bounds[1]
        glyph = Image.new("RGBA", (glyph_width, glyph_height), (255, 255, 255, 0))
        ImageDraw.Draw(glyph).text((-bounds[0], -bounds[1]), character, font=font, anchor="lt",
                                   fill=(255, 255, 255, 255), stroke_width=0)
        scaled_width = round(glyph_width * recipe.horizontal_scale)
        glyph = glyph.resize((scaled_width, glyph_height), Image.Resampling.NEAREST)
        left = (index % 16) * recipe.cell_width
        top = (index // 16) * recipe.cell_height
        x = left + (recipe.cell_width - glyph.width) // 2
        y = top + (recipe.cell_height - glyph.height) // 2
        if character == "-":
            y += recipe.dash_offset
        result.alpha_composite(glyph, (x, y))
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("font", type=Path,
                        help="licensed ByteBounce TTF; it is a build input and is not packaged")
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    recipes = (
        Recipe(60, 22, 36, 0.50, "byte-bounce-small-atlas.png"),
        Recipe(60, 28, 36, 0.625, "byte-bounce-notice-atlas.png"),
        Recipe(60, 36, 36, 0.80, "byte-bounce-medium-atlas.png"),
        Recipe(80, 36, 48, 0.60, "byte-bounce-display-atlas.png"),
        Recipe(80, 48, 48, 0.80, "byte-bounce-semibold-atlas.png"),
    )
    for recipe in recipes:
        atlas(args.font, recipe).save(OUTPUT / recipe.filename, optimize=True, compress_level=9)


if __name__ == "__main__":
    main()
