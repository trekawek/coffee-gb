# Shared A+B touch cue

Generated with the built-in ImageGen tool. `source.png` is the original transparent output.
The production asset is
`../../app/src/main/res/drawable-nodpi/coffee_gb_action_bridge_cue.png`: the generated alpha
bounds above alpha 8 were cropped, reduced to 124×124, and padded with two transparent pixels on each side
for a 128×128 RGBA resource. The artwork and alpha come from ImageGen.

`RasterSkin` composites the cue once when loading each DMG, CGB, or SGB skin. Its bounds
come from `TouchControlsLayout`: the measured midpoint between the painted A and B buttons,
with a diameter of 0.9 times the action-button hit radius. This is about 29% larger than the
initial cue and slightly overlaps both buttons in both orientations. The larger A+B touch
target and individual button behavior are unchanged.

The visual centers are `(738.25, 1180)` on the 941×1672 portrait raster and `(1497.5, 478)`
on the 1672×941 landscape raster, scaled with the skin. These follow the button artwork
rather than the approximate touch centers: about 0.5 pixels right and 4 pixels up in portrait,
and 4 pixels left in landscape. All three system families share the same button artwork.

## Final prompt

```text
Use case: ui-mockup
Asset type: production transparent PNG overlay for the tiny shared touch area between the existing B and A buttons on a cream retro handheld game controller.
Primary request: create ONE small, elegant embossed "+" touch cue that visually means pressing both neighboring buttons together.
Subject: a perfectly circular, shallow recessed cream inset, with a very fine muted burgundy outline and one LARGE, bold, clearly legible dark burgundy plus sign centered inside. The plus has equal horizontal and vertical arms; it is upright, never an X. The plus occupies about two thirds of the inset diameter. The shallow recess, soft bevel and restrained contact shadow should match realistic molded handheld-controller plastic. It should read as a tactile marking on the shell.
Composition: strict orthographic front view, centered, circular silhouette occupies 90% of a square canvas, tight even transparent margin. This asset will be displayed at only 40 pixels across, so prioritize a strong simple plus silhouette and very restrained detail.
Color palette: warm cream #D9CDBF, pale ivory highlight, muted deep burgundy #653C3B outline and plus. Gentle upper-left illumination.
Text (verbatim): "+"
Constraints: actual transparent background with clean alpha, no backdrop rectangle, no checkerboard drawn into the pixels, no other buttons, no A or B letters, no controller, no extra text, no arrows, no hand, no logo, no watermark, no metallic chrome, no glow. Deliver only the single isolated cue.
```
