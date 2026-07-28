# Native release checklist

Record the release tag, target, OS version, architecture, package filename/SHA-256, signing state,
tester, hardware/VM, and date for every row. CI launch smokes are necessary but do not replace
human audio, input, fullscreen, file-association, persistence, or uninstall evidence.

## Automated gate

- [ ] The Maven release tag is exactly `coffee-gb-VERSION`, is non-SNAPSHOT, and matches every
  package manifest and `--version`; its fully qualified ref peels to the full `source.commit`
  recorded in `NATIVE-PACKAGE-MATRIX.properties`.
- [ ] All four required targets passed their unit/integration build, pre-installer inspection,
  final installer unpack/mount, packaged `--version`, and `--package-smoke`.
- [ ] The release bundle contains the portable JAR, four target-specific CycloneDX SBOMs, Linux x64
  DEB, Windows x64 MSI, macOS x64 DMG, macOS arm64 DMG,
  `NATIVE-PACKAGE-MATRIX.properties`, and `SHA256SUMS`, plus every detached signature named by the
  matrix.
- [ ] Verify every `SHA256SUMS` entry independently after download.
- [ ] Release notes state whether each target is `unsigned`, `verified-embedded`, or
  `verified-detached` and call out any known platform limitation, including the current macOS
  system-SDL2 requirement for game controllers.
- [ ] No target is omitted. If a future reviewed support change removes one, its release notes and
  target/user documentation explicitly say so before publication.

## Manual behavior on each target

- [ ] Install or copy the package in a clean standard-user account without a system Java runtime.
- [ ] Launch with no ROM; confirm the window opens, remains responsive, and quits cleanly.
- [ ] Run packaged `--version`; compare it with the release tag and portable JAR.
- [ ] Open a known-good personally supplied ROM from the chooser, command line, drag-and-drop,
  recent list, and registered `.gb`/`.gbc`/`.rom` association.
- [ ] Confirm a failed/cancelled ROM open leaves the running session intact and reports a useful
  error without exposing a developer path.
- [ ] Play with keyboard input. Connect/disconnect a supported controller and verify mapping,
  hot-plug, and neutral release; on macOS also record whether compatible system SDL2 is installed.
- [ ] Confirm audio starts, mute/volume/device selection work, and quit releases the audio device.
- [ ] Enter and leave fullscreen, resize the window, and verify integer/aspect scaling on the
  attached display.
- [ ] Create battery-backed progress, quit, relaunch, and confirm it persisted. Repeat package
  upgrade/reinstall without losing it.
- [ ] Save and load a state, verify the thumbnail/metadata where available, and confirm a wrong-ROM
  or corrupt state is rejected without changing the live session.
- [ ] Exercise pause/reset/rewind and a screenshot/open-folder action where supported.
- [ ] Open the debug console once and confirm package-native fallback diagnostics contain no ROM,
  state, credential, or private path.
- [ ] Uninstall/remove the application. Confirm launchers, shortcuts, and file associations are
  removed while ROMs, batteries, states, settings, screenshots, and other user data remain.
- [ ] Reinstall the same build and confirm retained user data is still usable.

Do not upload test ROMs, battery saves, states, screenshots containing copyrighted game imagery,
logs with private paths, or signing material as release evidence. Record only the target, outcome,
sanitized failure category, and artifact digest.
