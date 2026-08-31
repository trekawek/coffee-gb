# ImageGen prompt set

Mode: built-in ImageGen precise-object edits. Each orientation used the corresponding production
DMG PNG as Image 1. Final project assets were normalized to the production canvas, composited only
where needed, and given deterministic binary-alpha apertures.

## CGB

```text
Make the smallest possible CGB variant of the production DMG template. Add only four tiny polished
enamel indicator dots in cyan, berry-magenta, lime, and warm amber on the existing olive display
bezel: a centered horizontal row in portrait and a vertical row on the left rim in landscape.
Keep the complete framing, screen assembly, cream shell, olive bezel, burgundy line, coffee-cup
badge, menu grille, D-pad, A/B pod, utility buttons, speaker slots, lighting, shadows, material
texture, outer silhouette, and all touch-control geometry unchanged. No text, extra controls,
platform logos, watermark, restyling, new panel, or moved controls.
```

## SGB

```text
Convert only the existing display assembly into an integrated SGB bezel for a 256x224 frame. Keep
the production DMG outer olive-bezel footprint unchanged; do not extend it toward the controls.
Inside that same molded part, create a centered 8:7 opening with a visibly balanced olive rim on
all four sides, a subtle recessed inner bevel, and smooth small-radius corners. Remove black tabs,
protrusions, doubled outlines, and any floating-panel appearance. Add only a tiny lavender/deep-plum
enamel-dot pair: horizontal on the lower portrait rim and vertical on the left landscape rim.
Preserve the full canvas, warm cream shell, olive/burgundy palette, coffee-cup badge, menu grille,
D-pad, A/B pod, utility buttons, speaker slots, lighting, shadows, material texture, and touch-control
geometry. No tablet placed on top, separate plate, stacked frame, text, platform logo, extra controls,
or watermark.
```

The final SGB display assembly came from ImageGen and was fitted back into the untouched production
bezel footprint after visual checks on a 720×1600 Redmi. Its deterministic apertures are
`[86,210)–[854,882)` in portrait and `[420,99)–[1252,827)` in landscape. Production pixels were
retained everywhere outside that assembly. The CGB and SGB dots use the generated treatment as the
design reference and were reconstructed deterministically for exact alignment.
