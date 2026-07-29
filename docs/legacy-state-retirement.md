# Runtime state architecture and historical importer boundary

## Current invariant

Normal Coffee GB execution has no Java-native state path. Live machine owners implement the
internal `StatefulComponent<T>` contract and return immutable, non-serializable
`ComponentState<T>` records through `captureState`; restore accepts the same typed records. The
controller converts those records to the deep-owned `MachineState`, `SessionState`, and
`LinkedSessionState` DTOs used by StateFile v1/v2. Rewind uses the separate paged `MachineSnapshot`
contract so unchanged dominant arrays are not cloned.

The ownership split is therefore:

| Operation | State contract |
|---|---|
| portable slot save/load and controller handoff | detached `MachineState` / StateFile v1 or v2 identity |
| protocol-v8 machine/session/checkpoint exchange | detached StateFile-v1 roots only |
| boot template and reset reuse | typed deep-owned `ComponentState` inside `BootState` |
| rewind | immutable structurally shared `MachineSnapshot` |
| historical local-file migration | importer compatibility records, immediately converted to detached state |

Capture/apply of a running machine occurs at the controller's emulation-thread frame safe point;
boot templates use their sole owner thread while the template is quiescent. A detached candidate
is completely reconstructed and validated before mutation; an unexpected live failure restores a
deep-owned rollback capture. Linked restore validates and commits every player as one group. No
state API admits threads, callbacks, event buses, streams, time sources, UI types, or mutable arrays
owned by a live component.

## Enforced native-serialization surface

`LegacySnapshotImporter` is the only production `ObjectInputStream` owner. Production contains no
`ObjectOutputStream`, `Originator`, `saveToMemento`, or `restoreFromMemento`. `SnapshotManager` can
reach the importer only after a local file begins with the exact `AC ED 00 05` stream header and
passes the legacy streaming byte limit. Network code has no importer reference and treats every
non-`CGBS` state prefix—including `AC ED 00 05` and `CGBN`—as unsupported protocol input.

Exact released binary names are necessary for Java stream resolution. Eighty-seven data-only
nested `*Memento` records therefore remain beside their owners, marked importer-only; live owners
never construct, return, or restore them. The four released serializable leaf descriptors also
remain importer-only under their historical names: `GameGeniePatch`, `GameSharkPatch`,
`Gpu.PendingPpuWrite`, and `PixelTransfer.DelayedWindowWrite`.

Normal execution uses a disjoint set of non-serializable values. Active cheats are
`GameGenieCheat`/`GameSharkCheat`; GPU and window queues use private runtime values; capture maps
those values to `Genie.GameGeniePatchState`, `Genie.GameSharkPatchState`,
`Gpu.PendingPpuWriteState`, and `PixelTransfer.DelayedWindowWriteState`. Legacy import instead maps
the four historical descriptors to those same stable numeric record IDs before normal detached
validation and apply. No normal capture, restore, rewind, disk, or network path constructs or
carries the compatibility leaves.

The architecture test strips the marked compatibility declarations and proves that the remaining
live owner source has no `Memento<T>` or compatibility-leaf dependency. It also proves that every
normal record is non-serializable, the normal and compatibility registries are disjoint, and the
94 normal IDs, 91 compatibility IDs, exact native-serialization allowlist, and network prohibition
remain pinned.

## Supported migration inputs and limits

Only the committed Coffee GB 1.7.13 and 1.7.14 local snapshot shapes are supported:

- 1.7.13 fixture SHA-256:
  `7a7ba6fa23538fcd2bdb734487a3b30a18452b69379e18712fc289858ba191ec`
- 1.7.14 fixture SHA-256:
  `3588e825efd91f8555d2fd941645a21af1c2ba330df6b56053f01ed81faf5573`

The descriptor manifest hash is pinned in `LegacySnapshotImporter`. The manual preflight and JEP
290 filter reject unknown classes or shapes, proxies, trailing bytes, truncation, malformed UTF,
unsupported roots, excessive graph depth/references/collection sizes/arrays, and checked-allocation
overflow before the graph is admitted. The legacy file cap is 33,554,432 bytes; the independent
depth, reference, string, collection, primitive-array, and cumulative-array limits are centralized
in `StateLimits`. The allowlist is not broadened for unknown Coffee GB releases.

After deserialization, `StateGraph.captureLegacyRoot` maps the parallel compatibility registry to
the same numeric detached record IDs. Narrow audited legacy normalizations run before the ordinary
target-aware structural and semantic validation. Apply then uses the same atomic safe-point
transaction as portable state. `SnapshotLoadException` reports a sanitized actionable format and
identity context; portable failures retain `StateDecodeReason`. ROM or state bytes are never placed
in diagnostics.

The production migration policy preserves the source file by default. An explicit opt-in may
rewrite it through the crash-recoverable persistence writer only after successful import,
validation, and restore. See [snapshot-migration.md](snapshot-migration.md) and
[atomic-persistence.md](atomic-persistence.md).

## Evolution and removal policy

StateFile extension/version rules remain those in [state-file-v1.md](state-file-v1.md) and
[state-file-v2.md](state-file-v2.md); retiring native serialization does not alter its bytes,
record IDs, limits, checksum, compression, or
protocol-v8 framing. The historical importer is a local migration aid, not a portable format and
not a promise of cross-emulator compatibility. It may be removed only in a future major release
after a separately announced deprecation window. Coffee GB does not promise indefinite support
for arbitrary serialized graphs or releases beyond the two committed fixtures.
