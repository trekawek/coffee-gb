# Machine/session state inventory and ownership contract

## Invariant established by Phase 1

`MachineState`, `SessionState`, and `LinkedSessionState` are the service-free capture/apply boundary
used by the portable StateFile v1/v2 codec. A capture owns every array and collection it exposes.
Before mutation, apply checks the hardware tag, nested record/mapper tree, invariant array dimensions,
serial endpoint and component-state root, cartridge RTC locations, runtime DTO, held input, and linked
topology against the already-configured target. Null is admitted only for audited owner/field or
array-element positions, not inferred from the non-null value's type. The adapter then reconstructs
the complete replacement and applies the semantic policy registered for each of the 91 admitted
record types. Those policies reject invalid indices, counts, capacities, command phases, and scalar
relationships before the first live mutation. If an unexpected component apply nevertheless
throws, the adapter restores the machine, both RTC locations, serial endpoint/runtime, held
buttons, and—when
linked—the other sessions and controller frame from rollback captures.

The model contains only `Int`, `Long`, `Boolean`, `Double`, `String`, explicit enum/type IDs, and
deep-owned immutable containers. It cannot represent a thread, callback, event bus, stream, file,
clock service, AWT/Swing object, or live mutable array. The format that encodes this model is
specified separately in [state-file-v1.md](state-file-v1.md) and
[state-file-v2.md](state-file-v2.md). Local slot snapshots use the minimum identity version able to
represent both profile and RTC phase semantics; protocol-v8 netplay uses only v1, and local legacy
migration remains isolated from network decoding.
The owning immutable hardware profile and exact `ClockSpec` are construction identity rather than
mutable machine data: MachineSnapshot retains the canonical ID, StateFile v1 derives that ID
from its frozen hardware/CGB0 identity fields, and StateFile v2 carries an explicit bounded
canonical ID. Both verify the complete profile before apply.
StateFile v1 coarse DMG is permanently canonical `dmg`; MGB is represented only by StateFile v2's
explicit `mgb` ID. `MachineSnapshot` likewise rejects DMG/MGB cross-restore before materialization.
Protocol v8 remains StateFile-v1-only and rejects MGB before linked construction or state writes.
The exact remaining compatibility surface and removal policy are documented in
[legacy-state-retirement.md](legacy-state-retirement.md).

The exact field-by-field inventory of all 91 admitted production record types is committed in
[state-memento-schema.md](state-memento-schema.md). The independently scanned list of all 99
production state contracts and capture owner/call-site files is committed in
[state-originator-sites.md](state-originator-sites.md). `StateTypeRegistry` is the executable type
allowlist. `StateCoverageMatrixTest` gives every mutable mapper and stateful serial peripheral an
explicit non-idle setup and compares a fixed continuation trace plus final state;
`DetachedStateTest` exercises the complete root graph, deep ownership, failure atomicity, display,
held input, endpoint runtime state, required-value null rejection, semantic cursor boundaries, and
difficult-cycle deterministic continuation. `StateInventoryTest` independently scans production
capture sites and proves every admitted record has a non-empty semantic-policy rationale.

## Supported safe points and thread ownership

There are only two supported capture/apply safe points:

