# Native package CI and release operations

Coffee GB continuously builds the actual native deliverables, not foreign archives assembled on
Linux. Each jpackage invocation runs on its target architecture:

| Target | GitHub-hosted runner | Deliverable | Final-package check |
| --- | --- | --- | --- |
| Linux x64 | `ubuntu-24.04` | DEB | `dpkg-deb --extract` |
| Windows x64 | `windows-2025` | MSI | `msiexec /a` administrative extraction |
| macOS x64 | `macos-15-intel` | DMG | read-only `hdiutil` mount |
| macOS arm64 | `macos-15` | DMG | read-only `hdiutil` mount |

JDK 21 supplies `jdeps`, `jlink`, and `jpackage`. The Windows image must also expose WiX
`candle.exe` and `light.exe`; each job fails before building if its architecture or required host
tool is wrong. Oracle jpackage cannot cross-build these formats, so a missing target runner is a
release blocker rather than permission to relabel another architecture.

## What CI proves

The wrapper runs the Maven-authoritative `clean verify` reactor, including unit tests and the
target-neutral staging integration tests, before building one unsigned installer. The package tool
then:

1. verifies the minimized runtime module closure and launches the neutral JAR with that runtime;
2. builds the host installer without a signing request;
3. inspects jpackage's exact payload image and runs packaged `--version`, `--package-smoke`, and
   production desktop launches both normally and with `--debug`;
4. writes `PACKAGE-RESULT.properties`, a target CycloneDX SBOM containing the Maven graph plus
   exact native/JDK/module/installer evidence, and exhaustive `SHA256SUMS`;
5. unpacks or mounts the final installer and repeats strict inspection and both launch smokes from
   an isolated temporary home; and
6. installs the DEB/MSI or copies and registers the DMG application, generates bounded `.gb`,
   `.gbc`, and `.rom` fixtures, opens each through the host shell's default registered handler,
   requires correlated unified-ROM-service evidence, and verifies package removal or Launch
   Services cleanup; and
7. uploads only the installer, SBOM, checksums, result manifest, and (once, from Linux) the
   unchanged portable Maven JAR.

`--package-smoke` is deliberately headless and device-free. Its ROM is generated in memory from
reviewable instructions; no ROM, save, screenshot, or StateFile fixture is committed or uploaded.
The smoke covers a complete display frame, an audio buffer, live input press/release, and the
existing portable-state codec/load path.

Each target also launches the packaged production desktop twice with isolated homes: once normally
and once through the supported `--debug` path. Linux runs under Xvfb; Windows and macOS use their
hosted desktop sessions. Each smoke accepts success only after a visible, displayable `JFrame`
contains the production menu and display, writes exclusive readiness evidence off the EDT, and
completes the normal bounded shutdown path. A synthetic core-only smoke cannot satisfy this gate.
Windows keeps the primary `Coffee GB.exe` GUI launcher console-free and uses the secondary
`Coffee GB Console.exe` launcher for debug output and the exact full-Maven-version command probe.

The association gate uses `xdg-open` under Xvfb and a private desktop configuration on Linux,
Windows ShellExecute after inspecting all three registered extension ProgIDs, and plain macOS
Launch Services `open` after copying the application to an isolated Applications directory. Every
`.gb`, `.gbc`, and `.rom` case must select Coffee GB as the OS default handler; the tests never
bypass selection by naming the executable or bundle ID. The macOS test opens each document into an
already-running instance and therefore requires `DESKTOP_OPEN_FILE`, while Linux and Windows
require the launcher's `INITIAL_ARGUMENT` source. All targets wait for an `Opened` result from the
shared service, compare its normalized path and cartridge title, exercise bounded shutdown, and
then prove the installed launcher/registration was removed.

The content verifier bounds traversal and rejects symlinks in application input, a second runtime,
foreign or altered target natives, ROM-like files, signing-store exports, private-key/token-shaped
text, developer home paths, missing notices, version drift, altered SBOMs, duplicate or stale
checksums, and unlisted files. It applies the same forbidden-name and content checks recursively to
JAR/ZIP entries, including nested archives, with a 256 MiB top-level container limit, 4,096-character
entry-name limit, maximum nesting depth of three, 50,000 aggregate entries, 512 MiB aggregate
expanded data, and 64 MiB per entry. The sole content-scan exception is
the exact target-native allowlist in `native-source.zip`: those upstream executables contain
compiler build paths and are treated as opaque only after the dedicated reader has enforced the
stored method, manifest order, safe regular entry type, exact byte counts, and SHA-256 values.
Foreign natives and extra, duplicate, traversing, compressed, or symlink entries still fail.

Maven's repository cache is keyed only by `pom.xml` inputs. CI never caches build output,
`~/.coffee-gb`, package smoke homes, settings, ROM directories, batteries, states, device data, or
native extraction caches. The generated association ROM remains only in non-uploaded `target/`
smoke output. Per-target artifacts have seven-day retention; the complete gated bundle has
fourteen-day retention.

## Tagged publication

