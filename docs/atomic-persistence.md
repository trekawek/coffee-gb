# Crash-safe local persistence

Snapshots and file-backed cartridge batteries use the single
`core.persistence.AtomicFileWriter` transaction. The invariant for replacement of an existing
valid file is:

> after an interrupted attempt and recovery, the visible target is exactly the complete old bytes
> or exactly the complete new bytes, never a truncated or mixed sequence.

The complete byte array is materialized before the writer touches the destination directory.
Operations for one normalized absolute target are serialized by a fixed set of process-local lock
stripes. Recovery and reader callbacks take the same lock, so an in-process reader cannot enter the
fallback's temporary missing-target interval. Lock striping is fixed-size and does not retain an
unbounded set of filenames.

## Artifacts and normal atomic path

For target `T` in directory `D`, the writer derives a 128-bit lowercase hex identifier from the
SHA-256 of `T`'s filename. It owns only these same-directory artifacts:

- unique temporary files `.coffeegb-<id>.tmp-<unique>.part`;
- at most one deterministic recovery backup `.coffeegb-<id>.backup`.

The normal transaction is:

1. recover a prior fallback transaction and remove bounded stale owned temps;
2. create a unique temp with `Files.createTempFile(D, ...)`;
3. write the complete intended array with a `FileChannel`, retrying partial writes;
4. call `FileChannel.force(true)` and close the channel;
5. first attempt `Files.move(temp, T, ATOMIC_MOVE, REPLACE_EXISTING)`;
6. best-effort force directory `D`, then remove a stale regular backup.

A failure other than `AtomicMoveNotSupportedException` from step 5 is reported directly. Permission,
space, and unrelated I/O failures do not silently select the weaker fallback.

## Same-directory fallback and recovery

Only `AtomicMoveNotSupportedException` selects the fallback. If an old `T` exists, the writer first
moves it to the deterministic backup, best-effort forces `D`, then moves the already-forced temp to
`T`. It forces `D` again before deleting the backup, and forces the backup deletion when supported.
The fallback therefore never removes the only valid old copy before another recovery name owns it.
A first write has no old file and moves the forced temp directly.

Recovery always prefers a present `T`; a present target makes an older regular backup stale. Only
when `T` is absent and the deterministic backup is a non-symlink regular file does recovery move
the backup back to `T`. Snapshot availability/load and battery load invoke this recovery before
existence or read, under the target lock.

| Interruption point | On-disk state before recovery | Recovered result |
|---|---|---|
| before/during temp write, before temp force, or before replacement | old `T`, possible temp | old `T` |
| after fallback preserves old target | backup contains old, `T` absent | old restored to `T` |
| immediately before fallback target rename | backup contains old, forced temp complete | old restored to `T` |
| immediately after target rename | new `T`, optional old backup | new `T`; target wins |
| after confirmed cleanup | new `T`, no transaction artifacts | new `T` |

A reported failure after target rename is deliberately still a caller failure. Battery state remains
dirty and retryable because the caller cannot assume every durability step completed, even though
the complete new target may already be visible.

## Cleanup and platform limits

Cleanup matches only the exact target-derived temp prefix in the exact normalized parent directory.
It never follows or deletes a symlink and ignores similar names. At most 32 stale regular temps are
admitted per cleanup pass; exceeding that bound rejects the operation without touching the valid
target. A successful quiescent transaction leaves no regular temp or backup.

POSIX filesystems commonly support both atomic rename and directory `fsync`. Windows, network
filesystems, and some providers cannot open or force directories. Directory metadata forcing is
therefore best effort and logged at debug level; file contents are always forced before a move.
When atomic replacement is unavailable, the same-directory backup protocol provides restart
recovery, but the platform's ordinary rename guarantees remain the limiting durability boundary.
The serialization guarantee is within one Coffee GB process; independently running processes
must not write the same snapshot or battery target concurrently.

## Failure routing

`BasicController` catches snapshot-save failures at its frame-boundary event dispatch, logs them,
posts `SnapshotSaveFailedEvent`, and does not post `SnapshotSavedEvent`. Swing presents the failure
with its normal error-dialog route. The previous slot remains readable/recoverable.

`FileBattery` retains its exact pending RAM and RTC buffers plus dirty/clock flags until
`AtomicFileWriter.write` returns success. A reported post-rename failure also retains them.
Synchronized battery operations ensure a newer generation cannot be cleared by completion of an
older flush. Load and save I/O failures are logged and posted as
`BatteryPersistenceFailedEvent`; a construction-time load failure is held until the session event
bus is initialized. Subscriber failures are contained so neither persistence I/O nor its error
route terminates emulation.
