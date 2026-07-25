# Portable StateFile format, version 1

StateFile v1 is the portable byte representation of the detached machine/session model. It is
independent of Coffee GB's Maven/application version and of Java serialization. All multibyte
integers are big-endian, all lengths are nonnegative, and all strings are strict UTF-8.

The codec and explicit decode-then-apply seam are used by local slot snapshots and protocol-v8
netplay. Rewind and `ControllerState` retain their existing in-memory mementos until their assigned
phases. Network state has no Java-memento compatibility path.

## Envelope

The fixed header is 68 bytes:

| Offset | Width | Field | v1 value/meaning |
|---:|---:|---|---|
| 0 | 4 | magic | ASCII `CGBS` |
| 4 | 2 | format version | unsigned `1` |
| 6 | 2 | header size | unsigned `68` |
| 8 | 4 | flags | bit 0 is raw DEFLATE; every other bit is zero |
| 12 | 1 | root kind | 1 machine, 2 session, 3 linked session |
| 13 | 1 | checksum algorithm | 1 = SHA-256 |
| 14 | 2 | reserved | zero |
| 16 | 4 | section count | unsigned |
| 20 | 8 | encoded payload length | signed storage, required nonnegative |
| 28 | 8 | decoded payload length | signed storage, required nonnegative |
| 36 | 32 | integrity checksum | SHA-256 of the exact encoded payload bytes |
| 68 | encoded length | encoded payload | one section stream, optionally compressed |

The file ends after the declared encoded payload. Truncation and trailing bytes are errors. For
uncompressed files, encoded and decoded lengths must be identical.

### Compression

Flag bit 0 selects one raw RFC 1951 DEFLATE stream (`nowrap=true`), using the canonical writer
settings `BEST_COMPRESSION`, no preset dictionary, and no zlib/gzip wrapper. The same input and
metadata therefore produce the same v1 bytes. Readers also accept flag zero (uncompressed).

The decoder verifies the encoded-payload SHA-256 before inflation. Inflation writes through a
bounded buffer, must finish at exactly the declared decoded length, and must consume every encoded
byte. Dictionaries, concatenated streams, trailing compressed data, corruption, truncation,
declared under/overrun, and expansion past the declared or global limit are rejected.

## Section stream

Each decoded section has a 16-byte header followed by its body:

| Width | Field |
|---:|---|
| 2 | unsigned numeric section ID |
| 2 | unsigned section schema version |
| 2 | flags: bit 0 means required; all other bits are zero |
| 2 | reserved zero |
| 8 | nonnegative body length |
| length | section body |

Section IDs are positive, unique, and strictly increasing. Writers emit IDs 1, 2, then optional
3. Readers reject duplicate/noncanonical sections, missing required singleton sections, an
unsupported version of a known section, and unknown required sections. A bounded unknown optional
section is skipped. Re-encoding a decoded file intentionally drops unknown optional sections;
applications that must preserve an extension must retain its original bytes.

| ID | Version | Required | Name |
|---:|---:|:---:|---|
| 1 | 1 | yes | machine identity/profile list |
| 2 | 1 | yes | detached root payload |
| 3 | 1 | no | diagnostic core/build strings |

### Section 1: identity/profile

The body begins with an unsigned 32-bit entry count (exactly 1 for machine/session roots or 4 for
linked roots). Entries are in canonical player order and contain:

1. unsigned 32-bit player index;
2. one strict boolean for machine presence;
3. when present, the primary ROM's 32-byte SHA-256;
4. one strict slot-presence boolean and, when present, the Datel pass-through slot ROM's 32-byte
   SHA-256;
5. unsigned 16-bit hardware-profile version (1);
6. hardware byte: 1 DMG, 2 CGB, 3 SGB;
7. bootstrap byte: 1 normal, 2 fast-forward, 3 skip;
8. unsigned 32-bit profile flags.

Profile flags are bit 0 CGB0 revision, bit 1 Mealybug DMG-blob behavior, bit 2 CodeBreaker rumble,
and bit 3 SGB-border display behavior. No ROM bytes are encoded. Diagnostic core/build strings do
not participate in compatibility. Every active linked player has its own identity; absent player
slots have no identity. Inapplicable configuration bits are canonical zero: CGB0 exists only on
CGB, Mealybug DMG-blob timing only on non-CGB hardware, and the border flag only on SGB.

### Section 2: root payload

A machine payload contains:

- hardware ID byte;
- primary and slot MBC3 runtime values, each tag 0 (absent) or tag 1 followed by a strict paused
  boolean and signed 64-bit pause-start milliseconds;
- a strict DMG-FIFO-supplement presence boolean (required for DMG/SGB, forbidden for native CGB);
- when present, timing and output FIFO records, each six signed 32-bit values:
  `linePixels`, `outCount`, `firstEntry`, `firstBgp`, `firstObp0`, `firstObp1`;
