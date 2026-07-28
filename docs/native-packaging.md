# Native packaging

This document defines the Phase 5 packaging contract for
[the Desktop 2.0 package work](https://github.com/trekawek/coffee-gb/issues/338). Maven remains the
authoritative application build. Target staging, minimized runtimes, application images, and native
installers consume Maven outputs; they never compile a second application copy or resolve a second
dependency graph.

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

## Building target packages

The two checked-in wrappers run `mvn -B -pl swing -am clean package`, locate exactly one matching
neutral JAR, universal JAR, and CycloneDX SBOM, and then invoke the same Java packaging tool:

```bash
# Linux x86-64 application image for a host smoke
./packaging/package-native.sh linux-x86-64 app-image

# Linux x86-64 DEB (the target's default installer)
./packaging/package-native.sh linux-x86-64 deb
```

```powershell
# Windows x86-64 MSI (the target's default installer)
.\packaging\package-native.ps1 windows-x86-64 msi
```

Both wrappers use the portable `mvn` command from `PATH`. Set `COFFEE_GB_MAVEN_COMMAND` to an
explicit Maven executable only when the host uses another location. The wrappers do not download
dependencies, JDKs, native libraries, or signing tools themselves.

`jpackage` cannot generate a foreign platform image. The selected target is never inferred from
the host; after selection, the tool rejects a mismatched host OS or architecture:

| Target | Host | Default installer | Other validated type | Host prerequisites |
| --- | --- | --- | --- | --- |
| `linux-x86-64` | Linux x86-64 | DEB | RPM, app-image | JDK 21+, `dpkg-deb` and `desktop-file-validate` for DEB, or `rpmbuild` for RPM |
| `windows-x86-64` | Windows x86-64 | MSI | EXE, app-image | JDK 21+, WiX supported by that JDK |
| `macos-x86-64` | macOS x86-64 | DMG | PKG, app-image | JDK 21+, Xcode command-line packaging tools |
| `macos-aarch64` | macOS arm64 | DMG | PKG, app-image | arm64 JDK 21+, Xcode command-line packaging tools |

The wrapper output is under `swing/target/native-package-TARGET[-TYPE]/`. A failed or interrupted
build leaves its private diagnostics in that generated directory and refuses to overwrite it; run
the wrapper again after Maven `clean`.

### Deterministic target staging

`NativePackageStager` constructs a fresh input tree and a sorted `STAGE-SHA256SUMS`. Every file and
directory in that tree receives the fixed timestamp `2000-01-01T00:00:00Z`. The content contains:

- the Maven `-app.jar`, renamed `coffee-gb.jar`, after proving it has the expected main class,
  version, no native files, and no ROM files;
- only the selected target entries from `NativeBundleManifest`, copied from Maven's universal JAR
  after exact occurrence, byte-count, and SHA-256 checks;
- the CycloneDX 1.6 JSON SBOM generated by Maven for the desktop runtime dependency graph;
- the MIT license, native and third-party notices, full Apache-2.0/LGPL-2.1 texts, and source URLs;
- a repository-native SVG icon plus a generated target PNG, multi-resolution ICO, or
  multi-resolution ICNS container;
- `.gb`, `.gbc`, and `.rom` file-association metadata; and
- a stable package inventory containing version, target, native fingerprint, runtime roots,
  source URL, and artifact digests.

The staging tree contains no universal JAR, foreign native, runtime image, ROM, developer path,
credential, or signing secret. `NativePackageIT` stages all four targets on Linux without attempting
foreign installers, checks the exact native allowlists and legal inventory, verifies every
checksum, and compares two independent Linux stages byte-for-byte.

### Minimized bundled runtime

`jdeps --multi-release 16 --print-module-deps` is checked against the locked static dependency set:

```text
java.base,java.compiler,java.desktop,java.logging,java.management,jdk.unsupported
```

`jdk.crypto.ec` is the one deliberate dynamic addition for encrypted Java transports. `jlink`
uses those seven roots with `--strip-debug`, `--no-header-files`, `--no-man-pages`, and
`--compress=zip-6`. The resulting ten-module transitive closure is verified before jpackage:

```text
java.base, java.compiler, java.datatransfer, java.desktop, java.logging,
java.management, java.prefs, java.xml, jdk.crypto.ec, jdk.unsupported
```

Any new static dependency or changed linked closure fails packaging and requires an explicit module
inventory update plus fresh host launch evidence. The tool runs `java -jar coffee-gb.jar --version`
with the linked runtime before invoking jpackage. An `app-image` build also runs its generated
launcher with `--version`; this proves it does not consult a system JRE and reports the same full
Maven version as the portable JAR.

### Application and installer metadata

Every target uses application ID `eu.rekawek.coffeegb`, vendor `Coffee GB contributors`, the
repository source/about URL, semantic numeric installer metadata, the vector-derived icon, and the
same `MainKt` entry point. Snapshot or prerelease suffixes remain in the JAR's reported application
version but are omitted from the numeric OS installer version.

Installers register `.gb`, `.gbc`, and `.rom` with Coffee GB. Association icon paths are stable
stage-relative paths resolved from the deterministic stage working directory. The Linux desktop
template includes `%f`, so the selected ROM path reaches the launcher rather than merely opening an
empty application. Linux packages install a freedesktop `Game;` menu shortcut while retaining the
Debian package section `games`. A DEB build extracts the generated entry and runs
`desktop-file-validate`, then verifies both that section and the expected `libasound2t64`
dependency; Windows packages request
Start-menu and desktop shortcuts, retain a fixed upgrade UUID for upgrade/uninstall identity,
expose help/update URLs, and provide an install-directory chooser. macOS packages set the Games
application category and bundle identifier. OS open-file delivery is handled by the desktop
ROM-open service; end-to-end installed-package association smoke remains part of the host release
matrix.

## SBOM, checksums, and release signing

`org.cyclonedx:cyclonedx-maven-plugin:2.9.2` generates
`coffee-gb-VERSION-sbom.cdx.json` during Maven `package`. It includes compile/runtime Maven
components, exact coordinates, hashes, licenses supplied by dependency metadata, and a reproducible
timestamp-free/serial-free build record. Tests and provided/system scopes are excluded because they
are not packaged.

After jpackage, the build copies `coffee-gb-VERSION-sbom.cdx.json` into `dist/` beside the installer
or application image. `SHA256SUMS` covers that directly uploadable SBOM plus every regular
installer or application-image file using sorted relative paths. Phase 6 release automation should
generate the final release-level checksum file over the portable universal JAR, SBOM, and
host-built installers after all optional signing.

All normal and pull-request builds are unsigned. Signing is reachable only through the explicit
`--release-sign` wrapper switch and then requires all of the following:

1. `COFFEE_GB_RELEASE_SIGNING=true`;
2. a `coffee-gb-VERSION` tag ref (or matching explicit local release tag);
3. a non-SNAPSHOT `COFFEE_GB_RELEASE_VERSION` matching the application exactly;
4. an event other than `pull_request` or `pull_request_target`; and
5. platform-specific keychain/certificate/GPG references.

Credentials remain in the OS-protected store and never enter arguments, staging, logs, or package
content. macOS adds jpackage signing and then uses a named `notarytool` keychain profile before
stapling. Windows invokes `signtool` with a certificate-store SHA-1 and HTTPS timestamp service.
Linux can create an armored detached GPG signature. Phase 6 owns the isolated environment-protected
release jobs and secret wiring; PR workflows must never invoke this mode.

## Installation warnings and fallback

The Linux x86-64 release baseline is Ubuntu 24.04 LTS or a compatible newer distribution, matching
the release build provenance and the generated DEB's `libasound2t64` dependency. A bare glibc
version is not a sufficient compatibility claim because the bundled Java runtime and desktop/audio
libraries have additional ABI and package requirements. Windows 10 x86-64 or newer and macOS 12 or
newer on the packaged architecture remain the other project release floors. These are conservative
tested floors, not a claim that every older machine fails. Each native package bundles its Java
runtime, so users do not install Java separately. Linux desktop integration depends on the
distribution's ordinary menu/MIME tools, Windows MSI/EXE creation depends on WiX, and macOS
Gatekeeper warns for unsigned local/PR builds; only protected release builds may be signed and
notarized.

The portable `coffee-gb-VERSION.jar` remains the platform-neutral fallback and main Maven artifact.
It requires Java 16 or newer (Java 21 LTS recommended) and retains all dependency natives, so it is
larger than a target package input. If a packaged native source is missing, corrupt, busy, or
unsupported, Coffee GB logs the typed category and continues with keyboard-only/portable behavior
instead of preventing startup. macOS controller support still requires a compatible system SDL2;
keyboard play, emulation, saves, and other desktop functions remain self-contained.

## Dependency and license constraints

Target packages must include third-party notices and the exact corresponding licenses:

| Dependency/native | Pinned version | Declared license |
| --- | --- | --- |
| OpenPnP OpenCV / OpenCV | `4.9.0-0` | Maven metadata: BSD; OpenCV 4.9 upstream: Apache-2.0 |
| libsdl4j / SDL2 | `2.28.4-1.6` | zlib |
| JNA dispatch | `5.13.0` | LGPL-2.1-or-later or Apache-2.0 |
| JLine (Java providers; JNI omitted) | `3.25.1` | BSD-3-Clause |

This table is an inventory aid, not a substitute for distributed license texts and notices.
Packages preserve both OpenPnP's published Maven metadata notice and OpenCV 4.9's upstream
Apache-2.0 text. Coffee GB selects JNA's Apache-2.0 alternative while also carrying JNA's exact
dual-license notice and the complete LGPL-2.1 text.

When any native-bearing dependency changes:

1. inspect the complete dependency JAR inventory, licenses, and supported architectures;
2. update only deliberate target entries in `NativeBundleManifest`, including sizes and SHA-256;
3. update the exact 54-entry source-inventory regression;
4. run the focused packaging tests and `mvn clean verify`;
5. inspect both JAR inventories and compare reproducible archive SHA-256 values using the pinned
   Maven/JDK toolchain; and
6. record platform launch evidence, including camera, controller, debug-console, missing-native,
   and keyboard-only fallback behavior.
