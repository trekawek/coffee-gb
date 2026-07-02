# DMG APU model derived from the DMG-CPU-B schematics (blargg dmg_sound-2 spec)

Implementation spec for the emulator's sound package, for **DMG only** (not CGB).
Derived from Furrtek's reverse-engineered DMG-CPU-B schematics
(`/home/newton/dev/dmg-schematics/dmg_cpu_b/*.kicad_sch`, signal/cell names quoted below are
the actual labels in those sheets), the wavedrom timing diagrams in
`/home/newton/dev/dmg-schematics/doc/wave_diagrams.html` (sections *APU Clocks*, *APU Channel 1*,
*APU Channel 3 - Wave RAM*), and the behaviors verified on real DMG hardware by blargg's
`dmg_sound-2` test ROMs (01-registers … 12-wave write while on).

Conventions used below:

- **T-cycle** = 1 period of the 4.194304 MHz master clock (`APU_4MHz`).
- **M-cycle** = 4 T-cycles.
- **APU clock** = 2 MHz (`AJER_2MHz`, the DFF `AJER` divides 4 MHz by 2; gated off by `~{APU_RESET3}` when powered down).
- Channel base clocks: `CH1_1MHz`, `CH2_1MHz` (DFFs `CALO`/`CEMO` + `DYFA`; CH1 and CH2 run on
  **opposite phases** of 1 MHz), `CH3_2MHz`, `CH4_1MHz`, `JESO_512K`/`HAMA_512KHz` (noise/EG base).
- "freq" `f` = the 11-bit value in NRx3/NRx4 (0..2047).

---

## 1. Frame sequencer

### 1.1 Source clock

There is **no dedicated frame-sequencer counter**; the APU taps the same divider chain that feeds
DIV. `clocks_and_reset.kicad_sch` exports taps `16384Hz, 8192Hz, 4096Hz, … , 512Hz`; the `512Hz`
hierarchical label is routed into `apu_control.kicad_sch`. The 512 Hz tap is **bit 12 of the
internal 16-bit divider** = **bit 4 of the visible DIV register** (period 8192 T-cycles).

Emulator rule (the "DIV-APU" rule):

> The frame sequencer advances one step on every **falling edge (1→0) of DIV bit 4**
> (internal counter bit 12). Consequently a CPU write to FF04 while DIV bit 4 = 1
> immediately advances the frame sequencer by one step.

### 1.2 The 8-step sequence

Inside `apu_control` the 512 Hz tick drives a small ripple of divider flip-flops, buffered as
`HORU_512Hz` → `BUFY_256Hz` → `BYFE_128Hz` (cells `HORU`, `BUFY`, `BYFE`), plus the gated
`CATE_128Hz` (cell `CATE`, reset by `~{FF24_FF25_FF26_RESET}`) which is the **sweep** clock.
Envelope is clocked at 64 Hz inside each channel ("EG timer clocked at 64Hz" annotation in
`channel1.kicad_sch`) by combining the 512/256/128 phases — i.e. one tick per 8 FS steps.
The emulator-visible equivalent is the classic 8-step sequencer at 512 Hz:

| Step | Length (256 Hz) | Sweep (128 Hz) | Envelope (64 Hz) |
|------|:---:|:---:|:---:|
| 0 | clock | – | – |
| 1 | – | – | – |
| 2 | clock | clock | – |
| 3 | – | – | – |
| 4 | clock | – | – |
| 5 | – | – | – |
| 6 | clock | clock | – |
| 7 | – | – | clock |

Because length (`BUFY`), sweep (`BYFE`/`CATE`) and envelope all ripple off the **same** 512 Hz tap,
their phases are locked forever — this is exactly what blargg's *07-len sweep period sync*
verifies: a sweep tick always coincides with a length tick (steps 2 and 6), and neither timer can
be re-phased by register writes (trigger reloads the sweep *timer value* but the timer is only
*decremented* on FS steps 2/6).

### 1.3 State after APU power-on

`APU_RESET` (driven by the big buffer `KEBA` from the NR52 power bit `FF26_D4`) holds
`HORU/BUFY/BYFE/CATE` in reset while the APU is off. The *APU Clocks* wavedrom shows: after
`/RESET` releases, `HORU`=1, `BUFY`=0, `BYFE`=0, and on the **first** rising edge of the (free
running) 512 Hz tap HORU falls and BUFY rises — i.e. the first tick behaves as **step 0**.

