# Desktop command line and settings contract

This document defines the first Desktop 2.0 compatibility boundary. The portable JAR and the
Swing application use the same command-line parser and the same typed settings model. Later
preferences, ROM-opening, and packaging work must extend this contract rather than introduce a
second parser or settings file.

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
`audio`, `input`, `saves`, and `advanced` sections. The controller-facing `EmulatorProperties`
class is a compatibility facade over that model while older menu code is migrated.

Schema 1 continues to use `${user.home}/.coffeegb.properties` and the established keys so the
portable JAR remains compatible during the migration window.

| Key | Typed value | Built-in default |
| --- | --- | --- |
| `settings.schemaVersion` | exact supported schema version | `1` |
| `system.dmgGames` | explicit stable profile or absent/Auto | Auto (`sgb`) |
| `system.cgbGames` | explicit stable profile or absent/Auto | Auto (`cgb`) |
| `system.bootstrapMode` | `SKIP`, `FAST_FORWARD`, or `NORMAL` | `SKIP` |
| `display.scale` | supported integer scale | `2` |
| `display.rotation` | `0`, `90`, `180`, or `270` degrees | `0` |
| `display.grayscale` | boolean | `false` |
| `display.blending` | boolean | `true` |
| `display.colorCorrection` | boolean | `true` |
| `display.showSgbBorder` | boolean | `false` |
| `sound.enabled` | boolean | `true` |
| `saves.batteryEnabled` | boolean | `true` |
| `rom.directory` | optional local path | absent |
| `rom.recent.<index>` | ordered local path | empty, capacity 10 |
| `datel.slot.rom` | optional local path | absent |
| `fullchanger.character` | optional stable menu value | absent |
| `btn_*`, `input.pN.btn_*` | validated keyboard binding | documented P1 defaults |
| `input.pN.gamepad` | disabled, automatic, or stable SDL device ID | P1 automatic |

Recognized values are validated before becoming live settings. Boolean values are exactly `true`
or `false` (case-insensitive); numeric settings have explicit ranges; hardware profiles,
bootstrap modes, input players, buttons, keys, and gamepad selectors must be known. Invalid input
does not partially update the active settings.

Unrecognized legacy keys are retained verbatim when schema 1 is saved. Keys in a reserved grammar,
such as an unknown `input.*` key, remain errors so a misspelled control binding is not silently
ignored. Schema 1 writes every active keyboard binding and the explicit P1 gamepad selection;
an absent schema-1 binding is therefore unbound rather than silently restored to a default.

## Migration and recovery

A file without `settings.schemaVersion` is legacy schema 0. Migration is a pure conversion into
schema 1, preserves unknown keys, retains absent profile mappings as Auto, and canonicalizes the
finite historical uppercase profile aliases. Repeating load/migrate/save produces the same
normalized settings. Legacy text is decoded with the platform-default charset used by the former
`FileReader`; versioned files use strict UTF-8 with deterministic ASCII escapes.

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

Normal window shutdown waits up to a bounded timeout for the latest pending settings and reports an
actionable failure if the writer cannot be stopped safely.
CLI parsing and settings file I/O occur before or outside Swing component mutation; Swing component
creation and mutation remain owned by the Event Dispatch Thread.
