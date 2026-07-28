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
3. inspects jpackage's exact payload image and runs both packaged `--version` and
   `--package-smoke`;
4. writes `PACKAGE-RESULT.properties`, a target CycloneDX SBOM containing the Maven graph plus
   exact native/JDK/module/installer evidence, and exhaustive `SHA256SUMS`;
5. unpacks or mounts the final installer and repeats strict inspection and both launch smokes from
   an isolated temporary home; and
6. uploads only the installer, SBOM, checksums, result manifest, and (once, from Linux) the
   unchanged portable Maven JAR.

`--package-smoke` is deliberately headless and device-free. Its ROM is generated in memory from
reviewable instructions; no ROM, save, screenshot, or StateFile fixture is committed or uploaded.
The smoke covers a complete display frame, an audio buffer, live input press/release, and the
existing portable-state codec/load path.

The content verifier bounds traversal and rejects symlinks in application input, a second runtime,
foreign or altered target natives, ROM-like files, signing-store exports, private-key/token-shaped
text, developer home paths, missing notices, version drift, altered SBOMs, duplicate or stale
checksums, and unlisted files.

Maven's repository cache is keyed only by `pom.xml` inputs. CI never caches build output,
`~/.coffee-gb`, package smoke homes, settings, ROM directories, batteries, states, device data, or
native extraction caches. Per-target artifacts have seven-day retention; the complete gated bundle
has fourteen-day retention.

## Tagged publication

The normal Maven release workflow creates the exact `coffee-gb-VERSION` tag and portable release,
then calls the reusable native workflow with the fully qualified `refs/tags/coffee-gb-VERSION`.
The workflow peels that tag once to a full immutable commit ID; every matrix/gate/publication
checkout uses that ID and verifies `HEAD`, and the tag is re-fetched and re-peeled before gating and
again immediately before upload. A moved tag therefore fails closed. The release matrix records
the bound source commit.

Publication waits for all four matrix jobs and a Linux release-gate job. The gate independently
downloads the results, requires one default installer and one target-specific SBOM per target,
renames installers with explicit architecture, retains detached signatures, and creates one
release-level checksum file.

No partial matrix is uploaded. A missing platform fails the release workflow, and the GitHub
release must not be announced as complete. If maintainers deliberately withdraw a target in a
future reviewed change, update `NativeTarget`, this table, user installation documentation, and
the release notes together; never silently omit it.

Automated CI artifacts, including tagged artifacts, are unsigned and the matrix records that fact.
The workflow has no signing-secret references on pull requests. Platform signing/notarization is a
separate protected-store operation gated by `--release-sign`; if maintainers use it, all resulting
target records and release notes must accurately say `verified-embedded` or
`verified-detached`; command execution without the platform verification step is not signed
release evidence.

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
