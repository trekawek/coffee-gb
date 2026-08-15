# SM83 interrupt / HALT / STOP model

Behavioral model of the SM83 CPU's interrupt dispatch, HALT, and STOP, derived from the SM83
schematics (`dmg-schematics/sm83/intr.kicad_sch`, `sequencer.kicad_sch`) and calibrated
cycle-exactly against the mooneye acceptance tests. This is the specification implemented by
`Cpu` and `InterruptManager`.

## Interrupt sampling and dispatch

- Raw console reset clears all five SoC IF latches, so FF0F initially reads `E0`; the boot process
  later produces the familiar post-boot `E1`. Standalone `InterruptManager` construction retains
  the post-boot fixture default, while a full NORMAL/FAST_FORWARD `Gameboy` drives FF0F to the raw
  reset value before its first CPU tick. SKIP bootstrap keeps the post-boot preset.
- At every machine-cycle boundary where the CPU would fetch an opcode, it samples
  `IME && (IE & IF & 0x1F) != 0`; if set, the 5 M-cycle dispatch starts instead of the fetch:

  | M | action |
  |---|--------|
  | 1 | internal delay; IME cleared |
  | 2 | internal delay |
  | 3 | SP--, push PC high |
  | 4 | SP--, push PC low; the held pending bank selects the highest-priority source and drives its local IF acknowledge |
  | 5 | PC := handler vector |

- Because the selection samples IE *after* the high-byte push, a push that overwrites IE can
  cancel the dispatch: no IF bit is cleared and PC := 0x0000 (`ie_push`).
- In the external DMG gate model, the five `IE & IF` latches are transparent during a CPU data-phase
  aperture. When that aperture closes, the complete pending vector is held; both vector priority
  and the one-hot IF acknowledge are derived from those same bits. A source can redirect selection
  only before closure. Production currently projects this as an atomic post-push action, so exact
  gate-to-Java phase integration remains future work.
- `DI` takes effect at the end of its own fetch cycle — an interrupt requested during the DI
  instruction is not dispatched (`di_timing-GS`).
- `EI` enables IME after the *following* instruction. The pending enable also commits when the
  following instruction is HALT, so `ei; halt` halts with IME=1: the wake dispatches and there
  is no halt bug (`halt_ime0_ei`).
- A peripheral can expose an IF bit before the CPU interrupt input accepts its edge. The
  early mode-2 STAT edge is readable during the preceding line's final M-cycle, but normal
  IME dispatch and HALT wake both accept it at the line boundary. A new LYC comparison,
  by contrast, exposes its IF edge at the line-start latch even though its level contribution
  to the STAT line takes one M-cycle to settle.

## HALT

The external DMG gate model separates direct HALT decode from the retained sleep latch. Direct HALT
decode is omitted from the instruction's own IDU-increment equation while instruction-register load
and PC write stay active, so HALT samples the following opcode while writing the unchanged IDU value
back to PC. A separately delayed decode sets the reset-dominant sleep latch; once set, the sequencer
keeps cycling. Consequently **a halted CPU behaves exactly as if it were executing NOPs**:

- Wake condition: `(IE & IF & 0x1F) != 0`, regardless of IME, after the peripheral edge has
  crossed the HALT wake synchronizer. IF itself can become CPU-readable slightly earlier:
  the timer overflow edge takes another 4 T to wake HALT. An already-pending IF bit is never
  re-delayed.
- With IME=1, the dispatch starts at the same machine cycle at which it would have started in
  a NOP stream (`halt_ime1_timing2-GS`).
- With IME=0, the instruction after HALT is fetched at that same cycle — no extra delay
  (`halt_ime0_nointr_timing`).
- Halt bug: executing HALT with IME=0 and a wake-synchronized `(IE & IF) != 0` prevents the sleep
  latch from setting after HALT's own missing IDU increment. The next opcode has already been
  sampled from the unchanged address and is fetched/executed again (`halt_bug`).

## STOP

On DMG, STOP and HALT do not share a wake condition. The sequencer's retained STOP latch is cleared
only by reset or the WAKE port. The DMG wrapper drives WAKE from the JOYP `AWOB` latch, so an
ordinary enabled IF source cannot release STOP even when IME is set. Coffee therefore leaves a DMG
CPU stopped, keeps the LCD disabled, and retains the IF bit until a physical P10-P13 line goes low.
That JOYP-low transition releases STOP independently of IE/IME and restores the LCD before normal
fetch/interrupt selection resumes. The CGB STOP/speed-switch projection retains its separately
calibrated behavior because its circuitry is outside the DMG source corpus.

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
