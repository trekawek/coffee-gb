#!/usr/bin/env python3
"""Extract the approved Proposal 3 pictograms into the shared menu asset library.

The old visual fixtures are retained under test resources as provenance.  Runtime screens never
load those full frames: this tool isolates only their illustration, removes the connected paper
background, and writes small RGBA sprites consumed by the portable compositor.
"""

from collections import deque
from pathlib import Path
from statistics import median

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
SOURCE = (
    ROOT
    / "src/test/resources/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/routes/templates"
)
OUTPUT = (
    ROOT
    / "src/main/resources/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/illustrations"
)

# Tight boxes deliberately exclude decorative panel rules and runtime text.  The names are logical
# library assets, not screen-owned paths.
ILLUSTRATIONS = {
    "settings": ("02-settings.png", (105, 260, 315, 485)),
    "audio": ("03-audio.png", (82, 205, 300, 400)),
    "touch-controls": ("04-touch-controls.png", (48, 238, 370, 485)),
    "controller": ("05-controller-mapping.png", (45, 300, 325, 460)),
    "peripherals": ("06-optional-devices.png", (40, 190, 315, 465)),
    "data-media": ("07-data-media.png", (92, 198, 272, 390)),
    "library": ("08-library.png", (94, 205, 320, 410)),
    "archive": ("09-choose-rom.png", (88, 165, 300, 405)),
    "system": ("10-system.png", (78, 185, 310, 430)),
    "about": ("11-about.png", (78, 225, 270, 420)),
    "warning": ("12-confirm-action.png", (45, 220, 365, 535)),
    "printer": ("13-printer-paper.png", (78, 175, 285, 455)),
}


def _paper_key(image: Image.Image) -> tuple[int, int, int]:
    width, height = image.size
    samples = []
    for x in range(width):
        samples.append(image.getpixel((x, 0))[:3])
        samples.append(image.getpixel((x, height - 1))[:3])
    for y in range(height):
        samples.append(image.getpixel((0, y))[:3])
        samples.append(image.getpixel((width - 1, y))[:3])
    return tuple(int(median(channel)) for channel in zip(*samples))


def _is_paper(pixel: tuple[int, int, int, int], key: tuple[int, int, int]) -> bool:
    red, green, blue, _ = pixel
    # Paper grain is broad but remains light and warm.  Requiring both closeness to the sampled
    # border and a minimum luminance keeps pale, enclosed pictogram faces intact.
    return (
        red + green + blue > 470
        and max(abs(red - key[0]), abs(green - key[1]), abs(blue - key[2])) <= 58
    )


def _remove_connected_paper(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    width, height = rgba.size
    key = _paper_key(rgba)
    pixels = rgba.load()
    queue: deque[tuple[int, int]] = deque()
    seen = bytearray(width * height)

    def offer(x: int, y: int) -> None:
        offset = y * width + x
        if seen[offset] or not _is_paper(pixels[x, y], key):
            return
        seen[offset] = 1
        queue.append((x, y))

    for x in range(width):
        offer(x, 0)
        offer(x, height - 1)
    for y in range(height):
        offer(0, y)
        offer(width - 1, y)

    while queue:
        x, y = queue.popleft()
        pixels[x, y] = (*pixels[x, y][:3], 0)
        if x > 0:
            offer(x - 1, y)
        if x + 1 < width:
            offer(x + 1, y)
        if y > 0:
            offer(x, y - 1)
        if y + 1 < height:
            offer(x, y + 1)

    alpha_bounds = rgba.getchannel("A").getbbox()
    if alpha_bounds is None:
        raise RuntimeError("illustration extraction removed every pixel")
    left, top, right, bottom = alpha_bounds
    padding = 4
    return rgba.crop(
        (
            max(0, left - padding),
            max(0, top - padding),
            min(width, right + padding),
            min(height, bottom + padding),
        )
    )


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for name, (filename, bounds) in ILLUSTRATIONS.items():
        source = Image.open(SOURCE / filename)
        sprite = _remove_connected_paper(source.crop(bounds))
        sprite.save(OUTPUT / f"{name}.png", format="PNG", optimize=True)


if __name__ == "__main__":
    main()