Emulator rules:

- On APU power-on (NR52 bit 7 0→1): **reset the frame sequencer so that the next step executed
  is step 0** (a length step). Do *not* reset DIV: the first tick arrives at the next falling
  edge of DIV bit 4, i.e. anywhere from 0 to 8192 T-cycles after the write.
- While the APU is off the sequencer does not run at all (length counters are never clocked
  while off — blargg *08*).
- Power-on also resets the square channels' duty position and ch3's sample buffer to 0
  (the duty counter DFFs are reset by `APU_RESET`; duty position is *not* reset by trigger,
  see §3).

---

## 2. Length counters (blargg *02-len ctr*)

### 2.1 Structure

- CH1/CH2/CH4: 6-bit down-counter, range 64..0. CH3: 8-bit, range 256..0.
- Writing NRx1 sets `length = 64 - (value & 0x3F)` (CH3: `length = 256 - value`).
- In the schematics the counters are built from **`TFFNL` cells** (T-flip-flop, *no reset input*,
  parallel-loadable — see `DANO/FAVY/DENA…` next to the "Length timer" annotation in
  `ch4_regs.kicad_sch`, loaded straight from `D0..D7`). No `APU_RESET` pin ⇒ **power-off does not
  clear length counters on DMG**, and the NRx1 load path is not gated by the power bit ⇒
  **length can be written while the APU is off** (§5).
- Clocking: `BUFY_256Hz` gated with the enable bit (`FF14_D6`/`FF19_D6`/`FF1E_D6`/`FF23_D6`) and
  with the write strobe `NRx4_D6_WR` (label in `ch4_regs`). When the counter reaches 0 with
  enable set, `CHx_LEN_TIMER_STOP` fires and the channel is disabled (its NR52 status bit clears).
  The counter **stops at 0** (no wraparound); it only leaves 0 via NRx1 write or trigger reload.

### 2.2 Normal clocking

On FS steps 0, 2, 4, 6: for each channel, `if (lengthEnabled && length > 0) length--;
if (length == 0) disableChannel()` (clears NR52 status bit; DAC state unaffected).

### 2.3 Extra length clocking on NRx4 writes ("first half" rule)

Define `firstHalf = (the frame sequencer's NEXT step is one that does NOT clock length)`
— i.e. the most recently executed step was even (0/2/4/6). If you keep a variable `step` holding
the step that will be executed next, `firstHalf = (step & 1) == 1`.
Hardware cause: the length clock is a gate on the 256 Hz phase line; flipping the enable bit while
that phase line is in its "just ticked" half creates an extra clock edge through `NRx4_D6_WR`.

Processing a write of `value` to NRx4 (let `T = value & 0x80`, `E1 = value & 0x40`,
`E0` = previous enable bit):

1. **Extra clock on enable rising edge.** If `firstHalf && !E0 && E1 && length > 0`:
   `length--`. If this makes `length == 0` **and `T` is clear**, the channel is **disabled**.
   (If `T` is set, the trigger below re-enables it.)
2. Store `E1` as the new enable bit.
3. **Trigger reload.** If `T` and `length == 0`: reload `length = 64` (CH3: 256); then, if
   `firstHalf && E1`, immediately clock it once more: `length = 63` (CH3: 255).
4. Rest of trigger processing (§3).

Notes verified by blargg 02/03:
- Writing NRx4 with enable already set (`E0==E1==1`) never causes an extra clock.
- Clearing enable never clocks.
- A trigger with `length > 0` does **not** reload the counter (it keeps counting from where it is).
- After power-on the next FS step is 0, so `firstHalf` is **false** until step 0 executes —
  enabling length immediately after power-on does not extra-clock.

---

## 3. Trigger behavior (blargg *03-trigger*, *06-overflow on trigger*)

Writing NRx4 with bit 7 set performs, in this order (after the length steps of §2.3):

**All channels**
1. Channel's NR52 status bit is set — then **immediately cleared again if the DAC is off** (§7).
   All other trigger side effects still happen even with the DAC off.
