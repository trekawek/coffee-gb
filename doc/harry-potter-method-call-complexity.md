# Harry Potter method-call complexity scan

This harness finds the commits that account for most of the added emulation work in the
Harry Potter intro. It counts JVM method entries in production classes under
`eu.rekawek.coffeegb.core.*` until a fixed emulated frame, so host timing noise does not affect
the result.

The ROM is not included. The 8 KiB battery save that skips the language menu is gzip-compressed
and embedded in `HarryPotterIntroHarness.java`. An external save is optional.

## Prerequisites

- Git, Maven, PowerShell, and Bash (Git Bash on Windows)
- JDK 16 or newer; JDK 21 is the validated configuration
- This exact ROM:
  `Harry Potter and the Sorcerer's Stone (USA, Europe) (En,Fr,De,Es,It,Nl,Pt,Sv,No,Da,Fi).gbc`
- Enough memory for the chosen worker count; start with four workers and reduce it if the
  machine starts paging

## Checkout and run

From PowerShell:

```powershell
git switch codex/a3e0ef8-split3
git pull --ff-only origin codex/a3e0ef8-split3

$env:HARRY_POTTER_ROM = 'D:/emu/roms/gbc/H/Harry Potter and the Sorcer''s Stone (USA, Europe) (En,Fr,De,Es,It,Nl,Pt,Sv,No,Da,Fi).gbc'
$env:HARRY_POTTER_WORKERS = '4'
$env:HARRY_POTTER_TARGET_FRAMES = '1800'

bash ./scripts/scan-harry-potter-method-call-complexity.sh
```

The scan defaults to the inclusive first-parent range
`ba648de56d4df327fc408a17912a059a9cc5b1d3..6ef530f5f28659ebf0fda4bc9c2b80673388ff12`.
To use another range, pass its endpoints:

```powershell
bash ./scripts/scan-harry-potter-method-call-complexity.sh <start-commit> <end-commit>
```

The script builds the instrumentation agent once, creates one isolated Git worktree per worker
and one JVM per commit run, distributes commits across the workers, merges the results, verifies
that every commit has one result, and runs the 80% ranking. It removes its temporary worktrees on
exit. Results are kept under `target/harry-potter-method-calls-<timestamp>-<pid>/` unless
`HARRY_POTTER_RESULTS_DIR` is set to a new, non-existing directory.

Important output files are:

- `method-call-results.txt`: one `METHOD_CALL_RESULT` record per commit
- `complexity-summary.txt`: the selected commits and their cumulative share
- `worker-*.log`: diagnostics if a worker fails

## What 1800 frames means

The Game Boy frame rate is approximately 59.7275 Hz, not exactly 60 Hz. Therefore:

- 1200 frames are approximately 20.09 seconds
- 600 frames are approximately 10.05 seconds
- 1800 frames are approximately 30.14 seconds
- 2000 frames would be approximately 33.49 seconds

The default target of 1800 frames models the requested 20-second lead-in plus 10-second music
window. The fixed frame endpoint matters more than the wall-clock interpretation.

## How `n` is selected

For every adjacent parent-to-child pair, the ranker computes:

```text
added_calls(commit) = max(0, calls(commit) - calls(parent))
```

It sums all positive additions, sorts commits by `added_calls` descending, and chooses the
smallest prefix whose cumulative share is at least 80%. Every commit tied at the cutoff is also
included. The resulting number of commits is `n`; it is data-driven rather than chosen in
advance. Negative deltas are retained in the raw data but do not cancel genuine additions.

Every commit in the range must be measured to attribute multiple non-monotonic regressions to
their exact parent-child edges. Concurrency reduces elapsed time without changing that
requirement because each worker has an isolated checkout, build directory, JVM, and counter.

## Reliability checks

Before trusting a new machine, run the same endpoint twice with a small worker count and confirm
that both `ticks` and `calls` match exactly. Keep the ROM, embedded or overridden save, target
frame count, JDK, and harness revision fixed for the whole scan. A different `ticks` value means
the emulated path changed; `calls_per_tick` in the summary helps distinguish path length from
per-tick implementation complexity.

To test only the currently checked-out commit:

```powershell
$env:HARRY_POTTER_TARGET_FRAMES = '1800'
bash ./scripts/count-harry-potter-intro-method-calls.sh
```

To print an exact ranking of the hottest production-core methods for that run:

```powershell
$env:HARRY_POTTER_METHOD_CALL_TOP = '500'
bash ./scripts/count-harry-potter-intro-method-calls.sh
```

Each `HOT_METHOD` record contains the rank, exact call count, share of all counted calls, and
fully qualified method name. The setting defaults to zero, which keeps normal historical scans
compact. A large ranking is most useful for investigating one current commit; set it during a
full scan only if per-method output for every commit is intentional.

To override the embedded save, set `HARRY_POTTER_BATTERY_SAVE` to an absolute `.sav` path.

This metric counts method entries, including constructors, in production core packages. It
excludes abstract methods, native methods, class initializers, and the performance harness
package itself. It is an exact complexity proxy, not a count of bytecode instructions or CPU
cycles.

## Sample CPU profiling

The throughput probe can also capture a Java Flight Recorder profile of only its 600-frame
measurement window. The recording starts after the 1,200-frame warm-up:

```powershell
$env:HARRY_POTTER_JFR = 'D:/tmp/coffee-gb-harry.jfr'
bash ./scripts/measure-harry-potter-intro-fps.sh
jfr view hot-methods D:/tmp/coffee-gb-harry.jfr
```

`jfr` is included with the JDK; the `view hot-methods` command is available in the validated
JDK 21 configuration. Its sampled profile is directional rather than an exact call count, and
recording adds overhead, so do not compare a profiled FPS result directly with an unprofiled
result. Use the exact `HOT_METHOD` ranking to find call-volume candidates and JFR to identify
which of those candidates consume meaningful CPU time.

## Instruction for another coding agent

```text
Checkout and pull branch codex/a3e0ef8-split3. Do not alter the harness or commit range. Set
HARRY_POTTER_ROM to the absolute path of the exact USA/Europe multilingual Harry Potter GBC ROM.
Use JDK 21, Maven, Bash, and PowerShell. Run
scripts/scan-harry-potter-method-call-complexity.sh with four workers and the default 1800-frame
target. Tests may run concurrently, but each commit run must use its worker's isolated Git
worktree and a separate JVM. If memory paging occurs, restart with two workers. Confirm that the
result count equals the commit count. Report complexity-summary.txt, including n, each selected
commit, added_calls, share, cumulative share, delta_ticks, and subject. Also report failures and
the results directory. Do not use FPS or wall-clock time to rank commits.
```