1. The owning controller/emulator thread at a frame boundary, after queued topology/input events
   and history reconciliation have completed and before the next group of machine ticks. Linked
   capture includes the authoritative frame, fixed topology tag, player slots, each session, and
   event/protocol-owned P1 held buttons. A Basic/local machine keeps SGB P1-P4 in its live service;
   linked live and replay machines force that service to `RELEASED` and use only frame-owned P1.
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
| DMA/HDMA | OAM ownership/restart/source/ticks/bus samples/interrupt collision state; VRAM-DMA registers, mode, block bytes, signed wrapping cumulative source-byte progress since the source-register write, CPU arbitration, HBlank/LCD/speed/halt request history | Source/destination address-space references are wiring; source-byte progress is an emulated `int` counter used for bus/start arbitration, not an allocation or array cursor |
| GPU/PPU | VRAM banks, registers/mix/write delays, LCDC/STAT, LY/mode/dot (including the LCD-enable `-1` dot), palettes, OAM-search state/sprites, fetcher, pixel and sprite FIFOs, window/object penalties and delayed writes, VRAM transfer | Both dot machines advance their owned eight-slot LCD delay rings; the DMG timing/output FIFOs additionally own `linePixels`, `outCount`, `firstEntry`, `firstBgp`, `firstObp0`, and `firstObp1` in a primitive detached supplement because the pinned legacy record predates them |
| Display/panel | Exactly one GPU-owned `DisplayState`: partial write buffer/index, enabled flag, complete visible frame and first-frame-after-enable state | Root `displayMemento` is null for new captures, retained as a stable v1 field label and nullable legacy-fixture input; buses/listeners are services |
| APU/channels | Master registers/output buffer/index, channel masks/enables, frame sequencer and divider phase, pulse/wave/noise phases, length/envelope/sweep/LFSR/polynomial counters and pending clocks | Host audio sink/mixer callback is a service; waveform/output behavior is captured |
| Joypad | JOYP selectors, debounced input history/lines, pending edge, SGB multiplayer transfer packet/bit/mode/selected-player state | The four-slot `PlayerInputSource` is a live platform service sampled once per Joypad tick and never captured. Basic/local restore keeps current physical P1-P4 input. Linked live/replay machines force `RELEASED`; their P1 is frame-owned history and protocol v8 deliberately has no local SGB P2-P4 stream. |
| Serial port | SB/SC, internal clock phase/count, received bits and HALT-wake delay | Active endpoint reference is session topology, not machine data |
| Byte receiver endpoint | SB and bit index | `ByteReceiver` callback is a host service |
| Peer cable endpoint | SB, received-bit count and bit index | Peer object pointer is reconstructed by link topology |
| Printer endpoint | Protocol parser, command/image buffers, checksum/status/reply framing and print delay | `PrintCallback` is a host/UI service |
| GPS endpoint | Master ticks, startup beacons, UART output queue/bit delay, RX parser/parity and TAIP command | No time/network/location service is retained; emulated response data is deterministic |
| Barcode Boy endpoint | Handshake/send/receive phase and exact 30-byte active frame in `BarcodeBoyState`; exact 30-byte queued frame and external-transfer latch in deep-owned `BarcodeBoyRuntimeState` | No callback/service is retained; active data exists exactly in `SENDING`, and pending payload arrays are cloned on capture and access |
| Four-player adapter | Shared SB/armed/connected/pending arrays, reply/transmit buffers, packet/bit/timing/rate/size/phase and restart requests | Endpoint objects and player-slot association are reconstructed from `LinkedTopologyState` |
| Infrared | RP register plus Full Changer schedule/armed/running/index/remaining phase | Physical/peer IR endpoint callback is topology/service state |
| Cartridge/battery | Mapper state, RAM/EEPROM/flash, bank/register/mode gates, write-dirty state; memory/file battery byte buffers, clock-presence and dirty flag | Immutable ROM bytes, file path, atomic-writer/event-bus services and pending user-error diagnostics are host persistence state rather than emulation behavior; BasicRom battery and Datel slot presence must match the configured target |
| MBC3 RTC | Six-bit seconds/minutes, five-bit hours, day/control, subsecond ticks, latch snapshot, halt/overflow; separately tagged primary and Datel-slot pause flag/reference in `CartridgeRtcRuntimeState` | Injected `TimeSource` is never captured; both physical cartridge constructors receive the configured service |
| HuC3 RTC/IR | Minute/day/alarm registers, command index/flags/read latch, primitive last-second reference and RAM | Injected `TimeSource` is never captured |
| TAMA5/TAMA6 | Command registers, RAM, four RTC pages, disable/alarm state and primitive last-second reference | Injected `TimeSource` is never captured |
| SGB | Command packet transfer, multiplayer joypad state, character/background/attribute/palette/border buffers, mask and fade/animation; palette-map and attribute IDs are 0..3 | SGB event buses and render listeners are services; historical null `systemPalettes` rows normalize to new four-zero rows, while restored delayed captures admit only the four practical transfers and a pending picture owns a validated committed PCT payload |
| Cheats/rumble | Non-null registered Genie/Shark runtime patch lists/maps are converted to disjoint non-serializable explicit-state leaves; CodeBreaker/MBC5/Makon motor state | Event-bus rumble consumers and the four historical serializable compatibility leaves are excluded from normal state |
| Session | Detached machine, serial endpoint tag/state/runtime and canonical unique event/protocol-P1 held-button enum list (a list on input so malformed duplicates can be rejected) | ROM/configuration, event bus, console, four local SGB physical-input slots, and endpoint callback objects are services. `Gameboy.getPressedButtons()` reports effective P1 for compatibility, while session capture deliberately reads the event-owned subset. Linked sessions have no live source, and SGB slots are not linked-emulator player indices. |
| Linked session | Authoritative frame, local player, explicit normal/four-player topology tag, exactly four canonical player slots, session states and held input; four-player copies must describe one coherent shared adapter | Network connections, peers, event queues and worker thread are controller services; apply requires the same already-configured active-session shape and rebases history at the safe point |

