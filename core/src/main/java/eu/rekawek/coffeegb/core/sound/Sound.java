package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.debug.DebugAudioChannelInspection;
import eu.rekawek.coffeegb.core.debug.DebugAudioInspection;
import eu.rekawek.coffeegb.core.debug.DebugByteData;
import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.trace.ApuTrace;
import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;
import eu.rekawek.coffeegb.core.memory.Ram;
import eu.rekawek.coffeegb.core.timer.Timer;

import java.util.Arrays;
import java.util.Objects;

public class Sound implements AddressSpace, StatefulComponent<Sound> {

    /**
     * Host-only PERFORMANCE audio calendars. OFF is the normal path, EXACT preserves the APU
     * channel clock state at every materialization boundary, and RELAXED_APU keeps the frame
     * sequencer/control-plane calendar while deliberately dropping deferred channel clocks.
     */
    public enum PerformanceSystemMutedAudioMode {
        OFF,
        EXACT,
        RELAXED_APU
    }

    /**
     * PERFORMANCE audio is intentionally represented at a decimated master-tick rate. The normal
     * source is sampled at approximately 76.26 kHz, which is still comfortably above the host
     * audio band and gives the emulator a 54-master-tick quiet window in which to batch the PSG.
     * SGB-family clocks use a 56-tick decimation, yielding exactly 1,254 samples in their
     * 70,224-tick frame while retaining a 55-master-tick quiet window. The pending span is
     * transient: CPU-visible operations and state boundaries materialize it before observing or
     * serializing channel state.
     */
    private static final int PERFORMANCE_AUDIO_DECIMATION = 55;

    /** Retained for portable PERFORMANCE states written before SGB decimation was widened. */
    private static final int LEGACY_SGB_PERFORMANCE_AUDIO_DECIMATION = 11;

    /** SGB/SGB2 compact samples exactly divide their 70,224-tick controller frame. */
    private static final int SGB_PERFORMANCE_AUDIO_DECIMATION = 56;

    private static final int ACCURACY_AUDIO_DECIMATION = 1;

    private static final int CGB_BOOT_DIV_APU_OFFSET = 2;

    private static final int[] MASKS =
            new int[]{
                    0x80, 0x3f, 0x00, 0xff, 0xbf, 0xff, 0x3f, 0x00, 0xff, 0xbf, 0x7f, 0xff, 0x9f, 0xff, 0xbf,
                    0xff, 0xff, 0x00, 0x00, 0xbf, 0x00, 0x00, 0x70, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
                    0xff, 0xff, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00
            };

    private final FrameSequencer frameSequencer = new FrameSequencer();

    private final SoundMode1 mode1;

    private final SoundMode2 mode2;

    private final SoundMode3 mode3;

    private final SoundMode4 mode4;

    private final AbstractSoundMode[] allModes;

    private final Ram r = new Ram(0xff24, 0x03);

    private int volume = 0x77;

    private int routing;

    private final int[] channels = new int[4];

    /** Derived mixer state; it is intentionally absent from portable sound state. */
    private transient boolean mixerDirty = true;

    private transient int mixedLeft;

    private transient int mixedRight;

    private boolean enabled = true;

    private final boolean[] overriddenEnabled = {true, true, true, true};

    private final int[] buffer;

    /** Clock identity advertised for the compact PERFORMANCE source stream. */
    private final ClockSpec outputClockSpec;

    private final boolean performanceAudio;

    private final int performanceAudioDecimation;

    private int i = 0;

    /** PERFORMANCE-only decimation state; included in save states at a mid-window boundary. */
    private int performanceSamplePhase;

    /**
     * PERFORMANCE-only channel clocks deferred by quiet spans. This is deliberately not part of
     * SoundState: captureState() materializes it first and restoreState() always resets it.
     */
    private transient int pendingPerformanceTicks;

    /**
     * Host-controlled PERFORMANCE-only calendar for a system-muted benchmark. It skips per-tick
     * channel dispatch and emits zero PCM while retaining sample cadence; deferred channel clocks
     * are materialized canonically at every existing APU boundary. It is never part of machine
     * state and is disabled by default.
     */
    private transient PerformanceSystemMutedAudioMode performanceSystemMutedAudioMode =
            PerformanceSystemMutedAudioMode.OFF;

    private transient long performanceSystemMutedAudioCalendarSkippedTicks;

    private transient long performanceSystemMutedAudioCalendarZeroSampleSlots;

    private transient long performanceSystemMutedAudioCalendarZeroSampleEvents;

    private transient long performanceSystemMutedAudioCalendarMaxPendingTicks;

    private transient long performanceSystemMutedAudioCalendarDroppedChannelTicks;

    private transient long performanceSystemMutedAudioCalendarApuReads;

    private transient long performanceSystemMutedAudioCalendarApuWrites;

    private transient long performanceSystemMutedAudioCalendarFrameSequencerCommits;

    private final Timer timer;

    private final boolean gbc;

    private transient EventBus eventBus = EventBus.NULL_EVENT_BUS;

