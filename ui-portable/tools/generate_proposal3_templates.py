#!/usr/bin/env python3
"""Mechanically derive Proposal 3 text-free runtime templates.

The source crops remain immutable visual references.  This script removes paper-panel glyphs
inside audited text bands and replaces complete row/action interiors with the packaged, text-free
widget surfaces.  It intentionally leaves illustration lettering (the ZIP badge and printer
wordmark) alone.

Pillow and NumPy are build-time tooling only; neither is used or packaged at runtime.
"""

from pathlib import Path

import numpy as np
from PIL import Image


MODULE = Path(__file__).resolve().parents[1]
MAIN = MODULE / "src/main/resources/eu/rekawek/coffeegb/ui/menu/artwork/proposal3"
TEST_RAW = MODULE / "src/test/resources/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/routes/raw"
MAIN_RAW = MAIN / "routes/raw"
RAW = TEST_RAW if TEST_RAW.is_dir() else MAIN_RAW
OUTPUT = MAIN / "routes/templates"
WIDGETS = MAIN / "widgets"


def rect(x: int, y: int, width: int, height: int) -> tuple[int, int, int, int]:
    return x, y, x + width, y + height


def inner(x: int, y: int, width: int, height: int) -> tuple[int, int, int, int]:
    return rect(x + 3, y + 3, width - 6, height - 6)


# Library is deliberately based on the Recent Games authority frame. Only this icon is retained
# from the original, narrower Library mockup.
LIBRARY_ICON_SOURCE = rect(85, 235, 200, 170)
LIBRARY_ICON_DESTINATION = (106, 225)


# Tight paper-only bands.  Borders, divider rules, icons, and previews sit outside these bounds.
COMMON_PAPER_TEXT = [
    rect(32, 31, 303, 49),       # COFFEE GB
    rect(320, 31, 420, 49),      # / ROUTE (longest: CONTROLLER MAPPING)
    rect(690, 36, 189, 38),      # BACK / OPEN ROM
    rect(70, 669, 240, 43),      # D-PAD MOVE
    # Preserve the approved A/B keycap glyphs.  Runtime changes labels only.
    rect(455, 669, 126, 48),     # OK -> CHOOSE
    rect(708, 669, 128, 48),     # BACK
]

COMMON_PAPER_RESTORE = [
    rect(16, 656, 896, 13),      # Footer inner top frame
    rect(16, 711, 896, 10),      # Footer inner bottom frame
    rect(333, 660, 17, 56),      # Footer separators
    rect(585, 660, 17, 56),
]


