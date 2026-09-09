# Sustained Android playback gate (v2)

`performance_soak.py` measures ordinary visible gameplay for **at least 15 minutes**,
using the **final continuous ten minutes** for the sustained gate. This is separate
from the strict 600-frame M2 `BenchmarkMatrix` protocol in
[README-benchmark.md](README-benchmark.md). It does not freeze the controller, arm an
M2 window, select a ROM, change settings, override thermal status, or substitute
headless rendering for device presentation.

Use the signed, release-like **benchmark APK**, built and verified with the existing
Android workflow. Its diagnostics build flag retains the audio and telemetry code;
ordinary release builds cannot emit soak records. Build/install it before collecting,
then open an authorized local game, choose PERFORMANCE mode and normal playback speed,
resume an active gameplay scene, and leave the game visible with audible system and
app volume. Do not enable `coffee_gb_benchmark`. The collector requires an already
running app process and verifies that the supplied APK matches the installed APK.
For reproducible comparisons, use the same local scene and input timeline on each
artifact and record a private scene description alongside the opaque run ID. The
collector intentionally does not read or store ROM names, paths, saves, or contents.
The effective media mute state matters: a Do Not Disturb policy may mute media even
after a volume command succeeds. Confirm audible playback before starting and restore
any temporary audio-policy changes after testing.

The benchmark APK defaults to M2 diagnostics on a bare launch. Start its ordinary
mode explicitly before choosing the scene (finish any current session first):

```sh
adb shell am force-stop eu.rekawek.coffeegb.android
adb shell am start -n eu.rekawek.coffeegb.android/.MainActivity --ez coffee_gb_benchmark false
```

The collector's later intent cannot convert an already active M2 runtime to ordinary
playback. It never force-stops or replaces the running session itself.

If the phone slept or the activity was backgrounded, waking it or bringing the activity
back to the foreground does not necessarily resume emulation. Use the ordinary menu's
**Resume** action and verify visible gameplay is moving before arming the collector. The
keep-awake flag applies only to an already awake, visible activity; it does not wake the
phone or resume paused gameplay. If a temporary screen-timeout extension is used during
setup, record the original value and restore it after the run.

After the collector exits, do not send a second soak-off start intent while the phone is
backgrounded or asleep. The collector already disables its own soak and TimeStats state;
a redundant cleanup launch can attempt a background service start and fail. Waking or
focusing the activity does not resume paused gameplay, so use the ordinary menu's
**Resume** path and verify movement before any new collection.

```sh
python3 android/performance_soak.py collect \
  --apk android/app/build/outputs/apk/benchmark/app-benchmark.apk \
  --power plugged \
  --output /tmp/coffee-gb-soak-candidate.jsonl

python3 android/performance_soak.py analyze /tmp/coffee-gb-soak-candidate.jsonl
python3 -m unittest discover -s android -p test_performance_soak.py -v
```

Use `--serial` when more than one Android device is attached. The default package is
`eu.rekawek.coffeegb.android`. Output creation is exclusive; choose a new file per run.
The default bound is 30 minutes; `--maximum-seconds` may increase it up to 3600.
`--minimum-seconds` may increase the 900-second minimum. Device commands have eight
second timeouts, and process/command cleanup adds a bounded tail. The collector stops
as soon as an eligible stable window yields PASS or FAIL; a thermal window still
changing at the bound is INCONCLUSIVE. Missing compatible app telemetry stops the
attempt after 30 seconds. Exit codes are 0 PASS, 1 FAIL, 2 INCONCLUSIVE.

`--power battery` is the default and requires actual unpowered operation, typically
using wireless ADB. A USB-powered run must use `--power plugged` and is labelled as
such. `dumpsys battery unplug` is not a battery test; overridden battery and thermal
reporting are rejected. Neither power policy is extrapolated to the other.

