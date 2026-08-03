# Native packaging

This document defines the native packaging and release-validation contract. Maven remains the
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
target must provide an external, read-only `native-source.zip` whose entry names match the locked
dependency resource paths:

```text
native-source.zip
  com/sun/jna/...
  nu/pattern/opencv/...
  linux-x86-64/...       # Linux package only
  win32-x86-64/...       # Windows package only
```

The staging build creates this deterministic stored ZIP from Maven-resolved artifacts using the
selected manifest; it does not download a second dependency set or infer a target from the machine
doing the build. The archive contains exactly the target manifest entries, in manifest order, with
fixed timestamps and no compression. A bounded reader rejects unsafe or duplicate paths, symlinks,
non-regular or compressed entries, extras, omissions, reordered entries, oversize archives, and
any size or SHA-256 mismatch before extraction. The package launcher supplies both properties:

```text
-Dcoffee-gb.native.target=linux-x86-64
-Dcoffee-gb.native.source=$APPDIR/native-source.zip
```

`coffee-gb.native.source` may be an absolute package path appropriate to the target launcher's
`$APPDIR` expansion. A directory remains supported as an injected development/test source, while
packaged applications always use the strict archive. `coffee-gb.native.cache` optionally overrides
the per-user cache; the default is `~/.coffee-gb/native-cache`.

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
# Windows x86-64 EXE (the target's default package)
.\packaging\package-native.ps1 windows-x86-64 exe
```

Both wrappers use the portable `mvn` command from `PATH`. Set `COFFEE_GB_MAVEN_COMMAND` to an
explicit Maven executable only when the host uses another location. The wrappers do not download
dependencies, JDKs, native libraries, or signing tools themselves.

`jpackage` cannot generate a foreign platform image. The selected target is never inferred from
the host; after selection, the tool rejects a mismatched host OS or architecture:

| Target | Host | Default installer | Other validated type | Host prerequisites |
| --- | --- | --- | --- | --- |
| `linux-x86-64` | Linux x86-64 | DEB | RPM, app-image | JDK 21+, `dpkg-deb` and `desktop-file-validate` for DEB, or `rpmbuild` for RPM |
| `windows-x86-64` | Windows x86-64 | EXE | MSI, app-image | JDK 21+, WiX supported by that JDK |
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
  into a deterministic stored ZIP after exact occurrence, byte-count, and SHA-256 checks;
- the CycloneDX 1.6 JSON SBOM generated by Maven for the desktop runtime dependency graph;
- a deterministic CycloneDX 1.6 native SBOM generated from the locked target inventory, including
  hashes for distributed native files and exact embedded-component build evidence;
- the exact target-specific legal inventory: the MIT license, native and third-party notices, full
  applicable license/EULA texts, attributions, and pinned source provenance;
- a platform-safe installer license derived from the canonical NFC UTF-8 MIT license;
- a repository-native SVG icon plus a generated target PNG, multi-resolution ICO, or
  multi-resolution ICNS container;
- an explicit no-registration policy for `.gb`, `.gbc`, and `.rom`; and
- deterministic Windows secondary-launcher metadata for the console-enabled
  `Coffee GB Console.exe` debug launcher; and
- a stable package inventory containing version, target, native fingerprint, runtime roots,
  source URL, and artifact digests.

The staging tree contains no universal JAR, foreign native, runtime image, ROM, developer path,
credential, or signing secret. `NativePackageIT` stages all four targets on Linux without attempting
foreign installers, checks the exact native allowlists and legal inventory, verifies every
checksum, and compares two independent Linux stages byte-for-byte.

### Minimized bundled runtime

`jdeps --multi-release 16 --print-module-deps` is checked against the locked static dependency set:

```text
java.base,java.compiler,java.desktop,java.logging,java.management,java.prefs,jdk.unsupported
```

`jdk.crypto.ec` is the one deliberate dynamic addition for encrypted Java transports. `jlink`
uses those eight roots with `--strip-debug`, `--no-header-files`, `--no-man-pages`, and
`--compress=zip-6`. The resulting ten-module transitive closure is verified before jpackage:

```text
java.base, java.compiler, java.datatransfer, java.desktop, java.logging,
java.management, java.prefs, java.xml, jdk.crypto.ec, jdk.unsupported
```

The merged app JAR excludes dependency `module-info.class` entries. Those descriptors describe
their original libraries, not the combined application, and retaining whichever descriptor was
unpacked last would make `jdeps` inspect an incomplete dependency graph.

Any new static dependency or changed linked closure fails packaging and requires an explicit module
inventory update plus fresh host launch evidence. The tool runs `java -jar coffee-gb.jar --version`
with the linked runtime before invoking jpackage. An `app-image` build also runs its generated
launcher with `--version`; this proves it does not consult a system JRE and reports the same full
Maven version as the portable JAR. Windows runs that command through the secondary
`Coffee GB Console.exe` launcher so stdout is observable while the normal `Coffee GB.exe` launcher
remains a console-free GUI application. With no explicit arguments, the secondary launcher enters
the supported `--debug` path.

### Application and installer metadata

Every target uses application ID `eu.rekawek.coffeegb`, vendor `Coffee GB contributors`, the
repository source/about URL, semantic numeric installer metadata, the vector-derived icon, and the
same `MainKt` entry point. Snapshot or prerelease suffixes remain in the JAR's reported application
version but are omitted from the numeric OS installer version.

The canonical packaged legal files remain NFC-normalized UTF-8 and staging requires the exact
author name `Tomasz Rękawek`. Linux passes that UTF-8 text to jpackage. Windows and macOS installer
license dialogs instead receive deterministic ASCII RTF whose `\u` escapes preserve every Unicode
code unit; the macOS resource override uses an RTF resource rather than the legacy `TEXT` resource.
This keeps the installed legal copy byte-identical while avoiding locale-dependent installer
decoding.

Installers do not register `.gb`, `.gbc`, or `.rom` with Coffee GB and do not request default- or
optional-handler status. Staging creates no jpackage association files, Linux desktop metadata has
no `MimeType`, macOS bundles declare no document or exported/imported type keys, and Windows
installation must not add Coffee GB to extension class or `OpenWith` registrations. Users can
still open ROMs deliberately from the chooser, command line, drag-and-drop, or recent list. The
Linux desktop template retains `%f` for an explicitly supplied path but advertises no ROM MIME
type. Linux packages install a freedesktop `Game;` menu shortcut while retaining the Debian package
section `games`. A DEB build extracts the package-owned
`/opt/coffee-gb/lib/coffee-gb-Coffee_GB.desktop` payload, runs `desktop-file-validate`, and proves
the bounded `postinst` script registers that exact file. It also verifies the section and expected
`libasound2t64` dependency; Windows packages request
Start-menu and desktop shortcuts, retain a fixed upgrade UUID for upgrade/uninstall identity,
expose help/update URLs, and provide an install-directory chooser. macOS packages set the Games
application category and bundle identifier. The desktop ROM-open service remains available for
explicit application-directed open-file events; packaging never claims those file types.

## SBOM, checksums, and release signing

`org.cyclonedx:cyclonedx-maven-plugin:2.9.2` generates
`coffee-gb-VERSION-sbom.cdx.json` during Maven `package`. It includes compile/runtime Maven
components, exact coordinates, hashes, licenses supplied by dependency metadata, and a reproducible
timestamp-free/serial-free build record. Tests and provided/system scopes are excluded because they
are not packaged.

Repository text is checked out with LF on every host, and `maven-jar-plugin:3.5.1` generates
LF-stable Maven descriptors, so reactor component hashes remain reproducible. Before native
staging, the raw document is strictly parsed and its root, direct component purls, and legal
inventory are validated. Staging normalizes only JSON CRLF or lone-CR whitespace to LF, then
repeats strict SBOM and legal validation. Every dependency hash, component identity and license,
metadata field, and dependency edge remains byte-significant. The staged Maven SBOM is therefore
byte-identical across all package hosts without weakening dependency provenance.

Maven coordinates alone do not describe code statically embedded in the OpenCV binaries.
`NativeComponentInventory` therefore generates a second deterministic CycloneDX 1.6 document,
`coffee-gb-native-sbom.cdx.json`, in package input. It identifies one explicit target, the exact
distributed JNA/OpenCV/SDL binaries and their SHA-256 values, every locked embedded OpenCV
component, its version and build evidence, the containing native library, and all applicable
packaged legal files. Verification regenerates the document byte-for-byte from the locked
inventory; unknown, stale, cross-target, or edited content fails packaging.

After jpackage, the build copies both documents into `dist/` as
`coffee-gb-VERSION-sbom.cdx.json` and
`coffee-gb-VERSION-TARGET-native-sbom.cdx.json`, beside the installer or application image.
The final installer digest is computed only after any signing or notarization step.
`PACKAGE-RESULT.properties` records the independently selected target, package type, complete Maven
version, verified signing state, and exact artifact, Maven SBOM, target-native SBOM, and detached
signature digests. `SHA256SUMS` is written only after both SBOM copies have been verified and covers
the result record, both short-lived validation SBOMs, any detached signature, and every regular
package or application-image file using sorted relative paths. The matrix gate consumes those JSON
files, but never copies them into the final release bundle.

The release gate accepts exactly one result for each of Linux x64, Windows x64, macOS x64,
and macOS arm64. It rejects a missing/duplicate target, non-default package type, version drift,
invalid target-native SBOM evidence, stale checksum, or unexpected file before copying anything into the
release bundle.
The final `NATIVE-PACKAGE-MATRIX.properties` names all four architecture-bearing packages and
records their validated Maven and target-native SBOM digests plus signing state. The JSON SBOMs
remain internal validation inputs and are not copied into the release bundle. Its release-level
`SHA256SUMS` covers the matrix, universal Maven JAR, all four packages, and every detached
signature; the release directory rejects every `*.json` file.

All normal and pull-request builds are unsigned. Signing is reachable only through the explicit
`--release-sign` wrapper switch and then requires all of the following:

1. `COFFEE_GB_RELEASE_SIGNING=true`;
2. a `coffee-gb-VERSION` tag ref (or matching explicit local release tag);
3. a non-SNAPSHOT `COFFEE_GB_RELEASE_VERSION` matching the application exactly;
4. an event other than `pull_request` or `pull_request_target`; and
5. platform-specific keychain/certificate/GPG references.

Credentials remain in an ephemeral OS-protected store and never enter staging or package content.
Protected release signing uses a two-stage image flow before any digest is finalized:

1. build and structurally inspect a prebuilt application image;
2. sign and verify its executable content;
3. build the default installer from that exact signed image;
4. sign and independently verify the outer installer;
5. inspect the installer payload and reverify the copied/installed executable content; and
6. only then copy the verified Maven dependency SBOM and target-native SBOM and write the result
   manifest and checksums.

On Windows the policy is deliberately EXE-only. It deterministically Authenticode-signs every
visible executable/DLL in the app image with SHA-256 and an RFC 3161 HTTPS timestamp, appending
rather than replacing an existing vendor signature, and requires `/pa /all /tw` verification
before EXE creation, in jpackage's package image, and after installation. Digest-locked
third-party native-source binaries are stored rather than compressed inside a deterministic ZIP;
platform signing seals that resource without rewriting the upstream bytes later checked during
runtime extraction. The policy then signs and verifies the outer EXE. On macOS the protected path
is deliberately DMG-only: jpackage signs the `.app` with its JDK-enabling default entitlements,
`codesign --deep --strict` verifies the complete bundle, and an explicit code requirement proves
that the application signature contains
`com.apple.security.cs.disable-library-validation=true`, which is required to load the separately
signed, digest-locked native libraries extracted at runtime. The DMG is externally signed with the
exact Developer ID Application identity; `codesign` and `hdiutil` verify it before notarization,
and `notarytool` acceptance is followed by stapling, Gatekeeper assessment, stapler validation,
installed-app signature/entitlement verification, and a real installed launch. Linux creates an
armored detached GPG signature and requires `gpg --verify`; the `.asc` is copied into the release
bundle, recorded in the matrix, and covered by checksums.

The reusable CI workflow always completes the auditable unsigned four-target gate first. A release
dispatch may additionally request the protected `native-release` signing matrix; pull requests and
tag pushes cannot invoke it. The release matrix records `unsigned`, `verified-embedded`, or
`verified-detached` for every target and never infers a verified state merely because a signing
command returned zero. See [native package CI](native-package-ci.md) for the exact protected secret
names and publication order. The manual checklist requires the state to be called out in release
notes.

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

Target packages must include third-party notices and the exact corresponding license texts.
Provenance links are evidence, not license delivery.

| Distributed native | Pinned version | License |
| --- | --- | --- |
| OpenPnP OpenCV / OpenCV | `4.9.0-0` / `4.9.0` | BSD-3-Clause and Apache-2.0 |
| libsdl4j / SDL2 (Linux and Windows only) | `2.28.4-1.6` / `2.28.4` | zlib |
| JNA dispatch | `5.13.0` | Apache-2.0 OR LGPL-2.1-or-later |
| JLine (Java providers; JNI omitted) | `3.25.1` | BSD-3-Clause |

Every locked OpenCV binary embeds this common static inventory:

| Embedded component | Exact build version | Packaged terms |
| --- | --- | --- |
| Intel ITT Notify | API build `20151119` | BSD-3-Clause and GPL-2.0-only alternatives |
| Protocol Buffers | `3.19.1` | BSD-3-Clause |
| libjpeg-turbo | `2.1.3-62` | BSD-3-Clause, zlib, and IJG terms |
| libwebp | `1.3.1` | BSD-3-Clause |
| libpng | `1.6.37` | libpng license |
| LibTIFF | `4.2.0` (ABI 42) | libtiff license |
| OpenJPEG | `2.5.0` | BSD-2-Clause |
| OpenEXR | `2.3.0` | BSD-3-Clause plus IlmBase/OpenEXR author notices |
| zlib | `1.3` | zlib license |
| FlatBuffers | `23.5.9` | Apache-2.0 |
| Berkeley SoftFloat | Release `3c` | BSD-3-Clause |
| MSCR chi table | OpenCV `4.9.0` revision | BSD-3-Clause |

Target-only OpenCV inclusions are locked separately:

| Target | Additional embedded component/version |
| --- | --- |
| `linux-x86-64` | Intel IPP/IW `2021.10.0`; vendored OpenCL headers `1.2` |
| `windows-x86-64` | Intel IPP/IW `2021.11.0`; OpenCL headers `1.2`; ADE `0.1.2d`; Intel vaSOT at the pinned OpenCV revision |
| `macos-x86-64` | Intel IPP/IW `2021.9.1`; system OpenCL framework, no vendored headers |
| `macos-aarch64` | NVIDIA Carotene `0.0.1`; OpenCV Tegra HAL; Edward Rosten FAST notice; no IPP |

The evidence lock is OpenPnP tag `v4.9.0-0` at commit
`854505364c4b1394380ef639348840824effc6ec` and OpenCV tag `4.9.0` at commit
`dad8af6b17f8e60d7b95a1203a1b4d22f56574cf`. Linux IPP comes from
`ippicv_2021.10.0_lnx_intel64_20230919_general.tgz` (MD5
`606a19b207ebedfe42d59fd916cc4850`), and macOS x86-64 IPP from
`ippicv_2021.9.1_mac_intel64_20230919_general.tgz` (MD5
`14f01c5a4780bfae9dde9b0aaf5e56fc`), both at OpenCV third-party commit
`0cc4aa06bf2bef4b05d237c69a5a96b9cd0cb85a`. Windows comes from the official OpenCV 4.9.0
self-extractor, SHA-256
`fefddff0623fbd5a6fa0cecb9bccd4b822478354e6c587ebb6e40ab09dacba51`. Its binary reports IPP/IW
`2021.11.0`, while the official adjacent Intel third-party notice names Update 10; the package
preserves that upstream notice verbatim and records the discrepancy. The separate Windows
`opencv_videoio_ffmpeg490_64.dll` is not selected, imported by the staged DLL, or distributed, so
FFmpeg is not represented as an embedded native component.

Packages preserve OpenPnP's BSD text, OpenCV 4.9's Apache-2.0 text, and OpenCV's exact upstream
copyright attribution. Coffee GB selects JNA's Apache-2.0 alternative while also carrying JNA's
exact dual-license notice and complete LGPL-2.1 text. Intel IPP targets carry the October 2022
Intel Simplified Software License and the exact third-party-program file accompanying their pinned
download.

`THIRD-PARTY-COMPONENTS.txt` maps every resolved third-party Maven purl—including Commons IO,
Commons Compress, Commons Codec, Commons Lang, Guava transitives, Kotlin, SLF4J, Checker Qual,
JLine, and XZ—to reviewed complete license files. Packaging fails if that set differs from the
Maven BOM, if a mapped license is absent, or if the human notice omits a coordinate. The complete
legal tree is embedded under `META-INF/coffee-gb/legal/` before either fat JAR is assembled and is
also copied into the native package, preventing dependency-unpack collisions from dropping it.

When any native-bearing dependency changes:

1. inspect the complete dependency JAR inventory, licenses, and supported architectures;
2. update only deliberate target entries in `NativeBundleManifest`, including sizes and SHA-256;
3. update the exact 54-entry source-inventory regression, target-native component/BOM lock, and
   target-specific full legal-file inventory;
4. run the focused packaging tests and `mvn clean verify`;
5. inspect both JAR inventories and compare reproducible archive SHA-256 values using the pinned
   Maven/JDK toolchain; and
6. record platform launch evidence, including camera, controller, debug-console, missing-native,
   and keyboard-only fallback behavior.

## Continuous package validation

`.github/workflows/native-packages.yml` runs `mvn clean verify` through the same checked-in wrappers
on Ubuntu x64, Windows x64, macOS Intel, and macOS arm64. Java setup caches only Maven dependencies
keyed by the POMs; `target/`, native caches, settings, ROMs, batteries, and state directories are
never cached. Target artifacts expire after seven days and the complete gated bundle after
fourteen.

Every host verifies jpackage's pre-installer payload, then unpacks or installs the actual DEB/EXE or mounts the
actual DMG and repeats the checks. Inspection requires:

- exactly one linked runtime and the locked ten-module closure;
- the exact target-native allowlist and digests, with no foreign native;
- no ROM-like file, signing key/certificate file, developer home path, or secret-shaped text;
- the same forbidden-content policy inside JAR/ZIP entries and nested archives, bounded to 256 MiB
  per top-level archive, 4,096 characters per entry name, three archive levels, 50,000 entries,
  512 MiB aggregate expansion, and 64 MiB per entry, with classic central-directory geometry and
  actual entry counts plus aggregate end-record/header scan work preflighted before an eager archive
  reader opens any package JAR/ZIP, followed by exact-length post-inflation reads for trusted entries;
- bounded properties/checksum metadata, fail-closed oversized recognized text, and a strict
  depth-bounded Maven CycloneDX document whose metadata root exactly identifies the verified
  Coffee GB version and whose top-level component objects each contain one unique direct Maven purl;
- exact digest-locked native entries are treated as opaque executable data after their dedicated
  stored-ZIP verifier succeeds (upstream binaries contain compiler build paths), while entry names,
  foreign native suffixes, archive structure, and every byte remain independently enforced;
- the complete catalog-validated legal inventory, canonical byte-identical Maven dependency SBOM,
  neutral app JAR, exact target-native SBOM, version, result manifest, and exhaustive sorted
  checksums; and
- packaged launcher `--version` plus `--package-smoke` from isolated temporary user roots and
  distinct empty direct-runtime and launcher extraction caches, requiring exact configured-target
  evidence rather than portable fallback; and
- normal and `--debug` production Swing startups (Xvfb on Linux, hosted desktop session elsewhere)
  that prove a visible frame, menu, display content, off-EDT readiness evidence, and bounded normal
  shutdown; and
- final-package checks proving the DEB desktop entry has no `MimeType`, the DMG application has no
  document or exported/imported type declarations, and the installed Windows EXE creates no Coffee
  GB `.gb`, `.gbc`, or `.rom` class/`OpenWith` registration.

The headless package smoke constructs its reviewable 32 KiB loop ROM, writes it into a private
temporary 7z, and opens it through the isolated helper. It verifies byte-exact extraction, the
archive-entry origin, video and audio events, a live input press/release, and an
encode/inspect/mutate/load/re-encode StateFile round trip. It reads no external or user ROM and
opens no device, user setting, or battery file; all generated temporary files are deleted and are
never packaged, cached, or uploaded. Its `native-target` result must equal the configured package
target; only a portable-JAR invocation with no target property may report `portable`.
The separate desktop smoke constructs the production Swing frontend without a ROM. See
[native package CI](native-package-ci.md) for publication and operator evidence.
