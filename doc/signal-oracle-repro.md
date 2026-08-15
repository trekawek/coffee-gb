# Signal-oracle reproducibility manifest

Status: evidence manifest for `signal-driven-core-experiments.md`, recorded 2026-08-15.

This file records how to inspect or rerun every external-netlist, gate-simulation, and SameBoy
claim currently used by the signal-driven-core spike. It is an MIT-side manifest only: it contains
repository coordinates, commands, node names, and concise expected observations. It does not copy
external source, generated netlists, test ROM bytes, save contents, or waveforms.

## Evidence-status vocabulary

- **VERIFIED** — the command was executed successfully while preparing this manifest.
- **OBSERVED** — the stated metadata or artifact was inspected in the retained local checkout, but
  the expensive producer command was not rerun.
- **RECONSTRUCTED** — the command follows from the checkout's Makefile, config log, artifacts, and
  timestamps; no shell transcript survived, so it must be rerun before relying on it.
- **TODO** — required evidence or automation does not currently exist.

A static source match proves only what the pinned external model contains. A gate trace proves only
what that simulator did under its delay policy. A SameBoy result is contrary emulator evidence, not
a hardware capture.

## Licensing and artifact boundary

- Coffee GB is MIT-licensed.
- `dmg-sim` declares CC BY-SA 4.0 in its root `LICENSE`.
- SameBoy's `Core/` is covered by its Expat license; its `iOS/` and `HexFiend/` exceptions are not
  involved here.
- The SM83 binutils fork and the Icarus/GTKWave tools retain their upstream licenses.
- Keep all external checkouts, modified external files, generated Verilog/VVP, assembled probe
  images, FST/VCD files, and SRAM/save dumps outside the Coffee GB worktree.
- Do not stage, publish, checksum in reports, or copy any probe ROM or save/dump content. Report
  only the signal relationships and terminal status listed below.
- This manifest is not legal advice. A production translation from the CC BY-SA model needs an
  explicit licensing decision even when a topology is independently reimplemented.

Suggested ephemeral roots:

```sh
ORACLE_DMG=/tmp/coffee-gb-dmg-sim
ORACLE_DMG_HALT=/tmp/coffee-gb-halt-sim
ORACLE_DMG_TIMER=/tmp/coffee-gb-dmg-sim-timer
ORACLE_DMG_APU=/tmp/coffee-gb-dmg-sim-apu
ORACLE_DMG_WINDOW=/tmp/coffee-gb-window-gate-sim
ORACLE_SAMEBOY=/tmp/coffee-gb-sameboy-signal
ORACLE_IVERILOG_SRC=/tmp/coffee-gb-iverilog-src
ORACLE_IVERILOG_PREFIX=/tmp/coffee-gb-iverilog-master
ORACLE_BINUTILS_SRC=/tmp/coffee-gb-binutils-sm83
ORACLE_BINUTILS_BUILD=/tmp/coffee-gb-binutils-sm83-build
```

Do not substitute a repository or home-directory path for these artifact roots.

## Pinned inventory

| Component | Repository and revision | Local evidence | License boundary |
| --- | --- | --- | --- |
| DMG model | `https://github.com/msinger/dmg-sim.git` at `ee559e1d963e1cc522df512e3bae1b4e5ff96fb5` | **VERIFIED** remote, revision, dirty status, source anchors, and retained artifacts | CC BY-SA 4.0; never copy its source/generated output into Coffee GB |
| SameBoy | `https://github.com/LIJI32/SameBoy.git` at `213a12ce93d66b105a113debd9396306066a7cfc` | **VERIFIED** clean tracked tree, local harness build, and result | Expat for `Core/`; harness remains local because it synthesizes a probe image |
| SM83 binutils | `https://github.com/msinger/binutils-sm83.git` at `32a405949ca49563370108273a10285a17ade344` | **VERIFIED** revision, config arguments, tool versions, and probe rebuild | Build tool only; generated probe artifacts remain external |
| Icarus Verilog | `https://github.com/steveicarus/iverilog.git` at `1d2aa1b6fa0add7723c99d624a9df01d3dec9282` | **VERIFIED** revision and installed version `14.0 (devel) (1d2aa1b)`; HALT gate compile and three runs completed, while the retained OAM compile below was only dry-run | GPLv2 tool; VVP output remains external |
| FST converter source | `https://github.com/gtkwave/gtkwave.git` at `7d7b4db9e2f5485afe2aeeab0ad112f5b6a9b94b` | **OBSERVED** nearby checkout and `/tmp/coffee-gb-fst2vcd`; binary-to-revision provenance is not cryptographically established | Tool and waveform remain external |

Recorded host: Linux `7.0.0-29-generic` x86-64, GCC `15.2.0`, GNU Make `4.4.1`, Git
`2.53.0`. These versions describe the successful local run; they are not yet a minimum-version
claim.

Clean-checkout commands are **RECONSTRUCTED** because network cloning was not repeated:

```sh
git clone https://github.com/msinger/dmg-sim.git "$ORACLE_DMG"
git -C "$ORACLE_DMG" checkout --detach ee559e1d963e1cc522df512e3bae1b4e5ff96fb5

git clone https://github.com/LIJI32/SameBoy.git "$ORACLE_SAMEBOY"
git -C "$ORACLE_SAMEBOY" checkout --detach 213a12ce93d66b105a113debd9396306066a7cfc
```

Verify both revisions before interpreting source or traces:

```sh
git -C "$ORACLE_DMG" rev-parse HEAD
git -C "$ORACLE_SAMEBOY" rev-parse HEAD
```

## Static DMG-model claims

All entries in this section are **VERIFIED static source inspections** at the pinned `dmg-sim`
revision. They establish candidate connectivity in that external model, not propagation timing or
DMG hardware truth.

Use this command template with each table row's expression:

```sh
git -C "$ORACLE_DMG" grep -n -E '<expression>' -- <source-path>
```