2. Frequency timer is reloaded (see per-channel timing below).
3. Envelope (CH1/2/4): volume := NRx2 bits 7-4; envelope timer := NRx2 bits 2-0 (an internal
   period value of 0 is treated as 8 for the timer); envelope re-enabled (it had stopped at 0/15).

**CH1 extra (sweep init — see §4)**
4. `shadowFreq := 11-bit freq from NR13/NR14`
5. `sweepTimer := period (NR10 bits 6-4); if period == 0, sweepTimer := 8`
6. `sweepEnabled := (period != 0) || (shift != 0)`
7. `negateUsed := false` (the "at least one negate calculation since trigger" latch)
8. If `shift != 0`: perform one frequency calculation + **overflow check immediately**
   (`> 2047` ⇒ channel disabled at once — blargg *06*). The result is **not written back** to
   NR13/14 or the shadow. In the schematic this is the `CH1_RESTART` → `CH1_RESTART_DLY`
   (cells `FYTE`/`FEMY`) path: `RESTART` is 4 T-cycles wide and `RESTART_DLY` fires 8 T-cycles
   after the write, pulsing `~{CH1_LD_SHIFT}`/`LD_SUM` and running the serial adder
   (`CH1_SHIFT_CLK` at 1 MHz, `SHIFTCOUNT` counting `7-shift … 7`) with `FREQ_UPD` held low.

**CH2 extra** — nothing.

**CH3 extra**
- `wavePosition := 0`, but the **sample buffer is NOT refilled** — the last-fetched byte keeps
  playing until the first fetch (the ch3 wavedrom shows `WAVE_INDEX` jumping to 0 on
  `CH3_RESTART` with no `/RAM_CS` strobe; the first strobe only comes after the first timer
  overflow, and the first fetch is at index 1's schedule — index increments *then* reads).
- Timer reload with extra delay: `CH3_FCOUNT` loads the freq value during `CH3_RESTART`
  (2 T wide) and then **holds for one extra 2 MHz cycle** before counting; the fetch pipeline
  (`BUSA→BANO→AZUS→AZET`, asserting `/RAM_CS` for 1.5 T and `/RAM_OE` for 1 T) adds ~2-3 T more.
  Emulator rule: after trigger the first wave-position advance/fetch occurs after
  **`(2048 - f) * 2 + 6` T-cycles** (the classic "+6 clocks" ch3 trigger delay).
- **Retrigger-while-on corrupts wave RAM** on DMG — see §6.3.

**CH4 extra**
- LFSR is reset. Hardware: the 15-bit LFSR uses **XNOR** feedback (`LFSR_BIT13_EQ_BIT14` label,
  `XNOR` cell in `channel4.kicad_sch`) and resets to all-zeros; in the conventional
  XOR/shift-right model this is `lfsr := 0x7FFF` (all ones), output = `~lfsr & 1`.

**Square channel timing subtleties (informative)**
- Trigger actions latch on the channel's own 1 MHz clock edge (`CH1_1MHz`; CH2 uses the opposite
  phase `CH2_1MHz`), so the reload takes effect within 0-4 T of the write and the first duty step
  lands `(2048 - f) * 4 (+0..4)` T-cycles later. The **duty position is NOT reset by trigger**
  (only by APU power-off). blargg 03 does not require T-exact modelling of the ±4 T skew.

Frequency timer periods (time between waveform steps):

| Channel | Period | Base clock |
|---|---|---|
| CH1, CH2 | `(2048 - f) * 4` T | 1 MHz (`CH1_1MHz`/`CH2_1MHz`) |
| CH3 | `(2048 - f) * 2` T | 2 MHz (`CH3_2MHz`) |
| CH4 | `divisor[r] << s` T, `divisor = {8,16,32,48,64,80,96,112}` | schematic: "Noise Counter CLK = 1MHz / 1,2,4,6,8,10,12 or 14" (`NOISE_COUNTER_BYPASS` for r=0), then /2^(s+1) in the LFSR prescaler — numerically identical |

---

## 4. Sweep (blargg *04-sweep*, *05-sweep details*, *06-overflow on trigger*)

