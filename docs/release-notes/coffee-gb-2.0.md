<p align="center">
  <img src="https://raw.githubusercontent.com/trekawek/coffee-gb/coffee-gb-2.0.0/packaging/resources/android/play-store-icon.png" width="128" alt="Coffee GB logo">
</p>

# Coffee GB 2.0

<p align="center"><strong>A new desktop, deeper compatibility, and more ways to play.</strong></p>

Coffee GB 2.0 is the milestone release that brings together the biggest advances from the
fast-moving 1.7 series with a completely refreshed desktop, a powerful new debugger, expanded
hardware support, and the project's strongest compatibility results yet. It feels new on the
outside—and is considerably more faithful underneath.

If you followed the 1.7.x releases, consider this the definitive 2.0 roll-up: the highlights below
cover the journey from 1.7.0 through the final wave of work after 1.7.16.

## Highlights

- **A completely refreshed desktop** with Light, Dark, and System themes, recent-game artwork,
  autosave previews, drag-and-drop, clearer status messages, reorganized menus, and redesigned
  Preferences and utility windows.
- **Coffee GB's strongest compatibility yet,** with every automated verdict passing, pixel-perfect
  reference-image suites, and the maximum GBEmulatorShootout score.
- **A new real-time debugger** with execution, memory, breakpoints, video, hardware and I/O, audio,
  and timeline tools—plus forward and reverse stepping, deterministic replay, and a headless CLI.
- **Much broader hardware support,** from the Game Boy Camera, Printer, Barcode Boy, and GPS Boy to
  infrared accessories, rumble, tilt, the four-player adapter, and Mobile Adapter GB.
- **First-class game-controller support** with hotplugging, stable per-player assignments, dead-zone
  tuning, axis inversion, rumble, tilt, and up to four local Super Game Boy players.
- **Native packages** for Linux, Windows, Intel macOS, and Apple-silicon macOS, with a bundled Java
  runtime. The universal executable JAR remains available.
- **More resilient rollback netplay,** dramatically more memory-efficient rewind, and a modern,
  crash-safe save-state experience.

<p align="center">
  <img src="https://raw.githubusercontent.com/trekawek/coffee-gb/coffee-gb-2.0.0/docs/release-notes/2.0/coffee-gb-2.0-gameplay.png" width="900" alt="Coffee GB 2.0 running Super Mario Land in the redesigned desktop interface">
</p>

## A game-first desktop

The redesigned interface keeps the game at the center. Coffee GB now opens onto a welcoming home
screen with recent-game cards and autosave thumbnails, then switches to a focused game view with a
compact command bar and persistent status. ROM opening is asynchronous and cancellable, and the
same safe flow powers the file chooser, drag-and-drop, recent games, command-line launches, and
bounded ZIP and 7z archives.

The window is freely resizable, preserves the original aspect ratio on high-DPI displays, remembers
its size and placement, and offers more polished fullscreen behavior. Display, audio, controls,
saves, hardware profiles, appearance, and peripherals now live in clear Preferences categories.

## Controllers that feel at home

SDL2-compatible controllers support hotplugging and stable assignments for Players 1–4. Each
player can use an automatic or explicit device assignment, while per-device movement and tilt dead
zones and axis inversion are available for fine tuning. Supported Player 1 controllers receive
cartridge rumble, and the right stick can drive MBC7 tilt controls.

Assign up to four controllers for independent local Super Game Boy multiplayer. Disconnects, focus
changes, ROM changes, and remapping safely release held inputs to avoid stuck buttons.

<p align="center">
  <img src="https://raw.githubusercontent.com/trekawek/coffee-gb/coffee-gb-2.0.0/docs/release-notes/2.0/coffee-gb-2.0-preferences.png" width="980" alt="Coffee GB 2.0 Controls Preferences showing keyboard mappings and player selection">
</p>

## A much larger world of Game Boy hardware

Coffee GB 2.0 supports a broad collection of cartridges and accessories:

