# Local snapshot persistence and legacy migration

Local numbered save slots keep their established `<rom-name>.sn<slot>` filenames and controller
events. Their contents now have an explicit format boundary.

## Production format

Every new controller save is a portable StateFile `SESSION` root encoded with deterministic raw
DEFLATE, so the selected serial peripheral and its bounded deterministic state travel with the
machine. DMG/CGB/CGB0 use the released v1 identity. `sgb`, `sgb2`, and `mgb` use the explicit
canonical identity in StateFile v2 so SGB RTC phase and DMG-family model identity cannot be
confused with v1. Historical v1 SGB files remain byte-exact on decode/re-encode and convert their
phase, while fully detached, before target validation and mutation. Capture runs when
`BasicController` dispatches the save event at its emulation-thread frame boundary.
`SnapshotManager` owns the exact active `GameboyConfiguration`, so the identity section records the
normalized primary-ROM SHA-256, optional Datel slot-ROM SHA-256, and complete stable hardware
profile. ROM bytes and host services are not written.

The live/runtime state contracts and the exact native-serialization allowlist are documented in
[legacy-state-retirement.md](legacy-state-retirement.md).

Production rewind states remain in memory as internal `SessionSnapshot` values. Each combines a
structurally shared `MachineSnapshot` with bounded serial-device state; `ControllerState` and
boot/reset handoff use explicit non-serializable component/detached state. Netplay protocol v8
independently uses StateFile v1, rejects SGB, SGB2, and MGB, and cannot invoke this local legacy
importer; see
[netplay-protocol-v8.md](netplay-protocol-v8.md).

## Bounded format detection

The first four bytes are authoritative and mutually exclusive:

| Prefix | Route | Streaming file limit |
|---|---|---:|
| ASCII `CGBS` | `StateCodec` only | 134,348,800 bytes |
| `AC ED 00 05` | strict local `LegacySnapshotImporter` only | 33,554,432 bytes |
| anything else, including fewer than four bytes | reject | no decoder |

The reader counts bytes while streaming and checks every capacity increase before allocation. It
does not rely on a potentially stale `File.length()` result. Exact-limit input is admitted to its
selected decoder and limit-plus-one is rejected before retention. StateFile then enforces its
envelope, section, encoded, decoded, graph, and work limits. No portable or unknown-prefix byte
sequence can enter Java native deserialization.

## Apply and diagnostics

Portable input is decoded to detached DTOs, checked against the source identities and target
configuration, and applied through the Phase-1 prepare/commit/rollback transaction. Failures retain
their stable `StateDecodeReason`.

The legacy route first uses the pinned descriptor manifest and JEP 290 bounds. It then checks the
current target's record, nullability, and invariant array layout before mutation and validates the
normalized candidate. Released 1.7.13/1.7.14 FIFO delay counts can exceed their physical ring
capacity. For only the two audited pixel-FIFO records, a coherent overfull ring is reduced to its
last eight entries and its head is advanced to the oldest retained entry; malformed shapes remain
invalid. This converts the historical monotonic representation to the current portable occupancy
invariant without changing logical order. Unexpected restore failures roll back the complete
machine, including RTC and DMG FIFO runtime supplements.

Load errors identify the selected format, target ROM SHA-256, optional slot hash, and target stable
profile. A safely inspectable portable source includes the corresponding source values. Legacy
files have no identity section, and corrupt files may fail before source metadata is trustworthy;
that absence is reported explicitly. ROM contents are never included.

## Migration policy

`LegacySnapshotMigrationPolicy.PRESERVE` is the production default. It restores an accepted local
legacy file without changing its bytes.

`REWRITE_AFTER_SUCCESS` is an explicit opt-in seam for callers and tests. On the production session
route, it captures and writes a portable SESSION-root file only after the legacy read, validation,
and live restore have all completed successfully. A failed read, preflight, or apply leaves both
the running machine and original file bytes unchanged.

Ordinary saves and this optional rewrite share the core crash-recoverable persistence transaction.
The complete portable bytes are forced in a unique same-directory temp and atomically replaced
where supported. Its narrowly selected recovery-backup fallback leaves a failed rewrite as either
the complete legacy file or the complete portable file. Snapshot availability and load recover an
interrupted fallback before checking or reading the slot. See
[atomic-persistence.md](atomic-persistence.md) for artifacts, crash points, cleanup bounds, and
platform limitations.

## Compatibility window

The strict local importer supports only the committed Coffee GB 1.7.13 and 1.7.14 fixture shapes;
it is a migration promise for those releases, not a general Java-object or cross-emulator format.
It is not reachable from netplay decoding. Network prefixes other than `CGBS`, including `AC ED 00
05` and the retired `CGBN`, are rejected as unsupported network state without format probing.
Adding another legacy descriptor requires a reviewed manifest update and a committed real-release
fixture. The importer may be removed only in a future major release after a separately announced
deprecation window; compatibility is not promised indefinitely.