Hardware: `ch1_sweep.kicad_sch` is an 11-bit **serial** adder/subtractor: accumulator
`ACC_D[0..10]` (the shadow frequency), result `SUM[0..10]`, shifted by `CH1_SHIFT_CLK` at 1 MHz
under a shift counter (`SHIFTCOUNT` counts `7-shift → 7`), negate via `~{FF10_D3}` into the
carry path, overflow flag `~{CH1_SUM_OVFL}` latched into `CH1_FDIS` (NAND latch `GEXU`), and
write-back strobes `CH1_FREQ_UPD1/2` → `CH1_LD_SUM`. A calculation takes a few µs of real time;
model it as instantaneous — blargg does not test intra-calculation timing.

State: `shadowFreq` (11-bit), `sweepTimer` (3-bit+, counts FS-128 Hz ticks), `sweepEnabled`,
`negateUsed`. NR10 fields: `period` (b6-4), `negate` (b3), `shift` (b2-0).

**Frequency calculation** (used everywhere):
```
delta   = shadowFreq >> shift
newFreq = negate ? shadowFreq - delta : shadowFreq + delta
if (negate) negateUsed = true
if (newFreq > 2047) disableChannel()          // overflow check
```

**On trigger**: §3 steps 4-8. Note the immediate calculation happens when `shift != 0`
**regardless of period** (even period == 0), and its result is discarded on success.

**On FS sweep step (2 or 6)**:
```
if (--sweepTimer == 0) {
    sweepTimer = (period != 0) ? period : 8      // "period 0 treated as 8"
    if (sweepEnabled && period != 0) {
        f = calculate()                          // overflow check may disable channel
        if (f <= 2047 && shift != 0) {
            shadowFreq = f
            NR13/NR14 frequency = f              // visible in register read-back path
            calculate()                          // 2nd calc, overflow check only,
        }                                        // result discarded
    }
}
```
The wavedrom shows the timer as a 3-bit up-counter (`SWEEPTIMER` values `6,7,6` for period 1;
reload `7-period`, tick `BEXA` fires on reaching 7) — observable behavior is exactly the
decrement-reload model above.

**Sweep details verified by blargg 05:**
- Timer period 0 counts as 8 ticks (~15.6 ms) — but even when the timer expires with
  `period == 0` in NR10, **no calculation happens** (only the reload).
- Trigger with `period == 0 && shift == 0` ⇒ `sweepEnabled = false`; ticks do nothing.
- Overflow from *any* calculation (trigger-time, tick-time first or second) clears the channel's
  NR52 bit immediately.
- Frequency write-back happens only when `shift != 0` (and no overflow); with `shift == 0`
  calculations still run for the overflow check but never modify the frequency.
- **NR10 writes while the channel is active** take effect on the *next* use of the fields:
  the running `sweepTimer` is *not* reloaded by an NR10 write (period changes apply at the next
  timer reload), and the shadow register is untouched.
- **Negate-mode quirk**: if at least one calculation has been made in negate mode since the last
  trigger (`negateUsed`), then writing NR10 with the negate bit **cleared** immediately
  **disables the channel**. (Hardware: the subtract path's carry/latch state is broken by the
  mode switch; emulate with the `negateUsed` flag.) Setting negate, or clearing it before any
  negate calculation, is harmless.

---

## 5. Power control — NR52 (blargg *08-len ctr during power*, *11-regs after power*, *01-registers*)

Hardware: NR52 bit 7 write strobe is `HAWU` = NAND(`APU_WR`, `FF26`, `D7`); the stored power bit
`FF26_D4`/`FF26_D4_B` gates the APU sub-clock dividers (`AVOK`, `JESO` sit next to `FF26_D4`) and
drives `APU_RESET` (buffer `KEBA`) plus the per-sheet copies `~{APU_RESET2..6}` and
`~{FF24_FF25_FF26_RESET}` (which also resets NR50/NR51 in `ff24_ff25.kicad_sch`).

**Powering off (write NR52 with bit 7 = 0):**
- All registers NR10-NR51 are reset to 0x00; subsequent reads return the OR-mask values of §5.1
  (i.e. as if the register were 0).
- All channels are disabled (NR52 bits 0-3 cleared), all internal state (envelopes, sweep,
  duty positions, timers, frame sequencer dividers) held in reset.