The Maven release workflow first runs only `release:clean release:prepare`. That operation creates
the exact `coffee-gb-VERSION` tag and release commits, but it does not deploy Maven artifacts or
create a GitHub release. The workflow then calls the reusable native workflow with the fully
qualified `refs/tags/coffee-gb-VERSION`. The native workflow peels that tag once to a full immutable
commit ID; every matrix and gate checkout uses that ID and verifies `HEAD`, and the tag is
re-fetched and re-peeled before each promotion. A moved tag therefore fails closed. The release
matrix records the bound source commit.

Publication waits for the unsigned build, installer inspection, desktop/debug launch, association,
and uninstall gates on all four targets. A Linux release-gate job independently downloads the
results, requires one default installer and one target-specific SBOM per target, renames installers
with explicit architecture, retains detached signatures, and creates one release-level checksum
file. If protected signing was requested, the workflow then rebuilds the same immutable commit in
the `native-release` environment, signs and reverifies all four targets, and assembles a replacement
complete bundle.

Only after the complete native bundle passes does a protected Maven job deploy the already-tagged
release with `mvn -P release deploy`. Only after Maven succeeds does a protected GitHub job recheck
the tag and bundle provenance, create the GitHub release, and upload the artifacts. Tag pushes do
not trigger the native workflow, so the release-created tag cannot start a duplicate run or cancel
the gated release call.

No partial matrix is uploaded. A missing platform fails the release workflow, and the GitHub
release must not be announced as complete. If maintainers deliberately withdraw a target in a
future reviewed change, update `NativeTarget`, this table, user installation documentation, and
the release notes together; never silently omit it.

Normal and pull-request artifacts are unsigned and the matrix records that fact. The release
dispatch can request `sign_native_packages`; only then, after the unsigned four-target gate, does
the protected `native-release` matrix import ephemeral credentials and invoke `--release-sign`.
The Windows path signs and verifies every visible executable/DLL in a prebuilt app image, creates
an MSI from that signed image, signs and verifies the MSI, then verifies the installed executable
payload. Digest-locked third-party native-source bytes live in a deterministic stored ZIP, so
platform signing seals the archive without changing the upstream sizes or SHA-256 values that the
runtime verifies while extracting.
The macOS path signs and verifies the app image, explicitly requires the JDK default
`com.apple.security.cs.disable-library-validation` entitlement used by the extracted native
libraries, creates and externally signs the DMG, verifies its structure and signature, notarizes
and staples it, and validates Gatekeeper, the ticket, the copied app signature, and an installed
launch. Linux creates and verifies an armored detached signature. Checksums and result/SBOM digests
are generated only after these operations.

Configure these protected environment secrets:

- Linux: `NATIVE_LINUX_GPG_PRIVATE_KEY`, `NATIVE_LINUX_GPG_KEY_ID`.
- Windows: `NATIVE_WINDOWS_CERTIFICATE_PFX_BASE64`,
  `NATIVE_WINDOWS_CERTIFICATE_PASSWORD`, `NATIVE_WINDOWS_TIMESTAMP_URL`.
- macOS: `NATIVE_MACOS_CERTIFICATE_P12_BASE64`,
  `NATIVE_MACOS_CERTIFICATE_PASSWORD`, `NATIVE_MACOS_SIGNING_KEY_USER_NAME`,
  `NATIVE_MACOS_SIGNING_IDENTITY`, `NATIVE_MACOS_NOTARY_APPLE_ID`,
  `NATIVE_MACOS_NOTARY_TEAM_ID`, and `NATIVE_MACOS_NOTARY_PASSWORD`.

The stores and decoded credential files live only under runner-temporary paths and are removed in
an `always()` cleanup step. A missing secret, unprotected event, snapshot, tag mismatch, signature
failure, timestamp failure, notarization failure, or incomplete signed matrix fails closed. The
release notes must accurately say `verified-embedded` or `verified-detached`; merely running a
signing command is not release evidence.

Every `actions/checkout`, `actions/setup-java`, `actions/upload-artifact`, and
`actions/download-artifact` use is pinned to a reviewed full commit SHA. Dependency-update review
must update the adjacent version comment and revalidate all workflow tests before changing a pin.

## Installing and removing

- Linux: verify `SHA256SUMS`, install the DEB with the distribution package tool, and remove the
  `coffee-gb` package with that same tool.
- Windows: verify `SHA256SUMS`, open the MSI, and remove Coffee GB through Installed Apps or the
  same MSI product identity.
- macOS: verify the checksum, open the DMG, copy Coffee GB to Applications, eject the image, and
  remove the application by moving it to Trash.

Native packages bundle Java. The universal JAR remains available for Java 16+ systems and as the
portable fallback. Removing an application does not delete ROMs, adjacent battery saves,
save-state storage, settings, screenshots, or native caches. Back up user data separately before
changing packages. Unsigned macOS builds may require the ordinary explicit Gatekeeper approval;
do not disable system security globally.

Before publishing, complete [the native release checklist](native-release-checklist.md) on actual
hardware or representative VMs in addition to automated evidence.