| Claim ID | Source anchor and grep expression | Concise expected finding | Limit |
| --- | --- | --- | --- |
| `SM83-IRQ-HALT` | `sm83/sm83.sv:4649-4795,8698-8774,8812-8886`; `sm83/cells/decoder2.sv:205-213`; expression `irq|yoii|halt|ime|idu` | Separate IE/IRQ sample bank, YOII/HALT paths, IME controls, and different decoder participation for HALT versus NOP/STOP | The dynamic probe below rejects a delayed PC gate in this external model; neither source nor simulation establishes silicon equivalence |
| `SERIAL-DIV-RESET` | `dmg_cpu_b/dmg_cpu_b.sv`; instances `tape`, `ufol`, `tama`, `uvyn`, `coty`, `cave`, `dawa`, `edyl`, `elys` | FF04 reset clears the divider stage feeding the internal serial-clock toggle DFF; the shift DFF clocks only on the resulting falling SCK transition | The full-hierarchy cone below establishes DMG-B external-model ownership, not CGB or physical-silicon equivalence |
| `TIMER-OVERFLOW` | `dmg_cpu_b/dmg_cpu_b.sv`; expression `(boga|mery|moba|nydu)_inst` | Instances at lines 7887, 24503, 24584, and 26046; inspect their pins to recover the sampled-TIMA-MSB/fall/reload cone | The dynamic probe below verifies selected CPU-write and IF/acknowledge apertures in this external model; silicon and unprobed phases remain outside it |
| `APU-FRAME-CLOCK` | same file; expression `(ajer|bara|bufy|byfe|bylu|caru|cate|coke|horu)_inst` | Instances at lines 4934, 7035, 8425, 8891, 9012, 9560, 9602, 10255, and 20124 form the selected DMG divider/ripple cone | Does not establish CGB tap/offset, power-on suppression, or speed-switch phase |
| `CH3-PORT` | same file; expression `(afum|agyl|axol|azet|azus|bano|bole|busa)_inst` | Address-owner/fetch-related instances at lines 4656, 4817, 6533, 6606, 6668, 6982, 7991, and 8658 | The two Java fetch-valid stages are fitted; retrigger and electrical collision are not established |
| `CH4-STEADY-TRIGGER` | same file; expression `(apu_phi|cary|cexo|dova|esep|gary|gaty|gone|gora|gysu|hazo|hezu|hoga|jaky|jare|jepe|jero|joto|jyco|jyfu|jyre|kavu|komu)_inst` | Named ratio, prescaler, tap, zero-reset/XNOR LFSR, CPU-write latch, GYSU sample, and restart cells form a bounded raw-clock path | The nodelay probe below verifies two DMG alignments; default-delay/sub-T collisions, other write apertures, and CGB remain outside it |
| `STAT-SPLIT` | `dmg_cpu_b_gameboy.sv:666-674,696-704`; `dmg_cpu_b/dmg_cpu_b.sv` expression `lyc_int|lyc_int_en|ff41|ff44` | CPU-visible FF41 and LY vectors are assembled from distinct internal nodes; coincidence and internal STAT state have separate signals | Does not establish the Java line/dot table or FF41 transient mask |
| `PPU-REG-BANKS` | `dmg_cpu_b/dmg_cpu_b.sv`; expressions `ff43_d[0-7]` and `ff4b_d[0-7]` | One eight-bit SCX storage family and one eight-bit WX storage family are present | Does not derive five Java receiver delays or their half-dot capture phase |
| `PPU-WINDOW-SOURCE` | same file at `4180-4225,6030-6075,8890-8950,9590-9630,25651-25760,25890-25902,26955,27253-27360,34020-34031,35505-35520,35790-35793,36439-36442`; expression `(anel|byha|ff40_d5|mehe|nunu|pyco|pynu|roco|start_oam_parsing|wymo|wxy_match|xahy|xofo|in_window)` | `wxy_match` crosses two sampled stages into a retained source latch; FF40.D5 feeds its asynchronous reset without a clocked receiver, and `xahy` has a reducible parser/reset cone | Static connectivity establishes immediate source reset, not which downstream window-flight stages account for Coffee GB's observed eight-dot path retirement |
| `LCD-MUX-RESET` | same file; expression `(kahe|kupa|nelo|nura|paty|pero|rajy|tade|xapo|xebe|xodo|xona)_inst` | Output-mux cells occur at lines 25511-30904, panel-clock muxes at 21471/22350, and reset-root cells at 35904-36541 | Establishes named external-model roots, not reset fanout through proposed Java scanout stages or the `old | data` envelope |
| `OAM-PORT` | `dmg_cpu_b/cells/generic_sram.sv`, `dmg_cpu_b/cells/oam.sv`, and scope `dmg_cpu_b_gameboy.dmg.oam_a_inst.sram_inst` | Separate address rails, sticky word-line state, four retained bit-line groups, common line, column select, precharge controls, and `wr` are observable | Directional sample/feedback split remains fitted; dynamic evidence is below |

For every static row, save only the command and a concise connectivity assertion in Coffee GB.
Do not paste the matching external source lines into issues, commits, or review comments.

## DMG Serial DIV-reset cone

Status: **VERIFIED external full-hierarchy cone** at DMG-model revision
`ee559e1d963e1cc522df512e3bae1b4e5ff96fb5` with Icarus
`14.0 (devel) (1d2aa1b)`. This is evidence about the reverse-engineered DMG-B model, not a physical
capture and not CGB evidence.

Static inspection identifies this exact chain in `dmg_cpu_b/dmg_cpu_b.sv`:

```text
tape (FF04 write decode, line 31082)
  -> ufol.reset_div_n (32726)
  -> tama asynchronous-clear divider DFF (31042)
  -> uvyn 16384-Hz inversion (32986)
  -> coty serial-clock toggle DFF (10509)
  -> cave internal/external clock mux (9642)
  -> dawa driven SCK (11971)
  -> edyl serial_tick_n inversion (13933)
  -> elys shift DFF (14489)
```

The wrapper maps `tama_n` to `reg_div16[5]` at `dmg_cpu_b_gameboy.sv:279`. The independent pad
path through `jago`, `kexu`, `kujo`, and `sck` corroborates `dawa` as driven SCK. The standard
`dffr.sv` cell is positive-edge triggered with active-low reset.

The testbench instantiates the complete `dmg_cpu_b` hierarchy with its actual default-timing cells
and forces only the upstream control inputs needed to distinguish the reset cases. It and its make
fragment remain outside both repositories:

```text
/tmp/serial_full_cone_tb.sv
  sha256 6d397ee0360811f167d1a6532b86a47f017db2345de93e51b420b403db180df0
/tmp/SerialFullCone.mk
  sha256 2d1c209e9af63ab4cde89867516f5f09aa5de7c26b4c4b2195f12e3929996e43
```

The following commands are **VERIFIED**:

```sh
make -B -f /tmp/SerialFullCone.mk /tmp/serial_full_cone.vvp
/tmp/coffee-gb-iverilog-master/bin/vvp -N /tmp/serial_full_cone.vvp
```

Expected distinguishing output:

```text
FULL_CASE_A stage_high sck_high -> toggle_to_sck_low shift=1
FULL_CASE_B stage_high sck_low -> toggle_to_sck_high shift=0
FULL_CASE_C stage_low -> no_toggle shift=0
FULL_PASS exact dmg_cpu_b hierarchy
```

Thus, in this DMG model, clearing a high preceding divider stage creates the edge which toggles
SCK, and `elys` shifts only when that toggle is high-to-low. Clearing an already-low stage creates
no edge. This independently distinguishes the retained scalar reset rule from the removed
future-event arithmetic. CGB normal/fast clocks, sub-gate analog behavior, and physical hardware
remain explicit limits.

### Window-source reset timing estimate

Status: **CALCULATED from the pinned external model**, not measured silicon timing. The path is
FF40.D5 Q through `xofo`, `pynu`, `nocu`, and `pore` to `in_window`. The lengths and transistor
widths come from those instances and `timing-default.sv`; the latter explicitly describes its
Elmore delays as guesses. This standalone calculation is reproducible without copying generated
netlist source:

```sh
awk 'function rn(w){return 43200/w} function rp(w){return 86400/w} function td(L,R){C=.2e-15*L+.5e-15; Rw=.05*L; Cw=.2e-15*L; return (0.69*R*C+0.38*Rw*Cw)*1e9} BEGIN {u=.454; latch=td(66,rn(10*u))+td(110,rp(10*u))+td(1436.234,rn(35*u)); qpath=td(1258.001,rp(35*u))+td(344.5156,rn(35*u))+td(116.6094,rp(35*u))+td(3035.032,rn(70*u)); printf("ff40_d5_Q_to_in_window=%.9f ns\nCPU_d5_to_in_window=%.9f ns\n",qpath,latch+qpath)}'
```

Expected output is `1.776725275 ns` from FF40.D5 Q and `2.709812620 ns` from CPU data bit 5
through the FF40 latch. At the DMG dot rate, the Q-path estimate is about 0.00745 dot. The useful
causal fact is the absence of a clocked receiver on this reset path; the numerical delays are only
properties of the selected simulator model.