- one StateValue whose registered record type is the Gameboy root.

A session payload adds:

- serial peripheral byte: 1 none, 2 byte receiver, 3 peer-to-peer, 4 printer, 5 GPS receiver,
  6 Barcode Boy, 7 four-player adapter;
- serial StateValue, whose registered root type must match that peripheral;
- runtime tag 0 (none) or 1 (Barcode Boy), with strict `transferArmed` and pending-presence
  booleans; a present pending scan is exactly 30 signed 32-bit byte-valued entries;
- unsigned 32-bit held-button count and strictly increasing button IDs:
  RIGHT 1, LEFT 2, UP 3, DOWN 4, A 5, B 6, SELECT 7, START 8.

A linked payload contains a nonnegative signed 64-bit frame, local-player byte, topology byte
(1 normal, 2 four-player adapter), unsigned 32-bit player count (exactly four), then four canonical
player index/presence/session tuples. Slots outside normal topology are absent. Active sessions use
the topology's endpoint type; four-player adapter StateValues must be identical because they
represent one shared adapter.

### Section 3: diagnostics

Two strict UTF-8 strings: core version then build ID. They are informational and never determine
target compatibility.

## StateValue grammar

Every value starts with a one-byte tag:

| Tag | Kind | Following bytes |
|---:|---|---|
| 0 | null | none |
| 1 | signed Int32 | 4 |
| 2 | signed Int64 | 8 |
| 3 | boolean | one byte, exactly 0 or 1 |
| 4 | Float64 | raw IEEE-754 binary64 bits in one signed Int64 |
| 5 | string | unsigned Int32 UTF-8 byte count, then strict UTF-8 |
| 6 | enum | unsigned Int32 enum type ID, unsigned Int32 one-based v1 value ID |
| 7 | record | unsigned Int32 record type ID and field count; then name/value pairs |
| 8 | bytes | unsigned Int32 count and bytes |
| 9 | Int32 array | unsigned Int32 count and signed Int32 elements |
| 10 | Int64 array | unsigned Int32 count and signed Int64 elements |
| 11 | boolean array | unsigned Int32 count and strict boolean bytes |
| 12 | object array | unsigned Int32 count and values |
| 13 | list | unsigned Int32 count and values |
| 14 | Int32 map | unsigned Int32 count, then signed Int32 key/value pairs |

Float NaN payloads and signed zero are preserved with `doubleToRawLongBits`. Int32-map keys are
strictly increasing and unique. Record type IDs are the one-based stable entries of the audited
91-record `MementoTypeRegistry`; field count, name, and declaration order are encoded and checked.
The 11 enum type IDs use the same audited ordering, while enum value IDs are an explicit v1
one-based registry verified against the production enum names. Class names from input are never
loaded or instantiated.

The record ID/name/field registry is the exact ordered 91-record appendix in
[state-memento-schema.md](state-memento-schema.md), where each bullet's one-based position is its
ID. The v1 enum registry is:

| Type ID | Enum | Value IDs in order starting at 1 |
|---:|---|---|
| 1 | CPU state | OPCODE, EXT_OPCODE, OPERAND, RUNNING, IRQ_WAIT_1, IRQ_WAIT_2, IRQ_PUSH_1, IRQ_PUSH_2, IRQ_JUMP, STOPPED, HALTED, SPEED_SWITCH, LOCKED |
| 2 | interrupt type | VBlank, LCDC, Timer, Serial, P10_13 |
| 3 | GPU mode | HBlank, VBlank, OamSearch, PixelTransfer |
| 4 | OAM search state | READING_Y, READING_X |
| 5 | HDMA CPU arbitration | NONE, UNRESOLVED, DMA, CPU |
| 6 | HALT/HDMA state | LOW, HIGH, REQUESTED |
| 7 | wake arbitration | NONE, REVERSE_PENDING, PREEMPT_CPU, YIELD_CPU |
| 8 | MBC7 EEPROM state | IDLE, COMMAND, READING, WRITING |
| 9 | Barcode Boy state | HANDSHAKE, READY, SENDING |
| 10 | four-player phase | PING, TRANSMISSION_INDICATOR, TRANSMISSION, PING_INDICATOR |
| 11 | SGB screen mask | CANCEL, FREEZE, BLANK_BLACK, BLANK_COLOR0 |

The byte parser constructs detached DTOs only. It does not use ObjectInputStream,
ObjectOutputStream, Java native serialization, callbacks, arbitrary class construction, or
class names supplied by the file.

## Limits

StateFile limits are independent of the Phase-0 legacy importer:

