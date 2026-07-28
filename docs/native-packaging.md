# Native packaging foundation

This document defines the Phase 5a boundary of
[the Desktop 2.0 package work](https://github.com/trekawek/coffee-gb/issues/338). Maven remains the
authoritative application build. This foundation does not claim that MSI, DMG, or DEB installers
exist yet; the follow-up packaging slice consumes the artifacts and contracts described here.

## Desktop artifacts

`mvn package` produces two executable desktop JARs in `swing/target/`:

| Artifact | Purpose | Native contents |
| --- | --- | --- |
| `coffee-gb-VERSION.jar` | Existing universal portable download | All native resources supplied by the pinned runtime dependencies |
| `coffee-gb-VERSION-app.jar` | Target-neutral `jlink`/`jpackage` input | None |

The unclassified universal JAR retains its filename, Maven main-artifact role, main class, version,
and normal classpath-native behavior. The attached `app` artifact is not offered as a standalone
replacement: camera and controller integrations need a verified target bundle or their ordinary
portable fallback.

The neutral assembly excludes every `.dll`, `.dylib`, `.jnilib`, `.so`, versioned `.so`, `.a`,
`.bundle`, and `.node` resource while retaining the Java APIs and service-provider descriptors.
The current locked dependency inventory is 54 native files: 7 OpenCV, 6 SDL2, 15 JLine JNI, and 26
JNA dispatch libraries. `NativeBundleManifestTest` locks this source inventory, while the
post-assembly `NativeArtifactIT` proves that the neutral JAR contains zero such files, the universal
JAR still contains all 54, both manifests report the same version, and both `--version` launch
smokes succeed.

## Explicit package targets

Package target selection is external and injectable. Runtime code never converts the build host's
`os.name` or `os.arch` into a release target. The only accepted stable target IDs are:

| Target ID | Locked bundle | Controller limitation |
| --- | --- | --- |
| `linux-x86-64` | JNA dispatch, OpenCV, SDL2 | SDL2 bundled |
| `windows-x86-64` | JNA dispatch, OpenCV, SDL2 | SDL2 bundled |
| `macos-x86-64` | JNA dispatch, OpenCV | System SDL2 still required |
| `macos-aarch64` | JNA dispatch, OpenCV | System SDL2 still required |

Every selected resource has an exact source path, destination path, byte count, and SHA-256 in
`NativeBundleManifest`. These locks correspond to OpenPnP OpenCV `4.9.0-0`, libsdl4j
`2.28.4-1.6`, and JNA `5.13.0`.

The pinned libsdl4j artifact has Linux and Windows SDL2 binaries but **no macOS SDL2 binary**.
Consequently, the current macOS target is not self-contained for game-controller input. Users may
install SDL2 with Homebrew; keyboard input remains available without it. A future package must pin
and audit an official macOS SDL2 framework (including its license/source offer requirements) before
claiming self-contained controller support. It must not copy or relabel an unrelated binary.

JLine's optional JNI provider is intentionally not selected for target bundles. Its Java, exec,
and JNA providers remain in the app artifact, and the selected JNA dispatch library supports the
JNA path. This removes 15 otherwise unused cross-target binaries. The package smoke matrix must
continue to exercise debug-console startup before release.

## External native-source handoff

The neutral app JAR cannot extract native bytes from itself. A package launcher that requests a
target must provide an external, read-only resource tree whose relative paths match the locked
dependency resource paths:

```text
native-source/
  com/sun/jna/...
  nu/pattern/opencv/...
  linux-x86-64/...       # Linux package only
  win32-x86-64/...       # Windows package only
```

The Phase 5b build must populate that tree from Maven-resolved artifacts using the selected
manifest; it must not download a second dependency set or infer a target from the machine doing the
build. It then supplies both launcher properties:

```text
-Dcoffee-gb.native.target=linux-x86-64
-Dcoffee-gb.native.source=$APPDIR/native-source
```

`coffee-gb.native.source` may be an absolute package path appropriate to the target launcher's
`$APPDIR` expansion. `coffee-gb.native.cache` optionally overrides the per-user cache; the default
is `~/.coffee-gb/native-cache`.

An explicit target without an external source produces `NativeSourceNotConfigured`. Unknown target
IDs, missing entries, a busy native cache, invalid manifests, digest/size mismatches, and I/O
failures likewise have distinct result types. Startup logs the failure category and uses the
portable fallback rather than crashing the desktop. With the universal JAR and no target property,
the old classpath-native path is unchanged and no cache I/O occurs.

## Extraction and cache safety

`NativeBundleResolver` accepts the target, resource source, and cache path as injected values. It:

1. validates all resource and output paths before opening a source;
2. rejects absolute paths, traversal, Windows drive paths, backslashes, empty segments, symlinks,
   duplicate outputs/components, oversized entries, and malformed digests;
3. serves an already-verified immutable cache hit without waiting for a writer, while cache misses
   serialize writers with interruptible JVM-local and OS file locks under one five-second deadline;
4. copies bounded bytes into a private staging directory and verifies exact size and SHA-256;
5. writes a deterministic manifest marker and atomically publishes the complete
   content-addressed directory; and
6. verifies every file and rejects unexpected files before returning paths to JNA, SDL2, or
   OpenCV.

Corrupt published content is reported and never silently overwritten. Failed staging directories
are removed and are never recognized as bundles. Resolution happens on the launch caller before
Swing or the emulation timing thread starts. Optional OpenCV loading, camera device open/close, and
stale-result cleanup run on a cancellable daemon worker; only current-result menu and emulator-source
updates run on the EDT. Native discovery and device I/O never run on the EDT or timing thread.

## Dependency and license constraints

Target packages must include third-party notices and the exact corresponding licenses:

| Dependency/native | Pinned version | Declared license |
| --- | --- | --- |
| OpenPnP OpenCV / OpenCV | `4.9.0-0` | BSD |
| libsdl4j / SDL2 | `2.28.4-1.6` | zlib |
| JNA dispatch | `5.13.0` | LGPL-2.1-or-later or Apache-2.0 |
| JLine (Java providers; JNI omitted) | `3.25.1` | BSD-3-Clause |

This table is an inventory aid, not a substitute for distributing the license texts, notices,
source URL, and any LGPL relinking/source obligations required by the final package. Phase 5b owns
that package content and its automated verification.

When any native-bearing dependency changes:

1. inspect the complete dependency JAR inventory, licenses, and supported architectures;
2. update only deliberate target entries in `NativeBundleManifest`, including sizes and SHA-256;
3. update the exact 54-entry source-inventory regression;
4. run the focused packaging tests and `mvn clean verify`;
5. inspect both JAR inventories and compare reproducible archive SHA-256 values using the pinned
   Maven/JDK toolchain; and
6. record platform launch evidence, including camera, controller, debug-console, missing-native,
   and keyboard-only fallback behavior.
