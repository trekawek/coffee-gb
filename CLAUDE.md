# CLAUDE.md — coffee-gb agent knowledge base

coffee-gb is a Game Boy (DMG) / Game Boy Color (CGB) emulator in Java (Maven,
modules: `core`, `swing`, `controller`, ...). The accuracy work lives on the
**`claude-compat`** branch. This file serializes everything needed to continue
that work without re-deriving it.

## Current status

**The whole test battery is green — no known failures** (as of commit 99f0005):

- mooneye acceptance + misc: all pass, including `intr_2_mode0_timing_sprites`
  (63 sprite-stall cases, T-exact), `boot_div-cgbABCDE` *and* `boot_div-cgb0`
  (different CGB revisions, selected via `GameboyConfiguration.setCgb0Revision`),
  `boot_hwio-C`, `hblank_ly_scx_timing-GS`, `lcdon_timing-GS`.
- blargg: cpu_instrs, instr_timing, mem_timing-2, halt_bug, dmg_sound-2 12/12,
  cgb_sound 12/12, oam_bug-2 8/8, interrupt_time.
- dmg-acid2, all unit tests.

The derived hardware models are documented in `doc/derived/` (ppu-stat-model.md,
cpu-interrupt-model.md, apu-model.md) — **the code implements those specs; read
them before touching Gpu/StatRegister/Cpu/Sound.**

## Build & test loop

- Use `/opt/maven/bin/mvn` in scripts (PATH lacks mvn in non-interactive shells).
- Run java/mvn from the repo root; use **absolute paths in classpaths** (relative
  classpath entries misbehaved under zsh in this environment).
- Full battery (each ~1-2 min):
  ```
  mvn -pl core test                            # unit
  mvn -pl core test -Ptest-mooneye             # all mooneye suites
  mvn -pl core test -Ptest-dmgacid2
  mvn -pl core test -Ptest-blargg-individual
  mvn -pl core test -Ptest-blargg              # aggregate roms
  ```
- Fast iteration mains in `core/src/test/.../integration/support/`:
  - `MooneyeMain <roms...>` — prints PASS/FAIL + register dump + test output
    (mooneye protocol: pass = B,C,D,E,H,L = 3,5,8,13,21,34; magic `LD B,B`+0x40
    loop; failure register dump shows expected/actual tables).
  - `BlarggMain <roms...>` — memory protocol ($A000 status, sig DE B0 61, text
    at $A004); **selects DMG/CGB from cartridge header byte 0x143** (bit 7).
  - `TraceMain`, `PpuProbe`, `BootProbe <rom> [cgb]`, `OamProbe`, `OamDump`,
    `SpriteStallProbe` (measures emergent mode-3 sprite stalls; expected values:
    1 sprite at screen x on a tile boundary = 11 T, offsets 1..4 → 10..7 T,
    5..7 → 6 T, +6 T per chained same-x sprite).
  - All mains need `System.exit(0)` — EventBus threads are non-daemon and hang
    the JVM otherwise.
- Classpath for direct java runs:
  `mvn dependency:build-classpath -pl core` output + `core/target/classes` +
  `core/target/test-classes` (absolute paths).
- Test runners: `MooneyeTestRunner` picks CGB for `*-C.gb`/`*-cgb*` roms, CGB0
  revision for `*-cgb0*`, NORMAL boot for `boot_*` roms, SKIP otherwise.

## The timing model — calibrated constants and their interlocks

These constants were pinned by hardware-verified tests. **They interlock; do not
change one without re-running the full battery.**

### Clock domains and phase

- `Gameboy.tick()` = 1 T-cycle (4.19 MHz). CPU works only every 4th tick
  (`Cpu.clockCycle`, a free-running global mod-4 counter, never re-anchored).
  All memory reads/writes therefore happen on one global machine-cycle phase.
