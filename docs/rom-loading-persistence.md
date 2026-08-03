# ROM identity, bounds, and persistence lifecycle

ROM ownership is the exact selected image, not merely the file that contained it. Code that opens,
transfers, snapshots, or resumes a game must retain its `RomImage` and `RomOrigin`.

## Identity and sidecars

- A direct file is identified by its normalized absolute path.
- An archive ROM is identified by the normalized container path, the entry's exact raw name, and
  its zero-based duplicate-name occurrence. Do not normalize entry spelling before constructing the
  identity. The public stable identity length-prefixes these fields, so delimiter text inside a
  path or entry cannot make two structured identities collide.
- An in-memory ROM uses an explicit stable identity (normally its SHA-256 digest) and has no
  implicit permission to create a sidecar file. Basic sessions therefore advertise no snapshot
  support for a memory origin, and `snapshotAvailable` returns false instead of inventing a path.

Direct-file sidecars retain the existing `<rom-base>.sav` and `<rom-base>.snN` names. Archive
sidecars use a bounded, filesystem-safe form:

```text
<container-base>--<entry-base>-<128-bit identity hash>.sav
<container-base>--<entry-base>-<128-bit identity hash>.snN
```

The hash covers the exact container filename, raw entry name, and occurrence. It prevents entries
with the same basename or normalized-looking spelling from sharing state, while keeping the
sidecar filename stable if the archive and its sidecars move together.

Older Coffee GB versions stored archive state beside the container as `<container-base>.sav` and
`<container-base>.snN`. That location is eligible for import/fallback only when a complete bounded
archive inventory proves there is exactly one ROM candidate. Ambiguous archives must never guess
which entry owns a legacy sidecar. Battery import copies to the entry-specific path and leaves the
legacy file recoverable; new snapshot saves always target the entry-specific path.

Archive entry names containing NUL, absolute or drive-qualified paths, or `.`/`..` traversal
components are rejected before a persistence path is derived.

## Input bounds

The byte and record-count values are the intended trust-boundary limits. The Commons Compress row
is a defense-in-depth ceiling applied to that library's decoder/memory estimate, not a measurement
of total JVM heap use:

| Input | Limit |
| --- | ---: |
| ROM image | 64 MiB |
| Header inspection | exactly `0x150` bytes |
| Compressed ZIP/7z container | 128 MiB |
| Archive records | 4,096 |
| Declared aggregate uncompressed archive data | 256 MiB |
| Commons Compress 7z decoder/memory estimate | 64 MiB |
| Isolated 7z helper JVM heap | 192 MiB |
| Linked battery payload | 2 MiB |

The ZIP loader parses bounded central-directory metadata, including ZIP64 end records and at most
one bounded central-directory digital signature, before constructing `ZipFile`; an excessive
declared entry count is therefore rejected before the JDK ZIP parser allocates its inventory.

`Rom(File)` is still a legacy compatibility constructor: ZIP preflight closes its channel before
`ZipFile` reopens the path, so a concurrently replaceable file is not one immutable validated
input. The unified asynchronous open service must snapshot once (or revalidate the same handle),
include a mutation regression, and be the only path advertised as an adversarial trust boundary.

A 7z entry count may live inside an encoded header. Commons Compress 1.28 can allocate a
count-sized `BitSet` while parsing empty-stream flags before its archive-statistics memory check,
so `setMaxMemoryLimitKiB` alone does not prove bounded entry-metadata allocation. The unified
open service therefore inventories and extracts 7z snapshots in a helper JVM with a 192 MiB heap
and a 60-second deadline. The helper also applies the 64 MiB decoder limit and the same entry,
aggregate-size, entry-path, and ROM-size checks as the ZIP path. A parser memory failure or timeout
terminates only the helper and is reported as an archive failure to the emulator process.

Both loaders inventory metadata before opening the selected entry. Entries with an unknown
declared uncompressed size are rejected. The selected stream is still counted while reading, so a
lying declaration cannot bypass the ROM limit. Readers must also make one-byte progress if a
broken stream reports a zero-length bulk read.

## Basic-controller replacement contract

ROM replacement is a transaction with three ownership phases:

1. **Prepare:** a loader worker reads and parses the exact `RomImage` and prepares a detached or
   deferred candidate. The current session remains owned by the controller.
2. **Persist:** at an emulation safe point, the controller pauses the old session and captures an
   immutable `BatteryFlush`. Its file I/O runs on the persistence worker. Candidate materialization
   that can read battery data also stays on that worker.
3. **Activate:** only after persistence succeeds does the timing thread finish constructing the
   candidate session and snapshot manager, commit candidate ownership, quiesce the old session
   without a second cartridge flush, and publish the new lifecycle events. Candidate event
   registrations are gated and its console is unattached until this commit, so old lifecycle or
   input events cannot mutate a staged machine.

A persistence failure keeps both the old session and its dirty generation alive. Retry reuses the
same immutable capture; cancel discards the candidate and resumes the old pause state. Explicit
stop uses the same barrier. Synchronous close has one shared hard deadline across timing-thread
quiescence, persistence, worker shutdown, and event-bus teardown; it throws
`Controller.PersistenceBarrierException` on failure or timeout, and retains its capture so the
caller can retry rather than discard state. A persistence task that outlives the caller deadline
is retained as the sole writer: retries observe that same task and never start an overlapping
writer. The desktop keeps the window open on failure, but the captured session remains paused
awaiting retry rather than resuming emulation from state that may already be in flight to disk. A
cancelled replacement or stop writer and the final close writer all use the same single-thread
executor. Even if cancellation interrupts a filesystem call that ignores interruption, the newer
close capture stays queued behind it and is the last generation published.

After final persistence succeeds, close first quiesces the ROM loader, persistence executor, and
controller event tree within the same deadline. Only then may `Session` release `Gameboy`
resources. A loader or subscriber timeout therefore retains both the console attachment and the
complete machine for retry. Replacement, stop, and candidate discard instead mark the timed-out
bus stopping and defer irreversible cleanup to a daemon; an already committed replacement is
never rolled back to a stopped old machine.

Session-owned core cleanup is silent after bus quiescence. The desktop owner releases held input
and resets host rumble before close, including failed or deferred quit attempts; direct
`Gameboy.close()` on an active bus still publishes the final motor-off transition. Synchronous
posts from a stopping/closed child bus are dropped and cannot route through a retained parent into
active siblings. Parent fork attachment and close traversal are one serialized ownership
transaction, so no newly started child worker can escape teardown.

Candidate construction failures leave the old session recoverable: all candidate-owned components
are staged first, discarded children are closed, subscriber exceptions are isolated, and the
console remains attached to the old machine until ownership commits.

`LinkedController` uses the same unified open and persistence boundaries. It rejects an oversized
or unreadable adjacent battery before replacing its retained payload/session. At its queued
frame-safe load boundary it persists the old session's current RAM/RTC capture, re-reads the
resulting bounded sidecar, and sends those exact bytes; a typed write/read failure retains old
ownership and publishes no new lifecycle. ROM parsing and battery preflight stay on the worker path,
outside the event caller and Swing EDT.

When the user keeps a session open after quit persistence fails, the desktop leaves its glass pane
and wait cursor active with explicit “paused; close again to retry” wording. The controller has
already entered its close pause, so ordinary input must remain blocked until that retry.