The collector enables the app's independent `coffee_gb_soak` intent flag, then enables
and clears SurfaceFlinger TimeStats. At the end it disables both. Do not run another
TimeStats measurement concurrently: the system counters are shared. These equivalent
manual commands are useful when diagnosing setup, and operate only on an existing
ordinary session:

```sh
adb shell am start -n eu.rekawek.coffeegb.android/.MainActivity --ez coffee_gb_benchmark false --ez coffee_gb_soak true
adb logcat -v raw -s CoffeeGbSoak:I '*:S'
adb shell am start -n eu.rekawek.coffeegb.android/.MainActivity --ez coffee_gb_benchmark false --ez coffee_gb_soak false
```

While the soak flag is enabled, the controller thread closes its ADPF hint session and
restores its original thread priority at the next work boundary. Emulation remains in
PERFORMANCE mode. The records include the actual hint-session state and thread priority;
boosted runs cannot pass this gate. Disabling the flag restores the ordinary performance
policy on the next work boundary. Surface content-rate hints remain active: they help
the display choose a suitable presentation cadence and are not CPU boost hints.
The Activity temporarily keeps its visible screen awake so a battery run can complete;
disabling the flag restores any keep-awake flag it added, without changing system
screen-timeout or developer power settings.

## Evidence and decisions

The versioned JSONL still has one `coffee-gb-soak-v1` metadata record, timestamped `app` and
`device` observations, and a final result. The live collector accepts only the asynchronous
`coffee-gb-soak-app-v2` records. The parser retains `coffee-gb-soak-app-v1` support for
offline analysis of historical runs, but v1 records cannot certify the asynchronous evidence
requirements below. Metadata includes APK and device hashes and an opaque run ID. V2 app
records contain:

- Controller monotonic time, master ticks, native VBlank frames, rendered frames,
  suppressed frames, hardware clock ratio, CGB DMG-compatibility state, CPU x1/x2
  state, and execution mode.
  CPU speed transitions are legal; native time remains in master ticks.
- Recent controller work p95/max, pacing debt, visibility, CPU hint/priority state.
- Completed app `unlockCanvasAndPost` submissions and failed submissions. These are
  **not compositor presentations**.
- Actual AudioTrack playback position, PCM writes, output/queue identity, underruns,
  overruns, discarded PCM, write/route failures, restarts, output play state, and app
  and system volume/mute state. This proves an active unmuted output consuming PCM;
  it does not record audio or replace a human listening check for sound quality.
- One immutable asynchronous audio request per owner sample. `audioSnapshotSequence` and
  `audioSnapshotDropped` expose busy requests; `FRESH` requires coherent route, output,
  queue, reopen, pause/flush, and PCM-policy generations. Drops, gaps, stale observations,
  incoherent boundaries, and unavailable audio make the evidence ineligible rather than
  being treated as zeroes.
- `hostTimeNanos` and all request/completion timestamps use Android's monotonic
  `System.nanoTime()` domain. `hostTimeNanos` marks the controller sample,
  `audioSnapshotRequestedAtNanos` marks enqueue, and `audioSnapshotCapturedAtNanos` marks
  the playback-position observation on the worker. `audioSnapshotCompletedAtNanos` includes
  later media-service queries. Audio cadence uses the captured observation timestamp, so
  cached playback counts are never paired with a later controller interval or completion
  timestamp; this is a separate observation timestamp, not a distinct hardware clock.

The host independently polls the exact active app SurfaceView, confirms its UID in
SurfaceFlinger TimeStats, and records real total presentations, dropped frames and
presentation interval histograms. Only the layer hash is retained. Current HAL CPU/skin
sensor temperatures (hashed sensor identities), thermal status, battery temperature,
power source, and the observed active display mode are also recorded. Raw logcat,
SurfaceFlinger, package, and thermal dumps are not written to the evidence file.

A PASS requires all of the following in the stable final ten minutes:

