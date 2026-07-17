# DMG PPU / STAT model

Behavioral model of the DMG PPU's CPU-visible timing, derived from the DMG-CPU B schematics
(`dmg-schematics/dmg_cpu_b/ff41_stat.kicad_sch`, `lcd.kicad_sch`, `ppu.kicad_sch` and the
wave diagrams in `dmg-schematics/doc/wave_diagrams.html`) and calibrated cycle-exactly against
the mooneye acceptance tests (which were verified on real DMG hardware). This is the
specification implemented by `Gpu` and `StatRegister`.

All times are T-cycles (4.19 MHz). `tl` is the tick within a line, `0..455`, as counted by
`Gpu.ticksInLine`. The key structural fact from the schematics (the RUTU/NYPE line strobes in
`lcd.kicad_sch`): the "new line" strobe fires **4 T-cycles before** the nominal line boundary,
so several events lead the boundary by 4 ticks.

## Per-line timeline (visible lines 0-143)

| tl | event |
|----|-------|
| 452 (prev. line) | LY increments (CPU-visible); OAM read-lock engages; mode-2 STAT IF can rise |
| 0 | STAT mode bits read 2; LY=LYC comparison re-latched for the new LY |
| 76 | VRAM read-lock engages |
| 80 | STAT mode bits read 3 (internal pixel transfer starts) |
| E+1 | STAT mode bits read 0; OAM+VRAM unlock (`E` = emergent pixel-pipeline end, 248 for SCX=0, no sprites, no window) |
| E+4 | mode-0 STAT interrupt line rises (3 T after the visible mode 0) |
| 452 | next line's LY / OAM lock |

- The **LY=LYC coincidence bit** (STAT bit 2) compares LYC against an LY value registered at
  `tl=0`; between the LY increment (452) and the next latch (0) the flag reads **0**
  (`lcdon_timing-GS`). On DMG, LY=153 is exposed during the early increment at the end of
  line 152 and is already 0 when internal line 153 starts; CGB holds 153 through `tl=0..3`.
  The comparator still latches 153 at line 153 `tl=0`, then latches 0 at `tl=8`.
- CGB LYC writes conflict with the comparator latch around `tl=448/452` and the line-153
  transition. The write-conflict windows can either expose the old LYC comparison briefly
  or defer the new comparison until the next latch (`ly_lyc*_write-C`).
- **Write asymmetry** (`lcdon_write_timing-GS`): CPU *writes* are only blocked by the actual
  scan/transfer: OAM writes pass during `tl=452..455` and during `tl=76..79` (between the end
  of the OAM scan and the pixel transfer); VRAM writes are blocked only while the mode is 3.

## Mode-3 length and the pixel pipeline

The pixel transfer is a T-exact dot pipeline (modelled after SameBoy's renderer): each
T-cycle pops at most one pixel from the FIFO and advances the tile fetcher by one state
(`GET_TILE_T1/T2`, `DATA_LOW_T1/T2`, `DATA_HIGH_T1/T2`, `PUSH`; the push completes in the
same T-cycle as `DATA_HIGH_T2` when the FIFO is empty). The line starts at
`position = -16` with 8 junk pixels in the FIFO; popping aligns to `SCX % 8` during
positions -16..-9, positions -8..-1 discard the fractional-scroll pixels, and positions
0..159 reach the screen. The last pixel pops at `E = 248 + (SCX % 8) + stalls`.

An object fetch suspends popping when a sprite's OAM X equals `position + 8` (clamped
to 0): it first waits until the background fetcher reaches `DATA_HIGH_T2` with a
non-empty FIFO (`max(0, 5 - fetcher_state)` T), then takes a fixed 6 T for the object's
OAM and tile-data reads. This yields the hardware stalls verified by
`intr_2_mode0_timing_sprites`: 11 T for a sprite on a tile boundary, down to 6 T at
tile offsets 5-7, chains of same-X sprites adding 6 T each. Sprite pixels are merged
into a separate 8-pixel object FIFO that pops in lockstep with the background FIFO, so
sprites hanging off the left edge are clipped by the discarded pops.

## STAT interrupt line

One level line; the IF flag (LCDC bit) is set only on its **rising edge** — this yields "STAT
blocking" (`stat_irq_blocking`) with no special cases:

```
line = (LYC enabled  AND settled coincidence)
    OR (mode0 enabled AND tl >= hblankIntFrom, lines 0-143)
    OR (mode1 enabled AND internal VBlank state)
    OR (mode2 enabled AND previous tl >= 452 or tl < 4, lines 0-144)
```

- The readable **coincidence flag** updates at `tl=0`, while its interrupt-line contribution
  settles at `tl=4` on ordinary lines. The comparison edge reaches IF at `tl=0` and is
  available to the CPU at its next interrupt sample (`ly_lyc_write-GS`). The edge-detector
  latch remains high through this settling window, so clearing IF before `tl=4` does not
  turn the settled level into a second edge.
- The **mode-2 term** becomes visible in IF during the final 4 ticks of the preceding line
  and remains high through the first 4 ticks of the new line — per the schematic
  annotation: *"INT_STAT = 1 when LY = LYC (whole line), VBLANK (MODE1), HBLANK (MODE0), and
  only for a few cycles at the start of OAM parsing when VCLK is high."* The CPU accepts
  the edge at the line boundary, after IF has become readable; this applies to both normal
  IME dispatch and HALT wake. The condition also fires at the start of line 144
  (`vblank_stat_intr-GS`, Wilbert Pol `intr_2_timing`, Mealybug mode-3 tests).
- The **mode-0 term** rises 3 T after the visible mode 0, i.e. at `E+4`
  (`hblank_ly_scx_timing-GS`, `intr_2_0_timing`); the pixel pipeline is T-exact, so no
  quantization is applied.
- The **mode-1 term** follows the internal VBlank state through the end of line 153. On
  DMG the readable mode bits briefly expose mode 0 there, but the interrupt source stays
  high until the line-0 mode-2 pulse takes over. Keeping those sources continuous is
  required for STAT blocking across the frame boundary (Altered Space).
- VBLANK IF (bit 0) is requested at `tl=0` of line 144.
- **DMG STAT write glitch**: during a STAT write all four enable bits act as 1 for a moment
  ("all interrupts are enabled before data settles" — `ff41_stat` annotation), which can
  produce a spurious rising edge. At the HBlank-to-OAM boundary, however, an enabled
  HBlank source masks the transient OAM source; this prevents a second edge when software
  writes STAT from its HBlank handler.

## LCD enable and the line grid phase

The line grid is locked to the machine-cycle phase: all LCDC writes happen on the CPU's
machine-cycle grid, and enabling the LCD starts a special first line whose counter begins
at the write itself and which is **one tick shorter** (455 T), so that the second and all
further lines share the same machine-cycle phase as the power-on grid. This phase equality
is what makes the mode2-interrupt-to-STAT-poll distances identical between tests that
re-enable the LCD and tests that run on the boot grid (`intr_2_mode0_timing` vs
`intr_2_mode0_timing_sprites`).

The first line (`Gpu.firstLine`) additionally:

- has no OAM scan and its mode bits read 0 until the pixel transfer (`tl=79`); the early
  mode-2 interrupt condition still appears at the end of this shortened line,
- keeps OAM and VRAM accessible until the pixel transfer,
- runs the LY=LYC comparison from the moment of enabling (`registeredLy=0`), dropping to 0
  at the end of the line (451) like a normal LY change.

Disabling the LCD freezes the coincidence bit, resets LY to 0, forces the mode bits to 0 and
opens both locks. The comparison does not run while the LCD is off (`stat_lyc_onoff`).
