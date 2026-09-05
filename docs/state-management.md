# Managed states, autosave, screenshots, input recording, and rewind

Coffee GB's desktop state browser extends the same ten slots used by the quick-save shortcuts with
named states, previews, autosave/resume, export, and recovery reporting. All disk access is performed
away from the Swing event-dispatch and emulation threads. Capturing and applying a machine state
still happens at a frame boundary on the emulation thread.

## Using the state browser

Open **Game > Manage States…** while a local game is running. The browser always shows ten
stable numbered slots, including empty ones, followed by named states and the autosave when present.
Each row reports its source, status, and reason when it cannot be loaded. A bounded thumbnail is
shown when one was committed with the state.

The browser is modeless and keyboard-accessible:

- <kbd>Enter</kbd> loads the selected state.
- <kbd>Delete</kbd> deletes the selected state after confirmation.
- <kbd>Ctrl</kbd>+<kbd>N</kbd> creates a named state.
- <kbd>F5</kbd> refreshes the catalog while the browser has focus.
- <kbd>Escape</kbd> closes the browser.

Saving, loading, deleting, exporting, and refreshing show completion or failure in both the browser
and the emulator display. Errors include a selectable, copyable diagnostic with host paths redacted
and hostile text bounded before display. A corrupt or
incompatible state remains visible with loading disabled, so it can still be inspected, exported,
or deleted from the exact source in which it was found.

State disk work uses one bounded worker queue. Repeated refresh, resume-scan, and screenshot requests
are coalesced, queue saturation is reported as a normal operation failure, and ROM-switch autosaves
run ahead of queued manual work. Shutdown stops emulation, drains every admitted manual operation,
closes the worker, and only then writes the exact terminal autosave directly. A queued save or
delete therefore cannot run after the terminal autosave. The whole sequence shares the desktop
close deadline.

The Game menu's selected slot, <kbd>F5</kbd>/<kbd>F7</kbd> quick save/load commands, and the ten rows in
Manage States are one set of managed slots. Quick load resolves the exact active or fallback source
shown by the browser. If every managed source for the selected slot is empty, quick load checks the
historical `.sn0`-`.sn9` sidecar as a read-only compatibility fallback. A present corrupt or
incompatible managed state remains authoritative and is reported instead of silently loading an
older sidecar. Legacy sidecars are never deleted, rewritten, or overwritten by this path; new
desktop quick saves use the managed portable `StateFile` layout described below.

## Storage layout and directory changes

The default game root is:

```text
<ROM directory>/.coffee-gb/games/<ROM SHA-256>/
```

When a directory is selected in **File > Preferences… > Saves**, the root becomes:

```text
<configured directory>/games/<ROM SHA-256>/
```

ROM titles and filenames are never used as path components. The exact ROM hash prevents two files
with the same display name from sharing save data. The configured directory must already exist,
must be writable, and must not contain symbolic-link path components. Coffee GB checks those
properties on a background worker before Apply changes either runtime or persisted settings. A
filesystem root itself is not accepted as the Saves directory; choose a named directory below it.
ROMs stored directly below a filesystem root remain supported, including their portable `.sav`
sidecars.

Each game root contains:

```text
battery.sav
states/
  slots/0..9/
  named/<UUID>/
  autosave/
screenshots/
replays/
```

`battery.sav` is used when a Saves directory is configured, including a distinct hash namespace for
an Action Replay/Datel pass-through slot ROM. With a blank Saves directory, battery saves retain the
portable-JAR-compatible `<ROM name>.sav` sidecar destination; states and screenshots continue to use
the hidden `.coffee-gb` game root shown above.

Every state directory has an authoritative `state.cgbstate`. Optional `metadata.properties` and a
hash-bound `thumbnail-<state SHA-256>.png` are sidecars; missing or damaged sidecars never make an
otherwise valid state unloadable. Saves use temporary files, durable flushes, and collision-safe
renames. Recovery scans clean abandoned temporary/backup files and report what was recovered.
Exports and screenshots use target-associated temporary files; a bounded recovery scan removes
abandoned files belonging to that exact target and reports the cleanup. Exports never replace an
existing destination.

Existing filesystem components are checked without following the final component before repository
access. A symlinked configured/game root or descendant is rejected rather than allowing state writes
to escape the selected path.

Changing the configured directory does not strand existing data. The most recent previous
configured roots are persisted in settings and remain read-only fallbacks after restart. Settings
retain at most four previous roots, and a battery storage plan accepts at most eight ordered import
candidates. Managed state browsing also checks the default root. Battery loading checks those
previous roots, the default root, the original `.sav` sidecar, and an old archive-wide sidecar only
when the archive scan proved that migration unambiguous. On first load, Coffee GB compares the
recoverable active target and every safe fallback candidate and atomically imports the most recently
modified valid battery into the active destination. This prevents switching A → B → A (or clearing
the configured directory) from restoring stale progress. The source is not deleted, so an older
portable Coffee GB remains usable. Unsafe symlink/non-file fallback entries are rejected. An unsafe parent component
also prevents Preferences Apply and blocks a managed load or write rather than substituting a
sibling path. New states, autosaves, screenshots, battery writes, and recovery writes always go to
the active destination. Deleting or exporting a fallback state acts only on the source identified
by that browser row.

Use **Game > Open Save Folder** to open the active game root. If the desktop cannot open it,
Coffee GB displays the full selectable path instead.

## Autosave and resume

