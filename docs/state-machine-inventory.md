# Machine/session state inventory and ownership contract

## Invariant established by Phase 1

`MachineState`, `SessionState`, and `LinkedSessionState` are the service-free capture boundary for
the future portable state format. A capture owns every array and collection it exposes. A restore
first reconstructs and validates the complete detached graph, checks the cartridge/serial runtime
families, and only then mutates the running machine. If a live restore throws, the adapter restores
the machine, RTC runtime, serial endpoint/runtime, and held buttons from rollback captures.

The model contains only `Int`, `Long`, `Boolean`, `Double`, `String`, explicit enum/type IDs, and
deep-owned immutable containers. It cannot represent a thread, callback, event bus, stream, file,
clock service, AWT/Swing object, or live mutable array. The Phase 2 byte envelope and section codec
are deliberately not part of this phase.

The exact field-by-field inventory of all 91 admitted production record types is committed in
[state-memento-schema.md](state-memento-schema.md). `MementoTypeRegistry` is the executable type
allowlist. `StateCoverageMatrixTest` exercises every mutable mapper and stateful serial peripheral;
`DetachedStateTest` exercises the complete root graph, deep ownership, failure atomicity, display,
held input, endpoint runtime state, and deterministic continuation.

## Supported safe points and thread ownership

There are only two supported capture/apply safe points:

1. The owning controller/emulator thread at a frame boundary, after queued topology/input events
   and history reconciliation have completed and before the next group of machine ticks. Linked
   capture includes the authoritative frame, fixed topology tag, player slots, each session, and
   physical held buttons.
2. A single-machine controller operation (disk snapshot, rewind, boot-state reuse) while that
   controller has quiesced its emulator thread. Capture/apply must run on that same owner thread.

Calling the detached adapter concurrently with `Gameboy.tick`, endpoint I/O, mapper battery flush,
or topology mutation is unsupported. UI threads may request an operation through the controller,
but never capture/apply a machine directly. Execution-control flags (`Thread`, stop/pause request
volatiles, monitor state) remain controller services and are not portable machine behavior.

## Complete behavior-affecting ownership inventory

