# ADR 0003: Keep the Android frontend on a same-checkout, portable build boundary

**Status:** Accepted (2026-08-03)

## Context

Issue [#354](https://github.com/trekawek/coffee-gb/issues/354) starts the Android frontend
roadmap. Coffee GB is currently a Maven reactor with desktop-oriented `core`, `controller`,
`cli`, and `swing` modules. It cannot yet be put on an Android runtime classpath:

| Current artifact | Blocking desktop surface | Phase-1 disposition |
| --- | --- | --- |
| `core` | `CameraSource` and `PocketCamera` use `BufferedImage`; `debug.Console` uses JLine | Replace camera frames with a portable pixel contract and move console presentation to a desktop adapter. |
| `controller` | `Agent` exposes `BufferedImage`; settings use AWT `KeyEvent`; PNG codec uses ImageIO | Expose portable frame/input data and keep desktop conversions in Swing adapters. |
| `swing` | AWT/Swing, OpenCV, SDL, desktop audio/UI dependencies | Keep strictly desktop-only. |

The old parent POM also made every child inherit JLine and desktop logging dependencies. That
would make a new Android-facing artifact unsafe by default.

## Decision

1. `android/` is a standalone Gradle project with an `app` module. Maven remains the source of
   truth for Coffee GB artifacts; Gradle does not include, compile, or copy emulator sources.
2. Phase 0 consumes only Maven module `android-portable`. It contains one Java record/switch probe
   and one Kotlin class. It proves that the Android D8/R8 pipeline consumes Coffee GB's current
   Java and Kotlin bytecode shape without falsely declaring `core` or `controller` portable.
3. Dependencies are managed in the parent POM but declared by each child. The temporary portable
   artifact therefore has only Kotlin's standard library in its runtime graph, rather than
   inheriting JLine or a desktop logging binding.
4. The Android build accepts `coffeeGbMavenRepository` only when it resolves exactly to
   `<checkout>/build/android-m2`. It has no Maven-local fallback. The documented Maven command
   installs the parent POM and `android-portable` into that directory before Gradle resolves.
   A clean checkout has no such directory, so Gradle fails rather than accepting stale artifacts.
5. The Android app is Java-only in Phase 0 and has no emulator feature. It is a minimal Activity
   that exercises both classes from the same-checkout artifact. It requests no permission.

This boundary is intentionally temporary. Phase 1 must make real reusable contracts portable. It
must not bypass the work by adding `core` or `controller` to `android/app` while the forbidden
surfaces above remain.

## Toolchain

As selected on 2026-08-03:

| Setting | Value | Reason |
| --- | --- | --- |
| Android Gradle Plugin | 9.3.0 | Current stable plugin; its release notes support API 37 and require Gradle 9.5/JDK 17. |
| Gradle wrapper | 9.5.0 | Required by AGP 9.3. |
| Gradle/Java toolchain | JDK 17 or newer; Java 17 source/target | Satisfies AGP 9.3 and D8/R8 language support. Maven continues compiling existing Java 16 code. |
| `minSdk` | 26 | Roadmap baseline (Android 8.0). |
| `compileSdk` / `targetSdk` | 36 | Android 16 is the current stable API at this decision. |
| Native ABIs | all supported Android ABIs | The starter app has no native code and applies no ABI filter. |

The versions are pinned. Updating AGP or either SDK level is an intentional build-boundary change,
not an implicit dependency upgrade.

## Enforcement and verification

`app:verifyAndroidPortability` scans Android production sources and the resolved debug runtime
JARs for AWT, Swing, `javax.sound`, JLine, OpenCV, and SDL references. It also runs a deliberately
forbidden `java.awt` fixture through the same scanner, so a regression in the check cannot make a
false pass. `app:reportAndroidDependencyGraph` writes the resolved runtime graph to
`android/app/build/reports/android-dependencies.txt`.

`app:verifyDebugPermissions` inspects the merged debug manifest and rejects Internet, camera,
microphone, broad-storage/media permissions, and vibration. The current manifest declares none.

From a clean checkout:

```bash
mvn -B test
mvn -B -pl android-portable -am install -DskipTests -Dmaven.repo.local="$PWD/build/android-m2"
./android/gradlew -p android -PcoffeeGbMavenRepository="$PWD/build/android-m2" \
  :app:check :app:lintDebug :app:assembleDebug :app:assembleRelease
```

`assembleRelease` keeps code and resource shrinking enabled, exercising R8. Device instrumentation
and Android 26/36 emulator coverage are Phase 9 work; no Phase-0 feature needs a device runner.

## Consequences

- Desktop Maven behavior remains unchanged at runtime, but modules now declare the libraries they
  actually use instead of inheriting them from the parent.
- The Android APK contains no Coffee GB emulator implementation yet. That is an honest portability
  boundary, not a second emulator or a claim of playable support.
- Phase 1 owns migration of the documented AWT/JLine APIs. Later phases own URI storage, session
  lifecycle, renderer, input, audio, UI, device integrations, and CI instrumentation.
- No Internet, broad storage, camera, microphone, analytics, or ROM/service integration is
  introduced. ROM and persistent-data ownership remain unchanged until their dedicated phases.