The repeat DMG FIFO audit found no further mutable behavior state: `pixels`, `spriteFifo`,
`delayEntry`, `delayStamp`, `delayHead`, `delaySize`, and `outputTicks` remain in the pinned
component state; the six fields named above are in the detached supplement; and `display`, `lcdc`,
`registers`, and `vRamTransfer` are final reconstructed wiring.

## Mapper coverage matrix

Every supported mutable mapper family is explicit and executable: `BasicRom`, `Mbc1`, `Mbc2`,
`Mbc3`/RTC, `Mbc5`, `Mbc6` RAM/flash, `Mbc7`/EEPROM, `Mmm01`, `PocketCamera`, `Huc1`, `Huc3`,
`Tama5`, `BungEms`, `BhgosMulticart`, `MakonNtOld2`, `DuzMulticart`, `Mani32kMulticart`,
`SlMulticart`, `Sintax`, `Bbd`, both `SachenMmc` modes, `WisdomTree`, and `Datel` with a real MBC3
pass-through slot. For each family the matrix captures a non-idle setup, runs address/tick probes,
restores through the detached graph, reruns the same probes, and compares both observable trace and
final state. Dedicated mid-operation cases capture MBC6 during JEDEC unlock/ID/program sequences,
MBC7 after EEPROM write-enable and halfway through a serial write, MBC5 with a real rumble cartridge
and an active motor latch, and the outer Datel flash during program, erase, and ID modes while a real
slot cartridge is present. Each case asserts the nested phase is non-default before comparing its
observable continuation and final detached state. Mapper ROM-bank masks/counts and immutable ROM
arrays are derived from the required matching ROM; all writable arrays/registers are owned state.
Separate virtual-time tests cover MBC3 subsecond/latch/halt/pause continuation and MBC3, HuC3, and
TAMA5 battery references, including all three families in a Datel slot.

## Semantic preflight audit

`StateGraph` enforces the registered schema, target mapper/endpoint tree, invariant dimensions,
container shape, graph limits, and exact audited nullable positions. `StateSemantics` then walks the
fully reconstructed candidate and dispatches by the same stable record class names used by the
allowlist. Its constrained policies cover every scalar that is later used as an array cursor,
queue size/offset, copy length, parser bit/count, GPU/PPU/DMA phase, audio buffer/channel counter,
IR/SGB packet/schedule index, RTC field, or mapper EEPROM/flash command phase.

Clock-derived values use a staged contract. Profile-independent reconstruction admits only a
bounded, stereo-aligned Sound prefix/full-buffer shape and a nonnegative RTC subsecond phase. A v1
canonical SGB portable root first converts every MBC3 phase from its frozen 4,194,304 denominator
to the registered exact SGB numerator domain using checked rational nearest rounding. The
machine/session prepare step then checks the Sound write index and either prefix or historical full
buffer against `2 * targetClock.controllerTicksPerFrame()`, and checks every primary or Datel-slot
MBC3 phase against `targetClock.secondPhaseLimit()`. The local legacy importer and internal rewind
restore use the same target-clock stage. These checks finish before the first apply callback or live
component mutation, so a registered profile may diverge without loosening state admission or
silently changing StateFile v1 bytes.

