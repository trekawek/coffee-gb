# CGB and SGB skin proposals

These are review candidates derived from the production DMG portrait and landscape skins:

- `android/app/src/main/res/drawable-nodpi/coffee_gb_skin_portrait.png`
- `android/app/src/main/res/drawable-nodpi/coffee_gb_skin_landscape.png`

They are intentionally stored outside `res/drawable-nodpi`, so the twelve exploratory proposals
are not packaged into the Android app. The later direct-template CGB and SGB pairs were selected,
copied into production resources, and wired to the active presentation family.

The selected, minimally changed source variants live in
[`template-variants/`](template-variants/README.md).

## Proposal families

| Proposal | CGB | SGB |
| --- | --- | --- |
| 1 — Heritage | Translucent grape shell, graphite controls, restrained four-color ticks | Soft gray 16-bit console plastic, lavender piping, purple A/B controls |
| 2 — Coffeehouse | Cream shell, teal panel, olive trim, burgundy piping | Warm gray/ivory shell, graphite bezel, Coffee GB olive/burgundy details |
| 3 — Arcade | Smoke-graphite shell, blue-black bezel, teal/berry accents | Pale frame, recessed graphite deck, plum/lavender details |

The three-slot menu glyph, coffee-cup badge, D-pad, A/B pair, utility controls, soft molded
bevels, and orthographic product-rendered treatment keep the proposals consistent with the DMG
skin. No platform wordmarks or additional controller buttons are present.

## Deliverables

- `cgb/proposal-*/portrait.png` and `cgb/proposal-*/landscape.png`
- `sgb/proposal-*/portrait.png` and `sgb/proposal-*/landscape.png`
- `cgb-contact-sheet.png` and `sgb-contact-sheet.png` for comparison
- `PROMPTS.md` with the built-in ImageGen prompt set

## Geometry

Coordinates use `[left, top)–[right, bottom)` bounds.

| System | Orientation | Canvas | Transparent aperture | Ratio |
| --- | --- | --- | --- | --- |
| CGB | Portrait | 941×1672 | `[100,215)–[840,881)` = 740×666 | 160:144 / 10:9 |
| CGB | Landscape | 1672×941 | `[436,104)–[1236,824)` = 800×720 | 160:144 / 10:9 |
| SGB | Portrait | 941×1672 | `[70,200)–[870,900)` = 800×700 | 256:224 / 8:7 |
| SGB | Landscape | 1672×941 | `[388,78)–[1284,862)` = 896×784 | 256:224 / 8:7 |

The SGB opening is larger than the corresponding CGB opening in both orientations. All assets
use opaque outer pixels and exactly one rounded, fully transparent screen opening. Alpha is binary
only (`0` or `255`) because `RasterSkin.transparentWindow()` finds the global bounds of every
fully transparent pixel.

The baked controls retain the current orientation-specific touch centers, so the proposals can
share today's touch geometry. The selected direct-template assets use the same invariant while
`CoffeeGbSurfaceView` routes DMG, CGB, and bordered-SGB presentations at runtime.

## Production scaling

The original DMG raster keeps its integer nearest-neighbour viewport. The selected CGB and SGB
rasters have exact source-ratio openings, so Android maps those frames directly to the skin's
floating aperture bounds with bitmap filtering disabled. This fills the themed bezel without a
rounded-coordinate gutter.
