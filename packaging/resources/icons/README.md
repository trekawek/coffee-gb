# Pocket Brew icon kit

**Pocket Brew** combines the silhouette of a warm coffee mug (handle and steam) with
the controls of a generic pocket game system. Keep it wordless and avoid details tied
to any particular commercial handheld.

## Source hierarchy

- `../coffee-gb.svg` is the authoritative 512 x 512 artwork for geometry and color.
- `../icon-concepts/coffee-gb-pocket-brew-generated.png` is the image-generated concept
  reference. It records the chosen direction only; do not package it or use it as an
  export source.
- `../icon-concepts/coffee-gb-pocket-brew-prompt.md` records the concept-generation
  prompt and mode.
- `../../../swing/src/main/java/eu/rekawek/coffeegb/swing/packaging/PackageIconWriter.java`
  mirrors the SVG geometry for desktop raster generation. Keep that rendering in sync
  whenever the SVG changes.
- `../android/res/` contains a standalone vector adaptation for a future Android module.

## Platform assets

The kit covers these delivered sizes. Desktop containers are generated during native
packaging; Android assets are committed under the standalone resource pack:

| Platform | Container | Included pixel sizes |
| --- | --- | --- |
| Linux | PNG | 256 |
| Windows | ICO | 16, 24, 32, 48, 64, 128, 256 |
| macOS | ICNS | 16, 32, 64, 128, 256, 512, 1024 |
| Android (legacy) | PNG | 36, 48, 72, 96, 144, 192 |

The standalone Android pack contains:

- `android/res/values/ic_launcher_background.xml`
- `android/res/drawable/ic_launcher_foreground.xml`
- `android/res/drawable/ic_launcher_monochrome.xml`
- `android/res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`
- `android/res/mipmap-anydpi-v33/ic_launcher.xml` and `ic_launcher_round.xml`
- `android/res/mipmap-{ldpi,mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`
  and `ic_launcher_round.png`
- `android/play-store-icon.png` at 512 x 512

The v26 launchers combine the adaptive background and foreground. The v33 overrides
also expose the monochrome layer for themed icons. The density PNGs cover launchers on
pre-adaptive-icon Android versions, while the Play PNG is only for the store listing.
These files are not connected to an Android manifest; copy the resource tree into an
Android module when one is added.

## Palette

| Role | Color |
| --- | --- |
| Espresso background | `#211410` |
| Warm oat mug/device | `#E7C790` |
| Muted sage screen | `#A8B86C` |
| Dark coffee/control | `#4B2A1B` |
| Rust-red buttons | `#C45C3C` |
| Cream steam/highlight | `#FFF0D2` |

## Scaling guidance

- Export from the SVG or vector geometry, never by enlarging the generated concept PNG.
- Keep the mug handle, at least one bold steam wisp, the D-pad, and the paired buttons;
  those are the minimum cues that make both halves of the name readable at 16-24 px.
- Avoid text, labels, thin strokes, texture, and small highlights. Simplify secondary
  depth before changing the outer silhouette.
- For adaptive Android icons, keep the foreground in its 108 x 108 viewport and within
  the existing `21..87` safe-zone bounds. Let the espresso background cover the full
  adaptive layer so circular and rounded-square masks do not reveal an edge.
- Inspect 16, 24, 32, 48, and 64 px previews on both light and dark backgrounds after
  changing geometry or contrast.

## Regeneration

There is no separate desktop-icon export command. Running the native packaging entry
point (`packaging/package-native.sh TARGET [TYPE]` or
`packaging/package-native.ps1 -Target TARGET`) invokes `PackageIconWriter` and rebuilds
the PNG, ICO, or ICNS container in the package staging area. Desktop containers are
generated build artifacts and should not be committed.

The adaptive Android files are maintained as vector resources. The legacy and Play PNGs
are raster exports of the same geometry and must be refreshed if the master changes.