| Owner | Captured behavior | Excluded/derived state and rationale |
|---|---|---|
| Gameboy root | Boot shadow, cartridge, GPU/display, STAT, MMU/OAM, CPU, interrupts, timer, DMA/HDMA, sound, serial/IR, rumble, joypad, speed, SGB, cheats, LCD refresh/off and speed-switch/boot handoff flags | Component wiring, immutable ROM/configuration, console, buses, thread and stop/pause control are host construction/ownership services |
| CPU/registers | A/B/C/D/E/H/L, SP, PC, flags, decoded opcode/operand pipeline, operation context/index, IRQ entry, HALT/STOP/halt-bug, clock cycle, HDMA prefetch/arbitration and speed-switch phase | Instruction tables and address-space references are immutable code/wiring |
| Interrupts | IME, IF/IE, delayed enable, blocked/phased sources, acknowledgement and PPU/timer/serial edge latches | Interrupt source object references are wiring |
| Timer/divider | DIV/TIMA/TMA/TAC, selected-bit history, overflow/reload, DIV reset, HALT wake/ripple and interrupt suppression edges | Speed/interrupt object references are wiring |
| Speed switch | Current multiplier, prepare latch, DMG compatibility plus Gameboy clock-mux tail/phase flags | Cartridge compatibility flags are immutable configuration |
| Boot/memory mapping | BIOS enabled shadow, MMU fixed RAM/WRAM/HRAM banks, SVBK, undocumented registers and OAM echo | BIOS bytes, address-space index and component references are reconstructed from configuration |
| DMA/HDMA | OAM ownership/restart/source/ticks/bus samples/interrupt collision state; VRAM-DMA registers, mode, block bytes, CPU arbitration, HBlank/LCD/speed/halt request history | Source/destination address-space references are wiring; all temporal arbitration caches that affect behavior are captured |
| GPU/PPU | VRAM banks, registers/mix/write delays, LCDC/STAT, LY/mode/dot, palettes, OAM-search state/sprites, fetcher, pixel and sprite FIFOs, window/object penalties and delayed writes, VRAM transfer | Palette decoding and component references are derived; behavior-affecting FIFO/cache/edge state is captured |
| Display/panel | Exactly one GPU-owned `DisplayMemento`: partial write buffer/index, enabled flag, complete visible frame and first-frame-after-enable state | Root `displayMemento` is null for new captures, retained only as a nullable legacy-fixture input; buses/listeners are services |
| APU/channels | Master registers/output buffer/index, channel masks/enables, frame sequencer and divider phase, pulse/wave/noise phases, length/envelope/sweep/LFSR/polynomial counters and pending clocks | Host audio sink/mixer callback is a service; waveform/output behavior is captured |
| Joypad | P1, debounced input history/lines, pending edge, SGB multiplayer transfer packet/bit/player state | Physical held buttons are intentionally session-owned and captured separately so rewind policy can differ |
| Serial port | SB/SC, internal clock phase/count, received bits and HALT-wake delay | Active endpoint reference is session topology, not machine data |
| Byte receiver endpoint | SB and bit index | `ByteReceiver` callback is a host service |
| Peer cable endpoint | SB, received-bit count and bit index | Peer object pointer is reconstructed by link topology |
| Printer endpoint | Protocol parser, command/image buffers, checksum/status/reply framing and print delay | `PrintCallback` is a host/UI service |
| GPS endpoint | Master ticks, startup beacons, UART output queue/bit delay, RX parser/parity and TAIP command | No time/network/location service is retained; emulated response data is deterministic |
| Barcode Boy endpoint | Handshake/send/receive phase and active data in the pinned memento; queued barcode and external-transfer latch in deep-owned `BarcodeBoyRuntimeState` | No callback/service is retained; pending payload arrays are cloned on capture and access |
| Four-player adapter | Shared SB/armed/connected/pending arrays, reply/transmit buffers, packet/bit/timing/rate/size/phase and restart requests | Endpoint objects and player-slot association are reconstructed from `LinkedTopologyState` |
| Infrared | RP register plus Full Changer schedule/armed/running/index/remaining phase | Physical/peer IR endpoint callback is topology/service state |
| Cartridge/battery | Mapper memento, RAM/EEPROM/flash, bank/register/mode gates, write-dirty state; memory/file battery byte buffers, clock-presence and dirty flag | Immutable ROM bytes, file path/stream and battery object identity are construction services |
| MBC3 RTC | Seconds/minutes/hours/day/control, subsecond ticks, latch snapshot, halt/overflow; pause flag/reference in `RtcRuntimeState` | Injected `TimeSource` is never captured |
| HuC3 RTC/IR | Minute/day/alarm registers, command index/flags/read latch, primitive last-second reference and RAM | Injected `TimeSource` is never captured |
| TAMA5/TAMA6 | Command registers, RAM, four RTC pages, disable/alarm state and primitive last-second reference | Injected `TimeSource` is never captured |
| SGB | Command packet transfer, multiplayer joypad state, character/background/attribute/palette/border buffers, mask and fade/animation | SGB event buses and render listeners are services |
| Cheats/rumble | Genie/Shark patch values and maps; CodeBreaker/MBC5/Makon motor state | Event-bus rumble consumers are services |
| Session | Detached machine, serial endpoint tag/state/runtime and physical held-button enum set | ROM/configuration, event bus, console and endpoint callback objects are services |
| Linked session | Authoritative frame, local player, explicit normal/four-player topology tag and fixed player-slot list of session states | Network connections, peers, event queues, histories and worker thread are controller services rebuilt around an authoritative checkpoint |

## Mapper coverage matrix

Every supported mutable mapper family is explicit and executable: `BasicRom`, `Mbc1`, `Mbc2`,
`Mbc3`/RTC, `Mbc5`, `Mbc6` RAM/flash, `Mbc7`/EEPROM, `Mmm01`, `PocketCamera`, `Huc1`, `Huc3`,
`Tama5`, `BungEms`, `BhgosMulticart`, `MakonNtOld2`, `DuzMulticart`, `Mani32kMulticart`,
`SlMulticart`, `Sintax`, `Bbd`, both `SachenMmc` modes, `WisdomTree`, and `Datel`. The matrix
captures, mutates/ticks, restores through the detached graph, and recaptures each instance. Nested
`Mbc7Eeprom` state is exercised as part of MBC7. Mapper ROM-bank masks/counts and immutable ROM
arrays are derived from the required matching ROM; all writable arrays/registers are owned state.

## Compatibility and display ownership

The legacy Java serialization shape and pinned manifest remain unchanged. Existing fixtures may
still carry the historical root display copy; restore accepts it after GPU restore. New captures set
that nullable compatibility component to null, so only `GpuMemento.displayMemento` owns the two
160x144 panel arrays. Visible frame, partial scanout/index, LCD enable and repeat behavior therefore
remain restorable without duplicate payload.

Disk snapshots, rewind, boot-state reuse, and current netplay continue to use their existing bounded
legacy/netplay adapters in this phase. The detached model is a new internal seam for Phase 2; it
does not change payload, graph, queue, frame, or work limits.

## Failure and extension policy

Unknown record/enum IDs, wrong field names/order/counts, type mismatches, invalid enum ordinals,
oversized/deep graphs, RTC-family mismatches, and endpoint/runtime mismatches are rejected before
live mutation. A serial endpoint outside the enumerated endpoint families throws
`StateCaptureException`; it is never silently serialized as null. Adding mutable production state
requires all of: a memento/runtime DTO field, registry/schema inventory update, deep-ownership rule,
and a coverage-matrix or deterministic-continuation regression.