| Limit | v1 value |
|---|---:|
| file bytes | 134,348,800 (128 MiB + 128 KiB) |
| encoded payload bytes | 134,283,264 (128 MiB + 64 KiB) |
| decoded payload bytes | 134,217,728 (128 MiB) |
| one section body | 134,217,728 |
| section count | 64 |
| graph depth | 96 |
| StateValue occurrences, including nulls | 100,000 |
| collection/map entries | 16,384 |
| primitive-array elements | 16,777,216 |
| primitive-array bytes | 33,554,432 |
| string UTF-16 characters | 65,536 |
| string UTF-8 bytes | 196,608 |
| linked players/identity entries | 4 |

Checked addition, subtraction, multiplication, element widths, and declared counts are validated
before allocation, copying, slicing, skipping, string decode, checksum retention, or inflation.
The decoded aggregate limit is intentionally the netplay aggregate ceiling. Every StateValue
position consumes the occurrence budget, including null record fields and null list, object-array,
or map values. The independent occurrence/array/collection limits therefore prevent a small
encoded input from creating an unbounded object graph or null-heavy collection backing storage.

## Typed failures

`StateDecodeException.reason` is stable and distinguishes:

- invalid magic;
- unsupported format or section version;
- unsupported flags;
- primary ROM, slot ROM, or hardware/profile mismatch;
- corrupt checksum;
- truncation;
- limit exceeded;
- malformed structure, value tag, enum, or UTF-8;
- missing, duplicate, or unknown-required sections;
- trailing file/section/compressed data;
- compression error;
- target-state incompatibility.

Identity diagnostics may include SHA-256/profile values, never ROM content.

## Decode, validation, and atomic application

`StateCodec.decode` and `StateFileInspector` never mutate or construct an emulator. The smallest
Phase-2 orchestration seam is `StateCodec.decodeAndApply` for an explicit Gameboy/configuration,
Session, or LinkedController. It performs, in order:

1. bounded envelope/checksum/decompression/section parsing;
2. detached structural and runtime validation;
3. primary/slot ROM and full profile comparison for every active machine;
4. Phase-1 target-aware graph, nullability, semantic, mapper, endpoint, and hardware validation;
5. the Phase-1 safe-point prepare-and-commit transaction.

No live-mutation callback occurs before all deterministic validation succeeds. Unexpected restore
failures use the Phase-1 rollback record. Linked preparation covers every player before the first
commit and rollback remains group-atomic.

## Local slot integration and legacy dispatch

`SnapshotManager` captures a machine root with the exact active `GameboyConfiguration` at
`BasicController`'s frame-boundary event-dispatch safe point and writes canonical DEFLATE StateFile
bytes to the existing `.sn<slot>` filename. New slot files therefore begin with `CGBS`; the local
save path never emits a Java serialization header.

Local reads use exactly four prefix bytes:

- `43 47 42 53` (`CGBS`) routes only to this codec and its 134,348,800-byte file limit;
- `AC ED 00 05` routes only to the strict, allowlisted local legacy reader and its 33,554,432-byte
  game-snapshot limit;
- every other complete or truncated prefix is rejected as an unknown format.

The selected limit is enforced by the streaming read count and by explicitly capped buffer growth,
not only by file metadata. Portable bytes never reach `ObjectInputStream`. After the complete
portable decode, identity/profile validation and detached preflight, apply uses the Phase-1
transaction. Rewind history is cleared only after `SnapshotManager` returns success.

The detailed local migration policy, diagnostics, and compatibility window are documented in
[snapshot-migration.md](snapshot-migration.md). Snapshot and battery replacement durability is
specified separately in [atomic-persistence.md](atomic-persistence.md); it does not alter StateFile
v1 bytes.

## Netplay protocol v8 integration

Protocol v8 negotiates StateFile format version 1 and the MACHINE, SESSION, and LINKED_SESSION root
capability set in both directions before START or any state-bearing command is accepted. Protocol
v7 has no downgrade path. Every non-null network state is the exact `CGBS` file sequence: the
StateFile envelope owns compression and Connection adds no snapshot compression layer.

An initial console transfer carries an optional MACHINE root. A four-player running checkpoint
carries one required SESSION root per active player followed by a synchronization record; all
sessions must identify the four-player endpoint and contain the same shared-adapter state. The
complete set is validated and prepared before one frame-boundary group commit. Detailed handshake,
command layout, limits, and failure behavior are specified in
[netplay-protocol-v8.md](netplay-protocol-v8.md).

## Fixture and evolution policy

The committed fixture
`controller/src/test/resources/state-file-v1/session-barcode-deflate.cgbstate` has SHA-256
`e5ae258c3f1a9405ca87518dbb13526def9fd3e44a4486d7a495c111958cf091`.
It is generated from a repository-owned synthetic ROM and contains no ROM bytes. Its local README
documents the opt-in update command; normal tests only decode and re-encode it.

New optional sections receive new numeric IDs and canonical positions. A new required concept,
changed field meaning/width, changed type/value registry, raised compatibility limit, or changed
compression/checksum contract requires a new section schema or format version plus an explicit
migration path. Version 1 never silently interprets unknown required data.
