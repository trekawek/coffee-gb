# Coffee GB netplay protocol v8

Protocol v8 makes portable StateFile v1 the only network state boundary. There is no protocol-v7
downgrade and no network legacy importer.

All multibyte integers use big-endian Java primitive encodings. Length fields are signed and must
be nonnegative and within their named limits before allocation or payload reading.

## Handshake

The server first sends:

| Width | Field | Required value |
|---:|---|---|
| 16 | ASCII protocol name | `CoffeeGB NETPLAY` |
| 1 | netplay version | `8` |
| 1 | link mode | enum ordinal, or `ff` for rejection |
| 1 | assigned player / rejection reason | player 1..3, or stable rejection code |

For an accepted connection, this is immediately followed by the four-byte state capability record:

| Width | Field | Required v8 value |
|---:|---|---|
| 1 | repeated netplay version | `8` |
| 1 | state-negotiation schema | `1` |
| 1 | StateFile format version | `1` |
| 1 | root mask | `07`: MACHINE, SESSION, LINKED_SESSION |

After validating both records, the client sends the identical capability record. The server
validates it before releasing queued bootstrap messages or START. A protocol-v7 client sends its
old one-byte marker (`01`) at this point; the server rejects that byte immediately and does not
consume a following v7 command as a v8 field. Missing, truncated, extra-version, and unsupported
capability values are compatibility failures. Server-full and handshake-pool-busy responses retain
their explicit rejection codes and do not have a capability record.

## State-bearing ROM record

Command `01` is followed by this 44-byte header:

| Offset | Width | Field |
|---:|---:|---|
| 0 | 1 | player index |
| 1 | 8 | frame |
| 9 | 1 | GameboyType ordinal |
| 10 | 1 | bootstrap mode ordinal |
| 11 | 4 | stable profile flags |
| 15 | 1 | held-button count |
| 16 | 4 | primary ROM decoded length |
| 20 | 4 | primary ROM DEFLATE length |
| 24 | 4 | optional slot ROM decoded length |
| 28 | 4 | optional slot ROM DEFLATE length |
| 32 | 4 | optional battery decoded length |
| 36 | 4 | optional battery DEFLATE length |
| 40 | 4 | direct StateFile wire length |

Payloads follow in this canonical order:

1. held-button ordinal bytes;
2. the exact direct StateFile bytes, when present;
3. compressed primary ROM;
4. compressed optional slot ROM;
5. compressed optional battery.

ROM, slot ROM, and battery retain their bounded transport DEFLATE representation. StateFile is not
wrapped in another compression stream. Its first four bytes must be `CGBS`, and its envelope's
encoded length plus 68-byte header must exactly equal the record declaration.

Profile bits are: bit 0 CGB0 revision, bit 1 Mealybug DMG-blob mode, bit 2 CodeBreaker rumble mode,
and bit 3 SGB-border behavior. Other bits and hardware-inapplicable combinations are invalid. The
StateFile identity section must agree with the received primary ROM, optional Datel slot ROM,
hardware, bootstrap mode, and every profile bit.

Phase 3 deliberately does not reinterpret these bytes. The pinned `GameboyType` plus CGB0 flag
canonicalizes to permanent profile ID `dmg`, `cgb`, `cgb0`, or `sgb` before candidate construction;
the remaining flags stay accessory/boot compatibility policy. Protocol v8 has no free-form profile
string and cannot negotiate an unknown future profile. Such an addition requires a new explicit
capability/version rather than display-name or ordinal guessing.

An ordinary initial transfer may omit state or carry one MACHINE root. A running four-player
checkpoint record must carry a SESSION root. The host emits one record for each active player,
then command `09` plus the checkpoint frame. An empty set followed by synchronization represents
all ports stopped. Non-empty groups reject duplicate players, non-four-player endpoints, or
different copies of shared adapter state.

Other commands keep their v7 payload meaning under the v8 handshake: `03` input, `06` reset, `07`
stop, `08` start, `09` synchronize, and `0a` protocol error. No command is parsed until capability
negotiation succeeds.

## Trust limits

The central `StateLimits` policy applies before allocation and payload reads:

- one direct StateFile wire sequence: 32 MiB, including its envelope;
- one direct StateFile decoded section stream: 32 MiB;
- one complete encoded ROM/state record: 128 MiB;
- one complete decoded ROM/slot/battery/state record: 128 MiB;
- a retained four-player checkpoint and each pending source: the same 128 MiB decoded aggregate;
- primary and slot ROM: 64 MiB each; battery: 2 MiB.

The independent StateFile v1 graph, value-occurrence, depth, collection, array, string, section,
checksum, and decompression limits still apply inside those tighter network caps. Checked
arithmetic is used for every aggregate. A declaration beyond a direct or aggregate limit is
rejected before reading the declared payload.

## Decode, safe point, and atomicity

Connection may read, checksum, decompress, and decode a StateFile off the emulator thread, but the
result is detached DTO data only. It validates root kind, identity/profile, mapper/hardware shape,
endpoint identity, and target compatibility against an isolated candidate. It never calls
`LegacyMementoCodec`, Java native serialization, or a peer-derived Memento decoder.

LinkedController receives the validated event through its bounded queue. At its existing
emulation-thread frame boundary it constructs and prepares every replacement before swapping live
sessions, configurations, ROM/battery buffers, frame floor, history, or topology. Four-player
checkpoints use one fresh shared adapter and commit as a group. Any decode, identity, construction,
preparation, or commit failure rejects only the source and leaves the live group unchanged;
unexpected commit failures restore the captured controller/history transaction.

Each candidate retains its registered profile and immutable `ClockSpec`. The complete linked group
is preflighted before construction/replay or replacement; differing master rates or controller tick
budgets are rejected before partial execution. All current v8-representable profiles intentionally
share the legacy 4,194,304-Hz, 69,905-tick controller budget, so this adds no wire or timing change.

Network `AC ED 00 05`, retired `CGBN`, unknown magic, corrupt/truncated/future StateFile data,
wrong roots, and identity/profile mismatches are protocol errors. They are never sent to the local
legacy dispatcher. Local numbered-slot legacy migration remains described solely in
[snapshot-migration.md](snapshot-migration.md).

Protocol extensions must allocate a new negotiation version, explicit capability bit, command
layout, or StateFile version as appropriate. Unknown v8 state capabilities and unknown required
StateFile sections fail closed.
