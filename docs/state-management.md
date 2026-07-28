# Managed states, autosave, screenshots, and rewind

Coffee GB's desktop state browser complements the original quick-slot shortcuts with named states,
previews, autosave/resume, export, and recovery reporting. All disk access is performed away from
the Swing event-dispatch and emulation threads. Capturing and applying a machine state still happens
at a frame boundary on the emulation thread.

## Using the state browser

Open **Emulation > Manage States…** while a local game is running. The browser always shows ten
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
are coalesced, queue saturation is reported as a normal operation failure, and ROM-switch/close
autosaves run ahead of queued manual work. Shutdown first drains the queue and then interrupts and
fully awaits the worker if the drain deadline expires.

The original <kbd>F5</kbd>/<kbd>F7</kbd> quick save/load shortcuts and their `.sn0`-`.sn9` files
remain supported for compatibility. They are separate from the managed browser's stable slots.
Managed states use the portable `StateFile` format described below.

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
properties on a background worker before Apply changes either runtime or persisted settings.

Each game root contains:

```text
battery.sav
states/
  slots/0..9/
  named/<UUID>/
  autosave/
screenshots/
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
when the archive scan proved that migration unambiguous. The first valid battery is imported
atomically into the active destination without deleting its source, so an older portable Coffee GB
remains usable. Unsafe symlink/non-file fallback entries are rejected. An unsafe parent component
also prevents Preferences Apply and blocks a managed load or write rather than substituting a
sibling path. New states, autosaves, screenshots, battery writes, and recovery writes always go to
the active destination. Deleting or exporting a fallback state acts only on the source identified
by that browser row.

Use **Emulation > Open Save Folder** to open the active game root. If the desktop cannot open it,
Coffee GB displays the full selectable path instead.

## Autosave and resume

The Saves preferences tab controls whether Coffee GB writes a distinct autosave before a ROM switch
and before the window closes. A ROM switch is cancelled when the autosave cannot be captured or
committed, leaving the current game active. On close, the error dialog offers retry, close without
autosaving, or cancel. No failed load replaces the active session: decoding and validation complete
first, and applying the state has rollback protection at the frame boundary.

On the next launch of the same ROM, resume policy can be:

- **Never** — ignore the autosave.
- **Ask** — offer to resume or start normally.
- **Always** — load a valid autosave automatically.

Only the active directory receives a new autosave. An existing autosave may be discovered in a
fallback root, which makes a directory change reversible.

## Screenshots and privacy

Press <kbd>F12</kbd> or choose **Emulation > Take Screenshot**. Screenshots use the complete
currently displayed frame, including an SGB border, and are written to the active game's
`screenshots/` directory as:

```text
coffee-gb-YYYYMMDD-HHmmss-SSS.png
coffee-gb-YYYYMMDD-HHmmss-SSS-1.png
```

The suffix increases on a same-millisecond or existing-name collision; existing files are never
overwritten. PNG dimensions and metadata are bounded before decoding. Embedded metadata is limited
to UTC capture time, hardware-profile ID, and `Coffee GB` as the software name. It deliberately
contains no ROM title, filename, path, or hash.

## Rewind bounds

The Saves tab can disable rewind completely, select a duration from 5 to 120 seconds, and set an
8-512 MiB retained-memory budget. Disabled rewind performs no capture or cadence work. When enabled,
history is bounded by both duration and memory; the oldest snapshots are evicted first. The default
is 30 seconds and 64 MiB.

## Settings compatibility note

This feature adds the optional `saves.rewindMemoryMiB` property to settings schema 5. Missing values
decode as 64 MiB, so schema-5 files written before this feature remain valid. Schema 5 was introduced
by the immediately preceding recent-ROM/settings phase; this phase deliberately extends that same
version instead of creating a migration that depends on parallel branch order. If another branch
consumes schema 5 before these changes are rebased, move this key's fixed-key ownership and migration
tests to the next schema number as one isolated block.

For the binary format and persistence guarantees, see [StateFile v2](state-file-v2.md) and
[atomic persistence](atomic-persistence.md).
