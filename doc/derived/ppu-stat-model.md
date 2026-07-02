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
| 452 (prev. line) | LY increments (CPU-visible); OAM read-lock engages |
| 0 | STAT mode bits read 2; LY=LYC comparison re-latched for the new LY |
| 76 | VRAM read-lock engages |
| 80 | STAT mode bits read 3 (internal pixel transfer starts) |
| E+1 | STAT mode bits read 0; OAM+VRAM unlock (`E` = emergent pixel-pipeline end, 251 for SCX=0, no sprites, no window) |
| 452 | next line's LY / OAM lock |

- The **LY=LYC coincidence bit** (STAT bit 2) compares LYC against an LY value registered at
  `tl=0`; between the LY increment (452) and the next latch (0) the flag reads **0**
  (`lcdon_timing-GS`). LYC writes take effect immediately against the registered LY
  (`stat_lyc_onoff`). On line 153 LY reads 153 only during `tl=0..3`, then 0; the comparison
  follows with the same 4-tick latch (extra latch point at `tl=8`).
- **Write asymmetry** (`lcdon_write_timing-GS`): CPU *writes* are only blocked by the actual
  scan/transfer: OAM writes pass during `tl=452..455` and during `tl=76..79` (between the end
  of the OAM scan and the pixel transfer); VRAM writes are blocked only while the mode is 3.

## Mode-3 length

`E = 251 + (SCX % 8) + sprite stalls (+ window stalls)`. Each fetched sprite stalls the
pipeline 6 T (3 fetcher steps at 2 MHz).

## STAT interrupt line

One level line; the IF flag (LCDC bit) is set only on its **rising edge** — this yields "STAT
blocking" (`stat_irq_blocking`) with no special cases:

```
line = (LYC enabled  AND coincidence)
    OR (mode0 enabled AND tl >= hblankIntFrom, lines 0-143)
    OR (mode1 enabled AND visible mode == 1)
    OR (mode2 enabled AND tl < 4, lines 0-144)
```

- The **mode-2 term** is a 4-tick pulse at the start of each line — per the schematic
  annotation: *"INT_STAT = 1 when LY = LYC (whole line), VBLANK (MODE1), HBLANK (MODE0), and
  only for a few cycles at the start of OAM parsing when VCLK is high."* It also fires at the
  start of line 144 (`vblank_stat_intr-GS`).
- The **mode-0 term** rises with the visible mode 0, but the SCX delay and sprite stalls are
  quantized to a 4-tick grid (`hblank_ly_scx_timing-GS`, `intr_2_mode0_timing_sprites`):
  `hblankIntFrom = 252 + quant(SCX%8) + quantSprite(stall)` where `quant(0)=0`,
  `quant(1..4)=4`, `quant(5..7)=8`, and `quantSprite` rounds the stall up to ≡2 (mod 4).
- VBLANK IF (bit 0) is requested at `tl=0` of line 144.
- **DMG STAT write glitch**: during a STAT write all four enable bits act as 1 for a moment
  ("all interrupts are enabled before data settles" — `ff41_stat` annotation), which can
  produce a spurious rising edge.

## LCD enable

Enabling the LCD starts a special first line (`Gpu.firstLine`):

- no OAM scan and no mode-2 STAT/interrupt; the mode bits read 0 until the pixel transfer,
- OAM and VRAM stay accessible until `tl=80`,
- mode 3 shows at `tl=80` and mode 0 at `tl=252` (no register lag on this line),
- the LY=LYC comparison runs from the moment of enabling (`registeredLy=0`), and drops to 0 at
  the end of the line (452) like a normal LY change.

Disabling the LCD freezes the coincidence bit, resets LY to 0, forces the mode bits to 0 and
opens both locks. The comparison does not run while the LCD is off (`stat_lyc_onoff`).

## Known remaining deviation

`intr_2_mode0_timing_sprites` requires the exact interleaving of sprite fetches with the BG
fetcher. The sprite fetch is modelled after SameBoy's object fetch (a 6-tick micro-sequence
that waits for the BG fetcher's tile data and preserves its state), which passes the first
testcase; the remaining per-x-phase stalls (measured against SameBoy: ~12 T at x%8=0, ~8 T at
1-4, ~4 T at 5-7, chained sprites alternating +4/+8) need a T-exact dual-FIFO pixel pipeline
in which pixel output continues during parts of the object fetch.
