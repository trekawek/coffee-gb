# Portable menu template and widget library

`ui-portable` owns the platform-neutral menu model, the shared raster template, reusable widgets,
illustrations, glyphs, composition, and viewport/input geometry. Swing and Android receive the
same immutable `MenuArgbFrame`; hosts do not decode assets, choose fonts, or lay out individual
screens.

## One template for every screen

Every route starts from this single runtime resource:

```
src/main/resources/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/
└── templates/common-menu-frame.png
```

It is a 924x736, 8-bit RGB, non-interlaced PNG. `Proposal3TemplateFrameCatalog` resolves that same
path for every `MenuRoute`; there is no route-specific production frame tree. The public
`MenuArtwork` metadata retains the old source filenames only as provenance and test-fixture keys.

`MenuScreenTemplate` is the canonical geometry authority. It defines:

- one title bar and title-safe area;
- one left panel with a 352x340 picture aperture and optional subtitle area;
- one right rail with exactly seven interchangeable 484x72 widget rows;
- 2px dividers outside the row hit targets;
- one shared footer for movement, choose, and back hints.

Pages with more than seven choices use a full-height up/down arrow row at the visible edge. A
scroll indicator replaces an ordinary row; controls are never compressed or assigned a different
font size.

## Reusable widget library

`MenuWidgetType` is the host-facing row vocabulary:

- `BUTTON`
- `DROPDOWN`
- `CHECKBOX`
- `SLIDER`

All four types use the same row bounds and `Proposal3GlyphAtlas.Role.MEDIUM` item typography.
Checkboxes use the typed `Item.checkbox(id, label, checked, enabled)` state; their display text is
only the label, so hosts do not encode or render `ON`/`OFF` strings.
Long labels can use two lines within the same row. Dropdowns use a second line when the label and
value would otherwise collide. Unused slots have no dividers, distinguishing blank space from
actual controls.
`Proposal3WidgetSkins` supplies only three route-neutral surface textures:

```
widgets/dark-widget.png
widgets/paper-widget.png
widgets/selected-widget.png
```

Dropdown fields, checkbox state, slider rails/thumbs, focus arrows, and scroll arrows are
deterministic raster primitives drawn by the portable compositor. No route owns a button image,
choice field, icon, action sprite, or settings-specific skin.

## Central illustration catalog

Reusable pictures live under `proposal3/illustrations/` and are selected through
`MenuIllustrationCatalog`. These are detached transparent pictograms, not full screen assets.
A host-provided `MenuPreview` takes precedence for screenshots, recent games, and save-state
thumbnails. Pages without either a preview or catalog illustration leave the common picture area
empty.

The illustration extractor is deterministic and consumes only test-side provenance fixtures:

```sh
python3 tools/generate_common_menu_illustrations.py
```

It requires Pillow. Its inputs remain in
`src/test/resources/.../proposal3/routes/templates/`; those historical frames are never packaged
in the runtime JAR.

## Common-frame ImageGen provenance

The frame was produced with the built-in ImageGen edit workflow using the historical Library
template (`08-library.png`) as the edit target and visual authority. The exact prompt was:

```text
Use case: precise-object-edit
Asset type: reusable Coffee GB menu shell raster for desktop and Android
Input images: Image 1 is the edit target and visual authority.
Primary request: remove only the folder-and-cartridge illustration from the left picture frame and reconstruct the exposed warm paper texture beneath it. Leave that picture frame empty for runtime artwork.
Constraints: preserve the exact 924x736 canvas, pixel geometry, title bar, left panel, right dark options panel, bottom navigation panel, every border and corner ornament, colors, texture, lighting, spacing, and aspect ratio. Change only the illustration inside the left picture frame. No text, no new icons, no watermark, no redesign, no crop, no scaling.
```

The selected built-in output was named
`exec-e1921d9a-eecc-4ab1-bc7b-e3e988195287.png`. It is 1405x1119 RGB PNG data with SHA-256:

```
415bedc2480f5d9f8b8a3ff572f10ba4fc388e30db4502ca0e42dcb4a6361af6
```

`tools/generate_common_menu_template.py` verifies that source digest, applies the audited Lanczos
conversion through ffmpeg, checks the PNG IHDR, and verifies the packaged digest:

```sh
python3 tools/generate_common_menu_template.py /path/to/exec-e1921d9a-eecc-4ab1-bc7b-e3e988195287.png
```

The expected runtime output digest is:

```
b8bacd0c2db9a996a8977c708f5daafe3036e02a44acb5f6f846b0e62d8eedb5
```

The transient `$CODEX_HOME/generated_images` location is not a repository or runtime dependency.
The selected-output digest and checked-in result are the stable provenance record.

## Glyphs and licensing

The Small, Notice, Medium, Display, and SemiBold bitmap atlases under `proposal3/overlay/` come from
the project's licensed ByteBounce source. The TTF is build-time-only and does not ship. Source and
atlas digests plus role metrics are recorded in `overlay/ByteBounce-licensed-source.txt`.
Runtime word spaces use a full glyph advance for readability; this does not alter the source atlas
recipe or the PNGs.

Regenerate them with:

```sh
python3 tools/generate_bytebounce_atlases.py
```

Repository artwork remains covered by the root MIT `LICENSE` and the notices recorded beside the
glyph atlases.

## Provenance fixtures versus runtime resources

Historical route material is deliberately test-only:

```
src/test/resources/.../proposal3/routes/raw/        # baked original crops
src/test/resources/.../proposal3/routes/templates/  # prior text-free route frames
```

These fixtures support provenance, illustration extraction, and regression inspection. Production
code and host adapters must never load them. `MenuArtworkRuntimeResourceTest` enforces that the
runtime tree contains exactly one template and no `routes/raw` or `routes/templates` directory.

## Portable rendering and viewport contract

`Proposal3MenuCompositor` clones the common base, paints only the title, picture, subtitle, seven
option slots, and footer, then returns a detached row-major `0xAARRGGBB` frame. No Android, Swing,
AWT, JavaFX, or decoder type crosses the portable public API.

`MenuViewport` aspect-fits the 924x736 frame into any positive integer viewport with long
cross-products and deterministic floor rounding. It centers the content, assigns odd remainder
pixels to the right/bottom bars, and rejects letterbox pixels plus half-open right/bottom edges
during inverse input mapping. Desktop and Android use this same placement rather than reflowing
the template.

`Proposal3MenuCompositor.hitTest` shares the renderer's visible-slot geometry. Desktop clicks and
Android taps inverse-map through `MenuViewport`, then use `MenuPointerGesture` to activate only
when press and release resolve to the same target. Footer targets follow their current hints;
unavailable actions, separators, and blank rows have no target. Slider track clicks adjust one
step toward the pointer. Keyboard and gamepad navigation remain available.

See [the UX review](../doc/ui-ux-review.md) for findings, changes, sources, and remaining limits.

Useful verification commands:

```sh
/opt/maven/bin/mvn -pl ui-portable test
/opt/maven/bin/mvn -pl ui-portable \
  -Dtest=MenuArtworkContractTest,MenuArtworkRuntimeResourceTest,Proposal3TemplateFrameCatalogTest,PngArgbDecoderTest test
```