- At least 99% native emulation cadence overall and in every rolling ten-second
  window; two consecutive roughly one-second intervals below 95% fail. An isolated
  small dip can pass. Faster-than-native playback is ineligible.
- At least 99% native rendered, submitted, and real compositor presentation cadence,
  including rolling windows. A two-frame rolling tolerance handles observation
  boundaries; the overall 99% threshold has no such allowance. Suppressed frames
  are reported and cannot conceal sustained under-rendering.
- Continuous active unmuted audio, advancing at at least 99% of its sample rate in
  rolling ten-second windows. New underruns, overruns, discarded PCM, output restarts,
  write/route failures, or failed surface submissions fail the stable window.
- At most 1% dropped compositor frames or presentation intervals of at least 50 ms.
  Missing presentation histograms/counters are ineligible, never inferred as zero.
- One thermal status throughout the final ten minutes, and at most 1°C difference
  between each sensor's mean in the first and last minute. Stable throttled states
  are evaluated normally; critical thermal states fail. Continuing drift extends
  the attempt to its bound, then returns INCONCLUSIVE.
- A display refresh at least as fast as the hardware's native frame cadence. DMG,
  MGB, CGB, CGB0, compat and SGB2 fit 60 Hz; SGB is about 61.17 Hz and needs a faster
  mode (typically 120 Hz on the target device). The collector does not change display
  settings or claim full SGB presentation on a 60 Hz display.

Counter resets, session/recording replacement, clock/profile or audio route changes,
visibility loss, observation gaps, incompatible telemetry, and missing thermal/power
or active display evidence make the run INCONCLUSIVE. A work p95 above two-thirds of
the native frame budget is reported as `headroomRisk`, even when cadence passes; it
is not silently treated as proof of comfortable headroom. Pacing debt, suppression,
maximum work time, and audio/presentation outcomes remain visible in the report.

The collector's thermal status and sensor means, work p95/max, and `headroomRisk` are
observations for this run. It does not collect physical CPU-frequency telemetry or test
whether work cost stabilizes over time. Display refresh and emulated master-tick/clock
measurements are separate signals and must not be presented as CPU-frequency or full
work-trend stabilization evidence.

A single passing scene only validates that artifact/device/scene combination. Use
multiple representative persistent workloads and compare parent/candidate artifacts;
this gate supplies sustained evidence, not a claim about the entire game catalogue.

The collector validates the recorded overlap between owner samples and device observations.
It has no terminal drain acknowledgement, so delayed final delivery can leave the recording
tail outside the accepted overlap; that tail remains diagnostic data and is not silently
treated as complete evidence.

## Diagnosing a device performance cliff

The benchmark manifest permits shell profiling. On a device with `simpleperf`, take
a separate short sample while ordinary visible playback and the soak flag are active:

```sh
adb shell simpleperf record --app eu.rekawek.coffeegb.android -g --duration 15 \
  -o /data/local/tmp/coffee-gb-perf.data
adb shell simpleperf report -i /data/local/tmp/coffee-gb-perf.data \
  --sort comm,dso -n
adb shell simpleperf report -i /data/local/tmp/coffee-gb-perf.data \
  --sort comm,symbol --children -n --percent-limit 1
adb pull /data/local/tmp/coffee-gb-perf.data ./coffee-gb-perf.data
adb shell rm /data/local/tmp/coffee-gb-perf.data
```

Keep the exact APK, install-time `.dm`, and its R8 `mapping.txt` with the local
profile. Minified symbols may include inlined functions; mapping a compiled method
to its outer method does not attribute each instruction to a particular inlined
callee. The shared-object report separates AOT code (`base.odex`) from the JIT cache;
interpreter symbols need separate inspection. Leave ART compilation settings alone
for acceptance runs. Sampling itself adds work, so keep these intervals outside the
sustained gate and strict M2 measurements. Restore and verify the effective media
volume and Do Not Disturb state after any temporary audio changes; a successful
volume command alone does not prove that the device applied the requested value.