ROUTE_PAPER_TEXT = {
    "00-pause-console.png": [
        rect(32, 409, 330, 34), rect(31, 496, 172, 48), rect(220, 496, 159, 48),
        rect(102, 577, 285, 52), rect(370, 500, 20, 45),
        # Pause has neither a context trail nor a header action.
        rect(340, 25, 325, 61), rect(688, 25, 207, 61),
    ],
    "01-save-states.png": [
        # State pages have no slot/status metadata beside or below the thumbnail.
        rect(30, 140, 352, 340), rect(30, 486, 352, 66),
        rect(688, 25, 207, 70),
    ],
    "16-recent-games.png": [
        # Recent Games reuses the Load State geometry: the selected game's screenshot and
        # timestamp occupy the left well while the right rail lists recent titles.
        rect(30, 140, 352, 340), rect(30, 486, 352, 66),
        rect(688, 25, 207, 70),
    ],
    "02-settings.png": [
        rect(38, 146, 342, 50), rect(36, 486, 342, 46), rect(36, 532, 342, 42),
        rect(36, 574, 342, 44),
    ],
    "03-audio.png": [
        rect(60, 156, 290, 32), rect(57, 407, 320, 30), rect(57, 498, 320, 30),
        rect(57, 540, 320, 30), rect(405, 145, 180, 48),
    ],
    "04-touch-controls.png": [
        rect(38, 146, 338, 54), rect(40, 504, 336, 46), rect(40, 548, 342, 42),
        rect(40, 588, 342, 40),
    ],
    "05-controller-mapping.png": [
        rect(38, 144, 310, 55), rect(38, 240, 310, 48), rect(38, 484, 310, 44),
        rect(38, 526, 310, 44), rect(212, 596, 490, 48),
    ],
    "06-optional-devices.png": [
        rect(36, 142, 304, 54), rect(36, 538, 304, 44), rect(36, 576, 304, 39),
        rect(36, 610, 304, 30), rect(38, 286, 138, 40), rect(178, 286, 140, 40),
        rect(55, 448, 118, 45), rect(176, 448, 146, 45),
    ],
    "07-data-media.png": [
        rect(34, 138, 330, 55), rect(34, 396, 330, 50), rect(34, 442, 330, 52),
        rect(34, 492, 330, 132),
    ],
    "08-library.png": [
        # Library uses the Recent Games authority frame; these are its preview and caption bands.
        rect(30, 140, 352, 340), rect(30, 486, 352, 66),
        rect(688, 25, 207, 70),
    ],
    "09-choose-rom.png": [
        rect(45, 114, 330, 56), rect(430, 114, 420, 56), rect(34, 382, 330, 52),
        rect(34, 426, 330, 52), rect(34, 468, 330, 48),
    ],
    "10-system.png": [
        rect(34, 146, 330, 54), rect(34, 438, 330, 40), rect(34, 520, 330, 40),
        rect(34, 560, 330, 35), rect(390, 470, 500, 54), rect(390, 514, 500, 54),
    ],
    "11-about.png": [
        rect(36, 134, 320, 112), rect(36, 388, 320, 68), rect(36, 470, 320, 72),
        rect(170, 572, 720, 60),
    ],
    "12-confirm-action.png": [
        rect(40, 152, 340, 49), rect(430, 160, 470, 83), rect(450, 274, 430, 102),
        rect(450, 411, 430, 88),
    ],
    "13-printer-paper.png": [
        rect(30, 144, 330, 58), rect(35, 474, 326, 54), rect(35, 516, 326, 54),
        rect(35, 558, 326, 54),
    ],
}

ROUTE_PAPER_RESTORE = {
    "00-pause-console.png": [rect(20, 471, 370, 12), rect(20, 557, 370, 12)],
    "05-controller-mapping.png": [rect(38, 476, 310, 13)],
    "10-system.png": [rect(34, 486, 330, 18)],
    "11-about.png": [rect(36, 455, 320, 15)],
    "12-confirm-action.png": [rect(40, 202, 340, 15), rect(444, 255, 440, 15),
                              rect(444, 387, 440, 15), rect(444, 497, 440, 15)],
    # Text bands must never eat the authored bezel rails. Keep the narrow rails from the raw
    # illustration after clearing copy; runtime widgets repaint only the panel interiors.
    "02-settings.png": [rect(394, 112, 18, 534)],
    "03-audio.png": [rect(345, 112, 4, 534), rect(367, 112, 11, 534)],
    "04-touch-controls.png": [rect(386, 112, 5, 534), rect(408, 112, 9, 534)],
    "05-controller-mapping.png": [rect(349, 112, 9, 494), rect(365, 112, 8, 470)],
    "10-system.png": [rect(354, 112, 12, 501), rect(378, 112, 8, 501)],
}

# These authored controls are no longer part of the immediate settings flow. Clear the complete
# old widget, including its outline, rather than leaving an unlabeled button shell behind.
ROUTE_FLAT_PAPER = {
    # The header Back outline is a source-art decoration, not a second navigation control. Remove
    # the complete footprint on every settings-related route; runtime B remains the sole back
    # action.
    "02-settings.png": [rect(744, 25, 151, 61)],
    "03-audio.png": [rect(744, 25, 151, 61)],
    "04-touch-controls.png": [rect(744, 25, 151, 61)],
    "05-controller-mapping.png": [rect(744, 25, 151, 61)],
    "10-system.png": [rect(744, 25, 151, 61)],
}

# Exact half-open inner pause-screen aperture.  It deliberately reaches under the stepped bezel
# while stopping before every visible frame stroke.  The source crop contains an illustration in
# this space, which must not survive behind an aspect-fitted live game frame at runtime.
PAUSE_PREVIEW_APERTURE = rect(30, 139, 351, 243)
PAUSE_PREVIEW_MATTE = (18, 27, 20)


