# SM83 interrupt / HALT model

Behavioral model of the SM83 CPU's interrupt dispatch and HALT, derived from the SM83
schematics (`dmg-schematics/sm83/intr.kicad_sch`, `sequencer.kicad_sch`) and calibrated
cycle-exactly against the mooneye acceptance tests. This is the specification implemented by
`Cpu` and `InterruptManager`.

## Interrupt sampling and dispatch

- At every machine-cycle boundary where the CPU would fetch an opcode, it samples
  `IME && (IE & IF & 0x1F) != 0`; if set, the 5 M-cycle dispatch starts instead of the fetch:

  | M | action |
  |---|--------|
  | 1 | internal delay; IME cleared |
  | 2 | internal delay |
  | 3 | SP--, push PC high |
  | 4 | SP--, push PC low; IE/IF sampled **after** the high push, and the highest-priority set bit selected and cleared |
  | 5 | PC := handler vector |

- Because the selection samples IE *after* the high-byte push, a push that overwrites IE can
  cancel the dispatch: no IF bit is cleared and PC := 0x0000 (`ie_push`).
- `DI` takes effect at the end of its own fetch cycle — an interrupt requested during the DI
  instruction is not dispatched (`di_timing-GS`).
- `EI` enables IME after the *following* instruction. The pending enable also commits when the
  following instruction is HALT, so `ei; halt` halts with IME=1: the wake dispatches and there
  is no halt bug (`halt_ime0_ei`).

## HALT

The schematic's halt latch only gates the instruction-register load/PC increment — the
sequencer keeps cycling. Consequently **a halted CPU behaves exactly as if it were executing
NOPs**:

- Wake condition: `(IE & IF & 0x1F) != 0`, regardless of IME.
- With IME=1, the dispatch starts at the same machine cycle at which it would have started in
  a NOP stream (`halt_ime1_timing2-GS`).
- With IME=0, the instruction after HALT is fetched at that same cycle — no extra delay
  (`halt_ime0_nointr_timing`).
- Halt bug: executing HALT with IME=0 and `(IE & IF) != 0` skips the PC increment of the next
  fetch (`halt_bug`).

## RST

`RST n` is 4 M-cycles: decode, internal delay, push PC high, push PC low (`rst_timing`
verifies the push placement against OAM DMA end; the internal-delay cycle precedes the
pushes, like CALL).

## OAM corruption bug

Putting a 16-bit value in 0xFE00-0xFEFF on the internal bus (16-bit INC/DEC, PUSH/POP,
LD A,(HL±)) while the PPU scans OAM corrupts the 8-byte row the PPU is reading
(`row = ticksInLine / 4`), directly in OAM RAM (bypassing the CPU-side OAM lock):

- *Write* corruption (INC/DEC, PUSH), rows 1-19: the row's first word becomes
  `((a ^ c) & (b ^ c)) ^ c` with `a` = that word, `b` = first word of the preceding row,
  `c` = third word of the preceding row; words 1-3 are copied from the preceding row.
- *Read* corruption: same, with `b | (a & c)`.
- *Read+increment in one cycle* (POP, LD A,(HL±)), rows 4-18: first the preceding row's first
  word becomes `(b & (a | c | d)) | (a & c & d)` (`a` = first word two rows back, `b` = first
  word of the preceding row, `c` = first word of the current row, `d` = third word of the
  preceding row) and the preceding row is copied over both neighbours; then a normal read
  corruption applies.

## Serial clock

The serial shift clock is not a private divider: with the internal clock selected, a bit is
shifted on every falling edge of **bit 8 of the DIV counter** (8192 Hz), so a transfer's
first bit is aligned to the free-running divider (`boot_sclk_align-dmgABCmgb`) and writing
FF04 re-phases the serial clock.

## Frame sequencer (APU)

The APU frame sequencer likewise taps the DIV chain: it advances on every falling edge of
bit 12 of the DIV counter (512 Hz) — see `doc/derived/apu-model.md`.