- Our CPU executes a memory-read op **one M-cycle late**: `LDH A,(a8)` samples
  the bus at tick +12 of the instruction, `LD A,(HL)` at +8. This is a known
  quirk; the DIV presets and PPU anchors are calibrated around it. If you ever
  fix read timing to the hardware M-cycle, *every* preset below must be
  re-derived.
- **PPU line grid is machine-cycle-phase-locked**: the LCD-enable line starts at
  the LCDC write itself and is **455 ticks** long (normal lines 456); the
  power-on grid starts `ticksInLine = 1`. Result: the boot/SKIP grid and any
  re-enabled grid share one phase. This equality is what makes the
  mode2-interrupt→STAT-poll distance identical between tests that re-enable the
  LCD and tests that don't — it is pinned to the T by
  `intr_2_mode0_timing_sprites` vs `intr_2_mode0_timing` + `lcdon_timing-GS`.

### PPU (see doc/derived/ppu-stat-model.md for the full spec)

- Mode 3 is a T-exact dot pipeline (SameBoy-style) in `PixelTransfer`/`Fetcher`:
  1 pixel pop + 1 fetcher state per T; 7 fetcher states with the push completing
  in the same T as the last data read; line starts at `position = -16` with 8
  junk pixels; SCX alignment during positions -16..-9; discards at -8..-1.
- Object fetch: waits until fetcher ≥ `GET_TILE_DATA_HIGH_T2` **and** FIFO
  non-empty (`max(0, 5 - state)` T), then fixed 6 T. Sprites merge into a
  separate 8-px `SpriteFifo` popped in lockstep with the bg FIFO (left-edge
  clipping emerges from discarded pops; overlay priority: transparent-fill for
  DMG, oam-index for CGB).
- Pixel end `E = 248 + SCX%8 + stalls`; **visible STAT mode 0 = E+1**;
  **mode-0 STAT interrupt line = E+4** (3 T after visible; no quantization).
- LY increments (visible) at tl=452 of the previous line (451 on the firstLine);
  coincidence re-latched at tl=0 (+ tl=8); mode-2 int is a 4-T pulse at tl<4
  (also line 144); STAT int is one level line, IF on rising edge only (this
  yields stat_irq_blocking for free); DMG STAT-write glitch = all enables act 1
  for a moment.
- firstLine (LCD enable): no OAM scan/mode-2 int; mode reads 0 until tl=79;
  OAM/VRAM open until then; end-of-line events at 451 instead of 452.
- OAM bug (`SpriteBug`, ported from SameBoy memory.c): write corruption
  `((a^c)&(b^c))^c` + row copy; read corruptions row-dependent (secondary for
  rows≡2 mod 4, tertiary/quaternary for rows≡0 mod 4 with specials at 4/8/12);
  PUSH = 3 write corruptions (extra internal SP cycle via
  `OpcodeBuilder.extraPushCycle`); scan window tl 452 (prev line) to 72;
  INC-class checks fire 1 M-cycle early, pop/push/ldi shifted −4; corruption
  also happens on the LCD-enable line.

### CPU

- Halted CPU behaves exactly like NOPs (wake = same timing as running state).
- EI commits when HALT executes (`ei; halt` → IME=1, no halt bug).
- Interrupt dispatch = 5 M-cycles; RST has an internal delay before the pushes.
- `Mmu.indexSpaces()` caches `accepts()` results **once at construction** — an
  AddressSpace must accept its full range unconditionally and gate access inside
  getByte/setByte (this bit us with the Gpu OAM lock).

### Timer / DIV / boot

- Internal divider: `Timer.presetDiv()`; **power-on presets: DMG 4, CGB 12,
  CGB0 536** (`Gameboy` ctor, non-SKIP branch); SKIP-boot presets (value at
  PC=0x100): DMG 0xABCC, CGB 0xB644.
