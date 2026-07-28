# Coffee GB

<p align="center">
  <strong>A highly compatible Game Boy and Game Boy Color emulator for the desktop.</strong>
</p>

<p align="center">
  <a href="https://github.com/trekawek/coffee-gb/actions/workflows/maven.yml"><img alt="Java CI" src="https://github.com/trekawek/coffee-gb/actions/workflows/maven.yml/badge.svg"></a>
  <a href="https://github.com/trekawek/coffee-gb/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/trekawek/coffee-gb?sort=semver"></a>
  <a href="LICENSE"><img alt="MIT License" src="https://img.shields.io/badge/license-MIT-blue.svg"></a>
</p>

<p align="center">
  <img src="doc/tetris.gif" width="326" alt="Coffee GB running Tetris DX">
</p>

Coffee GB emulates the original Game Boy (GB/DMG) and Game Boy Color (GBC/CGB).
It is built for high compatibility across the historic game library, unusual
cartridges and accessories, and modern homebrew, demos, and diagnostic ROMs.
The reusable emulation core is written in Java; the desktop application and its
orchestration layer use Kotlin and Java.

## Download and play

Coffee GB is distributed as a single executable JAR. It requires a desktop
**Java 16 or newer** runtime; [Java 21 LTS](https://adoptium.net/temurin/releases/?version=21)
is recommended and is the version used by CI and release builds.

1. Download the JAR from the [latest Coffee GB release](https://github.com/trekawek/coffee-gb/releases/latest).
2. Open it through your desktop's Java launcher, or start it from a terminal:

   ```bash
   java -jar coffee-gb-VERSION.jar
   ```

3. Choose **File > Load ROM**, or pass a ROM on the command line:

   ```bash
   java -jar coffee-gb-VERSION.jar path/to/game.gb
   ```

Run `java -jar coffee-gb-VERSION.jar --help` for the complete option list or
`java -jar coffee-gb-VERSION.jar --version` to print the packaged version. Command-line parsing is
strict: unknown, malformed, or conflicting options write a diagnostic to standard error and exit
with status 2 before Swing starts. Use `--` to end option parsing when a ROM path begins with `-`:

```bash
java -jar coffee-gb-VERSION.jar -- -homebrew.gb
```

ROMs are not included. Coffee GB accepts `.gb`, `.gbc`, and `.rom` files and
bounded ZIP archives containing those formats. You can also drop one local file
on the emulator window; macOS Finder open-file events use the same opening flow.
If a ZIP contains several valid ROMs, Coffee GB asks which exact entry to open.
7z files are deliberately rejected because their metadata allocation cannot be
bounded before parsing—extract the ROM or create a ZIP instead. Recent ROMs are
recorded only after a game starts successfully.

Opening is cancellable and runs away from the Swing event thread. Coffee GB
snapshots the selected bytes before parsing them, rejects remote URLs, multiple
dropped files, unsafe ZIP paths, and archives outside its size/count limits, and
keeps the current game running if preparation or save-before-switch fails. On
macOS, game-controller support also requires SDL2 (`brew install sdl2`);
keyboard input works without it.

For netplay, one player chooses **Link > Start server** and the other chooses
**Link > Connect to server**.

The next-generation pairing and consent design is frozen as **protocol v9**. An opt-in developer
foundation now validates CGB9 framing and HELLO capabilities. Its Part-1 API strictly parses
one-use invitations and authenticates a reserved guest slot. Its explicit Part-2 API exchanges
caller-prepared, bounded MANIFEST metadata. An additional explicit Part-3 plan enables two-sided,
item-scoped consent and one bounded ROM or battery transaction per approved proposal, then stops at
an immutable pre-START boundary. Content providers are opened only after both exact approvals;
verified receivers deliver only complete detached candidates. Callers without these opt-ins retain
their earlier boundary. Own-ROM exact match remains the default and performs no transfer.
An additional #349 developer plan enables direct StateFile-v2 checkpoints, atomic frame-safe
restore, START/READY, and bounded ACTIVE input/reset/stop. It remains opt-in and is not wired to the
default UI. Explicit v9 callers may enable bounded PING/rollback metrics and an EDT-safe sanitized
diagnostics panel. A separate trusted-LAN discovery service is off by default and advertises only
an untrusted numeric endpoint/public session ID while a listener has an open slot; confirmation
and the existing one-use authenticated invitation are still mandatory. It provides no automatic
connection, tokenless flow, matchmaking, NAT traversal, or encryption. Current user netplay
remains v8. V9 deliberately
does not interoperate or
downgrade to v8. Its TCP transport is plaintext—not confidential or secure against an on-path
attacker. See
[the v9 privacy/troubleshooting guide](docs/netplay-v9-privacy.md) and the
[normative v9 contract](docs/netplay-protocol-v9.md). The implemented foundation boundary is
documented in [netplay-v9-foundation.md](docs/netplay-v9-foundation.md).

Mobile Adapter GB support is likewise specification-only. The clean-room design treats it as a
bounded serial peripheral, never a rollback link mode, and neither connects to historical Nintendo
services nor bundles service data. See [the Mobile Adapter contract](docs/mobile-adapter-contract.md).

### Hardware profiles and command-line selection

Each emulation session resolves one immutable hardware profile before construction. The permanent
profile IDs are `dmg`, `cgb`, `cgb0`, `sgb`, `sgb2`, and `mgb`. Automatic selection preserves the desktop defaults;
an exact profile can be selected with:

```bash
java -jar coffee-gb-VERSION.jar --profile=cgb0 path/to/game.gbc
java -jar coffee-gb-VERSION.jar --profile=sgb2 path/to/sgb-game.gb
java -jar coffee-gb-VERSION.jar --profile=mgb path/to/game.gb
```

The legacy `--force-dmg`/`-d` and `--force-cgb`/`-c` flags remain available and map to `dmg` and
`cgb`. `--profile` cannot be combined with either force flag, and the two force flags cannot be
combined with each other. Unknown or malformed profile IDs fail before a core session is created
and report all supported IDs. Use `--use-bootstrap`/`-b` to run a bundled boot ROM normally; a
profile without a bundled boot ROM is rejected. Use `--disable-battery-saves`/`-db` to disable
battery-file reads and writes for that launch.

All explicit command-line choices are process-local overrides: they take priority over persisted
settings but are never written to `~/.coffeegb.properties`. Persisted uppercase `DMG`, `CGB`,
`CGB0`, and `SGB` values are migrated as finite compatibility aliases; new settings use canonical
lowercase IDs. See the [desktop command-line and settings contract](docs/desktop-settings.md) for
precedence, validation, migration, and recovery behavior, and
[`docs/hardware-profiles.md`](docs/hardware-profiles.md) for clocks, boot policy, state identity,
and extension rules.

The registry-generated System menu exposes `Auto (default)` plus every profile, including distinct
SGB, SGB2, and MGB choices. SGB2 and MGB require skip-bootstrap mode because Coffee GB does not
bundle their Nintendo boot ROMs. New SGB/SGB2/MGB local snapshots use StateFile v2 so exact model
identity and RTC phase meaning are unambiguous; old v1 snapshots remain importable. Protocol v8 is
frozen to StateFile v1, so SGB, SGB2, and MGB are rejected for netplay rather than transmitting an
ambiguous or aliased identity.

### Default controls

| Action | Key |
| --- | --- |
| D-pad | Arrow keys |
| A / B | <kbd>Z</kbd> / <kbd>X</kbd> |
| Start / Select | <kbd>Enter</kbd> / <kbd>Shift</kbd> |
| Pause | <kbd>Space</kbd> |
| Save / load state | <kbd>F5</kbd> / <kbd>F7</kbd> |
| Take screenshot | <kbd>F12</kbd> |
| Rewind | Hold <kbd>Backspace</kbd> |
| Toggle fullscreen | <kbd>F11</kbd> (disabled if assigned to an emulated button) |

In single-player mode, **Emulation > Manage States…** provides ten stable slots, named states,
previews, export, and autosave/resume. The original <kbd>F5</kbd>/<kbd>F7</kbd> quick slots and their
`.sn0`&ndash;`.sn9` files remain supported. Managed states and screenshots are namespaced by exact ROM
hash in a hidden directory beside the ROM, or below the directory selected in Saves preferences.
A selected Saves directory also owns collision-safe battery saves; leaving it blank retains the
portable `.sav` sidecar behavior.
See [managed states, autosave, screenshots, and rewind](docs/state-management.md) for storage,
fallback, recovery, privacy, and keyboard-access details. Pause, states, and rewind are disabled
during netplay.

<details>
<summary>Custom keyboard and game-controller mapping</summary>

Desktop settings are stored in `~/.coffeegb.properties`. **File > Preferences…** configures
General behavior, aspect-correct integer/fit/1×–4× display scaling, letterboxing, fullscreen,
four-player keyboard bindings, gamepad assignment/tuning, audio device/volume/mute/latency, and
Saves policy (directory, battery saves, rewind bounds, autosave, and resume) without manual editing.
The resizable display preserves DMG/CGB and SGB-border geometry; fullscreen restores and clamps
prior window placement across monitor and DPI changes. The complete typed schema, validation,
rendering fallback, migration, device fallback, and recovery rules are documented in the
[desktop command-line and settings contract](docs/desktop-settings.md). For keyboard mappings, use
[`KeyEvent`](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/event/KeyEvent.html)
constant names:

```properties
input.p1.btn_up=VK_UP
input.p1.btn_down=VK_DOWN
input.p1.btn_left=VK_LEFT
input.p1.btn_right=VK_RIGHT
input.p1.btn_a=VK_Z
input.p1.btn_b=VK_X
input.p1.btn_start=VK_ENTER
input.p1.btn_select=VK_SHIFT
```

Unversioned legacy files may use the historical `btn_*` P1 aliases; migration accepts and
rewrites them to the canonical `input.p1.btn_*` keys above. Do not add both forms to a versioned
settings file. SGB games can use independent P2-P4 keyboard mappings with disjoint keys:

```properties
input.p2.btn_up=VK_W
input.p2.btn_down=VK_S
input.p2.btn_left=VK_A
input.p2.btn_right=VK_D
input.p2.btn_a=VK_G
input.p2.btn_b=VK_F
input.p2.btn_start=VK_T
input.p2.btn_select=VK_R
```

The same `input.pN.btn_<button>=VK_*` grammar accepts `p1` through `p4`. A key may belong to
only one logical player. If a manual edit contains a malformed player/button/key or a collision,
Coffee GB preserves the invalid file, starts with safe defaults, and shows a clear settings warning
instead of silently overwriting a mapping.

P1 uses the first available SDL game controller by default. The explicit grammar is
`input.pN.gamepad=auto|none|sdl-<64 lowercase hex digits>`. Coffee GB logs every attached
controller's stable `sdl-*` ID once when it is discovered, including unassigned controllers; copy
that value to pin a physical device to P1-P4. Only one player
may use a given ID, and only one `auto` assignment is allowed. The ID hashes SDL's GUID, device
path, and name. If SDL exposes no path, the current connection's instance ID disambiguates otherwise
identical pads. IDs are stable across enumeration-order changes; an OS path change (or reconnect on
a path-less backend) is conservatively treated as device replacement.

Per-device movement and tilt dead zones plus X/Y inversion are available in Preferences. Controller
discovery stays on the SDL polling thread; disconnects, focus loss, ROM changes, and mapping changes
release held input to prevent stuck buttons.

Audio Preferences lists outputs asynchronously and retains an unavailable configured output while
using the system default as a safe runtime fallback, then returns to the configured output when it
reappears. Master volume and mute are software controlled; the LOW, BALANCED, and SAFE presets trade
latency for additional buffering without changing emulation timing.

Independent SGB P2-P4 desktop input is available only in local/basic-controller mode. Netplay
protocol v8 carries one frame-owned P1 stream per linked emulator and has no representation for
local SGB controller slots, so every linked machine masks the live four-slot desktop source.

</details>

## Features

- **Systems:** full DMG and CGB emulation, plus Super Game
  Boy borders and palettes.
- **Hardware-focused accuracy:** a cycle-stepped CPU and high-accuracy PPU, APU,
  timer, DMA, serial, and infrared behavior.
- **Everyday play:** battery-backed saves, ten save-state slots, pause/reset,
  hold-to-rewind, success-only recent ROMs, drag-and-drop, and bounded ZIP
  archive loading.
- **Rollback netplay:** TCP multiplayer for link-cable games, with local rollback
  hiding normal network latency and synchronized infrared communication.
- **Broad cartridge support:** MBC1/1M, MBC2, MBC3 with RTC and MBC30, MBC5,
  MBC6 with flash, MBC7 with EEPROM/accelerometer, MMM01, HuC1, HuC3, TAMA5,
  Pocket Camera, and numerous unlicensed and multicart mappers.
- **Accessories:** webcam-backed Game Boy Camera, Game Boy Printer with PNG
  export, Barcode Boy, Full Changer infrared, Datel Action Replay pass-through,
  cartridge rumble, and tilt input.
- **Desktop controls and display:** keyboard and game-controller input, scaling,
  rotation, grayscale, CGB color correction, LCD ghosting, and an SGB-border toggle.
- **Cheats:** Game Genie and GameShark codes, plus a bundled searchable
  [libretro cheat database](https://github.com/libretro/libretro-database/tree/master/cht/Nintendo%20-%20Game%20Boy).

## Compatibility

Compatibility is a defining feature of Coffee GB. Its test profiles exercise
**5,696 automated verdicts from 16 suite families**, covering all popular Game
Boy and Game Boy Color test suites, and every verdict passes. Coffee GB also
earns the **maximum score** in
[GBEmulatorShootout](https://tomek.rekawek.eu/GBEmulatorShootout/).

> **Compatibility status:** all popular GB/GBC test suites pass, and every
> exact-reference image suite is pixel-perfect: both Acid2 tests,
> CGB-ACID-HELL, Strikethrough, all four CasualPokePlayer tests, and all 24
> Mealybug Tearoom tests.

| Test suite | Cases exercised | Current result |
| --- | ---: | --- |
| [Blargg](https://github.com/retrio/gb-test-roms) | 54 | 54 / 54 pass\* |
| [Mooneye Test Suite](https://github.com/Gekkio/mooneye-test-suite) | 130 | 130 / 130 selected cases pass |
| [RTC3Test](https://github.com/aaaaaa123456789/rtc3test) | 3 | 3 / 3 menus pass |
| [SameSuite](https://github.com/LIJI32/SameSuite) | 71 | 71 / 71 later-revision cases pass |
| [Gambatte HWTests](https://github.com/pokemon-speedrunning/gambatte-core/tree/master/test) | 4,674 | 4,674 / 4,674 canonical DMG/CGB verdicts match hardware |
| [BullyGB](https://github.com/Ashiepaws/BullyGB) | 2 | 2 / 2 DMG and CGB cases pass |
| [MBC30Test](https://github.com/ZoomTen/mbc30test) | 1 | 1 / 1 ROM banking and SRAM case passes |
| [GBEmulatorShootout](https://github.com/gbdev/GBEmulatorShootout/) - specific tests | 9 | Maximum score; 8 / 8 images and the ROM+RAM test pass                                                             |
| [DMG-ACID2](https://github.com/mattcurrie/dmg-acid2) and [CGB-ACID2](https://github.com/mattcurrie/cgb-acid2) | 2 | 2 / 2 are pixel-perfect |
| [CGB-ACID-HELL](https://github.com/mattcurrie/cgb-acid-hell) | 1 | 1 / 1 is pixel-perfect |
| [Strikethrough](https://github.com/Ashiepaws/strikethrough.gb) | 1 | 1 / 1 is pixel-perfect |
| [CasualPokePlayer test ROMs](https://github.com/CasualPokePlayer/test-roms) | 4 | 4 / 4 are pixel-perfect |
| [Mealybug Tearoom](https://github.com/mattcurrie/mealybug-tearoom-tests) | 24 | 24 / 24 are pixel-perfect |
| [GBMicrotest](https://github.com/aappleby/GBMicrotest) | 482 | 482 / 482 machine-readable verdicts pass; 31 additional diagnostics are inventoried but have no automated verdict |
| [gbc-hw-tests](https://github.com/alyosha-tas/gbc-hw-tests) | 221 | 221 / 221 selected hardware-reference verdicts match exactly |
| [Misc.-GB-Tests](https://github.com/alyosha-tas/Misc.-GB-Tests) | 17 | 17 / 17 pass verdicts match |
| **Total** | **5,696** | **5,696 / 5,696 automated verdicts pass** |

\* Blargg's aggregate and individual checks overlap by design.

<details>
<summary>How strict compatibility results are interpreted</summary>

Every automated case must produce its documented pass value, match its selected
external hardware reference, or satisfy its upstream image oracle. Daid uses the
shootout suite's luminance tolerance; CGB-ACID-HELL, Strikethrough,
CasualPokePlayer, Acid2, and Mealybug are compared pixel for pixel with their
upstream references. All 4,674 Gambatte model cases match their canonical
hardware verdicts. Source revisions, archive membership, and selected hardware
models or ROM revisions are fixed for reproducibility. GBMicrotest's 31
non-verdict diagnostics are inventoried but are not included in the 5,696
exercised cases because they provide no machine-readable pass/fail result.

</details>

<details>
<summary>Running the exhaustive Gambatte profile</summary>

The profile evaluates all 4,674 canonical hexadecimal DMG/CGB verdicts from
3,077 ROMs with two parameter workers by default in a test JVM capped at 1 GiB.
It passes only when every case matches hardware:

```bash
mvn clean test -f core/pom.xml -Ptest-gambatte-hw
```

For bounded local runs, set both `gambatte.batchCount` and the zero-based
`gambatte.batchIndex`. Every index must run; batching partitions the
hardware-verdict matrix and does not suppress failures:

```bash
mvn test -f core/pom.xml -Ptest-gambatte-hw \
  -Dgambatte.batchCount=64 -Dgambatte.batchIndex=0
```

</details>

## AI-assisted compatibility work

Since 2026, Coffee GB has used AI coding agents as compatibility research tools.
A purpose-built [`controller.Agent`](controller/src/main/java/eu/rekawek/coffeegb/controller/Agent.kt)
API lets an agent run a ROM headlessly under scripted control, inject input,
capture frames and audio, inspect registers and memory, and disassemble
execution without driving the desktop UI.

The working loop is deliberately evidence-based:

1. Reproduce a reported problem with scripted input and capture the first point
   where emulation diverges.
2. Diagnose it against hardware-backed test ROMs, hardware captures, schematics,
   and targeted comparisons with reference emulators.
3. Make a focused change, add a regression test where practical, and run the
   focused checks; CI runs the full compatibility matrix before merge.

This makes AI useful for exploring difficult timing and cartridge edge cases,
while hardware evidence, automated tests, and maintainer review remain the
standard for correctness.

## Project history

Coffee GB began as a six-week deep dive into how a small computer works. The
[2017 origin story](https://blog.rekawek.eu/2017/02/09/coffee-gb/) covers the CPU,
pixel pipeline, audio, early compatibility testing, and first GBC implementation.
The later [rollback-netplay article](https://blog.rekawek.eu/2025/07/26/rollback-netplay-gb/)
explains how per-frame snapshots made high-latency link play practical.

| Date | Milestone |
| --- | --- |
| 31 Dec 2016 | [The project starts](https://github.com/trekawek/coffee-gb/commit/f83a638c6c296adbf8020f24cea80be23f69fb10). |
| 14 Jan 2017 | [The first playable version runs Tetris](https://github.com/trekawek/coffee-gb/commit/624885e1b6a390fd4ddc10ffb16d7375e2d43647), two weeks after the initial commit. |
| 5&ndash;7 Feb 2017 | [Game Boy Color support](https://github.com/trekawek/coffee-gb/commit/4ca6808b79bedc6a68311ed5402d9b54456e1ffd) lands with double-speed mode, banked RAM/VRAM, color graphics, and the GBC boot path. |
| 22 Dec 2017 | [Coffee GB 1.0.0](https://github.com/trekawek/coffee-gb/releases/tag/coffee-gb-1.0.0) is released. |
| 29 Feb 2024 | [Save-state support](https://github.com/trekawek/coffee-gb/commit/1ec86cb4aa8d69e3289f0542ea509013b228b67d) is added. |
| Jul 2025 | Fast mementos enable [rollback netplay](https://blog.rekawek.eu/2025/07/26/rollback-netplay-gb/), released in [1.5.0](https://github.com/trekawek/coffee-gb/releases/tag/coffee-gb-1.5.0). |
| Aug 2025 | [Super Game Boy borders and palettes](https://github.com/trekawek/coffee-gb/releases/tag/coffee-gb-1.5.2) arrive alongside command support and predefined game palettes. |
| Feb 2026 | [The headless agent interface](https://github.com/trekawek/coffee-gb/commit/377742d41f80105f8e042b9eccd1b257f7dadc2b) begins the AI-assisted compatibility workflow. |

## Architecture

Coffee GB is a Maven reactor with three modules. The dependency flow is
`swing` &rarr; `controller` &rarr; `core`; the desktop module also uses the core
directly.

| Module | Role | Depends on |
| --- | --- | --- |
| [`core`](core) | Reusable Java emulation engine: CPU, graphics, audio, memory, cartridges, serial/IR, SGB, and peripherals. | &mdash; |
| [`controller`](controller) | Kotlin orchestration: sessions, timing, save states, rewind, rollback history, and networking. | `core` |
| [`swing`](swing) | Kotlin/Java desktop UI, video/audio/input adapters, webcam and printer integration, and executable-JAR packaging. | `controller`, `core` |

The root [`pom.xml`](pom.xml) defines the reactor and shared build configuration.

## Build from source

Use a **JDK 16 or newer** and [Maven](https://maven.apache.org/) to build. JDK 21
is recommended and is used by CI.

```bash
git clone https://github.com/trekawek/coffee-gb.git
cd coffee-gb
mvn clean package
```

The executable fat JAR is created in `swing/target/`. On a development snapshot,
run it with:

```bash
java -jar swing/target/coffee-gb-*-SNAPSHOT.jar
```

## Kudos

Special thanks to [@ScottNash042](https://github.com/ScottNash042), whose
thorough compatibility testing, hard-to-find edge-case reports, and thoughtful
feature proposals have provided enormous value to Coffee GB.

Coffee GB also owes a great deal to the Game Boy hardware research community
and to the authors of every test suite linked above.

## License

Coffee GB is available under the [MIT License](LICENSE).