# Complete widget interiors.  Tuple values are (surface, bounds).
ROUTE_WIDGETS = {
    # A clean rail is repainted at runtime into seven 72px rows, with exact 2px dividers.
    "00-pause-console.png": [("dark", rect(424, 121, 484, 516))],
    # These rails are divided dynamically into a seven-item viewport. Scrolling replaces the
    # leading/trailing item with a chevron row, so the text-free authority only needs one clean
    # continuous dark surface beneath the runtime dividers.
    "01-save-states.png": [("dark", rect(420, 118, 489, 529))],
    "16-recent-games.png": [("dark", rect(420, 118, 489, 529))],
    "02-settings.png": [("dark", rect(423, 116, 487, 523))],
    # Keep only the slider's authored paper well and the live Mute row. The entire lower panel is
    # a quiet dark surface; the old Emulated Audio and Save/Cancel shells are gone.
    "03-audio.png": [("dark", rect(387, 316, 519, 78)),
        ("dark", rect(383, 397, 527, 244))],
    # Keep the Controls rail as one clean dark aperture; the compositor supplies one to three
    # centered rows from the host presentation, so no unused fixed dividers survive.
    "04-touch-controls.png": [("dark", rect(420, 118, 490, 333)),
        ("dark", rect(417, 469, 489, 162))],
    "05-controller-mapping.png": [("dark", rect(366, 115, 544, 467)),
        ("dark", rect(366, 583, 544, 61))],
    "06-optional-devices.png": [("dark", inner(*value)) for value in
        [(353, 117, 556, 67), (353, 187, 556, 66), (353, 256, 556, 66),
         (353, 324, 556, 66), (353, 393, 556, 66), (353, 461, 556, 66)]] +
        [("paper", inner(*value)) for value in [(370, 561, 239, 59), (643, 561, 241, 59)]],
    "07-data-media.png": [("dark", inner(*value)) for value in
        [(374, 119, 535, 85), (374, 207, 535, 84), (374, 293, 535, 83),
         (374, 379, 535, 83), (374, 465, 535, 84), (374, 553, 535, 86)]],
    # Library is the Recent Games viewport with only three populated rows.
    "08-library.png": [("dark", rect(420, 118, 489, 529))],
    # ZIP candidate selection also scrolls in seven compact rows, while its two actions retain
    # their authored lower-panel footprint.
    "09-choose-rom.png": [("dark", rect(387, 179, 524, 333))] + [("dark", inner(*value)) for
        value in [(13, 515, 898, 70), (13, 587, 898, 65)]],
    "10-system.png": [("dark", inner(*value)) for value in
        [(378, 124, 530, 95), (378, 223, 530, 103), (378, 329, 530, 103)]],
    "11-about.png": [("dark", inner(*value)) for value in
        [(352, 115, 558, 89), (352, 207, 558, 83), (352, 292, 558, 82),
         (352, 376, 558, 83), (352, 461, 558, 87)]] +
        [("paper", inner(18, 558, 888, 84))],
    "12-confirm-action.png": [("paper", inner(*value)) for value in
        [(440, 514, 198, 75), (665, 510, 213, 82)]],
    "13-printer-paper.png": [("paper", inner(*value)) for value in
        [(386, 574, 241, 53), (637, 574, 259, 53)]],
}


