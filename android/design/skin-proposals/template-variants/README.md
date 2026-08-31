# Direct CGB and SGB template variants

These four source assets preserve the production DMG template's cream shell, olive bezel,
burgundy pinline, controls, coffee-cup badge, menu grille, lighting, and touch geometry.

- CGB adds a four-color enamel-dot row. Portrait uses a horizontal row; landscape uses a vertical
  row in the available bezel rim.
- SGB reshapes the existing olive display assembly itself and adds a lavender/plum dot pair. It
  does not add a second plate, console deck, or floating screen.

## Files and geometry

Coordinates use `[left, top)–[right, bottom)` bounds.

| System | File | Canvas | Active transparent aperture | Ratio |
| --- | --- | --- | --- | --- |
| CGB | `cgb/portrait.png` | 941×1672 | `[100,215)–[840,881)` = 740×666 | 160:144 / 10:9 |
| CGB | `cgb/landscape.png` | 1672×941 | `[436,104)–[1236,824)` = 800×720 | 160:144 / 10:9 |
| SGB | `sgb/portrait.png` | 941×1672 | `[86,210)–[854,882)` = 768×672 | 256:224 / 8:7 |
| SGB | `sgb/landscape.png` | 1672×941 | `[420,99)–[1252,827)` = 832×728 | 256:224 / 8:7 |

Every PNG is RGBA with binary alpha only and exactly one connected transparent opening. The SGB
active area remains larger than the corresponding CGB opening in both orientations, while the
balanced olive rim keeps the 8:7 display visibly recessed inside the original DMG bezel footprint.
All pixels outside the display assembly and indicator treatment come from the production DMG
raster, so the current control hit centers remain aligned.

The selected copies are production resources named
`coffee_gb_skin_{cgb,sgb}_{portrait,landscape}.png`. Android selects CGB for CGB/CGB0 hardware,
SGB for bordered SGB/SGB2 frames, and the original DMG skin for DMG/MGB and borderless SGB output.
Each published frame carries its presentation choice so a live SGB-border toggle cannot pair old
pixels with the new aperture.

The renderer maps exact-ratio CGB and SGB frames directly to the skin's floating aperture bounds
with bitmap filtering disabled, so the themed bezel is filled without an integer-rounding gutter.
The original DMG skin retains its integer nearest-neighbour fit.

See [`PROMPTS.md`](PROMPTS.md) for the built-in ImageGen edit brief and post-processing invariants.
