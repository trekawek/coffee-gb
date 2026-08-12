# Portable Proposal 3 artwork contract

This module owns the portable menu model, raster decoding, and Proposal 3 compositor contract.
Android and desktop adapters only blit the immutable ARGB result supplied by this module and
map controller/touch input through the portable geometry. They must not decode, crop, reflow, or
stretch the internal raw reference frames.

## Provenance, license, and runtime boundary

The 14 source images are the original 1672x941 Proposal 3 compositions from
`output/imagegen/proposal-3-menu-tree/`. Those originals, contact sheets, and source-only assets
remain only in the existing untracked `output/` directory. They are not packaged by this module.

The runtime resources in
`src/main/resources/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/routes/raw/` are the exact
924x736 RGB, non-interlaced crops produced mechanically with
`ffmpeg -vf crop=924:736:374:102` and losslessly optimized with `optipng -o2`. All 14 frames are
byte-pinned immutable layer zero; platform-specific wording and changing values belong only to
bounded widget overlays. Raw resources and streams are never exposed to host adapters.

The repository is distributed under the MIT license in the root `LICENSE` file. This module does
not add a separate artwork license or alter the source provenance; the imported frames retain the
repository's applicable license and notices.

## Canonical visual authority

The original composition geometry is 1672x941. `MenuArtworkCatalog.SOURCE_VISIBLE_CROP` is
`x=374, y=102, width=924, height=736`, and every internal raw frame is already that crop. The
portable decoder produces straight-alpha row-major `0xAARRGGBB` pixels. The package-private raw
catalog decodes on demand and never exposes resource paths, streams, or raw frames through the
public metadata API.

Dynamic content is overlays-only: `Proposal3MenuCompositor` places runtime labels, slider state,
focus state, and bounded previews above immutable route artwork. It uses audited route masks and
leaves every pixel outside them byte-identical to layer zero. The compositor returns a detached
`MenuArgbFrame`; no Android, Swing, AWT, JavaFX, or image-decoder type crosses its public API.
Changing rows and actions first receive a complete pinned PNG surface fragment from `widgets/`;
text and the focus-arrow sprite are then composited inside that widget. The base frame is never
inpainted, tiled from a nearby scanline, or rewritten to move baked labels.

The widget surfaces are mechanical, text-free samples of the same approved crops: dark from the
Library list (`x=372,y=373,w=522,h=56`), selected from the Settings Audio row
(`x=600,y=122,w=300,h=45`), and paper from the Confirm panel
(`x=40,y=525,w=350,h=90`). Mirrored extension only enlarges those samples to the maximum widget
interior (`900x160`); hosts never stretch them. The 13x20 focus arrow is an alpha extraction of
the Settings row marker at `x=442,y=132`.

The audio slider is also asset-only. `widgets/audio-slider-empty.png` and
`widgets/audio-slider-filled.png` are complete opaque 438x59 slider surfaces derived mechanically
from the `raw/03-audio.png` crop at `x=427,y=201`. The empty surface preserves canonical local
`x=331..437` byte-for-byte and extends a per-row clean-paper gradient leftward. The filled surface
preserves local `x=0..299` byte-for-byte and extends a per-row clean-dark gradient rightward. Each
gradient reaches the preserved segment's boundary value beneath the exact knob, so it contains no
stationary join. Neither extension samples the knob/shadow footprint at local `x=300..330`.
Their SHA-256 digests are
`47b5f4ececa249baf992e5a2cd83c00161f5543a7a2900e859829b631a96e216` and
`52aaeeb688ee65f5b9b39e5ff2d659430bdbfc7b4373eceba2a5d147fa458a09`.
`widgets/audio-knob.png` is the exact 31x59 canonical crop at raw `x=727,y=201`, including its
attached two-pixel drop shadow, with digest
`dc14fb200f7f65c8730d3e3be8c5060c59fcd547a55af470a3e04887efd3e781`.
At runtime the compositor blits the complete empty surface, clips the filled surface through the
knob center, then blits the exact knob sprite. The fill boundary is therefore always hidden beneath
the opaque knob. Runtime code never samples neighboring pixels, classifies colors, transforms
palettes, redraws the rail, or reads the raw authority raster. At canonical 75%, the visible filled
segment, knob, and empty segment remain byte-identical to layer zero.
The Android file browser remains a native boundary. Open/import actions hand off to Android's native
document picker; this portable UI never draws a filesystem browser.

The Proposal 3 compositor's runtime glyph atlases are the compact Medium and SemiBold bitmap
atlases under `overlay/`, derived from the approved Pixelify Sans build-time source. The TTF is
not packaged. Their source digest, atlas digests, role metrics, and the applicable SIL Open Font
License 1.1 notice are recorded in `overlay/PixelifySans-OFL.txt`.

## Integer aspect-fit placement

`MenuViewport` places the 924x736 image into positive integer viewports with one deterministic
aspect-fit placement. It uses long cross-products and floor rounding, centers with integer left/top
offsets, and assigns odd remainder pixels to the right/bottom bars. `contentBounds()` is half-open:
`[left,right) x [top,bottom)`. Inverse input mapping returns `Optional`/`OptionalInt` and rejects
letterbox pixels and the right/bottom edges.

Portrait and desktop hosts must preserve this placement rather than reflowing or independently
stretching either axis. The approved portrait aperture `758x685` produces `[0,41,758,644)`;
the remaining pixels are letterbox bars.

## Runtime routes and atlas paths

`routes/raw/` and `overlay/` are deliberately internal resource paths, not adapter APIs. Host
adapters depend only on the portable menu/controller, compositor, immutable frame, viewport, and
input-mapping types. No adapter may depend on a fixture root, packaged path, raw resource stream,
or platform graphics object.
