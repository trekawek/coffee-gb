# In-process rewind machine snapshots

## Scope and ownership

`MachineSnapshot` is Coffee GB's internal rewind representation. It is immutable, service-free, and
owned by the emulator thread. It is not a StateFile root, has no byte codec, is not serializable,
and must never cross a disk or network boundary. Disk snapshots use StateFile v1/v2 as required by
profile identity; protocol-v8 netplay uses StateFile v1;
`ControllerState` and boot/reset use explicit detached/component state. The local-only historical
importer is a separate migration boundary and is never reachable from rewind.

`BasicController` captures only after a completed forward frame, after its queued events have been
dispatched and before the next frame starts. Restore occurs at the same owner-thread boundary
before the normal frame ticks that make the rewound frame visible and audible. Calling capture or
restore concurrently with `Gameboy.tick`, cartridge flushing, or endpoint mutation is unsupported.
The live four-slot physical-input service is deliberately not rewound. The event/protocol-owned P1
subset remains session input for linked rollback history.

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
nodes are also reused by identity.

Rewind does not call the ordinary deep-owned `Gameboy.captureState()` path. At the safe point, array-owning
machine components first declare the identities and logical lengths of their dominant live
primitive payloads without constructing a detached graph. They then build exactly one short-lived typed
record view and register its live primitive payloads with `MachineStateCapture`. Every dominant
declaration must be matched by the same array identity and length; registering
`capture.ints(ram.clone())` leaves the real backing unmatched and rejects the capture. Snapshot
construction reads and compares the registered arrays synchronously; the token is then closed and
the borrowed view cannot escape. Unchanged arrays therefore require neither a full source-payload
copy nor a retained-page copy. A changed page produces one private retained payload. Snapshot graph
construction also rejects every unregistered primitive array, preventing an owner from silently
falling back to an array-owning deep capture.

The independently declared dominant owners include WRAM, both VRAM banks, OAM, both display
buffers, the behavior-relevant pending-audio prefix, SGB packet/border/mask/palette/attribute
buffers, cartridge battery buffers, and every mutable mapper RAM/flash/EEPROM implementation,
including MBC6 flash, MBC7 EEPROM, wrapper delegates and nested Datel slots. Smaller CPU, PPU,
serial, IR and register arrays still use the mandatory token registration and immutable paging
path. The 24-family mapper matrix asserts that every mapper primitive payload is independently
declared, and focused tests reject both a copied dominant registration and an unregistered
primitive fallback. The importer-only record descriptors are unchanged. Boot, portable StateFile,
controller handoff and netplay use the explicit deep-owned component-state path; no normal capture
constructs an importer compatibility graph.

A restore always materializes new live arrays from the private pages. Live mutation can therefore
never write into a snapshot. Branching by restoring an old generation and capturing from it reuses
only content that is still equal. Removing an entry, clearing the queue, or evicting the oldest
entry merely drops references; it does not modify pages shared by surviving or externally held
snapshots.

Joypad machine state includes the SGB `MLT_REQ` mode, selected logical player, selectors, glitch
filter, and packet receiver, so those phases rewind exactly. Physical input is a separate immutable
four-slot service sampled at the next Joypad tick. It is not a snapshot page: restoring an older
machine cannot resurrect a released P1-P4 host button, while a button that is still physically held
remains held. Linked rollback replays P1 from its established frame-owned input history and forces
`PlayerInputSource.RELEASED` on every live/replay machine. Protocol v8 cannot represent local SGB
P2-P4, so those desktop slots are masked for linked sessions rather than sampled during a rebase.

Before live mutation, restore reconstructs the complete registered machine record, checks hardware
and mapper/battery ownership, runs the Phase-1 semantic validation, and validates both primitive
runtime supplements. It retains a rollback capture for unexpected failures while applying the
machine, FIFO runtime, and RTC runtime. Snapshot pages are private and the test/benchmark probe
returns only opaque page-identity tokens.

The semantic stage receives the restored target profile's exact `ClockSpec`: its Sound pending
prefix/full-buffer and write index must fit that clock's stereo controller-frame capacity, and each
MBC3 subsecond phase must be below that clock's tick rate. This target-dependent validation occurs
before the rewind apply callback, not inside a partially mutating component restore.

Each snapshot also retains the immutable canonical hardware-profile ID. Capture chains reuse pages
only within the same ID, and restore rejects `dmg`/`cgb`/`cgb0`/`sgb` mismatches before materializing
or mutating live arrays. The profile scalar adds no shared mutable state and does not change the
page-generation or retained-byte accounting. Deprecated coarse `GameboyType` is not rewind
identity.

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

Method: use one synthetic CGB MBC5+32 KiB RAM machine, run 1800 complete forward frames, and retain
the 300 states selected by `RewindManager`'s first-capture/every-sixth-frame cadence. Each frame
executes exactly the machine profile's `ClockSpec.controllerTicksPerFrame()` (69,905 for the
recorded CGB baseline; 70,224 for SGB/SGB2) before the record point, then
applies the same deterministic scattered WRAM, both-VRAM-bank, OAM and mapper-RAM workload to the
legacy and MachineSnapshot machines. The harness then restores all 300 entries.
The legacy baseline counts every distinct reachable primitive array using the measured 64-bit
HotSpot layout model (16-byte array header, element width, eight-byte alignment). The new exact
primitive payload figure counts each immutable page identity once. `modeledRetainedBytes` also adds
deterministic shallow estimates for snapshot/value/container nodes. Thread allocation comes from
HotSpot's `ThreadMXBean`; capture/restore times use `System.nanoTime`. Allocation and time are
reported for diagnosis only and have no flaky CI threshold. The normal deterministic guard repeats
the final 1800-frame MachineSnapshot workload and compares retained bytes to the recorded legacy
baseline; it does not assert time or allocation.

Environment: Coffee GB master `195d9172f27d707934f631fa64c08803b18776a4`, final Phase-5 branch,
Oracle HotSpot 21.0.1, Maven 3.8.6, Linux 6.17.0-41 x86-64, Intel i7-1165G7 (4 cores/8 threads),
38 GiB RAM. The exact pre-change master run and final run produced:

| Measurement | Legacy master | MachineSnapshot final |
|---|---:|---:|
| entries | 300 | 300 |
| retained primitive bytes | 337,665,600 | 8,036,600 |
| modeled retained bytes | not measured (object graph excluded) | 12,672,880 |
| retained arrays/pages | 194,400 arrays | 4,331 pages |
| unique immutable value nodes | n/a | 36,202 |
| copied retained page bytes | n/a | 7,967,292 |
| identity-verified dominant source bytes (cumulative) | n/a | 333,717,600 |
| capture allocated bytes | 340,432,880 | 318,594,760 |
| capture time | 90,746,351 ns | 777,744,061 ns |
| restore time | 43,962,026 ns | 1,525,814,700 ns |

Exact retained primitive payload fell by 97.62%; even the final modeled total is 96.25% below the
baseline's primitive arrays alone. Capture allocation is 6.41% below the identical legacy
baseline and 56.19% below the rejected transitional implementation's 727,215,224 bytes. The
deterministic ownership tests prove the no-full-clone property by making a copied dominant
registration fail; no synthetic "zero clone" counter is reported. Capture and restore remain
slower than the legacy graph; these honest timing costs come primarily from exact page comparison
and reflection-based immutable graph conversion/reconstruction. They are not hidden by copied-page
accounting and do not form a normal-test performance threshold.
