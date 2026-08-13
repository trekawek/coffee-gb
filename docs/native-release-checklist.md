# Native release checklist

Record the release tag, target, OS version, architecture, package filename/SHA-256, signing state,
tester, hardware/VM, and date for every row. CI launch and no-registration checks are necessary but
do not replace human audio, input, fullscreen, personally supplied ROM,
persistence, or uninstall evidence.

The completed record is the approval evidence for the protected `native-release` GitHub
environment. Do not approve that environment—or publish its bundle—until every required row below
has a named tester, target/architecture, date, and result. A tag push alone must never publish.

## Automated gate

- [ ] The Maven release tag is exactly `coffee-gb-VERSION`, is non-SNAPSHOT, and matches every
  package manifest and `--version`; its fully qualified ref peels to the full `source.commit`
  recorded in `NATIVE-PACKAGE-MATRIX.properties`.
- [ ] All four required targets passed their unit/integration build, pre-package inspection,
  final package extract/install/mount, packaged `--version`, `--package-smoke`, normal and `--debug`
  production desktop launches, no-registration checks for `.gb`, `.gbc`, and `.rom`, bounded
  shutdown, and MSI cleanup where applicable. Each package smoke named the exact configured target after
  starting from its own empty extraction cache.
- [ ] If protected signing was requested, every target was rebuilt from the same immutable source
  after the unsigned gate. Windows app-image executables and the MSI, macOS app bundles and DMGs,
  and Linux detached signatures all passed their independent platform verification. The extracted
  macOS app retained `com.apple.security.cs.disable-library-validation=true`, passed Gatekeeper,
  and launched with its extracted locked natives; checksums were generated afterward.
- [ ] The release bundle contains the portable JAR, Linux x64 DEB, Windows x64 MSI, macOS x64 DMG,
  macOS arm64 DMG, `NATIVE-PACKAGE-MATRIX.properties`, and `SHA256SUMS`, plus every detached
  signature named by the matrix. It contains no `*.json` files; the matrix records the Maven and
  target-native SBOM digests validated before assembly.
- [ ] Verify every `SHA256SUMS` entry independently after download.
- [ ] Release notes state whether each target is `unsigned`, `verified-embedded`, or
  `verified-detached`, exactly matching all four `target.*.signing` matrix values, and call out any
  known platform limitation, including the current macOS system-SDL2 requirement for game
  controllers.
- [ ] The GitHub release is public, non-draft, and non-prerelease only after its exact remote asset
  set and signing-state notes have been downloaded and verified.
- [ ] No target is omitted. If a future reviewed support change removes one, its release notes and
  target/user documentation explicitly say so before publication.

## Manual behavior on each target

- [ ] Install, copy, or run the package in a clean standard-user account without a system Java runtime.
- [ ] In the installer license UI where applicable and packaged legal file, confirm `Tomasz Rękawek`
  is displayed exactly, with no replacement characters or mojibake.
- [ ] Launch with no ROM; confirm the window opens, remains responsive, and quits cleanly.
- [ ] Run packaged `--version`; compare the complete Maven version with the release tag and
  portable JAR. On Windows use `Coffee GB Console.exe --version` and confirm normal GUI launches do
  not flash a console.
- [ ] Confirm the package did not make Coffee GB the default or optional OS handler for `.gb`,
  `.gbc`, or `.rom`. Then open a known-good personally supplied ROM from the chooser, command line,
  drag-and-drop, and recent list.
- [ ] Confirm a failed/cancelled ROM open leaves the running session intact and reports a useful
  error without exposing a developer path.
- [ ] Play with keyboard input. Connect/disconnect a supported controller and verify mapping,
  hot-plug, and neutral release; on macOS also record whether compatible system SDL2 is installed.
- [ ] Confirm audio starts, mute/volume/device selection work, and quit releases the audio device.
- [ ] Enter and leave fullscreen with both F11 and Escape, resize the window, and verify complete
  aspect-preserving fit plus the 1×/2×/4× window-size commands on the attached display.
- [ ] Create battery-backed progress, quit, relaunch, and confirm it persisted. Repeat package
  upgrade/reinstall without losing it.
- [ ] Save and load a state, verify the thumbnail/metadata where available, and confirm a wrong-ROM
  or corrupt state is rejected without changing the live session.
- [ ] Exercise pause/reset/rewind and a screenshot/open-folder action where supported.
- [ ] Launch through the packaged `--debug` path (the secondary console launcher on Windows) and
  confirm the production window starts and package-native fallback diagnostics contain no ROM,
  state, credential, or private path.
- [ ] For the Windows MSI, confirm that the Coffee GB desktop and Start Menu links launch
  `Coffee GB.exe`, no `Coffee GB Console.exe` shortcut exists, and no Coffee GB ROM association was
  added. Uninstall it and confirm the application and both Coffee GB shortcuts are removed.
- [ ] Confirm ROMs, batteries, states, settings, screenshots, and other user data remain usable
  after closing and relaunching the package.

Release notes may include up to three screenshots from lawfully obtained commercial games per
release when a project maintainer with release authority explicitly approves the exact images for
editorial or promotional use. Keep them narrowly focused on showing Coffee GB's UI or compatibility,
identify the game and rights holder, state that no endorsement is implied, and prefer freely
redistributable test ROMs for automated or conformance evidence.

Do not upload test ROMs, battery saves, states, private configuration files, logs with private
paths, or signing material as release evidence. Screenshots must not expose private paths, accounts,
credentials, or other user data. Apart from an approved release-note screenshot, record only the
target, outcome, sanitized failure category, and artifact digest.