## DMG window-source handoff probe

Status: **VERIFIED external-model trace** at revision
`ee559e1d963e1cc522df512e3bae1b4e5ff96fb5`, using both nodelay and default-delay builds with
Icarus `14.0 (devel) (1d2aa1b)`. The isolated checkout is `$ORACLE_DMG_WINDOW`. This is model
evidence, not a silicon timing capture; the two timing policies deliberately expose a race.

The temporary tracked delta is identified without embedding external source:

```text
sha256(git diff --binary)
  27ff275d9e7360dd988f1a1aae3971790fb349243732d0d96eee465d741f5524
numstat
  59  2  dmg_cpu_b_gameboy.sv
  72  0  sim-tests/window-gate.s
sha256(sim-tests/window-gate.s)
  931922d2dce60b792bb326ba64e0a963251562e6cd568975e61586fc9cf346ef
```

The independently written fixture puts blank tile 0 in BG map `9800`, solid tile 1 in window map
`9c00`, uses WX=7/WY=0, enters an active window line, then clears LCDC.5. `PRE_NOPS=0` and `1` cover
the two CPU-reachable mod-8 fetch alignments by adding one four-T NOP. It contains no sprites and
uses SCX=0, so it is a bounded source/fetch experiment rather than an image oracle.

The probe sources are built with the retained SM83 tools:

```sh
cd "$ORACLE_DMG_WINDOW"
"$ORACLE_BINUTILS_BUILD/gas/as-new" --defsym PRE_NOPS=0 \
  -o sim-tests/window-gate-p0.o sim-tests/window-gate.s
"$ORACLE_BINUTILS_BUILD/ld/ld-new" -o sim-tests/window-gate-p0.coff \
  -T sim-tests/coin.ld sim-tests/window-gate-p0.o
"$ORACLE_BINUTILS_BUILD/binutils/objcopy" -I coff-sm83 -O binary -j .text \
  sim-tests/window-gate-p0.coff sim-tests/window-gate-p0.bootrom
```

Repeat with `PRE_NOPS=1` and `p1` output names. The two VVP builds use
`TIMING=nodelay` and `TIMING=default`, simplified OAM, and simplified wave RAM. The four
**VERIFIED** run shapes are:

```sh
"$ORACLE_IVERILOG_PREFIX/bin/vvp" -N ./dmg_cpu_b_gameboy-nodelay-final.vvp -none \
  +WINDOW_ORACLE +BOOTROM=sim-tests/window-gate-p0.bootrom \
  +SECS=0.02 +MBC_TYPE=00 +RAM_SIZE=00
"$ORACLE_IVERILOG_PREFIX/bin/vvp" -N ./dmg_cpu_b_gameboy-nodelay-final.vvp -none \
  +WINDOW_ORACLE +BOOTROM=sim-tests/window-gate-p1.bootrom \
  +SECS=0.02 +MBC_TYPE=00 +RAM_SIZE=00
"$ORACLE_IVERILOG_PREFIX/bin/vvp" -N ./dmg_cpu_b_gameboy-default.vvp -none \
  +WINDOW_ORACLE +BOOTROM=sim-tests/window-gate-p0.bootrom \
  +SECS=0.02 +MBC_TYPE=00 +RAM_SIZE=00
"$ORACLE_IVERILOG_PREFIX/bin/vvp" -N ./dmg_cpu_b_gameboy-default.vvp -none \
  +WINDOW_ORACLE +BOOTROM=sim-tests/window-gate-p1.bootrom \
  +SECS=0.02 +MBC_TYPE=00 +RAM_SIZE=00
```

The monitor observes `ff40_d5`, `xofo`, `pynu`, `in_window`, `win_start`; tile-map/bitplane cycles
`tm_cy`, `bp_cy`, `bp_sel`; window selects `xeze`, `xucy`; BG selects `acen`, `asul`; MA/MD;
bitplane input latches; both shifter banks; and LD. It does not claim to have isolated one named
tile-ID vector; a retained tile identity is inferred only from the known map/tile contents and the
observed post-reset `0010/0011` bitplane reads.

In default-delay phase 0, FF40.D5 falls at raw trace time `32659550000`; `xofo`/`pynu` react at +1
ns, `in_window` at +2 ns, and the live address mux changes from window `1c0e`/MD=`01` to BG
`180e`/MD=`00` at +12 ns. `win_start` never reasserts and no new `1cxx` map read occurs. One
already-captured transaction nevertheless completes through `0010/0011`, loads solid bitplanes,
and LD first becomes BG `00` after 15.410 measured PPU-dot intervals. Phase 1 has no active map read;
already-staged solid bitplanes reload once and LD becomes BG after 11.410 dots.

The nodelay source/address mux switches in the same delta. Phase 0 cancels the map-stage
transaction and drains only the current shifter, reaching BG after 6.5 dots; phase 1 keeps one
staged solid payload and reaches BG after 10.5 dots. Thus both timing policies agree on the causal
topology—asynchronous source reset, combinational source mux, at most one retained shared
transaction, then one eight-bit shifter—but disagree on whether a boundary transaction survives.

Finite falsifiers for this model are: any new `1cxx` map read after source/select settling;
`win_start` reasserting while LCDC.5 remains low; more than one post-reset `0010/0011` completion
from pre-edge state; or solid window LD surviving after a known blank shared-latch reload or at
least sixteen PPU rising edges. Sprite overlap, nonzero SCX, other CPU phases, analog pad behavior,
and real-silicon race resolution remain outside this probe.

## DMG timer waveform probe

Status: **VERIFIED** at DMG-model revision
`ee559e1d963e1cc522df512e3bae1b4e5ff96fb5` with Icarus
`14.0 (devel) (1d2aa1b)`. The isolated checkout was
`/tmp/coffee-gb-dmg-sim-timer`. All external source, assembled probe images, generated VVP, and
terminal traces remained outside Coffee GB.

The one tracked temporary patch is identified without embedding CC BY-SA source:

```text
sha256(git diff --binary)
  639ea5883eb7fec7bde9b83c5f7b57174a106b7f9387ee8341eebd7ffbee8d3d
numstat
  39  2  dmg_cpu_b_gameboy.sv
```

The patch only relocates `vid_dump` after its wildcard-connected declarations for Icarus
compatibility and adds transition-only `+TIMER_TRACE` instrumentation. Two independently written,
executed probe sources and one earlier discarded fast-boot scratch source remain untracked in the
isolated checkout. The first executed probe marks four scenarios at the existing `E5A4` debug
port: natural overflow; a TIMA write after the MSB fall but before reload; a TMA write while reload
owns TIMA; and a DIV reset while the selected timer input is high. The second enables Timer
interrupt entry and terminates from vector `0050`. Both use the existing `E5A5` self-termination
port. This semantic description, not their bytes or source text, is the retained Coffee GB
evidence.

The external probe images were built with these commands:

