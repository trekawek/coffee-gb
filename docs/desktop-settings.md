# Desktop command line and settings contract

This document defines the Desktop 2.0 compatibility boundary. The portable JAR and the Swing
application use the same command-line parser and the same typed settings model. ROM-opening and
packaging work must extend this contract rather than introduce a second parser or settings file.

## Command line

Coffee GB accepts at most one ROM path. `--` ends option parsing, so a path beginning with `-` can
still be opened.

| Option | Meaning |
| --- | --- |
| `-d`, `--force-dmg` | Use the exact `dmg` hardware profile. |
| `-c`, `--force-cgb` | Use the exact `cgb` hardware profile. |
| `--profile=<id>` | Use an exact stable profile ID reported by `--help`. |
| `-b`, `--use-bootstrap` | Run the selected profile's bundled bootstrap normally. |
| `-db`, `--disable-battery-saves` | Disable battery-file reads and writes for this process. |
| `--debug` | Open the developer console. |
| `-h`, `--help` | Print usage and exit successfully. |
| `--version` | Print the packaged application version and exit successfully. |
| `--package-smoke` | Run the self-contained headless package self-test and exit successfully. |

Unknown, repeated, malformed, or conflicting options are usage errors. They write an actionable
diagnostic to standard error and exit with status 2 without constructing Swing components.
`--profile` conflicts with either legacy force option; the two force options conflict with each
other. Profiles without a bundled model-specific boot ROM cannot be combined with
`--use-bootstrap`.

## Precedence

Every effective option is selected independently in this order:

1. an explicit command-line override;
2. a per-game override, once that feature exists;
3. the persisted user setting;
4. the built-in default.

Command-line overrides are process-local and are never written into the settings file. In
particular, `--use-bootstrap` and `--disable-battery-saves` do not change the next launch.

## Typed settings model

The application owns one immutable `ApplicationSettings` value with typed `general`, `display`,
`audio`, `input`, `saves`, `advanced`, and desktop-shell sections. The controller-facing
`EmulatorProperties` class is a compatibility facade over that model while older menu code is
migrated.

Schema 6 continues to use `${user.home}/.coffeegb.properties`; schemas 0–5 are migrated in place
so the portable JAR remains compatible during the migration window.

| Key | Typed value | Built-in default |
| --- | --- | --- |
| `settings.schemaVersion` | exact supported schema version | `6` |
| `system.dmgGames` | explicit stable profile or absent/Auto | Auto (`sgb`) |
| `system.cgbGames` | explicit stable profile or absent/Auto | Auto (`cgb`) |
| `system.bootstrapMode` | `SKIP`, `FAST_FORWARD`, or `NORMAL` | `SKIP` |
| `display.scalingMode` | `INTEGER_FIT`, `ASPECT_FIT`, or `EXPLICIT` | `EXPLICIT` |
| `display.scale` | explicit integer scale in `1..4` | `2` |
| `display.letterboxColor` | six uppercase RGB hexadecimal digits | `000000` |
| `display.fullscreen` | boolean | `false` |
| `display.rotation` | `0`, `90`, `180`, or `270` degrees | `0` |
| `display.grayscale` | boolean | `false` |
| `display.blending` | boolean | `true` |
| `display.colorCorrection` | boolean | `true` |
| `display.showSgbBorder` | boolean | `false` |
| `desktop.windowWidth`, `desktop.windowHeight` | optional positive outer-frame dimensions; both or neither | absent |
| `sound.enabled` | boolean | `true` |
| `audio.outputDevice` | `default` or stable Java Sound device ID | `default` |
| `audio.masterVolume` | integer percentage in `0..100` | `100` |
| `audio.latencyPreset` | `LOW`, `BALANCED`, or `SAFE` | `BALANCED` |
| `saves.batteryEnabled` | boolean | `true` |
| `rom.directory` | optional local path | absent |
| `general.recentFileCapacity` | integer in `0..50` | `10` |
| `general.romChangeConfirmationPolicy` | `ALWAYS`, `WHEN_RUNNING`, or `NEVER` | `WHEN_RUNNING` |
| `general.recent.<index>` | ordered local path | empty |
| `rom.recent.<index>` | legacy ten-entry history imported during migration | empty |
| `settings.preservedUnknownCollisions.<chunk>` | bounded internal migration metadata | absent |
| `datel.slot.rom` | optional local path | absent |
| `fullchanger.character` | optional stable menu value | absent |
| `btn_*`, `input.pN.btn_*` | validated keyboard binding | documented P1 defaults |
| `input.pN.gamepad` | disabled, automatic, or stable SDL device ID | P1 automatic |
| `input.gamepad.<stable-id>.movementDeadZone` | raw SDL threshold in `0..32766` | `16384` |
| `input.gamepad.<stable-id>.tiltDeadZone` | raw SDL threshold in `0..32766` | `4096` |
| `input.gamepad.<stable-id>.invertMovementX/Y` | boolean | `false` |
| `input.gamepad.<stable-id>.invertTiltX/Y` | boolean | `false` |

