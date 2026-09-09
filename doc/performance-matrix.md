# Authored performance comparison

`scripts/performance-matrix.json` pins the 39 scenario/profile pairs used by the coverage
discovery run. The manifest preserves the original 17 workload IDs. The script accepts another
explicit manifest for a follow-up subset; the default matrix stays unchanged.

`scripts/performance-polling-matrix.json` declares a separate 60-case follow-up: persistent
IF, IE, DIV, TIMA, JOYP and NR52 reads across all ten hardware/speed profiles. Each workload
mixes LDH immediate, LDH C, absolute and indirect reads at the default dense spacing. It uses
the same three-trial timing protocol as the original matrix. To run that cost gate, add
`--manifest scripts/performance-polling-matrix.json` to the command below and choose a new
output directory. Keep its result separate from the original 39 cases; correctness and positive
batching assertions do not establish its throughput.

`scripts/performance-profile-cost-matrix.json` declares a separate 15-case profile-cost follow-up
for MGB, CGB0 ×2 and SGB2. It uses the original three-trial protocol; run it with
`--manifest scripts/performance-profile-cost-matrix.json` and a new output directory. Keep its
results separate from both existing matrices.

`scripts/performance-retained-fence-cost-matrix.json` declares a separate 12-case follow-up for
the four authored retained-fence fixtures: control-link MMIO, ROM-sourced OAM DMA, native CGB
OAM/VRAM DMA overlap and the MBC3 RTC/SRAM window. It uses the original three-trial protocol;
run it with `--manifest scripts/performance-retained-fence-cost-matrix.json` and a new output
directory. Keep its results separate from the 39-case, 60-case and 15-case matrices.

Compile and freeze both builds before running. Each classpath file must contain absolute paths
to compiled classes or JARs, with its own production classes first and the shared test/dependency
classes afterward. Supply the corresponding source/build receipt JSON files. The script records
their exact hashes and verifies that code classpaths stay unchanged throughout measurement;
the caller remains responsible for the association between source receipts and compiled builds.

Run from the repository root on a quiet host, with no concurrent builds, emulation, profiling or
other throughput tests:

```sh
python3 scripts/compare-performance-matrix.py run \
  --baseline-classpath-file "$baseline_classpath_file" \
  --candidate-classpath-file "$candidate_classpath_file" \
  --baseline-source-receipt "$baseline_source_receipt" \
  --candidate-source-receipt "$candidate_source_receipt" \
  --output "$new_result_directory" \
  --java "$java21_binary" --javac "$javac21_binary"
```

An optional `--cpu N` pins every measured JVM to one available Linux CPU. Record and use the
same affinity and JVM version for comparisons. Output directories must be new. The script
compiles only the common authored image generator and matrix main into its output; it does
not run Maven or modify either compiled build.

Each of three trials runs separate baseline, candidate and diagnostics JVMs, alternating the
baseline/candidate order. Cases are deterministically shuffled with seed `80571 + trial`.
Each case uses 2,097,152 warmup ticks and 8,388,608 measured ticks, `SKIP` initialization,
Performance execution, deterministic RTC time, disabled battery persistence and ordinary
sound generation. The held-input case presses A and RIGHT. The exact protocol and cases are
copied into the result directory. Host frame counts are native frame events; there is no display
or Android audio backend in this harness.

`results.tsv` retains every raw timing and execution counter. `metadata.json` records the JVM
commands, variant order, load averages, code hashes and source receipt hashes; it is local
provenance and can contain local build paths. `summary.json` contains authored case IDs,
paired ratios and receipt hashes without those local paths. The tool never reads a commercial
ROM, save or external catalogue mapping. Directory inventories hash `.class` files only;
compiled dependency JARs are hashed as artifacts.

```sh
python3 scripts/compare-performance-matrix.py summarize --output "$result_directory"
python3 -m unittest discover -s scripts/tests -p test_performance_matrix.py
```

The summary rejects incomplete triplets, duplicate cases, mismatched ticks or frames, and
changes to candidate execution accounting when diagnostics are enabled. Frame equality is
checked within the same case and trial, since separate windows can legitimately have different
frame counts. A median cost increase above 3% requests longer paired controls; it does not by
itself establish a regression. Enabled diagnostics overhead is measured separately from the
baseline/candidate throughput ratio. This does not measure the cost of disabled nullable
instrumentation against erased instrumentation.

This matrix is a host discovery gate. Sustained ordinary Android presentation and audio,
hardware correctness, save-state continuity, authorized catalogue scenes and long controls
remain separate acceptance requirements.