- webcam-backed **Game Boy Camera** input and camera-device selection;
- **Game Boy Printer** emulation with PNG export;
- **Barcode Boy** and **GPS Boy**;
- **Full Changer** infrared and synchronized infrared link play;
- **Datel Action Replay** pass-through;
- MBC5 and CodeBreaker **rumble**, plus MBC7 **tilt**;
- the **DMG-07 four-player adapter**, locally and over netplay; and
- **Mobile Adapter GB**, including the Japanese Pokémon Crystal adapter flow and explicitly
  configured custom services.

## Coffee GB's strongest compatibility yet

The 1.7 series brought a sustained accuracy push across CPU interrupts, PPU and window timing,
sprites, DMA and HDMA, timers, audio, serial and infrared communication, RTC behavior, Super Game
Boy commands, boot behavior, and cartridge mappers. It fixed a long list of difficult commercial,
homebrew, demo, prototype, and unlicensed cartridges while expanding strict regression coverage.

Coffee GB 2.0's [release compatibility matrix](https://github.com/trekawek/coffee-gb#compatibility)
exercises **5,696 automated verdicts from 16 suite families**. All **4,674 Gambatte hardware cases**
match their canonical model results; the Acid2, CGB-ACID-HELL, Strikethrough, CasualPokePlayer, and
Mealybug reference suites are pixel-perfect; and Coffee GB earns the **maximum GBEmulatorShootout
score**.

## A real-time debugger for emulator development

Seven independent, modeless tools cover **Execution, Memory, Breakpoints, Video, Hardware & I/O,
Audio, and Timeline**. Inspect coherent live snapshots while a game runs, pause and step forward or
backward by instruction or frame, capture selected hardware events, edit safe RAM while paused, and
switch between purpose-built CPU, graphics, timing, and full-workspace layouts.

The same deterministic foundations power ROM-free input recordings and a standalone headless CLI
that can run or replay sessions and emit bounded JSON, PNG, and WAV evidence—useful for compatibility
research, regression diagnosis, and automation.

## Better netplay, rewind, and saves

**Rollback netplay** has received extensive hardening across framing, handshakes, state transfer,
rollback input recovery, infrared synchronization, four-player sessions, reconnects, and clean
recovery after a remote disconnect. Held inputs now survive rollback rebases correctly, and the new
Netplay window supports both classic two-player link games and the four-player adapter.