Reachable boundary regressions exercise CH3 after a physical wave-RAM read, MBC3's 63/63/31
live-and-latched register values, Full Changer armed/running/completed phases, HDMA in its second
block, a signed-wrapped HDMA source counter, LCD enable at dot `-1`, both FIFO delay rings at
capacity, a pending DMG first-pixel palette latch, and a pending window rewind. Barcode
protocol/runtime coherence, SGB palette IDs, polynomial reload alignment, and Genie map element
types are rejected through the adapter before its live-mutation callback.

Records whose fields are deliberately not range-constrained still have an explicit policy and
rationale in that registry. Examples are raw bus/address/register latches, signed emulated clocks,
documented `-1`/minimum-value sentinels, and parent records whose only relationship-bearing values
are validated by nested records. `StateInventoryTest` requires the policy-key set to equal all 91
admitted record types, so a new state record cannot enter the model without an audited choice. Rollback
is retained for unexpected failures in legacy restore code; it is not the validation path for a
deterministically malformed detached candidate.

## Compatibility and display ownership

The importer-only Java serialization shape and pinned manifest remain unchanged. The six DMG FIFO
fields introduced after that manifest are captured once per dot machine by `DmgFifoRuntimeState`;
adding them to the compatibility-only `DmgPixelFifoMemento` would change its Java descriptor and
break the supported fixtures.
`linePixels` is the inclusive 0..160 LCD position; `outCount` is nonnegative but deliberately has
no unsound upper bound because it is bookkeeping rather than an array cursor. A pending packed
6-bit `firstEntry` is created only by the first due output and requires `outCount == 1`; an absent
entry permits any nonnegative count because the next tick clears the latch before considering
another due entry. Its three palette latches are bytes.
Existing fixtures may still carry the historical root display copy; restore accepts it after GPU
restore. New captures set that nullable compatibility component to null, so only
`GpuState.displayMemento` owns the two 160x144 panel arrays. Visible frame, partial scanout/index,
LCD enable and repeat behavior therefore remain restorable without duplicate payload.

Rewind now retains internal immutable, structurally shared `MachineSnapshot` generations described
in [rewind-machine-snapshots.md](rewind-machine-snapshots.md). Its safe-point capture consumes an
audited, short-lived record view that borrows registered live primitive arrays for synchronous
comparison; it neither calls the ordinary deep-owning component capture nor retains the borrowed view.
`ControllerState`, boot-state reuse, local slot snapshots, and netplay protocol v8 now use explicit
detached/component state; rewind alone uses `MachineSnapshot`. None of those normal paths can invoke
the historical importer. Their distinct disk and network limits do not weaken the graph, queue,
frame, or work limits.

## Failure and extension policy

Unknown record/enum IDs, wrong field names/order/counts, mapper or endpoint roots, invariant array
dimensions, required-value nulls, type mismatches, invalid enum ordinals, oversized/deep
graphs/primitive arrays/strings, invalid semantic indices/counts/phases, RTC-location mismatches,
malformed held input, and linked frame/player/topology/adapter mismatches are rejected before live
mutation. Optional SGB operation records, optional barcode/transfer payloads, historical nullable
`systemPalettes` rows (normalized to owned four-zero rows on restore), and the nullable legacy display record are explicit owner/field exceptions
rather than type-category exceptions. BasicRom battery and Datel slot presence are target identity
and cannot cross null/non-null configurations. A serial endpoint outside the enumerated endpoint
families throws
`StateCaptureException`; it is never silently serialized as null. Adding mutable production state
requires all of: a component-state/runtime DTO field, registry/schema and capture-site inventory updates,
deep-ownership rule, and a deterministic-continuation regression.
