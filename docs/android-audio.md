# Android audio operation and device validation

The Android frontend consumes the portable `StereoPcmConverter` used by the Swing adapter. It
performs fractional resampling, a two-period box filter, DC blocking, volume, muting, clipping,
and interleaved little-endian signed 16-bit stereo PCM conversion without Android dependencies.
The Android adapter constructs that converter with the initialized `AudioTrack` rate. A standard
native 44.1 or 48 kHz rate is tried first; unusual high native rates remain compatibility
fallbacks. The converter's fractional phase, PCM sequence, and selected sample rate are not
stretched, duplicated, or otherwise altered to hide host timing debt.

`AndroidAudioSink` is one producer/one consumer hand-off. Controller audio events run only the
producer path, which fills one of six preallocated PCM slots. If all available slots are busy, it
discards the oldest queued slot and increments the overrun counter. The only code that opens,
plays, pauses, flushes, writes, or releases an `AudioTrack` is the named
`coffee-gb-android-audio` consumer thread, which requests Android's audio thread priority on a
fail-soft basis. This keeps UI and emulation ownership separate and prevents a slow route from
increasing host-audio latency indefinitely.

Each fixed source slot is large enough for the maximum LEGACY, SGB, or SGB2 controller packet.
Hardware-profile cadence changes therefore update the event cadence without rebuilding the queue
or reopening `AudioTrack`, including when the first SGB packet immediately follows its profile
event. Each `AudioTrack` has capacity for at least five maximum converted packets at its actual
sample rate. The frontend asks Android to reduce the effective write reservoir toward that
five-packet target, although an OEM may retain a larger effective buffer. On Android 12 and newer,
the streaming start threshold is set to the universal minimum produced by four supported-profile
packets. Older releases conservatively use the effective reservoir reported by the platform and
accept an output only when that threshold is reachable with the six fixed source slots.

Initial open, explicit resume after pause/flush, and a legitimate output-route reopen all write at
least four complete, genuine, ordered PCM packets while stopped and never call `play()` before the
reported/conservative streaming start threshold is also present. Four packets provide roughly
65-67 ms, larger than the controller ticker's 50 ms debt ceiling. A pre-Android-12 threshold can
require part of packet five or six; that exact retained suffix is the first write after playback
starts, before any newer packet. Short writes are completed in order; a failed stopped output is
released and retained packets are replayed from their beginning into its replacement before
playback starts. Positive writes must remain aligned to one 4-byte PCM16 stereo frame, as required
by `AudioTrack`; a misaligned platform result fails closed into output recovery. No silence packet,
sample duplication, packet drop, rate change, or time stretch is used to manufacture the
reservoir.

On visibility or focus loss, the runtime pauses at its controller safe point, clears queued PCM,
and asks the existing battery-save lifecycle to flush. It never resumes automatically on focus
gain. A user Resume deliberately resumes the session and begins a fresh primer. An audio-device
callback releases and reopens the track on the consumer thread only when its change set contains
an output sink; queued same-rate PCM is retained in order for the replacement track's primer.
The producer offer is serialized with a sample-rate-changing queue handoff, so an event racing the
swap can only enter the replacement queue, never the already-cleared queue. Unrelated
microphone/input-device callbacks do not disturb playback. Transient play, pause, flush, and write
exceptions release the unusable track and recover through the same reopen and re-prime path
instead of terminating the audio worker. Pause/flush requests use a sticky monotonic fence, so even
an immediate pause then resume during a blocked short write cannot lose the flush or leave a packet
parked behind an already-playing output. A mute or volume change has its own generation fence: it
flushes PCM already held under the old policy, rejects any raced stale packet, and primes again
using only the new policy.

The consumer also treats a rise in `AudioTrack`'s cumulative underrun count as a recovery signal,
not merely a statistic. Android pauses a starved streaming data path until its start threshold is
refilled, so the worker keeps writing genuine ordered PCM into the same playing track. It tracks
every accepted aligned byte, including positive short writes, and extends the unsigned 32-bit
playback head across wrap within each output epoch. Repeated counter rises coalesce into the same
refill, but every newer rise establishes a fresh post-counter playback-head baseline and revokes
the preceding refill and stall proof. Ordinary streaming resumes only after genuine writes rebuild
the latest missing start-threshold reservoir and the head then advances beyond that latest
baseline, without another `play()`, or any pause, flush, duplication, drop, or manufactured
silence.

Refill writes never exceed the track's reported effective capacity, avoiding a blocking write that
could deadlock behind a frozen output. A capacity-full head that remains frozen for a bounded grace
period is released and reopened through the normal genuine-primer path. Completed refill packets
are committed as they enter the playing output, so even an OEM-large effective reservoir does not
tie up the fixed source slots. A missing, invalid, or
persistently non-progressing playback head uses the same fail-safe. As with a physical route loss,
that exceptional replacement can have a discontinuity because the failed output cannot prove what
reached the speaker. Statistics
include producer overruns, consumer underruns, and successful output restarts for diagnostics and
test probes. Closing the session unregisters the route callback and releases the output on that
same consumer thread.

The benchmark APK has one deliberately narrow exception: its established pre-ARM proof must move
an empty `AudioTrack` from stopped to playing while the emulated guest remains paused and cannot
produce PCM. Only that diagnostics-only runtime barrier may request empty playback. Ordinary app
startup, lifecycle resume, and route recovery cannot use the exception and always require the
four-packet genuine-PCM primer. Expected underrun-counter movement is ignored only while this empty
diagnostics play is authorized. The first genuine guest packet revokes the exception through the
same sticky stop/flush boundary, and playback cannot restart until all four real primer packets are
present.

No audio permission is needed for playback. The frontend requests neither microphone access nor
Internet, and no audio payload, ROM path, URI, or persistent game content is logged or exported.

## Device validation procedure

Automated JVM and Android unit tests prove converter parity, maximum-profile bounded slots,
capacity, threshold-aware primer duration, exact ordered PCM under short and threshold-splitting
writes, consumer-thread-only writes, exception recovery, route and sample-rate handoff, rapid
pause/resume/re-prime, playback-head wrap, in-place actual-underrun refill, frozen-output fallback,
and release. They do not claim physical latency measurements. Before
a release, run the following on the current Android emulator and at
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