def clear_surface(image: np.ndarray, bounds: tuple[int, int, int, int],
                  surface: np.ndarray) -> None:
    left, top, right, bottom = bounds
    height, width = bottom - top, right - left
    tiled = np.tile(surface, (height // surface.shape[0] + 1,
                              width // surface.shape[1] + 1, 1))
    image[top:bottom, left:right] = tiled[:height, :width]


def clear_paper_text(image: np.ndarray, bounds: tuple[int, int, int, int],
                     paper: np.ndarray) -> None:
    clear_surface(image, bounds, paper)


def tile_reference_vertical(image: np.ndarray, reference: np.ndarray,
                            destination: tuple[int, int, int, int],
                            source: tuple[int, int, int, int]) -> None:
    """Continue a narrow authored vertical bezel edge without inventing new artwork."""
    destination_left, destination_top, destination_right, destination_bottom = destination
    source_left, source_top, source_right, source_bottom = source
    tile = reference[source_top:source_bottom, source_left:source_right]
    if tile.shape[0] == 0 or tile.shape[1] != destination_right - destination_left:
        raise ValueError("Invalid vertical bezel continuation")
    for top in range(destination_top, destination_bottom, tile.shape[0]):
        bottom = min(destination_bottom, top + tile.shape[0])
        image[top:bottom, destination_left:destination_right] = tile[:bottom - top]


def paste_surface(image: Image.Image, surface: Image.Image,
                  bounds: tuple[int, int, int, int]) -> None:
    left, top, right, bottom = bounds
    # The pause rail is taller than the source texture.  Tile instead of allowing Pillow's
    # out-of-bounds crop to introduce a black band into the generated authority image.
    for y in range(top, bottom, surface.height):
        for x in range(left, right, surface.width):
            width = min(surface.width, right - x)
            height = min(surface.height, bottom - y)
            image.paste(surface.crop((0, 0, width, height)), (x, y))


def largest_component(mask: np.ndarray) -> np.ndarray:
    """Keep the connected opener illustration while discarding isolated paper-grain pixels."""
    height, width = mask.shape
    visited = np.zeros_like(mask, dtype=bool)
    largest: list[tuple[int, int]] = []
    for y in range(height):
        for x in range(width):
            if not mask[y, x] or visited[y, x]:
                continue
            component: list[tuple[int, int]] = []
            pending = [(x, y)]
            visited[y, x] = True
            while pending:
                current_x, current_y = pending.pop()
                component.append((current_x, current_y))
                for adjacent_y in range(max(0, current_y - 1), min(height, current_y + 2)):
                    for adjacent_x in range(max(0, current_x - 1),
                                            min(width, current_x + 2)):
                        if (mask[adjacent_y, adjacent_x]
                                and not visited[adjacent_y, adjacent_x]):
                            visited[adjacent_y, adjacent_x] = True
                            pending.append((adjacent_x, adjacent_y))
            if len(component) > len(largest):
                largest = component
    result = np.zeros_like(mask, dtype=bool)
    for x, y in largest:
        result[y, x] = True
    return result


def fill_mask_holes(mask: np.ndarray) -> np.ndarray:
    """Fill light paper-colored areas enclosed by the opener's dark pixel-art outline."""
    height, width = mask.shape
    outside = np.zeros_like(mask, dtype=bool)
    pending: list[tuple[int, int]] = []
    for x in range(width):
        pending.extend(((x, 0), (x, height - 1)))
    for y in range(height):
        pending.extend(((0, y), (width - 1, y)))
    while pending:
        x, y = pending.pop()
        if mask[y, x] or outside[y, x]:
            continue
        outside[y, x] = True
        if x > 0:
            pending.append((x - 1, y))
        if x + 1 < width:
            pending.append((x + 1, y))
        if y > 0:
            pending.append((x, y - 1))
        if y + 1 < height:
            pending.append((x, y + 1))
    return mask | ~outside


def extract_library_opener(source: Image.Image) -> Image.Image:
    """Turn the baked Library opener into a transparent sprite without redrawing it."""
    crop = np.array(source.crop(LIBRARY_ICON_SOURCE).convert("RGB"))
    border = np.concatenate((crop[:8].reshape(-1, 3), crop[-8:].reshape(-1, 3),
                             crop[:, :8].reshape(-1, 3), crop[:, -8:].reshape(-1, 3)))
    paper = np.median(border, axis=0)
    distance = np.sqrt(np.square(crop.astype(float) - paper).sum(axis=2))
    mask = fill_mask_holes(largest_component(distance > 35))
    padded = np.pad(mask, 1)
    dilated = np.zeros_like(mask, dtype=bool)
    for offset_y in range(3):
        for offset_x in range(3):
            dilated |= padded[offset_y:offset_y + mask.shape[0],
                              offset_x:offset_x + mask.shape[1]]
    rgba = np.dstack((crop, np.where(dilated, 255, 0).astype(np.uint8)))
    return Image.fromarray(rgba, "RGBA")


def main() -> None:
    if not RAW.is_dir():
        raise SystemExit(f"Proposal 3 raw reference directory is missing: {RAW}")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    surfaces = {
        "dark": Image.open(WIDGETS / "dark-widget.png").convert("RGB"),
        "paper": Image.open(WIDGETS / "paper-widget.png").convert("RGB"),
    }
    paper_pixels = np.array(surfaces["paper"])
    library_opener = extract_library_opener(
        Image.open(RAW / "08-library.png").convert("RGB"))
    for source in sorted(RAW.glob("*.png")):
        if source.name not in ROUTE_WIDGETS or source.name not in ROUTE_PAPER_TEXT:
            raise SystemExit(f"No audited template recipe for {source.name}")
        # The old Library reference has unique, narrower panel geometry. Start from the Recent
        # Games reference so every rail, border and footer seam is shared pixel-for-pixel.
        reference_source = RAW / ("16-recent-games.png"
                                  if source.name == "08-library.png" else source.name)
        template = Image.open(reference_source).convert("RGB")
        for surface, bounds in ROUTE_WIDGETS[source.name]:
            paste_surface(template, surfaces[surface], bounds)
        pixels = np.array(template)
        for bounds in COMMON_PAPER_TEXT + ROUTE_PAPER_TEXT[source.name]:
            clear_paper_text(pixels, bounds, paper_pixels)
        reference = np.array(Image.open(reference_source).convert("RGB"))
        for left, top, right, bottom in COMMON_PAPER_RESTORE:
            pixels[top:bottom, left:right] = reference[top:bottom, left:right]
        for left, top, right, bottom in ROUTE_PAPER_RESTORE.get(source.name, []):
            pixels[top:bottom, left:right] = reference[top:bottom, left:right]
        for bounds in ROUTE_FLAT_PAPER.get(source.name, []):
            clear_paper_text(pixels, bounds, paper_pixels)
        if source.name == "00-pause-console.png":
            left, top, right, bottom = PAUSE_PREVIEW_APERTURE
            pixels[top:bottom, left:right] = PAUSE_PREVIEW_MATTE
        if source.name in ("01-save-states.png", "08-library.png", "16-recent-games.png"):
            # Remove all legacy left-side copy while preserving the stepped bezel around the
            # thumbnail aperture.  The runtime fills this aperture with a detached thumbnail.
            clear_paper_text(pixels, rect(30, 140, 352, 340), paper_pixels)
            clear_paper_text(pixels, rect(30, 486, 352, 66), paper_pixels)
            # The state route has no header action and no save/load/delete action strip. Remove
            # the entire legacy framed strip and let the screenshot/date panel and state rail
            # continue to the controls footer as one coherent page.
            clear_paper_text(pixels, rect(688, 25, 207, 70), paper_pixels)
            clear_paper_text(pixels, rect(8, 552, 906, 101), paper_pixels)
            clear_surface(pixels, rect(420, 118, 489, 529), np.array(surfaces["dark"]))
            # The legacy full-width action strip also contained the side rails of the state
            # list. Continue those authored rails down to the footer so the new fourth row
            # does not look like a detached dark widget.
            tile_reference_vertical(pixels, reference, rect(407, 552, 14, 101),
                                    rect(407, 500, 14, 52))
            tile_reference_vertical(pixels, reference, rect(908, 552, 6, 101),
                                    rect(908, 500, 6, 52))
            # Keep the preview well blank until a persisted thumbnail is supplied at runtime.
            left, top, right, bottom = rect(30, 140, 352, 340)
            clear_paper_text(pixels, (left, top, right, bottom), paper_pixels)
        rendered = Image.fromarray(pixels, "RGB")
        if source.name == "08-library.png":
            rendered.paste(library_opener, LIBRARY_ICON_DESTINATION, library_opener)
        rendered.save(OUTPUT / source.name, optimize=True, compress_level=9)
    expected = set(ROUTE_WIDGETS)
    actual = {path.name for path in OUTPUT.glob("*.png")}
    if actual != expected:
        raise SystemExit(f"Unexpected template set: {sorted(actual ^ expected)}")


if __name__ == "__main__":
    main()
