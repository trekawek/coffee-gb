# Android audio operation and device validation

The Android frontend consumes the portable `StereoPcmConverter` used by the Swing adapter. It
performs fractional resampling, a two-period box filter, DC blocking, volume, muting, clipping,
and interleaved little-endian signed 16-bit stereo PCM conversion without Android dependencies.
The Android adapter constructs that converter with the initialized `AudioTrack` rate.

`AndroidAudioSink` is one producer/one consumer hand-off. Controller audio events run only the
producer path, which fills one of six preallocated PCM slots. If all available slots are busy, it
discards the oldest queued slot and increments the overrun counter. The only code that opens,
plays, pauses, flushes, writes, or releases an `AudioTrack` is the named
`coffee-gb-android-audio` consumer thread. This keeps UI and emulation ownership separate and
prevents a slow route from increasing host-audio latency indefinitely.

On visibility or focus loss, the runtime pauses at its controller safe point, clears queued PCM,
and asks the existing battery-save lifecycle to flush. It never resumes automatically on focus
gain. A user Resume deliberately resumes the session and audio output. An audio-device callback releases
and reopens the track on the consumer thread; PCM queued for the old route is cleared. Statistics
include producer overruns, consumer underruns, and successful output restarts for diagnostics and
test probes. Closing the session unregisters the route callback and releases the output on that
same consumer thread.

No audio permission is needed for playback. The frontend requests neither microphone access nor
Internet, and no audio payload, ROM path, URI, or persistent game content is logged or exported.

## Device validation procedure

Automated JVM and Android unit tests prove converter parity, bounded slots, consumer-thread-only
writes, underrun recovery, route reopen, pause/resume, and release. They do not claim physical
latency measurements. Before a release, run the following on the current Android emulator and at
least one physical mid-range device, then record the model, Android version, active route/rate,
and observed overrun/underrun/restart counts in the release or issue evidence:

1. Install the debug APK, load an owner-authorized game, and let audio warm up for one minute.
2. Use wired and, when available, Bluetooth or speaker routes; connect and disconnect each while
   the game is running, then confirm the next user Resume produces clean audio with no growing lag.
3. Background the app and trigger a transient audio-focus loss. Confirm it pauses, flushes, saves,
   and remains paused until the explicit Resume action.
4. Play for ten minutes after warm-up on each route. Capture the sink counters and note audible
   glitches and estimated end-to-end latency.

Device measurements are intentionally not fabricated by the repository: they require an attached
emulator/device and an owner-authorized game run.
