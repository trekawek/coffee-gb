# ImageGen prompt set

Mode: built-in ImageGen edit workflow. Each orientation used the matching production DMG raster
as Image 1 and was generated separately. The generated RGB images were then resized by at most two
pixels to the native canvas and given deterministic binary-alpha apertures documented in
`README.md`.

## CGB base prompt

```text
Use case: ui-mockup
Asset type: production-ready Android emulator raster skin proposal, <orientation> orientation
Input images: Image 1 is the edit target and exact layout/style reference: the existing Coffee GB DMG skin.
Primary request: Create CGB Proposal <number/name> by changing only the console shell, display bezel, and control material styling into a tasteful late-1990s color-handheld theme.
Proposal styling: <proposal styling below>
Style/medium: polished straight-on orthographic 3D product-rendered raster UI skin, matching Image 1's soft molded bevels, subtle shadows, restrained realism, and coherent Coffee GB visual language.
Composition/framing: preserve the complete native portrait or landscape canvas, outer rounded body, perimeter groove, exact screen placement, and every baked control center from Image 1. Keep the three-slot menu glyph and coffee-cup glyph in their original positions. Preserve the D-pad, B, A, SELECT, and START positions and sizes.
Screen: one clean empty black display aperture, exactly 10:9 aspect ratio representing 160x144, with simple rounded corners and no content, glare, text, reflection, or controls overlapping it.
Constraints: change only visual styling; keep interaction geometry and hierarchy unchanged; exactly one D-pad; exactly two round action buttons labeled only A and B; exactly two small pill utility buttons; retain the lower-right speaker slots in portrait; no extra controls; no brand names or platform logos; no watermark; no scene outside the full-frame skin; no perspective tilt.
Avoid: duplicate controls, malformed arrows or letters, decorative text, game imagery in the screen, photographic hands, floating-device mockup, strong bloom, busy circuitry.
```

CGB proposal styling:

1. **Heritage:** translucent smoky grape-purple polycarbonate, subtle molded ribs, deep charcoal controls, dark plum bezel, and tiny cyan/magenta/lime/gold ticks.
2. **Coffeehouse:** warm pearl-cream shell, desaturated teal inset, olive-black bezel, burgundy piping, espresso controls, and four tiny jewel-color indicators.
3. **Arcade:** translucent smoke-graphite shell, satin-black controls, blue-black bezel, restrained teal/berry edge accents, and an amber coffee-cup glyph.

## SGB base prompt

```text
Use case: ui-mockup
Asset type: production-ready Android emulator raster skin proposal for Super Game Boy play, <orientation> orientation
Input images: Image 1 is the edit target and visual/layout reference: the existing Coffee GB DMG skin.
Primary request: Create SGB Proposal <number/name>, a tasteful 16-bit home-console-themed Coffee GB skin with a substantially larger 256x224 display area.
Proposal styling: <proposal styling below>
Style/medium: polished straight-on orthographic 3D product-rendered raster UI skin, matching Image 1's soft molded bevels, subtle shadows, restrained realism, coffee-cup identity, and coherent material language.
Composition/framing: preserve the complete native portrait or landscape canvas, outer rounded body, perimeter groove, and existing control-center geometry. Keep the three-slot menu glyph and coffee-cup glyph in their original positions. Preserve the D-pad, B, A, SELECT, and START centers. The larger screen may use more bezel/matte area but must not overlap controls.
Screen: one clean empty black display aperture, exactly 8:7 aspect ratio representing 256x224 and visibly larger than the paired CGB skin. Use a slim molded bezel and simple rounded corners. No content, glare, text, reflection, or controls in the screen.
Controls: exactly one D-pad, exactly two round action buttons labeled only A and B, and exactly two small pill utility buttons. Do not add a four-button diamond; evoke four-color 16-bit hardware only with small bezel or trim accents. Retain lower-right speaker slots in portrait.
Constraints: no extra controls; no brand names or platform logos; no watermark; no scene outside the full-frame skin; no perspective tilt; keep baked touch targets visually aligned with Image 1.
Avoid: duplicate controls, malformed arrows or letters, decorative text, game imagery in the display, photographic hands, floating-device mockup, strong bloom, toy-like clutter.
```

SGB proposal styling:

1. **Heritage:** cool soft-gray console plastic, darker central deck, lavender/deep-violet piping, charcoal D-pad, purple A/B controls, and subtle horizontal vents.
2. **Coffeehouse:** warm light-gray and ivory shell, graphite trim, restrained burgundy/olive seams, espresso controls, and one muted red/yellow/green/blue bezel sequence.
3. **Arcade Dock:** recessed graphite center deck in a pale frame, satin-black controls, lavender utility accents and vents, plum bezel edge, and amber coffee-cup glyph.

## Targeted correction

SGB Proposal 3 landscape received one follow-up edit:

```text
Remove only the purple words "SELECT" and "START" below the two small utility buttons. Replace those letters with seamless matching graphite panel material and lighting. Change nothing else and add no replacement text or symbols.
```
