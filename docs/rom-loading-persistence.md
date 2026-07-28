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
  implicit permission to create a sidecar file.

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
so `setMaxMemoryLimitKiB` does not prove bounded entry-metadata allocation. The 64 MiB setting
remains useful decoder defense in depth, and the 4,096-entry check still applies after construction,
but direct 7z loading is legacy-only. The unified safe open service must reject 7z with a typed
unsupported-format result until a parser with a pre-allocation bound is available.

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
awaiting retry rather than resuming emulation from state that may already be in flight to disk.
Final close propagates a bounded event-subscriber timeout so its caller can retry before machine
resources are released. Replacement, stop, and candidate discard instead mark the timed-out bus
stopping and defer irreversible cleanup to a daemon; an already committed replacement is never
rolled back to a stopped old machine.

Candidate construction failures leave the old session recoverable: all candidate-owned components
are staged first, discarded children are closed, subscriber exceptions are isolated, and the
console remains attached to the old machine until ownership commits.

This transaction currently describes `BasicController`. `LinkedController` rejects an oversized or
unreadable adjacent battery before replacing its retained payload/session and forwards exact
in-memory ROM bytes, but its local ROM parse and battery preflight still run synchronously on the
event caller (which may be the EDT or timing thread). Linked worker-based prepare/persist/activate,
reset, stop, and close parity remains a required follow-up before issue #336 is complete. The
unified open service and desktop entry-point routing also belong to that follow-up.