- **DMG quirk: length counters are NOT cleared** (TFFNL cells have no reset — §2.1) and they
  keep their values across the off period. They are not clocked while off (FS is held).
- **DMG quirk: while off, writes to the length fields still work**: NR11/NR21 (bits 5-0 only —
  duty bits are lost), NR31 (all 8 bits), NR41 (bits 5-0) load the length counters. All other
  NR10-NR51 writes are ignored while off.
- Wave RAM (FF30-FF3F) is fully readable/writable while off and is **never affected by power**.
- NR52 itself remains writable (bit 7 only; bits 0-3 are read-only status; bits 4-6 unused).

**Powering on (bit 7 0→1):**
- Frame sequencer reset so the next step is step 0 (§1.3); duty positions reset; ch3 sample
  buffer = 0.
- Registers stay at 0 (they were cleared at power-off); length counters keep whatever they held.
- Writing bit 7 = 1 while already on, or 0 while already off, has no effect.

### 5.1 Register read-back OR masks

`read(reg) = storedValue | mask` (unused/write-only bits read 1):

| Reg | Addr | Mask | Reg | Addr | Mask | Reg | Addr | Mask |
|-----|------|------|-----|------|------|-----|------|------|
| NR10 | FF10 | 0x80 | NR22 | FF17 | 0x00 | NR41 | FF20 | 0xFF |
| NR11 | FF11 | 0x3F | NR23 | FF18 | 0xFF | NR42 | FF21 | 0x00 |
| NR12 | FF12 | 0x00 | NR24 | FF19 | 0xBF | NR43 | FF22 | 0x00 |
| NR13 | FF13 | 0xFF | NR30 | FF1A | 0x7F | NR44 | FF23 | 0xBF |
| NR14 | FF14 | 0xBF | NR31 | FF1B | 0xFF | NR50 | FF24 | 0x00 |
| —    | FF15 | 0xFF | NR32 | FF1C | 0x9F | NR51 | FF25 | 0x00 |
| NR21 | FF16 | 0x3F | NR33 | FF1D | 0xFF | NR52 | FF26 | 0x70 |
| | | | NR34 | FF1E | 0xBF | | | |

- FF27-FF2F: always read 0xFF.
- FF30-FF3F (wave RAM): normal RAM when CH3 is off; DMG-special when on (§6).
- NR52 reads `0x70 | (power << 7) | statusBits(3..0)`; when off it reads exactly 0x70.
- Note NRx4 masks are 0xBF: the **length-enable bit (bit 6) is readable**, trigger bit reads 1.

---

## 6. Wave channel corner cases (blargg *09-wave read while on*, *10-wave trigger while on*, *12-wave write while on*)

Hardware background (`ch3_wave_ram.kicad_sch` + the *APU Channel 3 - Wave RAM* wavedrom):
the 16-byte RAM has precharged bit lines (`WAVE_RAM_BL_PRECHARGE`); the address is muxed
(`MUXI` cells) between the CPU address (`FF3X` decode) and the channel's `WAVE_INDEX`
(`WAVE_A0..A3`). While `CH3_ACTIVE`, the mux is on the channel side and `/RAM_CS` is only
asserted during the channel's own fetch window, so a CPU access lands on the **channel's
current byte** if (and only if) it overlaps that window; otherwise the CPU just samples the
precharged bus ⇒ **0xFF** (reads) / the write is lost.

Fetch cycle (wavedrom, 1 unit = ½ T): when `CH3_FCOUNT` (an up-counter reloaded to `f`,
overflowing past 0x7FF every `2048-f` 2 MHz ticks) overflows, `CH3_FRST` pulses; `WAVE_INDEX`
increments **1 T later**; the pipeline `BUSA→BANO→AZUS→AZET` then asserts `/RAM_CS` for
**1.5 T** and `/RAM_OE` for **1 T**, and `WAVE_DATA_LATCH` captures the byte into the sample
buffer ≈ 2-3 T after the timer overflow.

### 6.1 CPU reads while CH3 is enabled (blargg 09)

- If the read occurs **within the same ~2 T-cycles (one 2 MHz APU clock) as a channel fetch**,
  it returns the byte the channel just fetched: `waveRam[wavePosition >> 1]` — regardless of
  which FF3x address the CPU used.