**Rewind** now reuses unchanged 4 KiB state pages rather than retaining a complete deep copy for
every point. In the [documented 300-entry benchmark](https://github.com/trekawek/coffee-gb/blob/coffee-gb-2.0.0/docs/rewind-machine-snapshots.md#reproducible-300-entry-measurement),
modeled retained memory fell by more than **96%**. Rewind duration and memory limits are
configurable, and disabling rewind removes its capture work entirely.

The new **State Manager** combines ten quick slots, named states, thumbnails, export, autosave, and
optional resume. States and batteries use crash-recoverable writes, storage can be moved to a chosen
directory, and legacy `.sn0`–`.sn9` files remain available as read-only fallbacks.

## Native downloads—no Java setup required

Choose the package for your platform from the assets below:

- **Windows x64:** portable EXE
- **Linux x64:** DEB package
- **macOS Intel:** x64 DMG
- **macOS Apple silicon:** arm64 DMG
- **Any supported desktop:** universal executable JAR

The native packages include a minimized Java runtime. The universal JAR remains the portable,
cross-platform option and requires Java 16 or newer; Java 21 LTS is recommended.

## And more

- Exact selectable **DMG, CGB, CGB0, SGB, SGB2, and Game Boy Pocket** hardware profiles.
- More complete Super Game Boy commands, borders, palettes, timing, and independent local
  four-player input.
- A searchable bundled Game Genie and GameShark cheat database with multi-select.
- Screen rotation, LCD ghosting, CGB color correction, grayscale palettes, and improved audio
  filtering and output selection.
- Safer ROM replacement, responsive loading overlays, archive selection, recent games, screenshots,
  autosave recovery, and clear on-screen state notifications.

<details>
<summary><strong>Platform and networking notes</strong></summary>

- On macOS, controller input requires a compatible system SDL2 installation
  (`brew install sdl2`). Keyboard input remains available without it; Linux and Windows native
  packages bundle SDL2.
- Current user-facing netplay uses direct, unencrypted TCP. It does not provide matchmaking, NAT
  traversal, or protection against an on-path attacker.
- Mobile Adapter networking is offline by default and supports only explicitly configured custom
  services. Coffee GB does not bundle Nintendo service data or emulate a real telephone connection.
- ROMs are not included.

</details>

## Merged pull requests

- [#431](https://github.com/trekawek/coffee-gb/pull/431) Add deterministic offline Mobile Adapter GB support — [@trekawek](https://github.com/trekawek)
- [#432](https://github.com/trekawek/coffee-gb/pull/432) Add bounded Mobile Adapter custom-server networking — [@trekawek](https://github.com/trekawek)
- [#433](https://github.com/trekawek/coffee-gb/pull/433) Add phase-one debug port infrastructure — [@trekawek](https://github.com/trekawek)
- [#434](https://github.com/trekawek/coffee-gb/pull/434) Add breakpoints and bounded debug tracing — [@trekawek](https://github.com/trekawek)
- [#435](https://github.com/trekawek/coffee-gb/pull/435) Add typed debug events and console controls — [@trekawek](https://github.com/trekawek)
- [#436](https://github.com/trekawek/coffee-gb/pull/436) Add deterministic replay recording and playback — [@trekawek](https://github.com/trekawek)
- [#437](https://github.com/trekawek/coffee-gb/pull/437) Add bounded reverse-frame debug history — [@trekawek](https://github.com/trekawek)
- [#438](https://github.com/trekawek/coffee-gb/pull/438) Add deterministic reverse-instruction debugging — [@trekawek](https://github.com/trekawek)
- [#439](https://github.com/trekawek/coffee-gb/pull/439) Add coherent desktop debugger shell — [@trekawek](https://github.com/trekawek)
- [#440](https://github.com/trekawek/coffee-gb/pull/440) Add graphics, audio, and timeline debugger panes — [@trekawek](https://github.com/trekawek)
- [#441](https://github.com/trekawek/coffee-gb/pull/441) Add deterministic headless debugger CLI — [@trekawek](https://github.com/trekawek)
- [#442](https://github.com/trekawek/coffee-gb/pull/442) Modernize Swing debugger breakpoint workflow — [@trekawek](https://github.com/trekawek)
- [#443](https://github.com/trekawek/coffee-gb/pull/443) Add realtime multi-window debugger workspace — [@trekawek](https://github.com/trekawek)
- [#444](https://github.com/trekawek/coffee-gb/pull/444) Move debugger windows into Debug menu — [@trekawek](https://github.com/trekawek)
- [#445](https://github.com/trekawek/coffee-gb/pull/445) Modernize desktop emulator interface — [@trekawek](https://github.com/trekawek)
- [#446](https://github.com/trekawek/coffee-gb/pull/446) Modernize desktop UI and debugger workflows — [@trekawek](https://github.com/trekawek)
- [#447](https://github.com/trekawek/coffee-gb/pull/447) Support Crystal Mobile Adapter wire flow — [@trekawek](https://github.com/trekawek)
- [#449](https://github.com/trekawek/coffee-gb/pull/449) Import Mobile Adapter configuration images — [@trekawek](https://github.com/trekawek)
- [#450](https://github.com/trekawek/coffee-gb/pull/450) Unify desktop debugger playback controls — [@trekawek](https://github.com/trekawek)
- [#451](https://github.com/trekawek/coffee-gb/pull/451) Correct Mobile Trainer first-run validation — [@trekawek](https://github.com/trekawek)
- [#452](https://github.com/trekawek/coffee-gb/pull/452) Expose Mobile Adapter desktop controls — [@trekawek](https://github.com/trekawek)
- [#453](https://github.com/trekawek/coffee-gb/pull/453) Persist guest Mobile Adapter configuration — [@trekawek](https://github.com/trekawek)
- [#454](https://github.com/trekawek/coffee-gb/pull/454) Fix Mobile Adapter TCP transfer timing — [@trekawek](https://github.com/trekawek)
- [#455](https://github.com/trekawek/coffee-gb/pull/455) Poll Mobile Adapter completions at byte boundaries — [@trekawek](https://github.com/trekawek)
- [#456](https://github.com/trekawek/coffee-gb/pull/456) Support bounded Mobile Adapter DNS aliases — [@trekawek](https://github.com/trekawek)
- [#457](https://github.com/trekawek/coffee-gb/pull/457) Prevent Gambatte cold-boot test timeouts — [@trekawek](https://github.com/trekawek)
- [#458](https://github.com/trekawek/coffee-gb/pull/458) Document Mobile Trainer REON acceptance — [@trekawek](https://github.com/trekawek)
- [#459](https://github.com/trekawek/coffee-gb/pull/459) Add persistent Game Boy play skill — [@trekawek](https://github.com/trekawek)
- [#460](https://github.com/trekawek/coffee-gb/pull/460) Shard gbc-hw-tests in CI — [@trekawek](https://github.com/trekawek)
- [#461](https://github.com/trekawek/coffee-gb/pull/461) Rework Mobile Adapter dialog into categories — [@trekawek](https://github.com/trekawek)
- [#462](https://github.com/trekawek/coffee-gb/pull/462) Use new Coffee GB application icon — [@trekawek](https://github.com/trekawek)
- [#463](https://github.com/trekawek/coffee-gb/pull/463) Fix chopped audio under sustained CGB load — [@trekawek](https://github.com/trekawek)
- [#464](https://github.com/trekawek/coffee-gb/pull/464) Fix About dialog layout overflow — [@trekawek](https://github.com/trekawek)

## Fixed issues

- [#310](https://github.com/trekawek/coffee-gb/issues/310) Harry Potter and the Sorcerer's Stone music is choppy — reported by [@ScottNash042](https://github.com/ScottNash042)
- [#315](https://github.com/trekawek/coffee-gb/issues/315) Add a time-travel debugger and deterministic replay toolkit — [@trekawek](https://github.com/trekawek)
- [#331](https://github.com/trekawek/coffee-gb/issues/331) Build the accessible desktop debugger UI — [@trekawek](https://github.com/trekawek)
- [#332](https://github.com/trekawek/coffee-gb/issues/332) Add headless debugger tools and diagnostic bundles — [@trekawek](https://github.com/trekawek)
- [#351](https://github.com/trekawek/coffee-gb/issues/351) Add exclusive peripheral selection and the offline Mobile Adapter engine — [@trekawek](https://github.com/trekawek)
- [#352](https://github.com/trekawek/coffee-gb/issues/352) Add bounded Mobile Adapter custom-server networking — [@trekawek](https://github.com/trekawek)
- [#353](https://github.com/trekawek/coffee-gb/issues/353) Validate Mobile Adapter compatibility and scope follow-ups — [@trekawek](https://github.com/trekawek)
- [#399](https://github.com/trekawek/coffee-gb/issues/399) Support the Mobile Adapter GB — [@trekawek](https://github.com/trekawek)
- [#448](https://github.com/trekawek/coffee-gb/issues/448) Mobile Trainer first-run setup stalls before Login ID — [@trekawek](https://github.com/trekawek)

## Thank you

Thank you to everyone who tested Coffee GB, reported hard-to-find compatibility problems, supplied
hardware evidence, proposed features, or contributed to the Game Boy research and test-ROM
ecosystem. Special thanks to [@ScottNash042](https://github.com/ScottNash042) for the extraordinary
depth of compatibility testing and issue reports throughout the 1.7 series.

Coffee GB 2.0 also benefited from AI-assisted development: **Anthropic Fable** and **OpenAI 5.6
Sol** served as force multipliers in tracking down hundreds of obscure compatibility bugs and
implementing the desktop UI redesign, with every change reviewed against the project's test suites.

*Super Mario Land* and its game imagery are © Nintendo. The screenshot is used for editorial
purposes to demonstrate Coffee GB's interface and compatibility; no affiliation or endorsement is
implied.

**Full changelog:** [Coffee GB 1.7.0 → 2.0](https://github.com/trekawek/coffee-gb/compare/coffee-gb-1.7.0...coffee-gb-2.0.0)
