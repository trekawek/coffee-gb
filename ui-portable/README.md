# Portable Proposal 3 artwork contract

This module owns the portable menu model, raster decoding, and future compositor output contract.
Android and desktop adapters will only blit the sanitized ARGB result supplied by this module and
map controller/touch input through the portable geometry. They must not decode, crop, reflow, or
stretch the internal raw reference frames.

## Provenance, license, and runtime boundary

The 14 source images are the original 1672x941 Proposal 3 compositions from
`output/imagegen/proposal-3-menu-tree/`. Those originals, contact sheets, and source-only assets
remain only in the existing untracked `output/` directory. They are not packaged by this module.

The runtime resources in
`src/main/resources/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/routes/raw/` are the exact
924x736 RGB, non-interlaced crops produced mechanically with
`ffmpeg -vf crop=924:736:374:102` and losslessly optimized with `optipng -o2`. They are internal
reference inputs only: they contain baked sample/fixed values and must never reach Android or
desktop host adapters. A following compositor revision must replace/sanitize them, mask those
values, and expose compositor-produced ARGB output instead. The runtime raster budget is
14,680,064 bytes.

The repository is distributed under the MIT license in the root `LICENSE` file. This module does
not add a separate artwork license or alter the source provenance; the imported frames retain the
repository's applicable license and notices.

## Canonical visual authority

The original composition geometry is 1672x941. `MenuArtworkCatalog.SOURCE_VISIBLE_CROP` is
`x=374, y=102, width=924, height=736`, and every internal raw frame is already that crop. The
portable decoder produces straight-alpha row-major `0xAARRGGBB` pixels. The package-private raw
catalog decodes on demand and never exposes resource paths, streams, or raw frames through the
public metadata API.

Dynamic content is overlays-only: later compositor work may place the ROM name, sliders, focus
state, or status values above the immutable visual, but must not redraw or reflow its base layout.
The Android file browser remains a native boundary. Open/import actions hand off to Android's native
document picker; this portable UI never draws a filesystem browser.

## Integer aspect-fit placement

`MenuViewport` places the 924x736 image into positive integer viewports with one deterministic
aspect-fit placement. It uses long cross-products and floor rounding, centers with integer left/top
offsets, and assigns odd remainder pixels to the right/bottom bars. `contentBounds()` is half-open:
`[left,right) x [top,bottom)`. Inverse input mapping returns `Optional`/`OptionalInt` and rejects
letterbox pixels and the right/bottom edges.

Portrait and desktop hosts must preserve this placement rather than reflowing or independently
stretching either axis. The approved portrait aperture `758x685` produces `[0,41,758,644)`;
the remaining pixels are letterbox bars.

## Future runtime routes and atlas paths

`routes/raw/` is deliberately an internal reference path, not an adapter API. The next sanitized
compositor PR will own production route and/or atlas paths, decode and compose them in `ui-portable`,
mask or replace the baked values, and expose only immutable ARGB blit data plus portable input
mapping to Android and desktop adapters. No host adapter should depend on `routes/raw/`, a fixture
root, or a raw resource stream.
