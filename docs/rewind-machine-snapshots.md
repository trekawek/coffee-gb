# In-process rewind machine snapshots

## Scope and ownership

`MachineSnapshot` is Coffee GB's internal rewind representation. It is immutable, service-free, and
owned by the emulator thread. It is not a StateFile root, has no byte codec, is not serializable,
and must never cross a disk or network boundary. Disk snapshots and netplay continue to use
StateFile v1. The local-only legacy importer and the remaining `ControllerState`/boot-state memento
uses are intentionally left for issue #326.

`BasicController` captures only after a completed forward frame, after its queued events have been
dispatched and before the next frame starts. Restore occurs at the same owner-thread boundary
before the normal frame ticks that make the rewound frame visible and audible. Calling capture or
restore concurrently with `Gameboy.tick`, cartridge flushing, or endpoint mutation is unsupported.
Physical held buttons remain session input and are deliberately not rewound.

`RewindManager` keeps the existing 300 entries and records every sixth emulated frame. A successful
ROM load/reset or disk-state load clears the history and resets capture cadence. The default
constructor enables rewind. Its explicit disabled mode returns before cadence bookkeeping or
machine inspection, so it creates no per-frame snapshot allocation and a rewind request cannot
freeze forward emulation.

## Immutable graph and page generations

The snapshot graph covers the complete Phase-1 machine inventory, including the primary and Datel
slot RTC runtime and the two compatibility DMG FIFO runtime records. It contains only immutable
scalars, enum/type IDs, immutable record/container nodes, and private primitive-array pages. It
never retains an event bus, callback, console, file, time source, thread, ROM byte array, or other
host service.

Primitive arrays use fixed 4096-byte payload pages:

| Primitive | Elements per full page |
|---|---:|
| byte / boolean | 4096 |
| int | 1024 |
| long | 512 |

Four KiB is small enough that normal scattered WRAM/VRAM/display changes do not copy an entire
large component, while keeping a 1 MiB mapper flash to 256 page identities instead of thousands of
tiny objects. The scheme applies uniformly to WRAM, both VRAM banks, OAM, display continuation and
visible-frame buffers, audio and peripheral buffers, SGB buffers, mapper RAM, Datel/MBC6 flash, and
MBC7 EEPROM.

Each capture is a generation whose predecessor is the previous retained snapshot. For every page,
capture computes a deterministic content hash and then performs an exact primitive comparison.
Equal content reuses an immutable predecessor page (or an equal page already seen in the same
generation); only a changed retained page payload is copied. This content comparison is the dirty
transition and cannot mistake a hash collision for equality. Unchanged scalar/container/record
nodes are also reused by identity. The temporary Phase-1 memento used as the audited capture source
is discarded immediately and is never stored in rewind history; eliminating that transitional
capture allocation belongs to the broader memento retirement in #326.

A restore always materializes new live arrays from the private pages. Live mutation can therefore
never write into a snapshot. Branching by restoring an old generation and capturing from it reuses
only content that is still equal. Removing an entry, clearing the queue, or evicting the oldest
entry merely drops references; it does not modify pages shared by surviving or externally held
snapshots.

Before live mutation, restore reconstructs the complete registered machine record, checks hardware
and mapper/battery ownership, runs the Phase-1 semantic validation, and validates both primitive
runtime supplements. It retains a rollback capture for unexpected failures while applying the
machine, FIFO runtime, and RTC runtime. Snapshot pages are private and the test/benchmark probe
returns only opaque page-identity tokens.

## Presentation state

Presentation data is retained only where it affects current output or deterministic continuation:

- `Display.buffer`, its write index, LCD/repeat flags, and `lastFrame` restore a partially rendered
  frame and the complete visible panel. Equal `buffer`/`lastFrame` pages share one identity.
- PPU FIFO/fetcher/OAM/palette state and VRAM-transfer buffers determine subsequent pixels.
- The APU output buffer/index and channel phases determine the post-restore audio stream.
- SGB border, mask, palette, and animation buffers determine the presented SGB frame.

Host render listeners, audio sinks, and UI buffers are services and are not retained. Phase 1's
single display-owner rule remains intact; the historical duplicate root display record stays null
for new captures.

## Reproducible 300-entry measurement

The manual harness is `MachineSnapshotBenchmarkTest`. It is skipped during normal tests and runs
with:

```text
/opt/maven/bin/mvn -B -pl controller -am \
  -Dtest=MachineSnapshotBenchmarkTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcoffeegb.rewind.benchmark=true test
```

Method: use one synthetic CGB MBC5+32 KiB RAM machine, run 1800 deterministic workload steps,
capture every sixth step, and retain 300 entries. Each step advances 2048 master ticks and changes
scattered WRAM, both VRAM banks, OAM, mapper RAM, and display state. Then restore all 300 entries.
The legacy baseline counts every distinct reachable primitive array using the measured 64-bit
HotSpot layout model (16-byte array header, element width, eight-byte alignment). The new exact
primitive payload figure counts each immutable page identity once. `modeledRetainedBytes` also adds
deterministic shallow estimates for snapshot/value/container nodes. Thread allocation comes from
HotSpot's `ThreadMXBean`; capture/restore times use `System.nanoTime`. Allocation and time are
reported for diagnosis only and have no flaky CI threshold.

Environment: Coffee GB master `195d9172f27d707934f631fa64c08803b18776a4`, final Phase-5 branch,
Oracle HotSpot 21.0.1, Maven 3.8.6, Linux 6.17.0-41 x86-64, Intel i7-1165G7 (4 cores/8 threads),
38 GiB RAM. The exact pre-change master run and final run produced:

| Measurement | Legacy master | MachineSnapshot final |
|---|---:|---:|
| entries | 300 | 300 |
| retained primitive bytes | 420,760,640 | 8,118,616 |
| modeled retained bytes | not measured (object graph excluded) | 12,310,608 |
| retained arrays/pages | 194,400 arrays | 4,090 pages |
| unique immutable value nodes | n/a | 31,872 |
| copied retained page bytes | n/a | 8,053,164 |
| capture allocated bytes | 423,527,328 | 727,215,224 |
| capture time | 125,434,035 ns | 954,542,780 ns |
| restore time | 52,817,678 ns | 1,503,275,044 ns |

Exact retained primitive payload fell by 98.07%; even the final modeled total is 97.07% below the
baseline's primitive arrays alone. The normal test asserts the deterministic primitive retained
total stays below half of the recorded master baseline. The higher transient allocation and timing
are explicit limitations of the Phase-1 memento bridge and reflection-based reconstruction; they
are not hidden by the retained-memory result and do not form a normal-test performance assertion.