Recognized values are validated before becoming live settings. Boolean values are exactly `true`
or `false` (case-insensitive); numeric settings have explicit ranges; hardware profiles,
bootstrap modes, input players, buttons, keys, and gamepad selectors must be known. Invalid input
does not partially update the active settings.

`WHEN_RUNNING` asks before replacing or closing an active emulation session, while an idle
application may close without a redundant prompt. `ALWAYS` also confirms an idle application
close, and `NEVER` suppresses this ordinary confirmation. A battery/autosave flush failure remains
actionable regardless of this preference. Setting recent-file capacity to zero disables history;
reducing it removes the oldest excess entries. Coffee GB has no update-check mechanism, so schema 6
does not invent or persist an update-check preference.

Unrecognized legacy keys are retained losslessly when the current schema is saved. Keys in a reserved grammar,
such as an unknown `input.*` key, remain errors so a misspelled control binding is not silently
ignored. The current schema stores active history under `general.recent.*`, so a numeric `rom.recent.*` legacy
key beyond Coffee GB's old ten-entry history remains untouched even if capacity later grows. Schema
6 writes every active keyboard binding and the explicit P1 gamepad selection; an absent versioned
binding is therefore unbound rather than silently restored to a default. If an older unknown key
occupies a name reserved by a later schema, Coffee GB keeps its original key/value in bounded
internal migration metadata rather than overwriting or reinterpreting it. At most 32 per-device
gamepad tuning profiles are retained.

## Preferences

Open **File → Preferences…** (or <kbd>Ctrl</kbd>/<kbd>⌘</kbd>+<kbd>,</kbd>) to change the
default ROM directory, recent-file capacity, ROM-change confirmation policy, system profile,
display behavior, and keyboard controls without editing the properties file. The System tab owns
the DMG/CGB hardware-profile and bootstrap choices formerly exposed as a top-level menu. The Display
tab exposes 1×/2×/4× window sizing, letterboxing, fullscreen, rotation, DMG grayscale, frame
blending, CGB color correction, and the Super Game Boy border. The Input tab arranges all eight
bindings for each of Players 1–4 like a Game Boy pad. Choose **Capture** and press a key, including
Enter or Escape. Tab remains reserved for focus navigation and Backspace remains reserved for
Rewind. Assigning an occupied key swaps the two bindings (or moves it when the destination was
unassigned); one **Reset keyboard defaults** action restores the complete keyboard layout. The
Gamepads tab lists up to four logical assignments without calling SDL on the EDT. It keeps an
unavailable configured controller visible, prevents duplicate explicit or automatic assignments,
and exposes independent movement/tilt dead zones and X/Y inversion for each stable device. A
mapping change releases all held input and waits for a neutral physical sample before accepting new
presses.

The Audio tab enumerates Java Sound outputs on a cancellable background worker. It supports system
default or an explicit descriptor-hashed output, a 0–100 master-volume slider, mute, and
LOW/BALANCED/SAFE bounded-latency presets. If an explicit output disappears, playback retains the configured ID,
falls back to System Default, records a diagnostic, and stays silent without blocking emulation if
no output can be opened. While fallback remains active, Coffee GB periodically returns to the
configured output after it reappears. Device, volume, mute, and latency changes do not reset emulated
audio clock or DC-filter phase.

**Apply** validates the complete draft, writes one settings update, and switches the live keyboard
mapping and display only after persistence accepts it. **Cancel**, the window close button, and
Escape discard unapplied edits. **Restore Defaults** only changes the visible draft until Apply is
chosen.

## Display scaling and fullscreen

The game image and the Swing component have independent sizes. Coffee GB copies each completed
emulated frame into an immutable private raster before publishing it to the EDT, so repainting
cannot retain or mutate an emulator-owned pixel buffer. DMG/CGB frames use their native 160×144
geometry; an enabled SGB border uses its native 256×224 geometry. Rotation is included before
viewport fitting, and both axes always use the same scale.

The renderer always uses the largest uniform scale that fits the available component, so resizing
never crops the image or distorts its aspect ratio. The 1×, 2×, and 4× commands are convenient
window-size commands: choosing one repacks the window to that native-size multiple while windowed.
Afterward the window remains freely resizable and the renderer continues to fit its complete frame.
Persisted 3×, integer-fit, and aspect-fit settings remain readable for compatibility, use the same
always-fit renderer immediately, and normalize to the nearest exposed window size when edited.

