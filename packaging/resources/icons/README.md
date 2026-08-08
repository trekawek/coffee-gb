# Coffee GB icon kit

The Coffee GB icon depicts a cream pocket game system with a pixel-art coffee cup on
its green display. One vector source feeds the desktop runtime, native packages, and
the standalone Android resource pack.

## Source hierarchy

- `../coffee-gb.svg` is the authoritative 1024 x 1024 vector artwork. Its 1254-unit
  view box preserves the original geometry while allowing lossless rendering at any
  delivered size.
- `../coffee-gb.png` is the committed 1024 x 1024 raster derivative bundled with the
  Swing application. Refresh it directly from the SVG whenever the vector changes.
- `../../../swing/src/main/java/eu/rekawek/coffeegb/swing/CoffeeGbIcon.java` loads the
  bundled raster derivative and progressively downsamples it for the desktop window
  and native package generators.
- `../../../swing/src/main/java/eu/rekawek/coffeegb/swing/packaging/PackageIconWriter.java`
  wraps those renderings in each desktop platform's icon container.
- `../android/res/` contains a standalone Android adaptation. Its full-color bitmap
  derives from the same master; its vector monochrome layer simplifies the handheld
  for Android themed icons.
- `../icon-concepts/` records the previous Pocket Brew design exploration only. It is
  not packaged and is not an export source.

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
- `android/res/drawable-nodpi/coffee_gb_icon.png`
- `android/res/drawable/ic_launcher_foreground.xml`
- `android/res/drawable/ic_launcher_monochrome.xml`
- `android/res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`
- `android/res/mipmap-anydpi-v33/ic_launcher.xml` and `ic_launcher_round.xml`
- `android/res/mipmap-{ldpi,mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`
  and `ic_launcher_round.png`
- `android/play-store-icon.png` at 512 x 512

The v26 launchers combine the near-white adaptive background and full-color
foreground. The v33 overrides also expose the simplified monochrome layer for themed
icons. The density PNGs cover launchers on pre-adaptive-icon Android versions, while
the Play PNG is only for the store listing. The master and raster derivatives use
transparent corners outside the rounded tile. These files are not connected to an
Android manifest; copy the resource tree into an Android module when one is added.

## Scaling guidance

- Always render `coffee-gb.png` from `coffee-gb.svg`, then derive smaller raster
  exports from that 1024 x 1024 image. Do not repeatedly resize an already reduced
  launcher image.
- The adaptive foreground keeps the complete artwork inside Android's `21..87` safe
  zone in the 108 x 108 viewport; the near-white background remains full-bleed for
  every launcher mask.
- Preserve the SVG's geometry, gradients, filters, and colors, and use a high-quality
  downsampling filter.
- The legacy `ic_launcher_round.png` files use a circular alpha mask. The regular
  launcher and Play Store images remain square while inheriting the master's
  transparent corners.
- Inspect 16, 24, 32, 48, and 64 px previews after changing the artwork; fine texture
  and the smallest speaker holes may naturally collapse at those sizes, but the dark
  handheld outline, green screen, D-pad, and red buttons must stay distinct.

## Regeneration

There is no separate desktop-icon export command. Running the native packaging entry
point (`packaging/package-native.sh TARGET [TYPE]` or
`packaging/package-native.ps1 -Target TARGET`) invokes `PackageIconWriter` and rebuilds
the PNG, ICO, or ICNS container in the package staging area. Desktop containers are
generated build artifacts and should not be committed.

The bundled `coffee-gb.png`, Android adaptive foreground bitmap, legacy launcher
PNGs, and Play Store PNG are raster derivatives and must be refreshed from the SVG
when it changes. The monochrome vector is deliberately simplified and should be
reviewed alongside them.
