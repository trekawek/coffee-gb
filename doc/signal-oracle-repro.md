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
| `TIMER-OVERFLOW` | `dmg_cpu_b/dmg_cpu_b.sv`; expression `(boga|mery|moba|nydu)_inst` | Instances at lines 7887, 24503, 24584, and 26046; inspect their pins to recover the sampled-TIMA-MSB/fall/reload cone | Static connectivity only; CPU-write and IF capture apertures remain outside it |
| `APU-FRAME-CLOCK` | same file; expression `(ajer|bara|bufy|byfe|bylu|caru|cate|coke|horu)_inst` | Instances at lines 4934, 7035, 8425, 8891, 9012, 9560, 9602, 10255, and 20124 form the selected DMG divider/ripple cone | Does not establish CGB tap/offset, power-on suppression, or speed-switch phase |
| `CH3-PORT` | same file; expression `(afum|agyl|axol|azet|azus|bano|bole|busa)_inst` | Address-owner/fetch-related instances at lines 4656, 4817, 6533, 6606, 6668, 6982, 7991, and 8658 | The two Java fetch-valid stages are fitted; retrigger and electrical collision are not established |
| `CH4-STEADY` | same file; expression `(cary|cexo|esep|gary|gaty|gone|gora|hazo|hezu|jaky|jare|jepe|jero|joto|jyco|jyfu|jyre|kavu|komu)_inst` | Named ratio, prescaler, tap, and zero-reset/XNOR LFSR cells exist at the pinned lines; compare their pins, not just names | Supports the steady cone only; the upstream GYSU/APU-PHI trigger aperture is missing |
| `STAT-SPLIT` | `dmg_cpu_b_gameboy.sv:666-674,696-704`; `dmg_cpu_b/dmg_cpu_b.sv` expression `lyc_int|lyc_int_en|ff41|ff44` | CPU-visible FF41 and LY vectors are assembled from distinct internal nodes; coincidence and internal STAT state have separate signals | Does not establish the Java line/dot table or FF41 transient mask |
| `PPU-REG-BANKS` | `dmg_cpu_b/dmg_cpu_b.sv`; expressions `ff43_d[0-7]` and `ff4b_d[0-7]` | One eight-bit SCX storage family and one eight-bit WX storage family are present | Does not derive five Java receiver delays or their half-dot capture phase |
| `PPU-WINDOW-SOURCE` | same file at `25651-25760,25890-25902,26955,27253-27360,36439-36442`; expression `(mehe|nunu|pyco|pynu|roco|wxy_match|xahy|xofo|in_window)` | `wxy_match` crosses two sampled stages into a retained source latch; `ff40_d5`, `xahy`, and `ppu_reset_n` form its asynchronous reset cone | Static connectivity only; CPU-write capture, `xahy` timing, clock polarity, and Coffee GB's observed eight-dot retirement remain unresolved |
| `LCD-MUX-RESET` | same file; expression `(kahe|kupa|nelo|nura|paty|pero|rajy|tade|xapo|xebe|xodo|xona)_inst` | Output-mux cells occur at lines 25511-30904, panel-clock muxes at 21471/22350, and reset-root cells at 35904-36541 | Establishes named external-model roots, not reset fanout through proposed Java scanout stages or the `old | data` envelope |
| `OAM-PORT` | `dmg_cpu_b/cells/generic_sram.sv`, `dmg_cpu_b/cells/oam.sv`, and scope `dmg_cpu_b_gameboy.dmg.oam_a_inst.sram_inst` | Separate address rails, sticky word-line state, four retained bit-line groups, common line, column select, precharge controls, and `wr` are observable | Directional sample/feedback split remains fitted; dynamic evidence is below |

For every static row, save only the command and a concise connectivity assertion in Coffee GB.
Do not paste the matching external source lines into issues, commits, or review comments.

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
| Directional OAM feedback split | **FITTED, not external evidence** | Observe the control split in a licensed netlist/transistor trace or falsify it across physical row/data/phase sweeps |
| Five PPU register receiver delays, output write envelope, and scanout reset fanout | **FITTED, not external evidence** | Trace each receiver/reset pin or obtain hardware phase sweeps; the static source-bank/reset-root/window-source observations are insufficient |
| Active-window deactivation latency | **Static external cone found; dynamic timing unresolved** | Probe `ff40_d5`, `xahy`, `xofo`, `pynu`, `roco`, and `mehe` across the CPU write and compare their transitions with the eight-dot Coffee GB trace without inserting a semantic delay |
| CH4 trigger aperture | **FALSIFIED bounded cone** | Recover and probe the upstream CPU-write to APU-PHI/GYSU/control path before proposing migration |

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
