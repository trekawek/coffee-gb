# Portable Proposal 3 artwork contract

This module owns the portable menu model, raster decoding, and Proposal 3 compositor contract.
Android and desktop adapters only blit the immutable ARGB result supplied by this module and
map controller/touch input through the portable geometry. They must not decode, crop, reflow, or
stretch the internal runtime templates.

## Provenance, license, and runtime boundary

The 14 source images are the original 1672x941 Proposal 3 compositions from
`output/imagegen/proposal-3-menu-tree/`. Those originals, contact sheets, and source-only assets
remain only in the existing untracked `output/` directory. They are not packaged by this module.

The immutable baked-text crops live under `src/test/resources/.../routes/raw/` as visual references
only. `tools/generate_proposal3_templates.py` mechanically derives the 14 packaged 924x736 RGB,
non-interlaced templates under `src/main/resources/.../routes/templates/`: complete dynamic widget
interiors come from the approved texture sprites and audited paper text bands are replaced by the
same approved paper grain. The runtime artifact therefore ships no baked copy and does not pay for
a duplicate frame tree. Every visible heading, label, value, hint, and confirmation string is
drawn at runtime; only decorative marks integral to an illustration (for example the ZIP badge and
printer hardware wordmark) remain in artwork. Resource streams are never exposed to host adapters.

The repository is distributed under the MIT license in the root `LICENSE` file. This module does
not add a separate artwork license or alter the source provenance; the imported frames retain the
repository's applicable license and notices.

## Canonical visual authority

The original composition geometry is 1672x941. `MenuArtworkCatalog.SOURCE_VISIBLE_CROP` is
`x=374, y=102, width=924, height=736`, and every runtime template uses that geometry. The portable
decoder produces straight-alpha row-major `0xAARRGGBB` pixels. The package-private template
catalog decodes on demand and never exposes resource paths, streams, or frames through the public
metadata API.

Dynamic content is overlays-only: `Proposal3MenuCompositor` places runtime labels, slider state,
focus state, and bounded previews above immutable text-free route artwork. It uses audited route
masks and leaves every pixel outside them byte-identical to layer zero. The compositor returns a detached
`MenuArgbFrame`; no Android, Swing, AWT, JavaFX, or image-decoder type crosses its public API.
Changing rows and actions first receive a complete pinned PNG surface fragment from `widgets/`;
text, the palette-drawn focus cursor, and mechanically extracted row/action icons are then
composited inside that widget. Focus only changes the surface, text color, and marker; it never changes a label's
font role, metrics, or baseline. The runtime base is prebuilt, never sampled or repaired while the
emulator is running.

The widget surfaces are mechanical, text-free samples of the same approved crops: dark from the
Library list (`x=372,y=373,w=522,h=56`), selected from the Settings Audio row
(`x=600,y=122,w=300,h=45`), and paper from the Confirm panel
(`x=40,y=525,w=350,h=90`). Mirrored extension only enlarges those samples to the maximum widget
interior (`900x160`); hosts never stretch them. The focus cursor, audio slider, and checkbox are
small palette primitives: their geometry is deterministic and free of sampled shadows. The
audio thumb and all eleven 0–100% ticks use the same coordinate system, and the selected rail is a
single flat color.
The Android file browser remains a native boundary. Open/import actions hand off to Android's native
document picker; this portable UI never draws a filesystem browser.

The Proposal 3 compositor's runtime glyph atlases are the Small, Notice, Medium, Display, and SemiBold
bitmap atlases under `overlay/`, derived from the project's licensed ByteBounce build-time source.
The TTF is not packaged: hosts share the exact same dependency-free raster glyphs, so Android and
desktop cannot substitute a platform font. Their source digest, atlas digests, and role metrics are
recorded in `overlay/ByteBounce-licensed-source.txt`.

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

`routes/templates/` and `overlay/` are deliberately internal resource paths, not adapter APIs. Host
adapters depend only on the portable menu/controller, compositor, immutable frame, viewport, and
input-mapping types. No adapter may depend on a fixture root, packaged path, raw resource stream,
or platform graphics object.