```sh
cd "$ORACLE_DMG_TIMER/sim-tests"
"$ORACLE_BINUTILS_BUILD/gas/as-new" -o timer-probe.o timer-probe.s
"$ORACLE_BINUTILS_BUILD/ld/ld-new" -o timer-probe.coff -T oam-bug.ld timer-probe.o
"$ORACLE_BINUTILS_BUILD/binutils/objcopy" -O binary -j .text timer-probe.coff timer-probe.bootrom
"$ORACLE_BINUTILS_BUILD/binutils/objcopy" -O binary -j .rom timer-probe.coff timer-probe.cartrom
"$ORACLE_BINUTILS_BUILD/gas/as-new" -o timer-ack-probe.o timer-ack-probe.s
"$ORACLE_BINUTILS_BUILD/ld/ld-new" -o timer-ack-probe.coff -T oam-bug.ld timer-ack-probe.o
"$ORACLE_BINUTILS_BUILD/binutils/objcopy" -O binary -j .text timer-ack-probe.coff timer-ack-probe.bootrom
"$ORACLE_BINUTILS_BUILD/binutils/objcopy" -O binary -j .rom timer-ack-probe.coff timer-ack-probe.cartrom
```

The following model build and two simulations are **VERIFIED**:

```sh
cd "$ORACLE_DMG_TIMER"
make dmg_cpu_b_gameboy.vvp \
  IVERILOG="$ORACLE_IVERILOG_PREFIX/bin/iverilog" \
  TIMING=default SIMPLIFIED_OAM=y SIMPLIFIED_WAVERAM=y
"$ORACLE_IVERILOG_PREFIX/bin/vvp" -N ./dmg_cpu_b_gameboy.vvp -none \
  +TIMER_TRACE +BOOTROM=sim-tests/timer-probe.bootrom \
  +ROM=sim-tests/timer-probe.cartrom +SECS=0.01 +MBC_TYPE=00 +RAM_SIZE=00
"$ORACLE_IVERILOG_PREFIX/bin/vvp" -N ./dmg_cpu_b_gameboy.vvp -none \
  +TIMER_TRACE +BOOTROM=sim-tests/timer-ack-probe.bootrom \
  +ROM=sim-tests/timer-ack-probe.cartrom +SECS=0.01 +MBC_TYPE=00 +RAM_SIZE=00
```

The transition monitor observes the following external-model nodes. `sogu` is described only as
the selected TIMA clock/input; the monitor's historical `selected_n` label is not a polarity claim.

```text
dmg_cpu_b_gameboy.dmg.sogu
dmg_cpu_b_gameboy.dmg.nuga
dmg_cpu_b_gameboy.dmg.nydu_n
dmg_cpu_b_gameboy.dmg.mery
dmg_cpu_b_gameboy.dmg.int_timer
dmg_cpu_b_gameboy.dmg.mexu
dmg_cpu_b_gameboy.dmg.mugy
dmg_cpu_b_gameboy.dmg.cpu_irq2
dmg_cpu_b_gameboy.dmg.cpu_irq_ack2
dmg_cpu_b_gameboy.dmg.clk_1mhz
dmg_cpu_b_gameboy.dmg.clk_t4
dmg_cpu_b_gameboy.dmg.tope
dmg_cpu_b_gameboy.dmg.tyju
dmg_cpu_b_gameboy.dmg.reset_div_n
dmg_cpu_b_gameboy.reg_div16
dmg_cpu_b_gameboy.reg_ff05
dmg_cpu_b_gameboy.reg_ff06
dmg_cpu_b_gameboy.reg_ff07
dmg_cpu_b_gameboy.reg_ff0f
```

Icarus reports `$time` in picoseconds. The absolute reset offset is irrelevant; differences expose
the ordering. The concise **VERIFIED external gate-model observations** are:

| Scenario | Raw time (ps) | Observation |
| --- | ---: | --- |
| natural overflow | 32042213000 / 32042226000 | The selected TIMA input falls; the TIMA ripple then takes bit 7 low, makes TIMA `00`, and raises MERY. |
| natural overflow | 32043187000 / 32043188000 | On the following BOGA/`clk_1mhz` capture, MOBA/`int_timer` rises and NYDU.Q falls; MERY then falls and MEXU reload ownership rises. |
| natural overflow | 32043189000 / 32043190000 | IF.2 rises; the parallel-load ripple settles TIMA to TMA=`A5`. |
| natural overflow | 32044163000 / 32044165000 | MOBA and MEXU fall after one BOGA interval. |
| TIMA cancellation | 32078325000 / 32078338000 | The selected fall produces TIMA=`00`, TIMA.7 low, and MERY high. |
| TIMA cancellation | 32078813000 / 32078814000 / 32078815000 | FF05 write decode begins; MEXU rises; NYDU.Q and MERY fall while TIMA begins loading `55`. No MOBA or IF.2 rise occurs before marker 03 at 32092842000. |
| TMA during reload | 32117365000 / 32117378000 | The selected fall produces TIMA=`00` and MERY high. |
| TMA during reload | 32118339000 / 32118340000 / 32118341000 / 32118342000 | MOBA rises; MEXU rises; IF.2 rises; TIMA settles to the old TMA=`A5`. |
| TMA during reload | 32118829000 / 32119194000 / 32119195000 | FF06 write begins while MOBA/MEXU are high; TMA settles to `3C`, then the reload-owned TIMA bus follows it to `3C`. |
| TMA during reload | 32119315000 / 32119317000 | MOBA and MEXU release. |
| DIV-induced edge | 32313052000 / 32313054000 / 32313066000 | FF04 reset begins while the selected input is high; reset takes that input low, and the TIMA ripple produces `00` plus MERY. |
| DIV-induced edge | 32313539000 / 32313540000 / 32313542000 | The next BOGA capture raises MOBA; MEXU rises and TIMA settles to TMA=`66`. IF.2 was already set by the preceding scenario, so this row does not claim a new IF edge. |
| interrupt acknowledge | 32048080000 / 32049042000 / 32049043000 | TIMA.7/MERY, then MOBA, then MEXU and IF.2 rise in causal order. |
| interrupt acknowledge | 32054653000 / 32054655000 / 32055877000 | CPU Timer acknowledge rises, IF.2 falls 2 ns later while acknowledge remains high, and PC reaches vector `0050`. |
| FF0F clear | 32018305000 / 32018321000 | The startup FF0F-clear write produces a 16 ns IF.2 propagation transient before the bit settles low. The other probe shows the same pattern at 32020257000 / 32020273000. |

The trace supports four local relationships in this external model: a selected-input fall clocks
the TIMA ripple; NYDU/MERY preserve an overflow until the next BOGA edge; the same TIMA load cone
both cancels that pending fall and exposes the live TMA bus during reload ownership; and Timer
acknowledge asynchronously clears the IF latch without altering reload ownership. FF04 is not a
separate semantic increment path: resetting a high selected divider stage creates the same falling
wire and downstream cone. Static inspection also shows acknowledge in the IF reset cone, so reset
dominates a simultaneous MOBA edge; the dynamic acknowledge run itself occurs later and does not
exercise that exact collision.

The first two relationships have explicit falsifiers in
`DmgTimerControlCompositionTest`: a TIMA ripple launched at a BOGA boundary may not feed the new
TIMA value back into MOBA on that same edge, and an intervening TIMA load must clear the sampled
fall before the next capture. The live-TMA and clear-dominant IF equations remain covered by
`TimerSignalTopologyTest` and the composition tests.

The FF0F pulse is deliberately **not** promoted into Java behavior. It is a sub-T-state keeper/data
propagation artifact of the selected default-delay model, was not sampled as a CPU-visible value,
and has no physical-DMG capture. Likewise, these timestamps do not establish absolute silicon
delays, CGB topology, untested write phases, or equivalence between a Java half-dot and every gate
transition.

## DMG CH4 trigger-clock probe