Coffee GB always writes a distinct autosave, with a thumbnail of the displayed game frame, before a
ROM switch, **Close Game**, and application shutdown. A ROM switch or game unload is cancelled when
the autosave cannot be captured or committed, leaving the current game active. On application
close, a completed permanent autosave failure or an unavailable workspace offers retry, close
without a new autosave, or cancel. A timed-out writer remains retained and cannot be waived because
it may still commit; retry or cancel remains available. No failed load replaces the active session:
decoding and validation complete first, and applying the state has rollback protection at the frame
boundary. The Autosave preference has been retired; its legacy settings value is normalized to this
mandatory behavior.

On the next launch of the same ROM, resume policy can be:

- **Never** — ignore the autosave.
- **Ask** — offer to resume or start normally.
- **Always** — load a valid autosave automatically.

Choosing **Reset** is always a fresh boot of the current ROM; it neither loads nor offers its
autosave.

Only the active directory receives a new autosave. An existing autosave may be discovered in a
fallback root, which makes a directory change reversible.

Managed states, autosave/resume, pause, and rewind are standalone-session features and become
unavailable when linked/netplay ownership begins. A linked session keeps the battery storage plan
captured at handoff; live Saves-preference changes are intentionally not applied to that managed
session. The new settings take effect after returning to single-player emulation or opening the
next local ROM.

## Screenshots and privacy

Press <kbd>F12</kbd> or choose **Screen > Take Screenshot**. Screenshots use the complete currently
displayed frame, including an SGB border, and are written to the active game's `screenshots/`
directory as:

```text
coffee-gb-YYYYMMDD-HHmmss-SSS.png
coffee-gb-YYYYMMDD-HHmmss-SSS-1.png
```

The suffix increases on a same-millisecond or existing-name collision; existing files are never
overwritten. After saving, Coffee GB shows the selectable absolute path and an **Open** button that
uses the operating system's default opener without blocking the Swing event thread. PNG dimensions
and metadata are bounded before decoding. Embedded metadata is limited
to UTC capture time, hardware-profile ID, and `Coffee GB` as the software name. It deliberately
contains no ROM title, filename, path, or hash.

## Input recording and rewind

Choose **Game > Input Recording > Start Input Recording** (or press <kbd>Ctrl/Cmd</kbd>+<kbd>Shift</kbd>+<kbd>R</kbd>)
to create a deterministic `.cgbreplay` controller-input recording. The dialog deliberately starts
with no selected mode:

- **From current moment** embeds the current portable machine state so recording can begin where
  play is now. It requires an explicit acknowledgement because that state can contain emulator
  memory and cartridge RAM/save data. It never contains ROM bytes, ROM paths, or host paths.
- **Restart from clean boot** saves the ordinary session first, then starts a battery-isolated clean
  boot with an empty link port. It records from the first emulated tick and includes no machine or
  cartridge save state. That clean session continues to avoid reading or writing the ordinary
  battery/autosave files until the game is loaded normally again. Active cheats must be disabled;
  use **From current moment** when their state needs to be part of the recording.

**Game > Input Recording > Stop Input Recording** is enabled while capture is arming or active. The recorder writes the finished artifact
away from both the emulation and Swing threads to `replays/` as
`coffee-gb-YYYYMMDD-HHmmss-SSS[-N].cgbreplay`; collisions never overwrite an existing file.

To play one, open the matching ROM and choose **Game > Input Recording > Load Input Recording**. Coffee GB reads
and validates the selected bounded `.cgbreplay` off the Swing and emulation threads, autosaves the
currently open session, then presents the recording in an isolated, read-only session. Live inputs,
battery saves, peripherals, managed states, and rewind are disabled during playback. Playback pauses
at the final matching checkpoint; close or reopen the game to return to normal play. CLI playback is
also documented in [headless-cli.md](headless-cli.md).

Replay v1 represents one forward input timeline. Rewind is therefore disabled while a recording is
arming or active; it becomes available again after stopping. Pausing suspends capture without adding
an emulated tick, and **Stop Input Recording** remains available while paused. Resuming continues the
same input timeline; cartridge RTC wall time is frozen across that pause. A clean-boot recording or
loaded playback always starts its replacement session running, even when the previous session was
paused. State loads, reset, ROM changes, peripheral changes, debugger pauses, and closing finish the
recording before their lifecycle boundary. Linked play, real serial/infrared devices,
host-time/sensor cartridges, reverse debugging, and starting a current-session recording while
paused are rejected with an actionable reason.

## Rewind bounds

The Saves tab can disable rewind completely, select a duration from 5 to 120 seconds, and set an
8-512 MiB retained-memory budget. Disabled rewind performs no capture or cadence work. When enabled,
history is bounded by both duration and memory; the oldest snapshots are evicted first. The default
is 30 seconds and 64 MiB.

## Settings compatibility note

This feature adds the optional `saves.rewindMemoryMiB` property to settings schema 5. Missing values
decode as 64 MiB, so schema-5 files written before this feature remain valid. Later desktop-window
geometry advances the current document to schema 6; schema-5 save fields retain their original
ownership and continue to decode before the schema-6 desktop section is applied.
Camera-device selection later advances the current document to schema 7; schemas 0-6 preserve the
future camera key without interpreting it during migration. Desktop appearance and command-bar
visibility advance the current document to schema 8; schemas 0-7 default those values without
reinterpreting preserved unknown keys.

For the binary format and persistence guarantees, see [StateFile v2](state-file-v2.md) and
[atomic persistence](atomic-persistence.md).