- Otherwise the read returns **0xFF**.
- Practical implementation: on every CH3 fetch record the T-cycle timestamp; a CPU read of
  FF30-FF3F while CH3 is enabled succeeds iff `now - lastFetchTime < 2` T-cycles (equivalently:
  the fetch happens during the same machine-cycle memory access). With a 2 T window blargg 09
  passes; larger windows fail.

### 6.2 CPU writes while CH3 is enabled (blargg 12)

Same window rule: within 2 T of a fetch the write goes to `waveRam[wavePosition >> 1]`
(the current byte); outside the window the write is **ignored**.

### 6.3 Retrigger while on: wave RAM corruption (blargg 10)

Triggering CH3 (NR34 write with bit 7) **while the channel is already enabled** and the write
lands within the same ≤2 T window in which the channel is about to perform a fetch corrupts the
first 4 bytes of wave RAM. Using `pos' = (wavePosition + 1) & 31` (the position about to be
read) and `i = pos' >> 1` (its byte index):

- if `i < 4` (i.e. `pos' < 8`): `waveRam[0] = waveRam[i]`
- else: `waveRam[0..3] = waveRam[i & ~3 .. (i & ~3) + 3]` (the aligned 4-byte block is copied
  to the start of wave RAM)

Implementation: on NR34 trigger with CH3 currently enabled (and DAC on), if the frequency timer
would expire within the next 2 T-cycles, apply the corruption before performing the normal
trigger actions. This happens *only* on DMG.

### 6.4 Position / buffer semantics

- `wavePosition` (0..31 nibbles) resets to 0 on trigger; the **sample buffer keeps its old
  value** and is what the DAC outputs until the first fetch (period + ~6 T after trigger, §3).
- On each timer expiry: `wavePosition = (wavePosition + 1) & 31`, then the byte
  `waveRam[wavePosition >> 1]` is fetched into the buffer; the output nibble is the high nibble
  for even positions, low nibble for odd (`WAVE_NIBBLE_SEL`), then shifted per NR32
  (`FF1C_D[5..6]`: 100%, 50%, 25%, mute).
- Power-off does not touch wave RAM; power-on zeroes only the buffer, not the RAM.

---

## 7. DAC enable and NR52 status semantics

**DAC enabled** conditions:

| Channel | DAC on iff |
|---|---|
| CH1 | `NR12 & 0xF8 != 0` |
| CH2 | `NR22 & 0xF8 != 0` |
| CH3 | `NR30 & 0x80 != 0` |
| CH4 | `NR42 & 0xF8 != 0` |

Rules:
- Writing NRx2/NR30 so that the DAC turns **off** immediately clears the channel's NR52 status
  bit (channel disabled). Turning the DAC back **on** does *not* re-enable the channel — a
  trigger is required.
- A trigger with the DAC off performs all side effects (length reload, timer/envelope/sweep
  init, LFSR reset…) but the channel ends up **disabled** (status bit 0).
- The NR52 status bit (bit 0-3 = CH1-4) is **set** only by trigger (with DAC on) and **cleared**
  by: length counter reaching 0 with length enabled, sweep overflow (CH1), DAC turned off,
  APU power-off.
- The status bit is *not* affected by envelope volume reaching 0 (channel stays "on"), nor by
  NR51 routing, nor by NR50.
- Length counters keep clocking (and can disable the status bit) even while the DAC is off.
- Mixing: a channel contributes its DAC output only while its status bit is set; a disabled
  channel with DAC on contributes the DAC's "0" level (relevant for analog pops, not for blargg).

---

## 8. Quick FS/write-order checklist for NRx4 writes (implementation order)

1. Compute `firstHalf` from the current FS phase (§2.3).
2. Extra length clock if enable rises in `firstHalf` (may disable channel if no trigger).
3. Latch new length-enable.
4. If trigger bit set: length reload 64/256 (→63/255 in `firstHalf` with enable), then §3
   trigger actions (CH3 corruption check *before* resetting position/timer), then DAC check.
5. Latch new frequency MSB (NRx4 bits 2-0) — note frequency bits take effect immediately for
   the *next* timer reload; the running timer is not restarted by a bare frequency write.