Status: **VERIFIED nodelay external-model trace** at revision
`ee559e1d963e1cc522df512e3bae1b4e5ff96fb5` with Icarus
`14.0 (devel) (1d2aa1b)`. The isolated checkout is `$ORACLE_DMG_APU`. Generated simulation output,
ROM state, and external source remain outside Coffee GB.

The one tracked temporary instrumentation patch is identified without embedding licensed source:

```text
sha256(git diff --binary)
  5355292c5a1a6e882d853c605026cd80387b214a6e5a45c8f7d0b69be68bbea9
numstat
  104  4  dmg_cpu_b_gameboy.sv
```

It relocates `vid_dump` after its wildcard-connected declarations for Icarus compatibility,
installs a minimal reset-time CPU program directly into the modeled boot mask ROM, and prints only
the CH4 write, APU-PHI, CH4 clock/restart, ratio-terminal, and LFSR transitions. The program powers
the APU, enables the CH4 DAC, writes NR43=`09`, writes NR44.7, and spins. No ROM artifact is created
or retained in Coffee GB.

The following build and minimal-probe simulation are **VERIFIED**:

```sh
cd "$ORACLE_DMG_APU"
make -B dmg_cpu_b_gameboy.vvp \
  IVERILOG="$ORACLE_IVERILOG_PREFIX/bin/iverilog" \
  TIMING=nodelay SIMPLIFIED_OAM=y SIMPLIFIED_WAVERAM=y
"$ORACLE_IVERILOG_PREFIX/bin/vvp" -N ./dmg_cpu_b_gameboy.vvp -none \
  +SECS=0.01 +MBC_TYPE=00 +RAM_SIZE=00
```

The traced write occurs at `32020242000 ps` with APU-PHI low, CH4 low, HAMA low, HOGA high, and
ratio cells `000`. CH4 rises one T later at `32020486000`; APU-PHI/GYSU raises CH4_START two T after
the write at `32020730000`; restart is visible at +9 T, delayed release at +25 T, and the first two
LFSR-clock rises are `32028294000` and `32032198000`, or +33/+49 T for the observed 244,000-ps T.

A second executed trace used the authorized local SameSuite
`apu/channel_4/channel_4_frequency_alignment` fixture. HAMA was high at HOGA assertion and the
first two internal LFSR rises were +29/+45 T. Its exact invocation was not retained, so it is
**OBSERVED but not command-reproduced** here. `DmgNoiseTriggerWriteConeTest` preserves both concise
vectors without carrying the ROM or trace.

Static connectivity places DOVA between DATA_PHASE and APU_PHI, FOXE/GOXO on the FF23 write path
into HOGA, GYSU between HOGA and CH4_START, GUZY on HOGA's asynchronous clear, ATYK/AVOK on
CH4_1MHZ, JESO on HAMA, and HAZO→GONE→GORA→GATY→JERY on restart/release. A reset-seeded raw-clock
wrapper therefore derives both retained-HAMA alignments without a caller-supplied phase or event
offset.

This trace does not prove physical silicon, default-delay/sub-T ordering, reset/gated-clock seed,
other CPU-write apertures, STOP/non-CPU writes, simultaneous APU reset or DAC disable, live NR43
collisions, test mode, CGB, or double speed. It also does not falsify Coffee GB's externally correct
`PolynomialCounter`; it only rejects treating that behavioral countdown as an internal gate-node
timestamp.

## SM83 HALT waveform probe

Status: **VERIFIED** at DMG-model revision
`ee559e1d963e1cc522df512e3bae1b4e5ff96fb5` with Icarus
`14.0 (devel) (1d2aa1b)`. All build products and traces remained in
`/tmp/coffee-gb-halt-sim`; no external source, ROM image, save, waveform, or generated artifact was
copied into Coffee GB.

The probe used an isolated clone so that the retained OAM experiment remained untouched. Its one
tracked temporary patch is identified without embedding CC BY-SA source:

```text
sha256(git diff --binary)
  44c5d8ed6ab32ac2b47acebbcae5b2394343f2774c53767bd2996e496368425a
numstat
  155  3  dmg_cpu_b_gameboy.sv
```

The patch has four mechanical parts:

1. Relocate `vid_dump` after its wildcard-connected signal declarations for compatibility with
   the retained Icarus build.
2. Accept `+HALT_SCENARIO`, clear the modeled internal boot mask ROM, and install one of three
   minimal programs without producing a ROM file: pending interrupt with IME disabled then
   `HALT; INC B`; timer-driven ordinary `HALT; INC B`; or pending interrupt then `EI; HALT`, with
   a timer-vector body that increments C and terminates.
3. Print stable CPU-phase samples and transition events for the named control nodes, internal CPU
   address, PC, instruction register, IME, and B/C.
4. Print a concise register/control result at the existing `E5A5` self-termination port.

Inspect the patch only in the isolated external checkout:

```sh
git -C "$ORACLE_DMG_HALT" rev-parse HEAD
git -C "$ORACLE_DMG_HALT" diff --check
git -C "$ORACLE_DMG_HALT" diff --numstat
git -C "$ORACLE_DMG_HALT" diff --binary | sha256sum
```

The following build command is **VERIFIED**:

```sh
cd "$ORACLE_DMG_HALT"
make dmg_cpu_b_gameboy.vvp \
  IVERILOG="$ORACLE_IVERILOG_PREFIX/bin/iverilog" \
  TIMING=default SIMPLIFIED_OAM=y SIMPLIFIED_WAVERAM=y
```

The three simulation commands are **VERIFIED**:

```sh
"$ORACLE_IVERILOG_PREFIX/bin/vvp" -N ./dmg_cpu_b_gameboy.vvp -none \
  +HALT_SCENARIO=halt_bug +SECS=0.002 +MBC_TYPE=00 +RAM_SIZE=00
"$ORACLE_IVERILOG_PREFIX/bin/vvp" -N ./dmg_cpu_b_gameboy.vvp -none \
  +HALT_SCENARIO=ordinary_wake +SECS=0.002 +MBC_TYPE=00 +RAM_SIZE=00
"$ORACLE_IVERILOG_PREFIX/bin/vvp" -N ./dmg_cpu_b_gameboy.vvp -none \
  +HALT_SCENARIO=ei_halt +SECS=0.002 +MBC_TYPE=00 +RAM_SIZE=00
```

The transition monitor reads these external-model nodes:

```text
dmg_cpu_b_gameboy.dmg.cpu_inst.ctl_op_halt
dmg_cpu_b_gameboy.dmg.cpu_inst.ctl_op_halt_delayed
dmg_cpu_b_gameboy.dmg.cpu_inst.ctl_idu_inc
dmg_cpu_b_gameboy.dmg.cpu_inst.ctl_fetch
dmg_cpu_b_gameboy.dmg.cpu_inst.ctl_reg_pc_to_idu_en
dmg_cpu_b_gameboy.dmg.cpu_inst.ctl_idu_to_reg_pc_en
dmg_cpu_b_gameboy.dmg.cpu_inst.ctl_reg_pc_we
dmg_cpu_b_gameboy.dmg.cpu_inst.opcode
dmg_cpu_b_gameboy.dmg.cpu_inst.reg_pcl
dmg_cpu_b_gameboy.dmg.cpu_inst.reg_pch
dmg_cpu_b_gameboy.dmg.cpu_inst.int_pending
dmg_cpu_b_gameboy.dmg.cpu_inst.yoii
dmg_cpu_b_gameboy.dmg.cpu_inst.halt
dmg_cpu_b_gameboy.dmg.cpu_inst.ime_state
dmg_cpu_b_gameboy.dmg.cpu_inst.ime_n
dmg_cpu_b_gameboy.dmg.cpu_inst.int_take
dmg_cpu_b_gameboy.dmg.cpu_inst.int_entry
dmg_cpu_b_gameboy.dmg.cpu_inst.irq_latch
dmg_cpu_b_gameboy.dmg.cpu_inst.data_phase
dmg_cpu_b_gameboy.dmg.cpu_inst.exec_phase
dmg_cpu_b_gameboy.dmg.cpu_inst.write_phase
dmg_cpu_b_gameboy.dmg.cpu_port_a
```