- These are calibrated against mooneye boot_div tests **with our late-read
  quirk**; SameBoy hardware truth: DIV counter at 0x100 = 0xABCC (DMG),
  0x2678 (CGB-E). Our CGB boot takes 13,051,494 ticks with the bundled
  authentic cgb_boot.bin.
- Frame sequencer: falling edge of div bit 12; **bit 14 in CGB double speed**
  (our div advances +2/tick in double speed; hardware taps bit 13 of its
  counter). This keeps the FS at 512 Hz real time — it's what blargg
  interrupt_time's `get_cpu_speed` measures (races a loop against length
  expiry).
- Serial clock: DIV-derived, tap leads the counter by +4, bit 8 (CGB fast:
  bit 3). SC (FF02) bit 1 (CGB speed bit) reads 1 at power-on.

### APU

- Length counters: extra-clock rules in `LengthCounter.setNr4` (enable rising in
  first half clocks; trigger with len==0 reloads max, max-1 if enabled+first
  half). CH3 frequency at the 2 MHz lattice (`divCounter & 1`); first
  post-trigger wave advance fetches (opens the CPU window) but plays the stale
  buffer; wave corruption on retrigger with `freqDivider <= 1`.
- **Wave DAC re-enable (NR30) must NOT touch the length counter** — it only
  rewinds the wave position (cgb_sound 03-trigger #11). `start()` (APU power-on)
  is the only place that resets the wave length on CGB.
- DMG-only: length registers writable while the APU is off.

### CGB specifics

- KEY0 (FF4C): boot ROM writes it to enter DMG-compat mode for non-color carts
  (`value & 0x0C != 0`); state lives in `SpeedMode.dmgCompat` (gated on
  `BiosShadow.isBootFinished()`); KEY1 and SVBK read 0xFF in compat mode; SKIP
  boot derives it from header 0x143 (`Rom.GameboyColorFlag.NON_CGB`).
- `boot_div-cgb0` vs `boot_div-cgbABCDE`: different CGB revisions with different
  boot DIV (cgb0 = cgbABCDE expectations +0x200); both pass via the revision
  config.

## Oracle & debugging techniques (the decisive methods)

1. **SameBoy as ground truth**: clone https://github.com/LIJI32/SameBoy, compile
   the Core headless:
   ```
   gcc -O2 -I sameboy-src -o sb_run harness.c sameboy-src/Core/*.c \
       -DGB_INTERNAL -D_GNU_SOURCE -DGB_VERSION='"0.16"' -w -lm
   ```
   Use the bundled authentic boot ROMs from `core/src/main/resources/bios/`.
   `GB_run` returns 8 MHz cycles — **halve for T-cycles**. With `GB_INTERNAL`,
   struct fields (`gb.div_counter`, `gb.pc`, `gb.mbc_ram`) are accessible.
   SameBoy's `display.c` mode-3 loop is the reference for the pixel pipeline;
   `memory.c` for the OAM bug and IO register masks; `apu.c` for length/trigger.
2. **Blargg result text without cart RAM**: read the BG tilemap at 0x9800 —
   blargg's font tiles are ASCII-indexed, so the tilemap *is* the text.
3. **Extract expected values from test ROMs**: mooneye tests embed assertion
   blocks (`LD A,$xx / LDH (FF8x),A` after the register dump stores) — a short
   Python disassembly of the ROM yields the hardware-expected values (used for
   boot_div and boot_hwio). Test sources: clone
   https://github.com/Gekkio/mooneye-test-suite and
   https://github.com/retrio/gb-test-roms (blargg, with sources).
4. **Bisecting a timing failure**: instrument reads/writes with a
   `-D`-flag-gated `System.err.printf` in the register class (Sound, Timer,
   StatRegister), print `ticksInLine`/`line`/div; correlate against the test
   source's instruction-path cycle counts. Poll grids are 4 T (nop-tuned), so a
   test pins an event to a 4 T window; two tests with different poll paths pin
   it exactly.
5. When a test's measured event is invariant to a change you expected to matter,
   suspect a *compensating anchor* (grid phase, read-sampling offset, boot
   duration) rather than the event itself.

## Controller Agent API (programmatic instrumentation)

`eu.rekawek.coffeegb.controller.Agent` provides automated debugging of ROMs:

- Execution: `tick()` (one M-cycle), `step()` (one instruction),
  `runUntilFrame(maxTicks)`, `runTicks(n)`.
- State: `getRegisters()`, `getByte(addr)` / `getMemory(addr, len)`,
  `writeMemory(addr, value)`, `getRomBank()`, `getCpuState()`,
  `isImeEnabled()` / `getIF()` / `getIE()`.
- Media/input: `getFrame()` (BufferedImage), `getAudio()`,
  `pressButton(b)` / `releaseButton(b)`.
- Debugging: `disassemble(address)`.

Run Kotlin test scripts in `controller/src/test/java` via
`mvn exec:java -pl controller -Dexec.mainClass=... -Dexec.classpathScope=test`,
or direct `java -cp` with a generated classpath if Maven caching gets stale.

Case study: *Zerd no Densetsu* was fixed by suppressing the spurious STAT
interrupt on a `0xFF41` write while LY=LYC already holds (hardware misses that
rising edge) — see `StatRegister`.

## Codebase gotchas

- Memento pattern everywhere (save states): every stateful class implements
  `Originator<T>` with a private record memento — **add new fields to the
  memento** or save states silently corrupt.
- `Gameboy.tick()` order: timer → cpu (or hdma) → dma → sound → serial → joypad
  → gpu → statRegister. A CPU read at tick T sees GPU state from tick T-1.
- `GameboyConfiguration` bootstrap modes: NORMAL (run boot ROM), SKIP (preset
  registers/DIV), FAST_FORWARD.
- Double speed: `div += 2` per tick, DMA `648/speed`; the PPU is *not* slowed
  relative to ticks (a known simplification — CGB double-speed PPU timing is
  not hardware-exact).
- blargg cgb_sound/dmg_sound "same" subtests are different builds (the CGB one
  defines `CGB_02` and adds tests); don't assume binary equality.

## Game-level debugging (issue work)

- **Headless screenshots**: `ShotMain <rom> <outdir> <frames> <shot1,shot2,..> [f:BUTTON[:dur],..]`
  (core test scope) — runs a ROM with scripted input and writes PNGs. SGB variant and a
  FAST_FORWARD variant live in the scratchpad pattern; SameBoy equivalents (`sb_shot`,
  `sb_shot_cgb`, `sb_sgb`, `sb_audio`) compile from the SameBoy clone for side-by-side
  reference frames/audio. Note: our FAST_FORWARD boot doesn't emit boot frames, SameBoy
  does — offset scripts by ~335 (DMG) / ~186 (CGB) frames when comparing.
- **Audio comparison**: dump our samples (1/tick stereo bytes via SoundSampleEvent),
  downsample to 48 kHz, FFT per 0.25 s window, compare dominant-note sequences with
  SameBoy's `GB_apu_set_sample_callback` output.
- **State diffing**: when a game diverges, diff WRAM/HRAM against SameBoy at the same
  game-frame — the culprit variable pops out (few diffs).
- Games found relying on subtle behaviors: Altered Space (single LYC=0 STAT int per
  frame across the 153->0 transition), mezase/GB-Video (timer phase-locked to the line
  grid -> needs FAST_FORWARD boot), Super Snakey (PCT_TRN before CHR_TRN -> border
  re-render), ZAS (30 Hz flicker -> LCD ghosting display option), Kirby (MBC7 EEPROM
  dirty flag + 256-byte save).
- Mappers now implemented: MBC1(+M), MBC2, MBC3(+RTC), MBC5, MBC6(+flash), MBC7(+EEPROM,
  accelerometer), MMM01, HuC1, HuC3(+RTC), TAMA5(+TAMA6 RTC).

## Mealybug Tearoom status (mid-mode-3 register writes, DMG)

`mvn -pl core test -Ptest-mealybug` — 24 DMG tests in
`core/src/test/resources/roms/mealybug/` (expected images = DMG-CPU B photos,
DMG-blob where no CPU B image exists; the `*2` ROM variants are CGB-only).
Compare via `ImageTestRunner` (stops at `LD B,B`). Not in CI yet — most still fail.

**Verified-to-the-dot model (implemented):**
- The DMG resolves pixels at the LCD interface, `OUTPUT_DELAY = 7` T after the
  FIFO pop (`DmgPixelFifo` delay line): palettes (BGP/OBP0/OBP1), LCDC bg/obj
  enable bits and the obj/bg mux are all read at *output* time. During the single
  T-cycle in which a CPU write commits, the output stage reads `old|new` for
  palettes and `old|(new&1)` for LCDC (SameBoy PALETTE_DMG / DMG_LCDC conflicts).
  This makes m3_bgp_change / m3_lcdc_bg_en_change / m3_window_timing pixel-exact
  except line 0.
- The fetcher samples the SCX/SCY-derived map offset at GET_TILE_T1, but resolves
  the LCDC map-select bit at the VRAM read, which happens at GET_TILE_DATA_LOW_T1
  (one T later than the state name suggests — the hardware fetch cycle ends with a
  two-dot push, placing its reads one dot later than our back-to-back restart).

**Measured but NOT yet implemented (next steps):**
- All remaining line-0 diffs: our line-0 mode-2 STAT interrupt fires ~4 T earlier
  than hardware (the test source compensates line 0 by 4 cycles; our rows 0 render
  shifted). Suspect the line-0 OAM int is delayed 4 T on hardware.
- Tile DATA reads (tile_sel): probing m3_lcdc_tile_sel_change shows hardware's
  data reads land ~5 T after our GET_TILE_DATA_HIGH_T1/T2, i.e. *inside the
  tile's own pop window* in our frame — the hardware dot machine (fetch+pop) runs
  ~4 T later than ours with the LCD delay being ~3 (our pop+7 == hw pop+3). A full
  machine retiming (mode 3 starting 4 T later, E moving 248→252) would need the
  CPU-visible mode-0/lock events to fire *before* the machine finishes (they are
  calibrated at E+1/E+4 with E=248) — blocked on early-completion bookkeeping
  because sprite stalls can still lengthen the line after the crossing.
- m3_scx_low_3_bits: mid-line SCX%8 changes must affect the pop alignment
  (hardware artifact at x=154..157); our alignment is line-start-only.
- Sprite-related conflicts (m3_bgp_change_sprites 992, m3_obp0_change 432 diffs
  at x=0..1, obj_en/size 146-390): SameBoy's DMG_LCDC obj_en special cases
  (position_in_line==0, during_object_fetch) not implemented.
- Window tests (m2_win_en_toggle, m3_lcdc_win_en_change_multiple*, wx_*): not
  yet analysed; disable_window_pixel_insertion_glitch in SameBoy is the model.
- SameBoy's conflict maps live in Core/sm83_cpu.c (dmg_conflict_map + the
  GB_CONFLICT_* cases); their per-register apply offsets are relative to a
  baseline 4 T before our CPU write commit tick.

## Possible future work

- CGB PPU rendering in DMG-compat mode (palettes via KEY0 path) — IO regs are
  compat-correct, rendering path is plain CGB.
- CGB double-speed PPU/APU relative timing (only the frame sequencer tap is
  double-speed-aware today).
- mealybug-tearoom-tests (mid-scanline register writes) — the T-exact pipeline
  is the right foundation; the fetcher currently latches LCDC map/tiledata per
  fetch state, close to hardware.
- SGB-specific timing is untested territory.
