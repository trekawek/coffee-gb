# Portable Proposal 3 artwork contract

This module owns the portable menu model and the future decoding/composition contract for Proposal
3. Android and desktop adapters will only blit the ARGB result supplied by the portable compositor
and map controller/touch input through the portable geometry; they must not reinterpret, crop,
reflow, or stretch the visual contract.

## Provenance, fixtures, and license

The 14 source images are the original 1672x941 Proposal 3 compositions from
`output/imagegen/proposal-3-menu-tree/`. The repository keeps those originals only in the existing
untracked `output/` directory. This revision stores no artwork in `src/main/resources`, so it does
not add the 22 MiB source set to an APK or desktop runtime artifact.

For contract tests, each source was transformed mechanically with
`ffmpeg -vf crop=924:736:374:102` and losslessly optimized with `optipng -o2`. The resulting
924x736 RGB, non-interlaced PNGs live under
`src/test/resources/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/source/`. Tests pin each encoded
PNG SHA-256 and decode its PNG filters in pure Java to pin the raw RGB frame SHA-256 produced by the
same ffmpeg crop. This is the lossless raw-pixel verification that guards the canonical crop and
does not require ImageIO or a platform image library.

The repository is distributed under the MIT license in the root `LICENSE` file. This module does
not add a separate artwork license or alter the source provenance; the imported fixtures retain the
repository's applicable license and notices.

## Canonical visual authority

The original composition geometry is 1672x941. `MenuArtworkCatalog.SOURCE_VISIBLE_CROP` is
`x=374, y=102, width=924, height=736`, and every packaged test fixture is already that crop. The
fixture itself is the complete image to decode and blit; hosts must not crop it again at runtime.
Each route also retains its original source filename for provenance.

Dynamic content is overlays-only: later compositor work may place the ROM name, sliders, focus
state, or status values above the immutable artwork, but must not redraw or reflow the base visual.
The Android file browser remains a native boundary. Open/import actions hand off to Android's native
document picker; this portable UI never draws a filesystem browser.

## Integer aspect-fit placement

`MenuViewport` places the 924x736 image into any positive integer viewport with one aspect-fit
placement. It uses long cross-products and floor rounding, centers with integer left/top offsets,
and assigns odd remainder pixels to the right/bottom bars. `contentBounds()` is half-open:
`[left,right) x [top,bottom)`. Inverse input mapping returns `Optional`/`OptionalInt` and rejects
letterbox pixels and the right/bottom edges.

Portrait and desktop hosts must preserve this placement rather than reflowing or independently
stretching either axis. The known portrait aperture `758x685` produces `[0,41,758,644)`; the
remaining pixels are letterbox bars.

## Future runtime paths

The `source/` path is intentionally test-fixture-only. A later sanitized compositor PR will own
the production runtime route and/or atlas paths in `src/main/resources`, decode and compose them in
`ui-portable`, and expose only ARGB blit data plus the portable input mapping to Android and desktop
adapters. No host adapter should depend on this fixture path or on raw resource streams.