Icarus reports `$time` in picoseconds for this model. The converted microseconds below are
`raw ps / 1,000,000`; the large absolute offset is reset/startup time, not a HALT latency. The
concise **VERIFIED external gate-model observations** are:

| Scenario | Raw time (ps) | Time (us) | Expected observation |
| --- | ---: | ---: | --- |
| halt bug | 32009149000 | 32,009.149 | Direct `ctl_op_halt` rises at PC `0009`, with pending and YOII already high. |
| halt bug | 32009158000 / 32009162000 | 32,009.158 / 32,009.162 | `ctl_fetch` and `ctl_reg_pc_we` rise while `ctl_idu_inc` remains low; CPU address and PC are `0009`. |
| halt bug | 32010003000 / 32010004000 | 32,010.003 / 32,010.004 | Direct HALT falls, then `ctl_op_halt_delayed` rises; the HALT latch remains clear. |
| halt bug | 32010019000 | 32,010.019 | The instruction register has settled to `INC B` while PC remains `0009`. |
| halt bug | 32010128000 / 32010136000 | 32,010.128 / 32,010.136 | The following instruction has ordinary IDU increment and PC write even while delayed HALT is high. |
| halt bug | 32010980000 / 32010984000 | 32,010.980 / 32,010.984 | Delayed HALT falls; PC then settles from `0009` to `000A`. |
| halt bug | 32016714000 | 32,016.714 | Terminal result is B=`02`, C=`00`, HALT=`0`: the unchanged fetch address duplicated `INC B`. |
| ordinary wake | 32022813000 / 32022826000 | 32,022.813 / 32,022.826 | Direct HALT rises and PC write follows at PC/address `0014`; IDU increment remains low. |
| ordinary wake | 32023668000 / 32023670000 | 32,023.668 / 32,023.670 | Delayed HALT and then the HALT latch rise; the instruction register subsequently settles to `INC B`. |
| ordinary wake | 32024642000 | 32,024.642 | Delayed HALT falls while the HALT latch remains set and retains the sleep state. |
| ordinary wake | 32027582000 / 32028547000 / 32028549000 | 32,027.582 / 32,028.547 / 32,028.549 | Timer pending rises, YOII samples it, and the HALT latch clears. |
| ordinary wake | 32028671000 / 32028680000 / 32029528000 | 32,028.671 / 32,028.680 / 32,029.528 | Resumed `INC B` has normal IDU increment and PC write, then PC advances `0014` to `0015`. |
| ordinary wake | 32035258000 | 32,035.258 | Terminal result is B=`01`, C=`00`: the preloaded instruction runs once after wake. |
| `EI; HALT` | 32012440000 / 32012932000 | 32,012.440 / 32,012.932 | `ime_state` rises and active-low `ime_n` falls before HALT decode. |
| `EI; HALT` | 32013053000 / 32013066000 | 32,013.053 / 32,013.066 | Direct HALT and PC write rise at PC/address `000D`; IDU increment remains low. |
| `EI; HALT` | 32013908000 / 32013910000 | 32,013.908 / 32,013.910 | Delayed HALT rises and interrupt entry begins; the HALT latch never sets. |
| `EI; HALT` | 32013923000 | 32,013.923 | The instruction register has settled to the mainline `INC B` during entry. |
| `EI; HALT` | 32017816000 | 32,017.816 | PC reaches timer vector `0050`. |
| `EI; HALT` | 32024522000 | 32,024.522 | Terminal result is B=`00`, C=`01`: the ISR, not the preloaded mainline opcode, runs once. |

This falsifies the prior `haltDecodeHeld` placement in the Coffee GB test hypothesis. Exactly one
effective PC increment is absent, but it is not a suppressed PC-write pulse: direct HALT decode
removes HALT's own `ctl_idu_inc` interval while PC write remains asserted. The delayed DFF has the
lifetime expected of `ctl_op_halt_delayed`, but its observed fanout is only the HALT SR-latch set
cone; it does not gate the next instruction's PC path. Ordinary wake and `EI; HALT` bound the same
interpretation on both sides of the halt-bug race.

These relationships are evidence about the pinned default-delay external model, not a physical
DMG capture. Absolute propagation delays, reset offset, CGB behavior, peripheral-to-IRQ source
apertures, interrupt acknowledge/vector capture, and equivalence between each Java coarse step and
one gate phase remain outside the claim. The Coffee GB repository intentionally retains only this
manifest and independently written bounded tests.

## Exact retained `dmg-sim` working state

The tracked checkout is intentionally dirty. **VERIFIED** status:

```text
 M dmg_cpu_b/cells/generic_sram.sv
 M dmg_cpu_b_gameboy.sv
?? sim-tests/oam-bug-push.coff
?? sim-tests/oam-bug-push.s
?? sim-tests/oam-bug.coff
```

The tracked patch is identified without embedding licensed source:

```text
sha256(git diff --binary for the two tracked files)
  7c912a93471f1bff9f94e7e6ad3b3744a17d56499f51b42f4da8aaf362b08a6e
numstat
  6  1  dmg_cpu_b/cells/generic_sram.sv
  1  3  dmg_cpu_b_gameboy.sv
```

Its exact semantic description is:

1. `dmg_cpu_b_gameboy.sv`: relocate the existing `vid_dump` instance from before signal
   declarations to immediately after the display-pin declarations. This is an elaboration-only
   adjustment for the selected Icarus build.
2. `dmg_cpu_b/cells/generic_sram.sv`: retain the existing cell update, but print one `OAM_CELL`
   diagnostic before a changing cell is assigned when the SRAM row-count parameter identifies the
   OAM array. The diagnostic reports time, scope, column, row, bit, old/new level, selected word
   lines, retained bit lines, and column mask.

The local PUSH source is an untracked derivative of upstream `sim-tests/oam-bug.s`. Its delta is
identified by:

```text
sha256(diff -u sim-tests/oam-bug.s sim-tests/oam-bug-push.s)
  5a321bd09ebfecdd984a189472decd049d0d445211d1cdb99593ee4509b55381
```

The only logical test change is selecting `PUSH BC` with `SP=FE01` instead of the upstream
`POP BC` case with `SP=FDFF`; all initialization and self-termination logic remains upstream.
Inspect the local diff in the external checkout rather than copying it here:

```sh
git -C "$ORACLE_DMG" diff --check
git -C "$ORACLE_DMG" diff --stat -- dmg_cpu_b/cells/generic_sram.sv dmg_cpu_b_gameboy.sv
diff -u "$ORACLE_DMG/sim-tests/oam-bug.s" "$ORACLE_DMG/sim-tests/oam-bug-push.s"
```

**TODO:** preserve this patch in a separately licensed location or provide a script which applies
the two transformations to a user-supplied checkout. The digest and prose alone identify the
current patch but do not reconstruct it automatically.

## DMG gate build and OAM probes

### Tool builds