    private final eu.rekawek.coffeegb.core.cpu.SpeedMode speedMode;

    private int pendingFrameSequencerStep = -1;

    /**
     * Position of the APU clock relative to the CPU clock mux. Gambatte's PSG keeps
     * this as the low two bits of its last-update timestamp: each speed switch moves
     * it by one sub-clock, and phase 1 makes a natural DIV-APU clock visible after the
     * CPU bus access in the same master tick.
     */
    private int frameSequencerClockPhase;

    /**
     * The later-revision CGB boot starts the CPU divider ten clocks into its period,
     * while the PSG tap starts at absolute phase twelve. Their relative offset is
     * therefore two clocks. A write to DIV resets both domains and removes it.
     */
    private int frameSequencerDivOffset;

    /** Owner-thread observation only; deliberately absent from portable machine state. */
    private transient DebugHooks debugHooks;

    /** Optional exact-output tap; disabled hot-path cost is one predictable null branch. */
    private transient SoundOutputObserver outputObserver;

    public Sound(Timer timer, eu.rekawek.coffeegb.core.cpu.SpeedMode speedMode, boolean gbc) {
        this(timer, speedMode, gbc, ClockSpec.LEGACY);
    }

    public Sound(Timer timer, eu.rekawek.coffeegb.core.cpu.SpeedMode speedMode, boolean gbc,
                 ClockSpec clockSpec) {
        this(timer, speedMode, gbc, clockSpec, ExecutionMode.ACCURACY);
    }

    /**
     * Builds the sound device for a specific execution mode.  The four-argument constructor is
     * deliberately retained as the exact/legacy default for standalone components and older
     * callers; only a Gameboy explicitly configured for PERFORMANCE opts into compact host audio.
     */
    public Sound(Timer timer, eu.rekawek.coffeegb.core.cpu.SpeedMode speedMode, boolean gbc,
                 ClockSpec clockSpec, ExecutionMode executionMode) {
        this.timer = timer;
        this.speedMode = speedMode;
        this.gbc = gbc;
        Objects.requireNonNull(clockSpec, "clockSpec");
        this.performanceAudio = Objects.requireNonNull(executionMode, "executionMode")
                == ExecutionMode.PERFORMANCE;
        this.performanceAudioDecimation = performanceAudio && isSgbClock(clockSpec)
                ? SGB_PERFORMANCE_AUDIO_DECIMATION : PERFORMANCE_AUDIO_DECIMATION;
        this.outputClockSpec = performanceAudio
                ? decimatedClock(clockSpec, performanceAudioDecimation)
                : clockSpec;
        this.buffer = new int[Math.multiplyExact(outputClockSpec.controllerTicksPerFrame(), 2)];
        frameSequencerDivOffset = gbc ? CGB_BOOT_DIV_APU_OFFSET : 0;
        mode1 = new SoundMode1(frameSequencer, gbc);
        mode2 = new SoundMode2(frameSequencer, gbc);
        mode3 = new SoundMode3(frameSequencer, timer, gbc);
        mode4 = new SoundMode4(frameSequencer, gbc);
        allModes = new AbstractSoundMode[]{mode1, mode2, mode3, mode4};
        // Initial volume
        r.setByte(0xFF24, 0x77);
    }

    private static ClockSpec decimatedClock(ClockSpec source, int decimation) {
        return new ClockSpec(
                source.ticksPerSecondNumerator(),
                Math.multiplyExact(source.ticksPerSecondDenominator(), decimation),
                source.controllerFramesPerSecondNumerator(),
                source.controllerFramesPerSecondDenominator());
    }

    private int audioDecimation() {
        return performanceAudio ? performanceAudioDecimation : ACCURACY_AUDIO_DECIMATION;
    }

    private static boolean isSgbClock(ClockSpec clockSpec) {
        return ClockSpec.SGB.equals(clockSpec) || ClockSpec.SGB2.equals(clockSpec);
    }

