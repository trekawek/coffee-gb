# CoffeeGbBench matrix

This is a fail-closed, device-only M2 measurement workflow. It accepts only redacted
`CoffeeGbBench` records and numeric SurfaceFlinger evidence; it never accepts, hashes, copies, or
logs a ROM, save, URI, title, payload, or path. Run the commands below from `android/` unless a
command is explicitly prefixed with `../`.

Build two distinct signed benchmark APKs with the repository workflow:

```text
cd ..
./upload_to_android.sh benchmark
```

The `benchmark` build type is signed with the machine-local Android debug key (no repository
secret). Parent and candidate must use the same certificate and must be distinct final signed APK
SHA-256 identities. The wrapper verifies the certificate and installs the selected APK; pass the
resulting signed APK paths to the scheduler.

The physical scheduler is the primary workflow. It pins exactly one configured Redmi device,
checks its model/API/UID/signing state, force-stops and reinstalls the selected artifact for every
run, pins 60 Hz for DMG/MGB/native CGB/CGB0/CGB-compat/SGB2 and 120 Hz for SGB, warms one real
SurfaceView anchor, takes a host baseline, arms one opaque app-generated epoch, and alternates
adjacent parent/candidate runs in 12 randomized seven-row blocks:

```text
./benchmark-device-matrix.sh \
  --parent-apk /path/to/parent-signed.apk \
  --candidate-apk /path/to/candidate-signed.apk \
  --color-slot <color-capable-recent-slot> \
  --non-color-slot <non-color-recent-slot> \
  --execution-mode accuracy \
  --output-dir /path/to/private-report-dir
```

`--execution-mode` accepts `accuracy` (the default) or `performance`. Run the matrix separately
for each mode; the app emits the selected strategy as `execution_mode` in both `matrix_run` and
`final_result` records. The setting is session-scoped and never enters save-state data.

For muted PERFORMANCE runs, pass `--audio-policy silent-pcm-v1`. This exact silent calendar
covers the complete seven-row matrix, including SGB and SGB2, while retaining its canonical APU
semantics. The explicitly relaxed `silent-pcm-relaxed-apu-v1` remains bounded to the five DMG/CGB
rows until its SGB clock has equivalent evidence. The host only reads the system music-volume/
mute state and fails closed if it is not already muted; it never changes system audio settings.

The two recent slots are selected by the user for the appropriate color/non-color workload. The
app assigns and durably persists random opaque workload nonces after selection; the host nonce
argument is deliberately not part of the workflow. Native CGB and CGB0 are separate rows, and
CGB-DMG-compat is a separate non-color row. An unavailable row is reported unavailable and never
substituted.

The host display target and producer cadence are different evidence. Legacy DMG/MGB/CGB/CGB0,
CGB-compat, and SGB2 use exact producer cadence `4,194,304/70,224 = 59.7275` FPS and a 60-Hz-or-
faster display target. SGB uses `47,250,000/772,464 ~= 61.168` FPS and a 120-Hz display target. The
app advertises the exact cadence (`surface_content_rate_millihz`) to `Surface.setFrameRate`; it
never advertises 120 FPS content for SGB. The report emits both `display_target_hz` and
`surface_content_rate_millihz`. A row is accepted only when its candidate bootstrap lower bound
is at least 99% of nominal and no individual parent or candidate run is below 58 FPS.

`unlockCanvasAndPost` is app submission evidence, not compositor presentation. For each run the
scheduler resolves the one active `SurfaceView[...](BLAST)#id` from `dumpsys SurfaceFlinger --list`,
then uses the API 35 text grammar from a finite timestats dump:

```text
adb shell dumpsys SurfaceFlinger --timestats -dump > before.txt
# run the one arm-relative 600-submission window and wait for settle
adb shell dumpsys SurfaceFlinger --timestats -dump > after.txt
./surface-timestats-gate.sh \
  --uid <installed-app-uid> --layer 'SurfaceView[...](BLAST)#<id>' \
  --display-refresh-hz <observed-rate> --before before.txt --after after.txt \
  --artifact-id <signed-apk-sha256> --device-id <redacted-device-sha256> \
  --pair-id <pair-token> --matrix-block <block-token> --row-order <0..6> \
  --run-side parent|candidate --benchmark-generation <app-generation>
```

The compositor gate requires one linked layer and deltas of exactly 600 total frames and 600
present2present histogram frames, with zero dropped, late-acquire, and bad-desired-present frames.
Its output is a bounded `compositor_result` record linked to the app artifact/device/pair/block/
side/generation. The layer name and full dumps remain temporary host data.

For parser-only review, `benchmark-matrix.sh` is pure JDK and does not require Gradle or Maven:

```text
./benchmark-matrix.sh --parent-apk /path/to/parent-signed.apk \
  --candidate-apk /path/to/candidate-signed.apk redacted.log
```

Use `--adb` only for a finite `adb logcat -d -v threadtime -s CoffeeGbBench:I '*:S'` dump. APK
arguments may be paths or explicit 64-hex hashes; log input must be `.log`/`.txt` or stdin.
Acceptance is pinned to the intrinsic installed base-APK hashes, not launch labels. Unknown fields,
unknown events, malformed values, mixed artifact/device/audio/display/environment evidence, missing
rows, missing compositor evidence, fewer than 12 complete pairs, and any workload-bearing value
are rejected. The parser retains bounded evidence and errors only; it has no ROM/save input.

Current builds may truthfully fail the performance or compositor gates. Do not relax the cadence,
display, 600-window, audio-continuity, thermal, power, or identity checks to make a run pass.

## Eight-cell PERFORMANCE goal matrix

`benchmark-goal-matrix.sh` is the focused PERFORMANCE workflow for four private, app-catalog
workloads. It runs exactly eight cells: D on DMG/CGB compatibility/SGB, U on
DMG/native CGB/SGB, and C1/C2 on native CGB. SGB2 is intentionally outside this contract. The
four recent entries must already occupy app-owned slots 0 through 3 in D, U, C1, C2 order; only
their random persisted workload nonces appear in evidence. ROM names, paths, titles, headers, and
hashes never leave the app-private selection boundary.

```text
./benchmark-goal-matrix.sh \
  --parent-apk /path/to/parent-signed.apk \
  --candidate-apk /path/to/candidate-signed.apk \
  --bootstrap fast-forward \
  --output-dir /path/to/private-report-dir
```

The default bootstrap is `fast-forward`; `skip` and `normal` are accepted for explicit validation.
Each artifact runs once per cell under the exact `silent-pcm-v1` calendar. The phone must already
have STREAM_MUSIC muted at volume zero. The runner only reads audio state, pins the required
60/120-Hz display mode transactionally, and restores every display setting on success or failure.
It requires an identity-bound boot result before ARM, an exact frame-600 core scheduler result,
600 ready/submitted frames, clean AudioTrack and compositor evidence, exact per-profile clocks,
and candidate ready/submission cadence at least 99% of nominal. The standalone strict report also
requires each candidate/parent cell ratio to be at least 0.995.

For the two SGB cells, the parent artifact is the explicit allocation control: it must report
exactly one output-array allocation for each of the 600 measured frames. The candidate must report
zero. This keeps the A/B mechanism visible in the core result instead of accepting two identical
optimized artifacts as a performance comparison.

Run `./benchmark-goal-matrix-test.sh` for the host-only three-bootstrap hermetic contract test. It
uses fake device commands and never changes a connected phone.