The SM83 tool configuration below is **OBSERVED** in
`/tmp/coffee-gb-binutils-sm83-build/config.log`; the final build/install commands are
**RECONSTRUCTED**:

```sh
git clone https://github.com/msinger/binutils-sm83.git "$ORACLE_BINUTILS_SRC"
git -C "$ORACLE_BINUTILS_SRC" checkout --detach 32a405949ca49563370108273a10285a17ade344
mkdir -p "$ORACLE_BINUTILS_BUILD"
cd "$ORACLE_BINUTILS_BUILD"
"$ORACLE_BINUTILS_SRC/configure" \
  --target=sm83-coff \
  --prefix=/tmp/coffee-gb-binutils-sm83-install \
  --disable-gdb --disable-readline --disable-sim --disable-nls --disable-werror \
  --enable-deterministic-archives
make -j2
```

The retained tools report GNU Binutils `2.31.1-sm83-r0`. The upstream `oam-bug.sh` names
`sm83-elf-*`, while this pinned build produces COFF. The following direct build-tree invocation is
**VERIFIED** and rebuilt both retained ordinary and PUSH probe artifacts byte-for-byte, without
publishing their bytes or checksums:

```sh
ORACLE_PROBE_BUILD=$(mktemp -d /tmp/coffee-gb-oam-probe.XXXXXX)
"$ORACLE_BINUTILS_BUILD/gas/as-new" \
  -o "$ORACLE_PROBE_BUILD/oam-bug.o" "$ORACLE_DMG/sim-tests/oam-bug.s"
"$ORACLE_BINUTILS_BUILD/ld/ld-new" \
  -o "$ORACLE_PROBE_BUILD/oam-bug.coff" \
  -T "$ORACLE_DMG/sim-tests/oam-bug.ld" "$ORACLE_PROBE_BUILD/oam-bug.o"
"$ORACLE_BINUTILS_BUILD/binutils/objcopy" \
  -I coff-sm83 -O binary -j .text \
  "$ORACLE_PROBE_BUILD/oam-bug.coff" "$ORACLE_PROBE_BUILD/oam-bug.bootrom"
"$ORACLE_BINUTILS_BUILD/binutils/objcopy" \
  -I coff-sm83 -O binary -j .rom \
  "$ORACLE_PROBE_BUILD/oam-bug.coff" "$ORACLE_PROBE_BUILD/oam-bug.cartrom"
```

Repeat with `oam-bug-push.s` and PUSH-specific output names for the second probe. Do not move those
outputs into the Coffee GB repository.

The Icarus source configuration is **OBSERVED**; the build is **RECONSTRUCTED**:

```sh
git clone https://github.com/steveicarus/iverilog.git "$ORACLE_IVERILOG_SRC"
git -C "$ORACLE_IVERILOG_SRC" checkout --detach 1d2aa1b6fa0add7723c99d624a9df01d3dec9282
cd "$ORACLE_IVERILOG_SRC"
sh autoconf.sh
./configure \
  --prefix="$ORACLE_IVERILOG_PREFIX" \
  LDFLAGS=-L/tmp/coffee-gb-iverilog-build-tools/usr/lib/x86_64-linux-gnu \
  CPPFLAGS=-I/tmp/coffee-gb-iverilog-build-tools/usr/include
make -j2
make install
```

Those `LDFLAGS`/`CPPFLAGS` paths are the exact retained configuration, not portable dependency
instructions. **TODO:** record the package names or a container image which provides the equivalent
development headers and libraries on a clean host.

The retained `dmg_cpu_b_gameboy.vvp` header **OBSERVED** Icarus `1d2aa1b`, typical delays,
`dmg_cpu_b/timing-default.sv`, `sm83/timing-default.sv`, generic OAM SRAM, and simplified wave RAM.
This exact compile shape is **RECONSTRUCTED and dry-run verified**, but not rerun for this manifest:

```sh
make -C "$ORACLE_DMG" -B dmg_cpu_b_gameboy.vvp \
  IVERILOG="$ORACLE_IVERILOG_PREFIX/bin/iverilog" \
  TIMING=default SIMPLIFIED_OAM= SIMPLIFIED_WAVERAM=y
```

### Simulation commands

The retained FST/VCD/save artifacts and their timestamps are **OBSERVED**. No shell transcript was
retained, so both long-running commands are **RECONSTRUCTED and not rerun**:

```sh
"$ORACLE_IVERILOG_PREFIX/bin/vvp" -N "$ORACLE_DMG/dmg_cpu_b_gameboy.vvp" \
  -fst-speed \
  +DUMPFILE=/tmp/oam-bug.fst \
  +SAV_FILE=/tmp/oam-bug.sav \
  +BOOTROM="$ORACLE_PROBE_BUILD/oam-bug.bootrom" \
  +ROM="$ORACLE_PROBE_BUILD/oam-bug.cartrom" \
  +SECS=0.005 +MBC_TYPE=03 +RAM_SIZE=02

"$ORACLE_IVERILOG_PREFIX/bin/vvp" -N "$ORACLE_DMG/dmg_cpu_b_gameboy.vvp" \
  -fst-speed \
  +DUMPFILE=/tmp/oam-bug-push.fst \
  +SAV_FILE=/tmp/oam-bug-push.sav \
  +BOOTROM="$ORACLE_PROBE_BUILD/oam-bug-push.bootrom" \
  +ROM="$ORACLE_PROBE_BUILD/oam-bug-push.cartrom" \
  +SECS=0.005 +MBC_TYPE=03 +RAM_SIZE=02
```

Expected terminal line for both runs:

```text
System self-terminated by writing to address 0xe5a5
```

The conversion command is **RECONSTRUCTED** from retained output and the converter interface:

```sh
/tmp/coffee-gb-fst2vcd -f /tmp/oam-bug.fst -o /tmp/oam-bug.vcd
/tmp/coffee-gb-fst2vcd -f /tmp/oam-bug-push.fst -o /tmp/oam-bug-push.vcd
```

**TODO:** pin and document the exact `fst2vcd` build command. The current source checkout revision
and binary are adjacent evidence, not a proven build provenance chain.

### OAM probe paths and acceptance output

Probe these stable hierarchical paths; VCD identifier tokens are build-specific and must not be
hardcoded:

```text
dmg_cpu_b_gameboy.dmg.oam_a_inst.sram_inst.a[6:2]
dmg_cpu_b_gameboy.dmg.oam_a_inst.sram_inst.a_n[6:2]
dmg_cpu_b_gameboy.dmg.oam_a_inst.sram_inst.wl[19:0]
dmg_cpu_b_gameboy.dmg.oam_a_inst.sram_inst.wl_new[19:0]
dmg_cpu_b_gameboy.dmg.oam_a_inst.sram_inst.bl0[7:0]
dmg_cpu_b_gameboy.dmg.oam_a_inst.sram_inst.bl1[7:0]
dmg_cpu_b_gameboy.dmg.oam_a_inst.sram_inst.bl2[7:0]
dmg_cpu_b_gameboy.dmg.oam_a_inst.sram_inst.bl3[7:0]
dmg_cpu_b_gameboy.dmg.oam_a_inst.sram_inst.col[3:0]
dmg_cpu_b_gameboy.dmg.oam_a_inst.sram_inst.com[7:0]
dmg_cpu_b_gameboy.dmg.oam_a_inst.sram_inst.wr
dmg_cpu_b_gameboy.dmg.oam_a_inst.sram_inst.wldrv_pch_n
dmg_cpu_b_gameboy.dmg.oam_a_inst.sram_inst.bl_pch_n
```

