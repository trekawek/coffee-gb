# DMG-CPU-B schematic to Coffee GB class map

This document maps the complete internal DMG circuit source in the sibling
`/home/newton/dev/dmg-schematics` checkout to Coffee GB's production model. It is an architecture
cross-reference, not a claim that Coffee GB is a gate-level transcription.

The source examined here is the clean [dmg-schematics repository](https://github.com/msinger/dmg-schematics)
at revision `02399f96e0893783c130cf6f03fad7a1148ae60a` (2026-05-18). Régis Galland and
Michael Singer are credited in its source title blocks; its README records derivation from
Furrtek's DMG-CPU-Inside work. The source is licensed
[CC BY-SA 4.0](https://github.com/msinger/dmg-schematics/blob/02399f96e0893783c130cf6f03fad7a1148ae60a/LICENSE), while Coffee GB
remains MIT. No schematic geometry, cell-level wire graph, generated RTL, or copied circuit
implementation is stored here. The repository-local `scripts/index-dmg-schematics.py` reader emits
only structural metadata, including sheet hierarchy and interface labels, and can reproduce the
inventory:

```sh
scripts/index-dmg-schematics.py /home/newton/dev/dmg-schematics --check
scripts/index-dmg-schematics.py /home/newton/dev/dmg-schematics > /tmp/dmg-schematic-index.json
```

Where this document cites SameBoy, the independent source is
[SameBoy `213a12ce`](https://github.com/LIJI32/SameBoy/tree/213a12ce93d66b105a113debd9396306066a7cfc):
`Core/memory.c` supplies the FF50 rule/readback and `Core/apu.c` supplies the CH3 output behavior.

The evidence labels used below are:

- **direct**: the Coffee class owns the same externally visible register, state, or data path;
- **composed**: several Coffee classes divide one physical sheet, or one class spans sheets;
- **projection**: behavior is calibrated to the circuit or hardware, but state/timing is represented
  differently;
- **absent**: the circuit behavior is deliberately not modeled;
- **outside DMG**: the Coffee class represents CGB, SGB, cartridge, host, persistence, debug, or
  service behavior that does not exist inside the DMG-CPU-B source.

## Complete source inventory

The checkout contains 65 KiCad sheets: 49 under `dmg_cpu_b`, 14 under `sm83`, and two
standard-cell reference/catalog sheets. The DMG hierarchy reaches 47 of its 49 sheets. The empty
`ff04_div.kicad_sch` is an unused placeholder; current DIV circuitry is in `clocks_and_reset`.
The unreferenced `sprite_store.kicad_sch` is an unused older/draft sprite-store sheet that overlaps
active part 1; the active hierarchy uses parts 1 and 2. All 14 SM83 sheets are reachable. The
catalog sheets are
`dmg_cells/dmg_cells.kicad_sch` and `sm83_cells/sm83_cells.kicad_sch`; they are library/reference
circuits, not runtime hierarchy instances.

All 82 raw `.nl` files are included by the single `netlist/Makefile`: 66 in `NETLIST_FILES` and 16
in `SM83_NETLIST_FILES`. Their declarations describe 4,756 cells, 5,114 wires, 221 type
declarations (77 DMG and 144 SM83; 214 unique spellings across the separate namespaces), 63 logical
categories, and 72 aliases. Wave RAM, boot ROM, high RAM, and two OAM banks are opaque macro cells.
The DMG top-level CPU is a macro boundary, but the same checkout separately expands the SM83 in its
14 sheets and 16 raw files. Physical analog cells and connectivity are present under the
`-codegen` condition; only generated digital RTL substitutes an `audio` black box. Consequently,
generated SystemVerilog is not a transistor-complete or analog-behavioral chip model.

The checkout also carries `dmg_cells/dmg-cpu.jelib`, `sm83_cells/sm83.jelib`, and
`dmg_cpu_b/overlay/dmg-cpu-b_overlay.svg`. The supporting `doc/wave_diagrams.html` illustrates
clock, APU, LCD, and BG/window waveforms. These layout/library, overlay, and explanatory artifacts
are acknowledged but not separately class-mapped: this audit maps logical behavior, and their
corresponding cells/nets are represented by the indexed schematic/netlist corpus.

One source-interface anomaly was found during hierarchy validation: the child
`dmg_cpu_b/oam.kicad_sch` declares `DMA_PHI`, but the OAM sheet instance in
`dmg_cpu_b/ppu.kicad_sch` has no corresponding sheet pin. The other 58 parent/child unique
pin-name sets match. This is an upstream schematic metadata issue, not a Coffee GB behavior claim.

The raw files are physical regions rather than subsystem modules. This manifest accounts for every
one; the index JSON supplies each individual filename and its category histogram.

| Raw-netlist group | Files | Cells | Wires | Contents |
| --- | ---: | ---: | ---: | --- |
| DMG metadata/glue (`defines`, `types`, `categories`, `labels`, `bus`, `port`, `pwr`) | 7 | 6 | 161 | declarations, shared signals and ports |
| DMG `io/{top,bottom,left,right}` | 4 | 78 | 49 | package pads and input/output cells |
| DMG macro memory/CPU (`cpu-mem`, `ram-col-a`, `ram-row-w`) | 3 | 55 | 79 | opaque cores/arrays and their glue |
| DMG analog (`analog-audio`, `analog-col-t`) | 2 | 54 | 26 | audio macro/physical analog and spare cells |
| DMG `top-left-col/{r,s,t}` | 3 | 207 | 158 | VRAM, external buses, boot-ROM decode |
| DMG `top-center-col/{l,m,n,p,r,s,t,u}` | 8 | 311 | 302 | timer, DMA, IF, clocks and address paths |
| DMG `top-center-row/{a,b,c,d,e,y,z}` | 7 | 189 | 163 | clocks, serial, boot ROM and wave RAM |
| DMG `bottom-left-col/{k,l,m,n,p,r,s,t,v,w,x}` | 11 | 929 | 918 | BG/window/STAT/palette/FIFO circuits |
| DMG `bottom-center-col/{a,b,c,d,e,f,g,w,x,y,z}` | 11 | 973 | 769 | OAM, sprite storage/match/priority/control |
| DMG `right-col/{a,b,c,d,e,f,g,h,j,k}` | 10 | 1,329 | 1,329 | APU, JOYP and test circuits |
| SM83 declarations and `reg`/`alu`/`dec`/`seq`/`dbus` groups | 16 | 625 | 1,160 | complete CPU-core raw model |
| **Total** | **82** | **4,756** | **5,114** | |

## DMG top level and common I/O

Across this table and the APU/PPU subsystem tables below, every reachable DMG sheet is represented.
A class in the last column is the closest behavioral owner, not necessarily a one-to-one
implementation.

| Schematic sheet | Circuit responsibility | Coffee GB owner(s) | Mapping |
| --- | --- | --- | --- |
| `dmg_cpu_b/dmg_cpu_b.kicad_sch` | Chip hierarchy, pads, shared buses | `Gameboy`, `Mmu`, `AddressSpace` | composed/projection |
| `dmg_cpu_b/cpu.kicad_sch` | SM83 wrapper, CPU bus and clocks | `Cpu`, `Registers`, `InterruptManager`, `Mmu` | composed/projection |
| `dmg_cpu_b/clocks_and_reset.kicad_sch` | Master clocks, phase clocks, divider, reset tree | `Gameboy`, `Timer`, `FrameSequencer`, `SerialPort` | projection |
| `dmg_cpu_b/ext_addr_buses.kicad_sch` | CPU/PPU/DMA address-bus arbitration | `Mmu`, `Cpu`, `Dma`, `Gpu` | projection |
| `dmg_cpu_b/ext_data_buses.kicad_sch` | Shared data buses and direction gates | `Mmu`, `AddressSpace`, `DmaAddressSpace`, `DmaCpuAddressSpace`, `DmaOamAddressSpace` | projection |
| `dmg_cpu_b/sys_decode.kicad_sch` | Address decode, boot ROM, high RAM, FF50 latch | `Mmu`, `Bios`, `BiosShadow`, `Ram` | direct/projection |
| `dmg_cpu_b/ff00_joyp.kicad_sch` | JOYP selectors, input filter, wake and IF pulse | `Joypad`, `Cpu`, `InterruptManager` | projection |
| `dmg_cpu_b/ff01-02_serial.kicad_sch` | SB/SC, internal clock, SCK/SIN/SOUT and serial IF | `SerialPort`, `SerialEndpoint`, `InterruptManager` | direct/projection |
| `dmg_cpu_b/ff05-07_timer.kicad_sch` | TIMA/TMA/TAC, selected DIV wire, overflow/reload | `Timer`, `InterruptManager` | projection |
| `dmg_cpu_b/ff0f_int.kicad_sch` | Five IF latches and source set/reset paths | `InterruptManager` plus all interrupt producers | projection |
| `dmg_cpu_b/ff04_div.kicad_sch` | Empty unused DIV placeholder | `Timer` through `clocks_and_reset` | orphaned source |

The two remaining KiCad files are reference catalogs rather than hierarchy instances:

| Catalog sheet | Content | Coffee GB owner | Mapping |
| --- | --- | --- | --- |
| `dmg_cells/dmg_cells.kicad_sch` | DMG standard-cell reference circuits | none | source reference, no runtime class |
| `sm83_cells/sm83_cells.kicad_sch` | SM83 standard-cell reference circuits | none | source reference, no runtime class |

`SpeedMode`, `Hdma`, `GbcRam`, `UndocumentedGbcRegisters`, `InfraredPort`, and the CGB portions of
`Gpu`, `Timer`, `SerialPort`, and `Sound` are outside this DMG source. SGB packet reception is also
not on the DMG die: `Joypad` combines the DMG JOYP behavior with an external ICD2-facing model.

The corresponding DMG-visible ownership is complete at the register boundary:

| Address/range | Circuit sheet | Coffee GB owner |
| --- | --- | --- |
| 0000-00FF during boot | `sys_decode` opaque boot-ROM macro and `BOOT_SEL` | `Bios`, `BiosShadow` |
| FF00 | `ff00_joyp` | `Joypad` |
| FF01-FF02 | `ff01-02_serial` | `SerialPort` |
| FF04-FF07 | clocks/reset plus `ff05-07_timer` | `Timer` |
| FF0F, FFFF | `ff0f_int`, SM83 `intr` | `InterruptManager` |
| FF10-FF26, FF30-FF3F | APU sheets | `Sound` and its channel classes |
| FF40-FF45, FF47-FF4B | PPU decode/background/window/STAT/palettes | `Lcdc`, `Gpu`, `GpuRegisterValues`, `StatRegister` |
| FF46 | `ff46_dma` | `Dma` |
| FF50 | `sys_decode` | `BiosShadow` |
| FF60 | `ff00_joyp` test-mode cone | intentionally absent |
| 8000-9FFF | VRAM interface and external buses; storage is off-die | `Gpu`, `Ram`, DMA address-space wrappers |
| C000-DFFF, E000-FDFF | External WRAM decode/buses and echo selection; storage is off-die | `Ram`, `ShadowAddressSpace`, `Mmu` |
| FE00-FE9F | OAM sheets and external buses | `Gpu`, `Ram`, DMA address-space wrappers |
| FF80-FFFE | `sys_decode` opaque high-RAM macro | `Ram` registered in `Mmu` |

Unused/unmapped addresses, external cartridge RAM/ROM control, and CGB-only registers are outside
that internal DMG register inventory. DMG work RAM at C000-DFFF and video RAM storage at
8000-9FFF are off-die; the source contains their bus decode/interface rather than their storage.
Coffee reuses the generic `Ram` class for these off-die arrays as well as the on-die HRAM/OAM/wave
storage projections. `ShadowAddressSpace` is on-die echo decode over the off-die work RAM.

## SM83 CPU core

| Schematic sheet | Circuit responsibility | Coffee GB owner(s) | Mapping |
| --- | --- | --- | --- |
| `sm83/sm83.kicad_sch` | CPU-core hierarchy | `Cpu`, `Registers`, `Flags`, `InterruptManager`, opcode classes; `Mmu`/`AddressSpace` bus boundary | composed/projection |
| `sm83/register_bank.kicad_sch` | GP/SP-PC/WZ/IDU/ALU hierarchy and buses | `Registers`, `Flags`, `AluFunctions`, `Cpu` operand/context state | composed/projection |
| `sm83/reg_gp.kicad_sch` | A, B/C, D/E, H/L and IR storage; F-bus and DAA-comparator interface | `Registers`, `Cpu`, `Flags`, `AluFunctions` | direct/projection |
| `sm83/reg_sp_pc.kicad_sch` | SP and PC storage | `Registers`, `Cpu`, `OpcodeBuilder` | direct/projection |
| `sm83/reg_wz.kicad_sch` | Internal W/Z temporary registers | `Cpu`, `Argument`, `OpcodeBuilder` | projection |
| `sm83/idu.kicad_sch` | 16-bit increment/decrement and signed-offset address unit | `AluFunctions` D16 operations, `OpcodeBuilder`, `Registers`, `Cpu` | projection |
| `sm83/alu.kicad_sch` | ALU core, flags, DAA path | `AluFunctions`, `Flags`, `BitUtils` | direct/projection |
| `sm83/alu_decoder.kicad_sch` | ALU control decode | `Opcodes`, `OpcodeBuilder`, `AluFunctions` | projection |
| `sm83/decoder1.kicad_sch` | Opcode/control decoder bank 1 | `Opcodes`, `OpcodeBuilder`, `Opcode`, `Op`, `Cpu` phase metadata | projection |
| `sm83/decoder2.kicad_sch` | Opcode/control decoder bank 2 | `Opcodes`, `OpcodeBuilder`, `Opcode`, `Op`, `Cpu` phase metadata | projection |
| `sm83/decoder3.kicad_sch` | Opcode/control decoder bank 3 | `Opcodes`, `OpcodeBuilder`, `Opcode`, `Op`, `Cpu` phase metadata | projection |
| `sm83/sequencer.kicad_sch` | T/M-cycle state, IME/EI/DI/RETI, HALT/STOP/NMI and interrupt-entry sequencing | `Cpu`, `Opcode`, `Op`, `InterruptManager`; `Gameboy`/`SpeedMode`/`Display` clock boundary | projection |
| `sm83/intr.kicad_sch` | IE storage, transparent IE&IF aperture bank, priority/vector and one-hot acknowledge | `InterruptManager`, `Cpu` interrupt/wake boundary, `Mmu` FFFF boundary | projection |
| `sm83/dbus_bridge.kicad_sch` | Internal/external CPU data-bus gates | `Cpu`, `Mmu`, `AddressSpace` | projection |

Coffee's instruction model preserves architectural register and ALU results, but it does not
instantiate the decoder product terms, transparent register buses, or persistent T1-T4 bus cycle.
In particular, an operation currently reads memory one Coffee machine-cycle later than the
corresponding SM83 transaction. Boot-DIV, PPU, interrupt, and DMA timings are calibrated around
that known skew. Fixing it is a cross-subsystem migration, not a local decoder rewrite.

## APU and analog audio

| Schematic sheet | Circuit responsibility | Coffee GB owner(s) | Mapping |
| --- | --- | --- | --- |
| `dmg_cpu_b/apu.kicad_sch` | APU hierarchy | `Sound` | composed/projection |
| `dmg_cpu_b/apu_control.kicad_sch` | NR52 power/status, APU clocks and frame taps | `Sound`, `FrameSequencer`, channel `start`/`stop` paths | projection |
| `dmg_cpu_b/apu_decode.kicad_sch` | NR10-NR52 and wave-RAM bus decode | `Sound` address dispatch | direct/projection |
| `dmg_cpu_b/ff24_ff25.kicad_sch` | NR50 volume/VIN and NR51 routing | `Sound` mixer/routing | partial |
| `dmg_cpu_b/ch1_regs.kicad_sch` | CH1 frequency, duty, length, envelope registers | `SoundMode1`, `AbstractSoundMode`, `LengthCounter`, `VolumeEnvelope` | direct/projection |
| `dmg_cpu_b/ch1_sweep.kicad_sch` | CH1 sweep timer/shift/adder | `FrequencySweep` | direct/projection |
| `dmg_cpu_b/channel1.kicad_sch` | CH1 oscillator, duty, trigger and output | `SoundMode1`, `AbstractSoundMode` | direct/projection |
| `dmg_cpu_b/ch2_regs.kicad_sch` | CH2 duty, length, envelope and frequency registers | `SoundMode2`, `AbstractSoundMode`, `LengthCounter`, `VolumeEnvelope` | direct/projection |
| `dmg_cpu_b/channel2.kicad_sch` | CH2 oscillator, duty, trigger and output | `SoundMode2`, `AbstractSoundMode` | direct/projection |
| `dmg_cpu_b/ch3_regs.kicad_sch` | NR30-NR34, CH3 trigger/status/level | `SoundMode3`, `LengthCounter` | direct/projection |
| `dmg_cpu_b/ch3_wave_ram.kicad_sch` | Wave RAM port, byte buffer, nibble mux, CH3 output | `SoundMode3` | direct/projection |
| `dmg_cpu_b/ch4_regs.kicad_sch` | NR41-NR44, envelope, trigger and divisor controls | `SoundMode4`, `LengthCounter`, `VolumeEnvelope`, `PolynomialCounter` | direct/projection |
| `dmg_cpu_b/channel4.kicad_sch` | CH4 clock divider, LFSR and output | `SoundMode4`, `PolynomialCounter`, `Lfsr` | projection |
| `dmg_cpu_b/analog.kicad_sch` | Four DACs, VIN, mixer, amplifiers and pins | `Sound`, `StereoPcmConverter`, `DcBlocker`, `SoundOutputObserver` | partial/absent |

Coffee keeps the four digital channels and a linear stereo projection. It does not accept an
external VIN signal, despite the physical NR50 VIN gates, and it does not model DAC voltage curves,
the analog mixer/amplifier, power ramps, or output-pin loading. `StereoPcmConverter` and
`DcBlocker` are host-output approximations rather than mapped cells.

## PPU, OAM, VRAM, and LCD

| Schematic sheet | Circuit responsibility | Coffee GB owner(s) | Mapping |
| --- | --- | --- | --- |
| `dmg_cpu_b/ppu.kicad_sch` | PPU hierarchy and shared control | `Gpu`, `Lcdc`, `StatRegister` | composed/projection |
| `dmg_cpu_b/ppu_decode.kicad_sch` | PPU register and cycle decode | `Gpu`, `Lcdc`, `GpuRegisterValues` | projection |
| `dmg_cpu_b/background.kicad_sch` | SCX/SCY latches and background controls | `GpuRegisterValues`, `Fetcher`, `PixelTransfer` | direct/projection |
| `dmg_cpu_b/win_detect.kicad_sch` | WY/WX compare, window start/source latch | `GpuRegisterValues`, `PixelTransfer`, `Fetcher` | projection |
| `dmg_cpu_b/bg_win_cycles.kicad_sch` | Tile-map/data fetch sequencing | `Fetcher`, `PixelTransfer` | projection |
| `dmg_cpu_b/bg_px_shifter.kicad_sch` | Background pixel load and shift bank | `DmgPixelFifo`, `Fetcher`, `PixelTransfer` | projection |
| `dmg_cpu_b/palettes.kicad_sch` | FF47-FF49 palette latches | `GpuRegisterValues`, `DmgPixelFifo` | direct/projection |
| `dmg_cpu_b/pixel_mux.kicad_sch` | BG/OBJ priority, LCDC masks, final DMG pixels | `DmgPixelFifo`, `SpriteFifo`, `Lcdc`, `Display` | projection |
| `dmg_cpu_b/ff41_stat.kicad_sch` | STAT enables/sources, LY, LYC and coincidence | `StatRegister`, `Gpu` | projection |
| `dmg_cpu_b/lcd.kicad_sch` | LCD clocks, line/frame output and mode-related latches | `Gpu`, `Display`, `GpuTimingSnapshot` | projection |
| `dmg_cpu_b/oam.kicad_sch` | OAM bus, precharge, scan and memory control | `Gpu`, `OamSearch`, `Dma`, `SpriteBug`, `Ram` | projection |
| `dmg_cpu_b/oam_mem.kicad_sch` | Two physical OAM SRAM macros | `Ram`, `Gpu`, `DmaOamAddressSpace` | opaque/projection |
| `dmg_cpu_b/sprite_y_comparator.kicad_sch` | Ten-row OAM search/Y matching | `OamSearch` | direct/projection |
| `dmg_cpu_b/sprite_store_part1.kicad_sch` | First half of ten selected sprite registers | `OamSearch`, `PixelTransfer` sprite records | projection |
| `dmg_cpu_b/sprite_store_part2.kicad_sch` | Second half of selected sprite registers | `OamSearch`, `PixelTransfer` sprite records | projection |
| `dmg_cpu_b/sprite_store.kicad_sch` | Unused older/draft sprite-store sheet overlapping active part 1 | `OamSearch`, `PixelTransfer` only through the active part 1/2 sheets | orphaned source |
| `dmg_cpu_b/sprite_x_match.kicad_sch` | OBJ-enable/X-match aggregation | `PixelTransfer` object-start logic | projection |
| `dmg_cpu_b/sprite_x_matchers_part1.kicad_sch` | First five sprite X comparators | `PixelTransfer` | projection |
| `dmg_cpu_b/sprite_x_matchers_part2.kicad_sch` | Last five sprite X comparators | `PixelTransfer` | projection |
| `dmg_cpu_b/sprite_x_prio.kicad_sch` | DMG sprite X/OAM priority selection | `OamSearch`, `SpriteFifo` | direct/projection |
| `dmg_cpu_b/sprite_control.kicad_sch` | Object fetch/control state | `OamSearch`, `PixelTransfer`, `Fetcher` | projection |
| `dmg_cpu_b/sp_px_shifter.kicad_sch` | Object bitplane load/shift bank | `SpriteFifo`, `PixelTransfer` | projection |
| `dmg_cpu_b/vram_interface.kicad_sch` | VRAM address/data arbitration and read strobes | `Gpu`, `Fetcher`, `Mmu` | projection |
| `dmg_cpu_b/ff46_dma.kicad_sch` | OAM DMA register, counter, source and grants | `Dma`, `DmaAddressSpace`, `DmaCpuAddressSpace`, `DmaOamAddressSpace` | projection |

`ColorPalette`, `ColorPixelFifo`, `TileAttributes`, `Hdma`, CGB VRAM banking, and CGB priority
rules are outside the DMG-CPU-B source. `SpriteBug` is a behavioral model fitted to physical DMG
captures; the checked-in raw netlist exposes symmetric generic SRAM macros and cannot validate the
directional bitline corruption algorithm.

The largest topological difference is deliberate: the die has one forward address/latch/shifter/LCD
path. Coffee runs one unshifted `PixelTransfer` for timing and a second renderer four dots behind,
then uses reread, refresh, rewind, and catch-up operations to reproduce hardware images. Existing
ROM results validate that projection, but it is not the physical pipeline and cannot be simplified
one local special case at a time.

## Raw netlist categories

The 63 declared logical categories classify 4,674 of the 4,756 raw cells. The remaining 82 are
spares, trivial/virtual supply and port cells, uncategorized pads/glue, or the top-level opaque CPU
and generated-audio wrappers. This table maps every declared category to the sheet/class groups
above; physical region files intentionally contain several categories.

| Raw category set | KiCad sheet group | Coffee GB owner(s) |
| --- | --- | --- |
| `sys-decode`, `bootrom`, `hram` | `sys_decode` | `Mmu`, `Bios`, `BiosShadow`, `Ram` |
| `bus-adr` | `ext_addr_buses` | `Mmu`, `Cpu`, `Gpu`, `Dma` |
| `bus-data` | `ext_data_buses`, CPU/DMA glue | `Mmu`, `AddressSpace`, CPU/GPU/DMA owners |
| `clocks` | `clocks_and_reset` | `Gameboy`, `Timer`, `FrameSequencer`, `SerialPort` |
| `joypad` | `ff00_joyp` | `Joypad` |
| `test` | distributed FF60/test/reset/bus cone across the root, `ff00_joyp`, `sys_decode`, `clocks_and_reset`, and bus/VRAM sheets | no production owner; package-test pads, shadow overrides, and FF60 test mode are absent |
| `serial` | `ff01-02_serial` | `SerialPort` |
| `timer` | `ff05-07_timer` | `Timer` |
| `int` | `ff0f_int` | `InterruptManager` |
| `apu-decode`, `apu-control` | APU decode/control and `ff24_ff25` | `Sound`, `FrameSequencer` |
| `apu-ch1`, `apu-ch2`, `apu-ch3`, `apu-ch4` | corresponding channel/register sheets | `SoundMode1`-`SoundMode4` and helper classes |
| `apu-analog` | `analog` | `Sound` host projection; physical analog/VIN absent |
| `ppu-control`, `ppu-decode` | `ppu`, `ppu_decode` | `Gpu`, `Lcdc`, `GpuRegisterValues` |
| `ppu-bgscroll`, `ppu-window` | `background`, `win_detect` | `GpuRegisterValues`, `PixelTransfer`, `Fetcher` |
| `ppu-stat`, `ppu-lcd` | `ff41_stat`, `lcd` | `StatRegister`, `Gpu`, `Display` |
| `ppu-bgfifo`, `ppu-cycles` | BG shifter and fetch-cycle sheets | `Fetcher`, `DmgPixelFifo`, `PixelTransfer` |
| `ppu-oam`, `ppu-ycomp` | `oam`, `oam_mem`, Y comparator | `OamSearch`, `Gpu`, `Dma`, `DmaOamAddressSpace`, `Ram`, `SpriteBug` |
| `ppu-pal`, `ppu-mux` | `palettes`, `pixel_mux` | `GpuRegisterValues`, `DmgPixelFifo`, `SpriteFifo`, `Display` |
| `ppu-objctl`, `ppu-xprio`, `ppu-xcomp`, `ppu-objfifo`, `ppu-objreg` | sprite control/matcher/store/shifter sheets | `OamSearch`, `PixelTransfer`, `SpriteFifo` |
| `ppu-vram`, `ppu-dma` | `vram_interface`, `ff46_dma` | `Gpu`, `Fetcher`, `Dma` |
| `reg`, `reg-bus`, `reg-ir`, `reg-a`, `reg-hl`, `reg-de`, `reg-bc`, `reg-wz`, `reg-sp`, `reg-pc` | SM83 register sheets | `Registers`, `Cpu` |
| `idu` | `idu` | `AluFunctions` D16 operations, `OpcodeBuilder`, `Registers`, `Cpu` |
| `irq`, `irq-ie` | `intr` | `Cpu`, `InterruptManager` |
| `alu`, `alu-flag`, `alu-dec`, `alu-core`, `alu-daa` | `alu`, `alu_decoder` | `AluFunctions`, `Flags`, opcode construction |
| `dec1`, `dec2`, `dec3` | decoder sheets | `Opcodes`, `OpcodeBuilder`, `Opcode`, `Op` |
| `seq`, `seq-irq` | `sequencer` | `Cpu`, interrupt microprogram |
| `dbus`, `dbus-rd`, `dbus-mreq` | `dbus_bridge` | `Cpu`, `Mmu`, `AddressSpace` |

## Reverse map: Coffee GB core production classes

The following rows cover every class under `core/src/main/java` by either naming its exact circuit
sheets or placing its entire package in an explicit no-die category. Small data records/enums
inherit the mapping of their owning row. The Swing, CLI, controller, portable-UI, and Android
modules are host front ends/services and therefore do not own DMG-die circuits. This grouping was
mechanically checked against all 293 core production Java files across 32 packages.

| Coffee GB class or class family | Schematic source | Boundary |
| --- | --- | --- |
| `Gameboy`, `AddressSpace` | DMG root, clocks, buses, CPU/PPU/APU roots | orchestration/projection |
| `Cpu`, `Registers`, `Flags`, `AluFunctions`, `BitUtils`, `Opcodes`, `Argument`, `DataType`, `Op`, `Opcode`, `OpcodeBuilder` | all SM83 sheets plus DMG `cpu` wrapper | CPU architecture/projection |
| `DebugCpuAddressSpace`, `DebugPpuAddressSpace` | CPU/PPU bus observation boundaries | debugger adapters, not die logic |
| `InterruptManager` | `ff0f_int`, SM83 `intr`/`sequencer` | IF/IE/IME/entry projection |
| `Timer` | `ff05-07_timer`, `clocks_and_reset` | timer projection |
| `Joypad`, `Button`, `JoypadButtonMask` | `ff00_joyp` | DMG electrical path plus host/SGB extensions |
| remaining `joypad` input-source, snapshot, timeline, and event classes | none beyond the `Joypad` port boundary | host input/replay services |
| `SerialPort`, `NaiveSerialPort`, `ClockType` | `ff01-02_serial`, clocks | on-die SB/SC state and clock projection |
| `SerialEndpoint` | serial pins at the `ff01-02_serial` boundary | external-device/pin contract; implementations are outside the die |
| `Sound`, `FrameSequencer`, `AbstractSoundMode`, `SoundMode1`-`SoundMode4`, `LengthCounter`, `VolumeEnvelope`, `FrequencySweep`, `PolynomialCounter`, `Lfsr` | all APU sheets | digital APU projection |
| `StereoPcmConverter`, `DcBlocker`, `SoundOutputObserver` | `analog` only at functional boundary | host approximation, not analog transcription |
| `Gpu`, `Lcdc`, `StatRegister`, `GpuRegister`, `GpuRegisterValues`, `Mode`, `GpuTimingSnapshot` | PPU root/decode/STAT/LCD/register sheets | control projection |
| `Fetcher`, `PixelTransfer`, `DmgPixelFifo`, `SpriteFifo`, `PixelFifo`, `IntQueue`, `OamSearch` | PPU fetch/shifter/sprite sheets | data-path projection |
| `GpuPhase` | PPU sequencing sheets | Java phase vocabulary for the projection |
| `HBlankPhase`, `VBlankPhase` | none as active runtime owners | retained public/state-registry compatibility types; no current production references |
| `Display` | PPU `lcd`/`pixel_mux` output boundary | host LCD sink |
| `SpriteBug` | OAM sheets plus hardware-fitted external captures | behavioral compatibility model |
| `VRamTransfer` | none | SGB ICD2-facing rendered-pixel/tile capture service, despite its historical name |
| `Bios`, `BiosShadow`, `Mmu`, `ShadowAddressSpace` | `sys_decode` and external buses | decode/direct projection; echo decode fronts off-die WRAM |
| `Ram` | opaque on-die HRAM/OAM/wave macros where used; no DMG die storage for Coffee's WRAM/VRAM instances | generic storage reused across on-die projections and off-die arrays |
| `Dma`, `DmaAddressSpace`, `DmaCpuAddressSpace`, `DmaOamAddressSpace` | `ff46_dma`, OAM, external buses | projection |
| `OamEchoRam` | only the post-OAM decode boundary is DMG-relevant | model-dependent FEA0-FEFF projection; writable RAM/alias behavior is CGB-only |
| `SpeedMode`, `Hdma`, `GbcRam`, `UndocumentedGbcRegisters`, `ColorPalette`, `ColorPixelFifo`, `TileAttributes` | none | outside DMG: CGB hardware |
| `InfraredPort`, `InfraredEndpoint`, `FullChanger`, `Peer2PeerInfraredEndpoint` | none | outside DMG: CGB/host device |
| `SuperGameboy`, `SgbDisplay`, `Background`, `Commands`, `DefinedPalettes` | none | outside DMG: SGB/SNES-side hardware |
| remaining serial peripherals/helpers and `serial.mobile` | only `SerialEndpoint` meets the on-die port | external accessories/services |
| every class under `memory.cart` (mappers, RTC, battery, camera, ROM/source, and archive helpers) | only cartridge-bus pads at DMG root | outside die/cartridge/host persistence |
| `hardware` profiles and `GameboyType` | none | model configuration and non-DMG variants |
| `debug`, `events`, `genie`, `rumble`, `persistence`, `memento`, and `state` packages | none | debugger, services, cheats, host I/O, persistence |
| `Dumper`, `InputStreams` | none | host utilities |

## Circuit/model differences found by this audit

These are separated from ordinary abstraction differences. “Circuit mismatch” means the checked
DMG-B source has a concrete different dependency or transition. It does not by itself outrank
physical measurements, hardware-verified ROMs, or a known game regression.

| Area | Circuit | Coffee GB | Classification and action |
| --- | --- | --- | --- |
| FF50 boot disable/readback | Sticky `TEPU` samples `D0 OR TEPU` only on an FF50 write; D0 reads as `TEPU` | Any FF50 write disabled the boot ROM and FF50 always read `FF` | direct DMG-B mismatch; corrected to sticky D0 and `FE`/`FF` readback, with the CGB rule independently corroborated by SameBoy |
| Raw IF reset | `~RESET2` clears all five FF0F latches, so the pulled-up register reads `E0` | `InterruptManager` construction carries the SKIP/post-boot `E1` preset | raw-reset/post-boot conflation; authentic boot now drives the existing FF0F boundary to `E0` before its first tick |
| Raw APU power | Reset clears NR52 master `HADA`; its complement holds NR50/NR51 reset, giving `00/00/70` | `Sound` construction carries the SKIP/post-boot `77/00/F0` preset | raw-reset/post-boot conflation; authentic boot now drives NR52 off before its first tick |
| JOYP IF | Four 1 MHz stages sample the **aggregate** selected P10-P13 low level; IF rises when the current and three-edge-old samples are both high | Four histories advanced every 4.19 MHz tick and each filtered line had its own edge | direct topology/phase mismatch; corrected with an executable cone and exhaustive state test |
| JOYP wake | A separate earlier latch observes the aggregate low level | STOP polls readable FF00 directly | similar intent, different phase; do not merge with the IF correction |
| DMG STOP release | Sequencer latch `ZUMN` is reset only by reset or `ZWLM`/WAKE from the JOYP `AWOB` latch; ordinary IF has no path | IME plus enabled Timer/Serial IF could dispatch directly from `STOPPED` and re-enable LCD | direct DMG wake-source mismatch; corrected while retaining the separately calibrated CGB policy |
| CH3 inactive output | Every CH3 output bit is AND-gated by `CH3_ACTIVE` | A disabled channel could return retained `lastOutput` while DAC remained on | direct circuit and SameBoy mismatch; corrected, with a bounded game-level risk check |
| CH3 trigger sample | Restart resets nibble select, exposing stale buffer high nibble at current NR32 level | Trigger reset position but left prior `lastOutput` until the first frequency event | direct circuit and SameBoy mismatch; corrected at both 2 MHz phases |
| VIN/analog | Fifth VIN input and voltage-domain DAC/mixer/amplifier exist | Four-channel linear PCM only | deliberate absent feature |
| Clock/reset power-up | Oscillator, clock buffers, reset latches and ripple stages establish electrical phase | Constructors and profile-specific DIV/PPU presets establish a calibrated phase | deliberate projection; absolute reset propagation is not modeled |
| Shared buses | Tri-state/open-drain drivers, precharge, keepers and multiple simultaneous masters | `Mmu` indexes the first accepting `AddressSpace`; collision wrappers add selected exceptions | architectural projection; a persistent resolved-bus fabric remains future work |
| NMI/test hardware | An NMI pad and FF60/test/ECO cone exist in the raw source | No production owner | deliberately absent undocumented/test behavior |
| SM83 register reset | Reset seeds physical PC latches to `FFFF`; the first fetch/IDU path presents `0000`, while most GP/SP/WZ/IR/flag cells have no reset fanout | Constructors expose zeroed architectural registers and PC=`0000` directly | externally equivalent boot entry but structurally different; compare the first address/fetch phase, not an instantaneous register scalar |
| CPU bus timing | Persistent T1-T4 bus/control cycles | Atomic operation callbacks, with known one-M-cycle-late reads | architectural projection debt |
| CB-prefix restore | Physical IR storage and `TABLE_CB` sequencing distinguish a fetched prefix from a decoded second byte | Restore decoded stale `opcode2` even when the saved phase was still waiting for that byte | released-state projection bug; corrected by retaining the pending decoder phase without changing the state record |
| Timer/IF | Ripple counter, sampled overflow/reload stages, local set/reset IF latch | Semantic countdowns plus scheduler-aligned request suppression | calibrated projection; prior local rewrite was falsified by missing shared phase |
| Interrupt selection | Transparent pending bank closes before T4; held owner drives ACK and vector | Callback/state projections and peripheral forecasts | calibrated scheduler debt; must migrate as one sub-cycle island |
| HALT bug | HALT decode suppresses its own IDU increment; delayed HALT feeds the sleep latch | Architectural outcome is modeled through semantic CPU state | outcome-compatible, internal control differs |
| DMG pixel pipeline | One forward source/fetch/latch/shifter/LCD path | Two `PixelTransfer` timelines plus refresh/rewind/catch-up | calibrated but topologically different |
| LCDC.5 window disable | Source resets asynchronously; at most committed flight retires | Delayed renderer launches an additional post-reset window map transaction | concrete internal mismatch hidden by output-equivalent repair |
| LCDC.1 object disable | Future match/output mask changes immediately; committed byte/shifter flight retires | Three-dot catch-up repays duplicated-timeline phase debt | local mechanism differs; deletion alone fails a hardware image |
| OAM corruption | Physical direction/bitline behavior is below the desired abstraction | `SpriteBug` implements hardware-fitted formulas; raw model uses symmetric SRAM macros | raw source cannot validate this behavior |
| CH1 restart/sweep | Active status is downstream of the restart/adder request cone | retained production now ignores compatibility `wasActive` timing input | corrected, DMG-grounded; CGB differential only |
| CH4 trigger timing | Raw write/clock/retained phase selects restart alignment | Semantic countdown/alignment cases | unresolved projection; faithful and lean replacements failed 8/13 CH4 SameSuite ROMs |

## Bounded corrections retained by this audit

Six production slices were small enough to correct without importing a gate simulator or changing
a released state record.

### FF50 boot-ROM disable

In the flattened source, `SATO` combines CPU `D0` with the retained `TEPU.Q`; the result drives
`TEPU.D`, while `TUGE` supplies the FF50-write clock. The DMG equation is therefore
`TEPU.next = TEPU.Q OR D0`, not “disable on any write.” `SYPU` exposes the retained latch on D0,
so FF50 reads as `FE` while the boot ROM is mapped and `FF` after it is disabled. `BiosShadow` now
implements both behaviors. CGB is outside this schematic corpus, but pinned SameBoy independently
uses the same sticky-D0 rule and `FE`/`FF` readback for every model. SKIP boot writes one explicitly.

The state record remains exactly one `isEnabled` bit.

### Raw reset versus post-boot presets

`~RESET2` clears the five SoC IF latches `LOPE`, `LALU`, `NYBO`, `UBUL`, and `ULAK`; their pulled-up
FF0F read therefore starts at `E0`, not the VBlank-posted `E1` used after boot. The same reset tree
clears the NR52 master latch `HADA`. `HADA.~Q` propagates through `JYRO`/`KEPY` and holds NR50/NR51
reset, yielding `FF24=00`, `FF25=00`, and `FF26=70` before the boot ROM powers the APU.

Coffee's component constructors intentionally remain convenient post-boot defaults for direct
fixtures and SKIP bootstrap. `Gameboy` now separates the full-machine boundary: NORMAL and
FAST_FORWARD drive FF0F=`00` and NR52=`00` before the first CPU tick, while SKIP retains
`E1/77/00/F0`. The DMG values come directly from the reset cones. Pinned SameBoy's zeroed machine
reset and APU initialization independently corroborate the same pre-boot boundary for CGB. No
component field, constructor, public API, or state record changed.

### CB-prefix decoder phase on restore

The SM83 register bank retains an eight-bit instruction register, while the sequencer's
`TABLE_CB` phase distinguishes a fetched `CB` prefix from a decoded extended opcode. Coffee already
retains the equivalent `EXT_OPCODE` phase, but restore previously rebuilt `currentOpcode` from the
still-stale `opcode2` field. A checkpoint taken immediately after the prefix therefore installed
`CB 00`; when execution resumed, the real second byte was read but could not replace that non-null
placeholder. Restore now leaves the decoder empty only for `opcode1=CB` in `EXT_OPCODE`, allowing
the following byte to select the instruction exactly as uninterrupted execution does. STOP's
separate two-byte path is unchanged. A regression captures at the prefix boundary and proves that
`CB 11` executes `RL C`, not `RLC B`; the public API and `CpuState`/`CpuMemento` shapes are unchanged.

### JOYP interrupt qualifier

The physical pad-low inputs first lose their identity in the `KERY` OR gate. `BATU`, `ACEF`,
`AGEM`, and `APUG` then sample that one signal on BOGA/`CLK_1MHz`; `ASOK = BATU AND APUG` clocks
the joypad IF latch. This has three consequences that the former implementation missed:

- histories advance once per four master ticks, not once per master tick;
- pressing a second selected key while one remains held cannot retrigger the aggregate line; and
- the two middle receiver samples need not be low, because ASOK compares only the current and
  three-edge-old samples.

`Joypad` retains the existing four packed per-line history field and ORs its nibbles to reconstruct
the single physical aggregate receiver. A test-only gate cone models the four simultaneous DFF
captures and the separate transparent `AWOB` wake latch. Tests exhaust all 256 eight-edge aggregate
sequences and all 65,536 packed histories by 16 input vectors. The focused Joypad/SGB suites pass
62/62, the core unit suite passes 1,584 tests with eight skipped, and the GBC-HW profile passes
221/221. The shared CGB behavior is therefore a tested fit, not a topology claim; the DMG sheets do
not establish the CGB receiver. SGB/SGB2 also share this receiver model around their external ICD2
protocol, without a separate sourced receiver trace.

The released state-record shape is unchanged, but the packed history's temporal unit changed from
one master tick to one 1 MHz receiver edge. Current snapshots and restores are exact because the
record also retains the master-grid `tick` phase. A partially settled snapshot written by an older
Coffee GB build has no four-edge DMG history from which to reconstruct the new receiver exactly;
it loads structurally and treats the stored nibbles as the best available receiver state.

STOP wake remains a bounded difference. Hardware drives CPU wake through the earlier transparent
`AWOB` latch, while Coffee samples the readable FF00 level directly. That phase should move only
with an explicit common clock fabric, not be folded into the IF correction.

### DMG STOP wake ownership

The SM83 sequencer retains STOP in `ZUMN`. Its only functional clear is `ZWLM`, whose non-reset
input is the CPU WAKE port; in the DMG wrapper that port is driven solely by JOYP's transparent
`AWOB` latch. Timer, Serial, and the other maskable IF sources have no path to that clear. Coffee
previously considered `STOPPED` an interrupt-acceptance state, so IME plus an enabled IF bit could
start dispatch and re-enable the LCD without a P10-P13 line going low. DMG interrupt acceptance is
now blocked until the existing JOYP-low path first releases STOP. Focused tests hold enabled Timer
and Serial requests across several machine cycles, then separately prove that a real low JOYP line
wakes and restores the LCD. The CGB interrupt-wake path is intentionally unchanged: CGB is outside
this schematic corpus and retains its existing ROM-calibrated policy. SGB/SGB2 use the DMG-family
gate because their handheld CPU is non-CGB; no separate SGB STOP receiver trace was available.

### CH3 output ownership and restart projection

In `ch3_wave_ram`, `BARY`, `BYKA`, `BOPA`, and `BELY` AND every digital CH3 output bit with
`CH3_ACTIVE`. `CH3_RESTART` also resets `EFAR`, the wave-nibble selector, so the stale sample
buffer's high nibble reaches the output immediately through the current NR32 shift. A pinned
SameBoy source cross-check independently implements both transitions. `SoundMode3` now returns
zero when the active latch clears and reprojects the stale high nibble/current volume at trigger.

The focused CH3 tests pass 8/8; Blargg individual passes 46/46 (including both 12-case DMG/CGB
sound groups), and SameSuite passes 77/77. The historical retained-output workaround was exercised
with an authorized local 3D Pocket Pool run rather than dismissed: during a scripted 1,800-frame
run it encountered 195,446 master ticks with NR30 on and CH3 inactive. After Coffee's real
48 kHz/DC-block output path the aggregate absolute-sample change was 0.1233% and squared-energy
change 0.0374%.
That bounds this replay, but it is not a listening test or proof that every route through the game
is unaffected.

## Final verification

The combined tree was verified after all six corrections, rather than by adding isolated-worktree
results. The unit and integration reports contain no failures or errors:

| Suite | Result |
| --- | ---: |
| Core unit tests | 1,584 run, 8 skipped |
| Controller unit tests | 904 run, 2 skipped |
| Mooneye + dmg-acid2 + cgb-acid2 | 132/132 |
| Blargg aggregate + individual | 54/54 |
| SameSuite + Mealybug strict images | 103/103 |
| Gambatte hardware + GBMicrotest | 5,156/5,156 |
| GBC hardware + misc-gb + Daid | 247/247 |
| RTC3 + MBC30 + cgb-acid-hell + Strikethrough + CasualPokePlayer + BullyGB | 15/15 |

The integration rows total 5,707/5,707. They establish regression safety for the bounded Coffee GB
changes; they do not upgrade the schematic source into CGB, SGB, analog-timing, or physical-silicon
evidence. Relative to the pre-audit baseline, `javap -public -s` is identical for all five changed
production classes, and `javap -p -s` is identical for their ten `State`/`Memento` records. The
behavioral corrections therefore do not change the public Java descriptors or released record
component order.

## Architectural conclusion

The mapping supports a hybrid architecture, not a whole-chip simulator. CPU bus intent, clock
selection, IF latches, DMA ownership, and the PPU control/front-end are the places where physical
storage and edge ownership would delete the most semantic forecasting. ALU math, pixel values,
waveforms, RAM contents, and host output remain better as behavioral data planes.

The source is strongest for topology and relative ordering and weaker for absolute delay. Memories
remain opaque macros. Analog connectivity is present, but it is not a digital behavioral oracle and
the generated RTL replaces it with a black box. CGB, SGB, cartridge logic, and peripherals require
separate evidence. Production changes should therefore satisfy all of these gates:

1. a bounded circuit cone names the actual inputs, storage and outputs;
2. an executable test distinguishes it from the old semantic dependency;
3. hardware-verified ROMs or an independent emulator do not contradict it;
4. public API and released state shapes remain compatible, or a migration is explicit; and
5. the change deletes more live timing/provenance/repair complexity than it adds.

The deeper signal-fabric proposal, previously executed external traces, rejected cuts, and full
regression baseline remain in `signal-driven-core.md`, `signal-driven-core-experiments.md`, and
`signal-oracle-repro.md`.