    public void init(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    public void tick() {
        tick(timer.consumeDivReset());
    }

    public void tick(boolean divReset) {
        materializePendingPerformanceTicks();
        if (!enabled) {
            play(0, 0);
            return;
        }

        int enabledBefore = debugHooks == null ? 0 : getEnabledChannelMask();

        int channel1 = mode1.tick(divReset);
        int channel2 = mode2.tick(divReset);
        int channel3 = mode3.tick();
        int channel4 = mode4.tick();
        boolean channelOutputsChanged = channel1 != channels[0]
                | channel2 != channels[1]
                | channel3 != channels[2]
                | channel4 != channels[3];
        mixerDirty |= channelOutputsChanged;
        channels[0] = channel1;
        channels[1] = channel2;
        channels[2] = channel3;
        channels[3] = channel4;
        if (debugHooks != null) {
            notifyDebugChannelDisables(enabledBefore);
        }

        if (mixerDirty) {
            mixChannels();
        }
        play(mixedLeft, mixedRight);
    }

    /**
     * Defers a PERFORMANCE-only quiet span without visiting the four channel dispatches for
     * every master tick. The caller must split the span at CPU-visible sound writes,
     * frame-sequencer commits, DIV resets, and compact-output boundaries. The channel clocks are
     * materialized arithmetically at the next such boundary.
     */
    public void tickPerformanceQuietSpan(int ticks) {
        if (ticks <= 0) {
            return;
        }
        if (performanceSystemMutedAudioCalendarUsable()) {
            accumulateSilentPcmTicks(ticks);
            return;
        }
        if (!performanceAudio || debugHooks != null || outputObserver != null
                || performanceSamplePhase + ticks >= performanceAudioDecimation) {
            materializePendingPerformanceTicks();
            for (int j = 0; j < ticks; j++) {
                tick(false);
            }
            return;
        }
        // The PERFORMANCE horizon stops before the next decimated sample, where the pending span
        // is materialized. Consequently this accumulator is bounded by
        // performanceAudioDecimation - 1, so an overflow-checked add in the hot path cannot
        // provide any additional protection.
        pendingPerformanceTicks += ticks;
        performanceSamplePhase += ticks;
    }

    /**
     * Makes deferred PERFORMANCE channel clocks visible without producing host samples. This is
     * the sole transition from the lazy span representation back to canonical channel state.
     */
    public void materializePendingPerformanceTicks() {
        int ticks = pendingPerformanceTicks;
        if (ticks <= 0) {
            return;
        }
        pendingPerformanceTicks = 0;
        if (performanceSystemMutedAudioMode == PerformanceSystemMutedAudioMode.RELAXED_APU) {
            performanceSystemMutedAudioCalendarDroppedChannelTicks += ticks;
            return;
        }
        if (!enabled) {
            return;
        }
        int channel1 = mode1.tickPerformanceSpan(ticks);
        int channel2 = mode2.tickPerformanceSpan(ticks);
        int channel3 = mode3.tickPerformanceSpan(ticks);
        int channel4 = mode4.tickPerformanceSpan(ticks);
        boolean channelOutputsChanged = channel1 != channels[0]
                | channel2 != channels[1]
                | channel3 != channels[2]
                | channel4 != channels[3];
        mixerDirty |= channelOutputsChanged;
        channels[0] = channel1;
        channels[1] = channel2;
        channels[2] = channel3;
        channels[3] = channel4;
        if (mixerDirty) {
            mixChannels();
        }
    }

    private void mixChannels() {
        int selection = routing;
        int left = 0;
        int right = 0;
        // The DAC maps digital 0-15 to analog +15..-15. Keep the call sites concrete so
        // HotSpot can inline this 4.2 MHz mixer rather than dispatching through allModes.
        if (overriddenEnabled[0]) {
            int analog = mode1.dacEnabled ? 15 - 2 * channels[0] : 0;
            if ((selection & 0x10) != 0) {
                left += analog;
            }
            if ((selection & 0x01) != 0) {
                right += analog;
            }
        }
        if (overriddenEnabled[1]) {
            int analog = mode2.dacEnabled ? 15 - 2 * channels[1] : 0;
            if ((selection & 0x20) != 0) {
                left += analog;
            }
            if ((selection & 0x02) != 0) {
                right += analog;
            }
        }
        if (overriddenEnabled[2]) {
            int analog = mode3.dacEnabled ? 15 - 2 * channels[2] : 0;
            if ((selection & 0x40) != 0) {
                left += analog;
            }
            if ((selection & 0x04) != 0) {
                right += analog;
            }
        }
        if (overriddenEnabled[3]) {
            int analog = mode4.dacEnabled ? 15 - 2 * channels[3] : 0;
            if ((selection & 0x80) != 0) {
                left += analog;
            }
            if ((selection & 0x08) != 0) {
                right += analog;
            }
        }

        // NR50 volume 0 means "very quiet", not silence: the scale factor is volume+1
        int volumes = volume;
        left *= ((volumes >> 4) & 0b111) + 1;
        right *= (volumes & 0b111) + 1;

        mixedLeft = left;
        mixedRight = right;
        mixerDirty = false;
    }

    /**
     * Updates DIV-APU independently of channel sampling. It is called both before the
     * CPU (for natural DIV edges) and after it (for an edge caused by an FF04 write).
     */
    public void tickFrameSequencer() {
        tickFrameSequencer(timer.isDivResetPending());
    }

    public void tickFrameSequencer(boolean divReset) {
        int divCounter = (timer.getDivCounter() + frameSequencerDivOffset) & 0xffff;
        int firedStep = frameSequencer.tick(divCounter, enabled, speedMode.getSpeedMode() == 2);
        if (firedStep >= 0) {
            pendingFrameSequencerStep = firedStep;
        }
        if (divReset) {
            frameSequencerDivOffset = 0;
        }
    }

    /**
     * Commits clocks selected by the DIV-APU edge sampled earlier in this master tick.
     * The sequencer phase changes before the CPU access, while length/status side effects
     * become visible immediately after it.
     */
    public void commitFrameSequencerClock() {
        int firedStep = pendingFrameSequencerStep;
        pendingFrameSequencerStep = -1;
        if (firedStep < 0) {
            return;
        }
        if (performanceSystemMutedAudioMode != PerformanceSystemMutedAudioMode.OFF) {
            performanceSystemMutedAudioCalendarFrameSequencerCommits++;
        }
        materializePendingPerformanceTicks();
        int enabledBefore = getDebugEnabledChannelMask();
        notifyDebugEvent(ApuTrace.Kind.FRAME_SEQUENCER_STEP, -1, -1, firedStep);
        for (AbstractSoundMode m : allModes) m.tickEnvelopeClock(firedStep);
        if ((firedStep & 1) == 0) {
            for (AbstractSoundMode m : allModes) m.tickLength();
        }
        if (firedStep == 2 || firedStep == 6) {
            for (AbstractSoundMode m : allModes) m.tickSweep();
        }
        if (firedStep == 7) {
            for (AbstractSoundMode m : allModes) m.tickEnvelope();
        }
        notifyDebugChannelDisables(enabledBefore);
    }

    public boolean isFrameSequencerClockAfterCpu() {
        return frameSequencerClockPhase == 1;
    }

    /** True when a DIV edge has queued a frame-sequencer side effect not yet committed. */
    public boolean hasPendingFrameSequencerClock() {
        return pendingFrameSequencerStep >= 0;
    }

    /** Returns the largest compact-output-safe quiet span not crossing the next sample slot. */
    public int performanceQuietSpanLimit(int requested) {
        if (requested <= 0 || !performanceAudio || debugHooks != null || outputObserver != null
                || pendingFrameSequencerStep >= 0) {
            return 0;
        }
        if (performanceSystemMutedAudioMode != PerformanceSystemMutedAudioMode.OFF) {
            return requested;
        }
        return Math.min(requested,
                performanceAudioDecimation - performanceSamplePhase - 1);
    }

    /**
     * Horizon used by the native-CGB coarse epoch.  With lazy PERFORMANCE audio enabled,
     * stop immediately before the next compact sample; with audio disabled there is no host
     * sample boundary to cross and the regular per-tick state update remains exact.
     */
    public int performanceEpochSpanLimit(int requested) {
        if (requested <= 0 || debugHooks != null || outputObserver != null
                || pendingFrameSequencerStep >= 0) {
            return 0;
        }
        if (!performanceAudio) {
            return requested;
        }
        if (performanceSystemMutedAudioMode != PerformanceSystemMutedAudioMode.OFF) {
            return requested;
        }
        return Math.min(requested,
                performanceAudioDecimation - performanceSamplePhase - 1);
    }


    /**
     * Defers the Sound clock belonging to a scalar PERFORMANCE scheduler boundary. CPU-visible
     * work in that tick has already had a chance to materialize pending clocks through get/set
     * accessors; only the next compact-output boundary needs to run this tick immediately.
     */
    public void tickPerformanceBoundary(boolean divReset) {
        if (performanceSystemMutedAudioCalendarUsable()) {
            accumulateSilentPcmTicks(1);
            return;
        }
        if (!performanceAudio || debugHooks != null || outputObserver != null
                || performanceSamplePhase + 1 >= performanceAudioDecimation) {
            tick(divReset);
            return;
        }
        // The branch above materializes on the sample boundary, bounding the pending
        // count to the same sub-decimation interval as tickPerformanceQuietSpan().
        pendingPerformanceTicks++;
        performanceSamplePhase++;
    }

    public void onSpeedSwitch() {
        materializePendingPerformanceTicks();
        frameSequencerClockPhase = (frameSequencerClockPhase + 1) & 3;
    }

    /**
     * Compatibility setter for the original silent-pcm-v1 policy. True selects EXACT; false
     * selects OFF. New callers should use {@link #setPerformanceSystemMutedAudioMode}.
     */
    public void setPerformanceSystemMutedAudioCalendar(boolean enabled) {
        setPerformanceSystemMutedAudioMode(enabled
                ? PerformanceSystemMutedAudioMode.EXACT
                : PerformanceSystemMutedAudioMode.OFF);
    }

    /** Sets the transient owner-thread system-muted PERFORMANCE audio calendar mode. */
    public void setPerformanceSystemMutedAudioMode(PerformanceSystemMutedAudioMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (mode == performanceSystemMutedAudioMode) {
            return;
        }
        if (mode != PerformanceSystemMutedAudioMode.OFF && !performanceAudio) {
            throw new IllegalStateException(
                    "system-muted audio calendar requires PERFORMANCE audio");
        }
        // Materialize using the old mode before changing policy. This preserves exact channel
        // state when arming relaxed mode and records any intentionally dropped debt when turning
        // relaxed mode off.
        materializePendingPerformanceTicks();
        if (mode != PerformanceSystemMutedAudioMode.OFF) {
            Arrays.fill(buffer, 0);
        }
        performanceSystemMutedAudioMode = mode;
    }

    public boolean isPerformanceSystemMutedAudioCalendarEnabled() {
        return performanceSystemMutedAudioMode != PerformanceSystemMutedAudioMode.OFF;
    }

    public PerformanceSystemMutedAudioMode getPerformanceSystemMutedAudioMode() {
        return performanceSystemMutedAudioMode;
    }

    public void resetPerformanceSystemMutedAudioCalendarCounters() {
        performanceSystemMutedAudioCalendarSkippedTicks = 0L;
        performanceSystemMutedAudioCalendarZeroSampleSlots = 0L;
        performanceSystemMutedAudioCalendarZeroSampleEvents = 0L;
        performanceSystemMutedAudioCalendarMaxPendingTicks = 0L;
        performanceSystemMutedAudioCalendarDroppedChannelTicks = 0L;
        performanceSystemMutedAudioCalendarApuReads = 0L;
        performanceSystemMutedAudioCalendarApuWrites = 0L;
        performanceSystemMutedAudioCalendarFrameSequencerCommits = 0L;
    }

    public long getPerformanceSystemMutedAudioCalendarSkippedTicks() {
        return performanceSystemMutedAudioCalendarSkippedTicks;
    }

    public long getPerformanceSystemMutedAudioCalendarZeroSampleSlots() {
        return performanceSystemMutedAudioCalendarZeroSampleSlots;
    }

    public long getPerformanceSystemMutedAudioCalendarZeroSampleEvents() {
        return performanceSystemMutedAudioCalendarZeroSampleEvents;
    }

    public long getPerformanceSystemMutedAudioCalendarMaxPendingTicks() {
        return performanceSystemMutedAudioCalendarMaxPendingTicks;
    }

    public long getPerformanceSystemMutedAudioCalendarDroppedChannelTicks() {
        return performanceSystemMutedAudioCalendarDroppedChannelTicks;
    }

    public long getPerformanceSystemMutedAudioCalendarApuReads() {
        return performanceSystemMutedAudioCalendarApuReads;
    }

    public long getPerformanceSystemMutedAudioCalendarApuWrites() {
        return performanceSystemMutedAudioCalendarApuWrites;
    }

    public long getPerformanceSystemMutedAudioCalendarFrameSequencerCommits() {
        return performanceSystemMutedAudioCalendarFrameSequencerCommits;
    }

    private boolean performanceSystemMutedAudioCalendarUsable() {
        return performanceSystemMutedAudioMode != PerformanceSystemMutedAudioMode.OFF
                && performanceAudio
                && debugHooks == null && outputObserver == null
                && pendingFrameSequencerStep < 0;
    }

    private void accumulateSilentPcmTicks(int ticks) {
        pendingPerformanceTicks += ticks;
        performanceSystemMutedAudioCalendarSkippedTicks += ticks;
        performanceSystemMutedAudioCalendarMaxPendingTicks = Math.max(
                performanceSystemMutedAudioCalendarMaxPendingTicks, pendingPerformanceTicks);

        long phaseTicks = (long) performanceSamplePhase + ticks;
        int sampleSlots = (int) (phaseTicks / performanceAudioDecimation);
        performanceSamplePhase = (int) (phaseTicks % performanceAudioDecimation);
        performanceSystemMutedAudioCalendarZeroSampleSlots += sampleSlots;
        for (int slot = 0; slot < sampleSlots; slot++) {
            buffer[i] = 0;
            buffer[i + 1] = 0;
            i += 2;
            if (i == buffer.length) {
                performanceSystemMutedAudioCalendarZeroSampleEvents++;
                eventBus.post(new SoundSampleEvent(buffer, outputClockSpec));
                i = 0;
            }
        }
    }

    /** Package-private so the disabled exact-output hot path can be allocation-regression tested. */
    void play(int left, int right) {
        SoundOutputObserver observer = outputObserver;
        if (observer != null) {
            observer.onSample(left, right);
        }
        if (performanceAudio) {
            if (performanceSamplePhase < performanceAudioDecimation - 1) {
                performanceSamplePhase++;
                return;
            }
            performanceSamplePhase = 0;
        }
        buffer[i] = left;
        buffer[i + 1] = right;
        i += 2;
        if (i == buffer.length) {
            eventBus.post(new SoundSampleEvent(buffer, outputClockSpec));
            i = 0;
        }
    }

    private AddressSpace getAddressSpace(int address) {
        if (r.accepts(address)) {
            return r;
        }
        for (AbstractSoundMode m : allModes) {
            if (m.accepts(address)) {
                return m;
            }
        }
        return null;
    }

    @Override
    public boolean accepts(int address) {
        if (address == 0xff76 || address == 0xff77) {
            // the PCM12/PCM34 registers only exist on the CGB
            return gbc;
        }
        return getAddressSpace(address) != null;
    }

    @Override
    public void setByte(int address, int value) {
        if (performanceSystemMutedAudioMode != PerformanceSystemMutedAudioMode.OFF) {
            performanceSystemMutedAudioCalendarApuWrites++;
        }
        materializePendingPerformanceTicks();
        if (address == 0xff26) {
            int enabledBefore = getDebugEnabledChannelMask();
            if ((value & (1 << 7)) == 0) {
                if (enabled) {
                    enabled = false;
                    stop();
                }
            } else {
                if (!enabled) {
                    enabled = true;
                    start();
                }
            }
            notifyDebugRegisterWrite(-1, address, value);
            notifyDebugChannelDisables(enabledBefore);
            mixerDirty = true;
            return;
        }

        if (!enabled && address < 0xff30) {
            // while the APU is off, the only writable register bits are the DMG length
            // counters (and NR52 handled above); everything else is ignored
            int channel = -1;
            if (!gbc) {
                switch (address) {
                    case 0xff11:
                        allModes[0].writeLengthWhileOff(value);
                        channel = 1;
                        break;
                    case 0xff16:
                        allModes[1].writeLengthWhileOff(value);
                        channel = 2;
                        break;
                    case 0xff1b:
                        allModes[2].writeLengthWhileOff(value);
                        channel = 3;
                        break;
                    case 0xff20:
                        allModes[3].writeLengthWhileOff(value);
                        channel = 4;
                        break;
                }
            }
            if (channel != -1) {
                mixerDirty = true;
                notifyDebugRegisterWrite(channel, address, value);
            }
            return;
        }

        AddressSpace s = getAddressSpace(address);
        if (s == null) {
            return;
        }
        boolean accepted = !(s instanceof SoundMode3 mode3)
                || mode3.isWriteAccepted(address);
        int enabledBefore = getDebugEnabledChannelMask();
        s.setByte(address, value);
        if (address == 0xff24) {
            volume = value;
        } else if (address == 0xff25) {
            routing = value;
        }
        if (!accepted) {
            return;
        }
        mixerDirty = true;
        int channel = getDebugChannel(address);
        notifyDebugRegisterWrite(channel, address, value);
        if (isTriggerRegister(address) && (value & 0x80) != 0) {
            notifyDebugEvent(ApuTrace.Kind.CHANNEL_TRIGGERED, channel, address, value & 0xff);
        }
        notifyDebugChannelDisables(enabledBefore);
    }

    /** Installs an optional owner-thread observer without emitting an alignment event. */
    public void setDebugHooks(DebugHooks debugHooks) {
        materializePendingPerformanceTicks();
        this.debugHooks = debugHooks;
    }

    /**
     * Exclusively attaches an exact-output observer at an owner-thread safe point.
     *
     * @return {@code false} when another observer already owns the transient tap
     */
    public boolean attachOutputObserver(SoundOutputObserver observer) {
        Objects.requireNonNull(observer, "observer");
        materializePendingPerformanceTicks();
        if (outputObserver != null) {
            return false;
        }
        outputObserver = observer;
        return true;
    }

    /** Detaches only the observer that currently owns the transient tap. */
    public boolean detachOutputObserver(SoundOutputObserver observer) {
        Objects.requireNonNull(observer, "observer");
        materializePendingPerformanceTicks();
        if (outputObserver != observer) {
            return false;
        }
        outputObserver = null;
        return true;
    }

    private void notifyDebugRegisterWrite(int channel, int address, int value) {
        notifyDebugEvent(ApuTrace.Kind.REGISTER_WRITTEN, channel, address, value & 0xff);
    }

    private void notifyDebugChannelDisables(int enabledBefore) {
        DebugHooks hooks = debugHooks;
        if (hooks == null || enabledBefore == 0) {
            return;
        }
        int disabled = enabledBefore & ~getEnabledChannelMask();
        for (int i = 0; i < allModes.length; i++) {
            if ((disabled & (1 << i)) != 0) {
                hooks.onApuEvent(ApuTrace.Kind.CHANNEL_DISABLED, i + 1, -1, -1);
            }
        }
    }

    private void notifyDebugEvent(ApuTrace.Kind kind, int channel, int register, int value) {
        DebugHooks hooks = debugHooks;
        if (hooks != null) {
            hooks.onApuEvent(kind, channel, register, value);
        }
    }

    private int getDebugEnabledChannelMask() {
        return debugHooks == null ? 0 : getEnabledChannelMask();
    }

    private int getEnabledChannelMask() {
        int result = 0;
        for (int i = 0; i < allModes.length; i++) {
            if (allModes[i].isEnabled()) {
                result |= 1 << i;
            }
        }
        return result;
    }

    /** Captures internal APU state without applying CPU register masks or wave-RAM locks. */
    public DebugAudioInspection captureDebugAudioInspection() {
        materializePendingPerformanceTicks();
        var debugChannels = new java.util.ArrayList<DebugAudioChannelInspection>(4);
        for (int i = 0; i < allModes.length; i++) {
            AbstractSoundMode mode = allModes[i];
            debugChannels.add(new DebugAudioChannelInspection(
                    i + 1,
                    mode.isEnabled(),
                    mode.isDacEnabled(),
                    mode.isEnabled() ? mode.getCurrentOutput() : 0,
                    mode.length.getValue(),
                    mode.length.isEnabled(),
                    i == 1 || i == 3 ? 0 : mode.getNr0(),
                    mode.getNr1(),
                    mode.getNr2(),
                    mode.getNr3(),
                    mode.getNr4()));
        }
        return new DebugAudioInspection(
                enabled,
                getDebugFrameSequencerStep(),
                r.getByte(0xff24),
                r.getByte(0xff25),
                getByte(0xff26),
                debugChannels,
                new DebugByteData(((SoundMode3) allModes[2]).copyDebugWaveRam()));
    }

    public int getDebugFrameSequencerStep() {
        materializePendingPerformanceTicks();
        return frameSequencer.getDebugStep();
    }

    private static boolean isTriggerRegister(int address) {
        return address == 0xff14 || address == 0xff19
                || address == 0xff1e || address == 0xff23;
    }

    private static int getDebugChannel(int address) {
        if (address >= 0xff10 && address <= 0xff14) {
            return 1;
        }
        if (address >= 0xff15 && address <= 0xff19) {
            return 2;
        }
        if ((address >= 0xff1a && address <= 0xff1e)
                || (address >= 0xff30 && address <= 0xff3f)) {
            return 3;
        }
        if (address >= 0xff1f && address <= 0xff23) {
            return 4;
        }
        return -1;
    }

    @Override
    public int getByte(int address) {
        if (performanceSystemMutedAudioMode != PerformanceSystemMutedAudioMode.OFF) {
            performanceSystemMutedAudioCalendarApuReads++;
        }
        materializePendingPerformanceTicks();

        int result;
        if (address == 0xff26) {
            result = 0;
            for (int i = 0; i < allModes.length; i++) {
                result |= allModes[i].isEnabled() ? (1 << i) : 0;
            }
            result |= enabled ? (1 << 7) : 0;
        } else if (address == 0xff76) {
            return (allModes[0].isEnabled() ? allModes[0].getCurrentOutput() : 0)
                    | (allModes[1].isEnabled() ? allModes[1].getCurrentOutput() << 4 : 0);
        } else if (address == 0xff77) {
            return (allModes[2].isEnabled() ? allModes[2].getCurrentOutput() : 0)
                    | (allModes[3].isEnabled() ? allModes[3].getCurrentOutput() << 4 : 0);
        } else {
            result = getUnmaskedByte(address);
        }
        return result | MASKS[address - 0xff10];
    }

    private int getUnmaskedByte(int address) {
        AddressSpace s = getAddressSpace(address);
        if (s == null) {
            throw new IllegalArgumentException();
        }
        return s.getByte(address);
    }

    private void start() {
        // the registers were zeroed at power-off and the length counters keep their values
        for (AbstractSoundMode m : allModes) {
            m.start();
        }
        frameSequencer.reset(timer.getDivCounter(), speedMode.getSpeedMode() == 2);
        // Power-on re-synchronizes the PSG to the selected CPU clock. In double
        // speed, its last-update timestamp starts one sub-clock before phase zero.
        frameSequencerClockPhase = speedMode.getSpeedMode() == 2 ? 3 : 0;
    }

    private void stop() {
        for (AbstractSoundMode s : allModes) {
            s.stop();
        }
        r.setByte(0xff24, 0);
        r.setByte(0xff25, 0);
        volume = 0;
        routing = 0;
    }

    public void enableChannel(int i, boolean enabled) {
        materializePendingPerformanceTicks();
        overriddenEnabled[i] = enabled;
        mixerDirty = true;
    }

    @Override
    public ComponentState<Sound> captureState() {
        materializePendingPerformanceTicks();
        return captureState(null);
    }

    @Override
    public ComponentState<Sound> captureState(MachineStateCapture capture) {
        materializePendingPerformanceTicks();
        var allModeMementos = new ComponentState[allModes.length];
        for (int i = 0; i < allModes.length; i++) {
            allModeMementos[i] = capture == null
                    ? allModes[i].captureState()
                    : allModes[i].captureState(capture);
        }
        // Only the prefix before i has been written. The rest is overwritten before the next
        // SoundSampleEvent can expose it, so retaining the full frame buffer in every rewind
        // state wastes memory and creates a G1 humongous allocation.
        int[] pendingSamples = capture == null ? Arrays.copyOf(buffer, i) : capture.ints(buffer, i);
        return new SoundState(
                allModeMementos,
                capture == null ? r.captureState() : r.captureState(capture),
                frameSequencer.captureState(),
                capture == null ? channels.clone() : capture.ints(channels),
                enabled,
                capture == null ? overriddenEnabled.clone() : capture.booleans(overriddenEnabled),
                pendingSamples, i, pendingFrameSequencerStep,
                frameSequencerClockPhase, frameSequencerDivOffset,
                performanceSamplePhase,
                audioDecimation());
    }

    @Override
    public void declareMachineStatePayloads(MachineStateCapture capture) {
        materializePendingPerformanceTicks();
        r.declareMachineStatePayloads(capture);
        capture.declareInts(buffer, i);
    }

    @Override
    public void restoreState(ComponentState<Sound> state) {
        if (!(state instanceof SoundState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        if (this.allModes.length != mem.allModeMementos.length) {
            throw new IllegalArgumentException("ComponentState allModes length doesn't match");
        }
        if (this.channels.length != mem.channels.length) {
            throw new IllegalArgumentException("ComponentState channels length doesn't match");
        }
        if (this.overriddenEnabled.length != mem.overriddenEnabled.length) {
            throw new IllegalArgumentException("ComponentState overriddenEnabled length doesn't match");
        }
        if (mem.i < 0 || (mem.i & 1) != 0) {
            throw new IllegalArgumentException("ComponentState buffer position is invalid");
        }
        // New mementos retain only buffer[0..i). Accept the former full-buffer shape as
        // well so save states written by older Coffee GB versions remain loadable. A state made
        // by the other execution mode can have a larger pending prefix; that host-only audio
        // backlog is discarded below while all emulated channel state is retained.
        if (mem.buffer.length < mem.i || (mem.buffer.length & 1) != 0) {
            throw new IllegalArgumentException("ComponentState buffer length doesn't contain its prefix");
        }
        if (mem.audioDecimation != ACCURACY_AUDIO_DECIMATION
                && mem.audioDecimation != LEGACY_SGB_PERFORMANCE_AUDIO_DECIMATION
                && mem.audioDecimation != PERFORMANCE_AUDIO_DECIMATION
                && mem.audioDecimation != SGB_PERFORMANCE_AUDIO_DECIMATION) {
            throw new IllegalArgumentException("ComponentState audio decimation is invalid");
        }
        if (mem.performanceSamplePhase < 0
                || mem.performanceSamplePhase >= mem.audioDecimation) {
            throw new IllegalArgumentException("ComponentState audio decimation phase is invalid");
        }
        if (mem.audioDecimation == ACCURACY_AUDIO_DECIMATION
                && mem.performanceSamplePhase != 0) {
            throw new IllegalArgumentException(
                    "ComponentState full-rate audio decimation phase is invalid");
        }
        for (int i = 0; i < allModes.length; i++) {
            this.allModes[i].restoreState(mem.allModeMementos[i]);
        }
        this.r.restoreState(mem.ramMemento());
        int[] restoredRegisters = r.getSpace();
        this.volume = restoredRegisters[0];
        this.routing = restoredRegisters[1];
        this.frameSequencer.restoreState(mem.frameSequencerMemento());
        System.arraycopy(mem.channels, 0, this.channels, 0, this.channels.length);
        this.enabled = mem.enabled();
        System.arraycopy(mem.overriddenEnabled, 0, this.overriddenEnabled, 0, this.overriddenEnabled.length);
        int restoredBufferIndex = Math.min(mem.i, this.buffer.length - 2);
        boolean pendingAudioFits = mem.audioDecimation == audioDecimation()
                && mem.i == restoredBufferIndex;
        if (pendingAudioFits) {
            System.arraycopy(mem.buffer, 0, this.buffer, 0, restoredBufferIndex);
        }
        this.i = pendingAudioFits ? restoredBufferIndex : 0;
        this.pendingFrameSequencerStep = mem.pendingFrameSequencerStep;
        this.frameSequencerClockPhase = mem.frameSequencerClockPhase;
        this.frameSequencerDivOffset = mem.frameSequencerDivOffset;
        this.performanceSamplePhase = pendingAudioFits ? mem.performanceSamplePhase : 0;
        this.pendingPerformanceTicks = 0;
        this.performanceSystemMutedAudioMode = PerformanceSystemMutedAudioMode.OFF;
        resetPerformanceSystemMutedAudioCalendarCounters();
        this.mixerDirty = true;

    }

    public record SoundSampleEvent(int[] buffer, ClockSpec clockSpec) implements Event {
        public SoundSampleEvent(int[] buffer) {
            this(buffer, ClockSpec.LEGACY);
        }
    }

    public record SoundEnabledEvent(boolean enabled) implements Event {
    }

    private record SoundState(ComponentState<AbstractSoundMode>[] allModeMementos, ComponentState<Ram> ramMemento,
                                ComponentState<FrameSequencer> frameSequencerMemento, int[] channels,
                                boolean enabled, boolean[] overriddenEnabled, int[] buffer,
                                int i, int pendingFrameSequencerStep,
                                int frameSequencerClockPhase,
                                int frameSequencerDivOffset,
                                int performanceSamplePhase,
                                int audioDecimation) implements ComponentState<Sound> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record SoundMemento(Memento<AbstractSoundMode>[] allModeMementos, Memento<Ram> ramMemento,
                                Memento<FrameSequencer> frameSequencerMemento, int[] channels,
                                boolean enabled, boolean[] overriddenEnabled, int[] buffer,
                                int i, int pendingFrameSequencerStep,
                                int frameSequencerClockPhase,
                                int frameSequencerDivOffset) implements Memento<Sound> {
    }
}