The upstream `sim-tests/oam-bug.gtkw` already names these paths. The ordinary trace's expected
concise relationship is **OBSERVED**:

```text
word lines: 0x00002 -> 0x00003 -> 0x00007, without an intervening precharge
address: retained row 1 -> transient row 0 -> transient row 2
column: 0 -> 1
common line: 0x67 -> 0x57
final relation: rows 0 and 2 take row 1's prior contents; unrelated rows stay unchanged
```

The PUSH trace's expected concise relationship is **OBSERVED**:

```text
SRAM wr remains deasserted during corruption
row 1 is selected while retained bit lines still carry row 0, then row 2 is selected likewise
OAM_CELL diagnostics report ordinary read-feedback cell changes for those selections
final relation: rows 1 and 2 take row 0's prior contents; later rows stay unchanged
```

Do not include final byte arrays or save contents in the extractor output. Absolute timestamps are
diagnostic only; acceptance is based on ordering, absence of precharge/write strobe, and row
relationships.

**TODO:** commit an MIT-side extractor which resolves full VCD scope paths, emits only the concise
relationships above, and fails on extra affected rows. Until that exists and reruns from a clean
checkout, the dynamic OAM claims are `OBSERVED`, not fully reproducible.

## STAT waveform claim

The ordinary OAM run also contains the full LCD/STAT trace. These paths are **OBSERVED** in the VCD
header:

```text
dmg_cpu_b_gameboy.reg_ff44[7:0]
dmg_cpu_b_gameboy.reg_ff41[7:0]
dmg_cpu_b_gameboy.dmg.lyc_int
dmg_cpu_b_gameboy.dmg.lyc_int_en
dmg_cpu_b_gameboy.dmg.ff44
```

The experiment log's narrow expected observation is that, after LCD enable, the LY vector,
readable FF41 mode bits, and coincidence-related nodes do not all change on one atomic timestamp;
brief raw-vector hazards may occur before the readable boundary settles. This supports separate
receiver boundaries only. It does not validate `DmgStatControlPlane`'s line schedule or transient
enable mask.

**TODO:** the exact extraction interval, LCD-enable marker, transition transcript, and automated
assertion were not retained. This claim must remain non-promotable until an MIT-side extractor emits
a concise ordered transition table from a clean rerun.

## SameBoy CGB speed-switch timer probe

The SameBoy tracked tree is clean at the pinned revision. It has two untracked local files:
`speed_timer_harness.c` and its binary. The source is identified, without copying its embedded probe
image bytes, by:

```text
sha256(speed_timer_harness.c)
  f5078be2aa3fb51f3ea95aded4604824e40a36d7de968a9280a882c44f4bc7f8
```

Harness behavior:

1. Allocate and zero-fill a local 32-KiB CGB cartridge buffer.
2. At the entry point, reset DIV and TIMA, select TAC value `0x04`, request double speed through
   KEY1, execute STOP, read TIMA, store it in work RAM, and loop at a sentinel PC.
3. Mark the cartridge CGB-capable, initialize model `GB_MODEL_CGB_E`, bypass the boot ROM, set
   `PC=0100` and `SP=FFFE`, and run until the sentinel or 200,000 slices.
4. Print PC, TIMA, stored work-RAM value, internal divider, and speed state.

This avoids distributing a ROM file, but the current C harness is still local and untracked. The
following compile and run commands are **VERIFIED**:

```sh
cd "$ORACLE_SAMEBOY"
cc -O2 -I. -DGB_INTERNAL -D_GNU_SOURCE '-DGB_VERSION="signal-oracle"' \
  -o /tmp/coffee-gb-speed-timer-verify speed_timer_harness.c Core/*.c -lm
/tmp/coffee-gb-speed-timer-verify
```

Expected exact output at revision `213a12ce`:

```text
pc=0114 tima=80 stored=80 div=0020 double=1
```

Relevant SameBoy source anchors are `Core/sm83_cpu.c:393-450` for STOP/speed-switch entry and
`Core/timing.c:418-493` for PHI gating, countdown/freeze, timer advancement, and speed alignment.
The source itself contains a TODO about part of the speed-switch timing. The result therefore
falsifies promotion of the gated-DIV candidate against this emulator; it does not decide actual CGB
routing.

**TODO:** replace the untracked C byte-buffer harness with a reviewed MIT-side probe generator or
assembler-driven local fixture that contains no copied ROM bytes, then rerun it in CI or an explicit
oracle job.

## External claims not reproduced by these checkouts

The following references in `signal-driven-core-experiments.md` are not supported by a retained
artifact in either inspected checkout:

| Claim | Current status | Required addition |
| --- | --- | --- |
| Nine Daid CGB speed-switch captures | **TODO** | Pin the original source URL/revision, capture names, observation point, and an extractor yielding the nine expected durations without redistributing ROMs |
| Public descriptions of an 8200-T speed-switch interval | **TODO** | Add a precise citation and define whether its observation window is comparable to the 0x20000 selected-clock calibration |
| Actual CGB TIMA behavior during the generated STOP probe | **TODO hardware capture** | Run an authorized physical CGB capture with a machine-readable result; record board revision, boot mode, probe source provenance, and sampling point |
| Serial DIV-reset ownership on physical DMG and CGB | **DMG-B external model only; CGB differential only** | Capture SCK/shift behavior across DIV-reset phases on physical DMG and CGB revisions; recover the CGB normal/fast divider/mux topology independently |
| DMG Timer silicon equivalence and simultaneous MOBA/acknowledge aperture | **External default-delay model only** | Capture selected divider/TIMA/IF nodes on physical DMG hardware across register-write phases; dynamically force request/acknowledge overlap instead of inferring reset dominance from static connectivity |
| Directional OAM feedback split | **FITTED, not external evidence** | Observe the control split in a licensed netlist/transistor trace or falsify it across physical row/data/phase sweeps |
| Five PPU register receiver delays, output write envelope, and scanout reset fanout | **FITTED, not external evidence** | Trace each receiver/reset pin or obtain hardware phase sweeps; the static source-bank/reset-root/window-source observations are insufficient |
| Active-window downstream retirement | **Bounded in external nodelay/default-delay models; silicon race unresolved** | Obtain a physical phase sweep or another independent gate model to choose whether the pre-edge shared transaction survives; extend source-tagged replay across sprite/window/SCX overlaps before migration |
| CH4 trigger aperture | **Two DMG nodelay alignments traced; wider timing unresolved** | Reproduce the SameSuite invocation, run default-delay/sub-T collision probes, sweep other CPU-write/reset phases, and treat CGB as a separate topology |

Mealybug images and Coffee GB ROM profiles remain integration oracles documented by the repository's
test configuration. Their passing result is not evidence that a detached experimental cone rendered
the image, so they are intentionally not relabeled as external-netlist reproduction here.

## Promotion checklist

Before citing an external claim as grounds for a production cut:

1. Start from the pinned clean revision and apply an identified external patch.
2. Record tool versions and distinguish source inspection, model simulation, emulator comparison,
   and physical capture.
3. Run an MIT-side driver/extractor which emits only concise, non-copyrighted assertions.
4. Match the expected output in this manifest and fail on additional transitions/affected state.
5. Keep all source, ROM, save, VVP, FST, VCD, and generated-netlist artifacts outside Coffee GB.
6. Link the run log and this manifest from the hypothesis; do not upgrade a claim with unresolved
   `RECONSTRUCTED` or `TODO` steps.