Unused space is letterboxed or pillarboxed with the configured RGB color. The viewport uses
half-open geometry and conservative paint bounds, so odd device-pixel margins put the extra pixel
on the right or bottom without stretching or cropping the final source pixel.

The main window is resizable and has a base 160×144 minimum content area. **Screen → Fullscreen**
enters borderless fullscreen; <kbd>F11</kbd> is its accelerator unless F11 is assigned to an emulated
button, in which case the accelerator is automatically disabled and the menu command remains
available. <kbd>Escape</kbd> always leaves fullscreen when the main game window owns focus, while
remaining available to windowed emulation and dialogs. Menu radio items, Preferences, persisted
settings, and the live renderer are synchronized through one EDT-owned coordinator.

Coffee GB stores the last normal, windowed outer-frame width and height. On the next launch that
size is clamped to the current frame minimum and primary usable work area, then centered as before.
Fullscreen, maximized, and iconified dimensions never replace the saved normal size; window
position and maximized state are intentionally not persisted. Live-resize updates are coalesced,
while a final normal size is captured before a successful settings close. If settings are read-only,
window geometry remains session-only and the existing file is untouched.

Entering fullscreen remembers window placement relative to the monitor's stable AWT device ID and
current scale transform. Exiting converts through the latest per-monitor transform and clamps the
window to the current usable work area. If that monitor disappears, Coffee GB selects the nearest
remaining monitor deterministically. The window minimum is lowered before entry so it cannot
constrain the monitor bounds, then restored after exit; restored bounds are preserved unless they
are too small for a display-size change made while fullscreen. While
fullscreen, move/resize notifications plus a fullscreen-only one-second monitor refresh recover
from monitor removal, work-area changes, and per-monitor DPI changes without retaining stale
`GraphicsDevice` instances.

Swing does not expose a reliable cross-platform swap-interval or vertical-sync capability.
Coffee GB therefore keeps its existing event-driven repaint pacing and nearest-neighbor rendering;
it does not present a nonfunctional vsync option. A future backend may expose optional pacing only
after it can report and test that capability, with this repaint path remaining the fallback.

## Migration and recovery

A file without `settings.schemaVersion` is legacy schema 0. Schema 0 through schema 5 migration is a
pure conversion into schema 6, preserves unknown keys, retains absent profile mappings as Auto, and
canonicalizes the finite historical uppercase profile aliases. Existing display scales migrate to
explicit scale with a black letterbox and windowed startup; no previous launch is unexpectedly
forced fullscreen. Older schemas have no saved desktop size and retain the existing packed,
centered startup. Repeating load/migrate/save produces the same normalized settings. Legacy text
is decoded with the platform-default charset used by the former `FileReader`; versioned files use
strict UTF-8 with deterministic ASCII escapes.

The complete file is bounded, parsed, migrated, and validated before it can replace the in-memory
defaults. If syntax or a recognized value is corrupt, Coffee GB moves the original bytes to a
unique `.coffeegb.properties.corrupt-*` sibling, starts with safe defaults, and displays one
actionable warning. If the original cannot be preserved, persistence is disabled while validated
changes can still live in the current session.
Generic read or recovery I/O failures are not classified as corruption: the target remains in
place, persistence is disabled, and one read-failure warning is displayed.

A schema version newer than this build supports is not corruption. Coffee GB leaves that file
untouched, starts with safe defaults, and explains that a newer Coffee GB version is required.
Validated menu changes remain session-local, but persistence stays disabled so the future document
is never overwritten.

## Persistence and lifecycle

Runtime updates replace the immutable in-memory settings immediately. Disk writes are coalesced on
a background worker so rapid menu changes do not block the Swing Event Dispatch Thread or rewrite
the file for every click. The serialized properties are deterministic and sorted.

Each save uses the repository's crash-safe same-directory writer: write a unique temporary file,
flush it, force it to stable storage, then atomically replace the destination where the file system
supports that operation. The existing recovery fallback preserves the last complete file.

Normal window shutdown leaves the EDT immediately. Controller, gamepad, audio, and final settings
teardown run on a dedicated worker; audio and settings waits are bounded, and a final watchdog
prevents a stalled host provider from leaving a hung desktop process. A settings failure preserves
the previous complete file and reports an actionable error.
CLI parsing and settings file I/O occur before or outside Swing component mutation; Swing component
creation and mutation remain owned by the Event Dispatch Thread.
