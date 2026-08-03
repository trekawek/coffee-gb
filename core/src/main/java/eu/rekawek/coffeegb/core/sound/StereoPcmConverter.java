package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.hardware.ClockSpec;

import java.util.Objects;

/**
 * Stateful, platform-neutral conversion from Coffee GB's per-master-tick stereo mix to signed
 * little-endian 16-bit host PCM.
 *
 * <p>The converter owns fractional resampling, the two-period box filter, and DC blockers. It
 * writes into a caller-provided byte array, allowing a host sink to preallocate its bounded queue
 * rather than allocate work proportional to emulated master ticks. One instance has one producer
 * thread; callers must serialize a converter if their event delivery is not single-threaded.
 */
public final class StereoPcmConverter {

    /** The mixer maximum is four channels times ±15 at volume 8, per stereo side. */
    private static final int VOLUME_SCALE = 62;
    private static final double HIGHPASS_CUTOFF = 28.0;
    private static final int BYTES_PER_STEREO_FRAME = 4;

    private final int sampleRate;
    private final DcBlocker dcBlockerL;
    private final DcBlocker dcBlockerR;

    private ClockSpec activeClock = ClockSpec.LEGACY;
    private ClockSpec.RateAccumulator sampleAccumulator;
    private long sumL;
    private long sumR;
    private long previousSumL;
    private long previousSumR;
    private int count;
    private int previousCount;

    public StereoPcmConverter(int sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("Sample rate must be positive");
        }
        this.sampleRate = sampleRate;
        sampleAccumulator = activeClock.newTickRateAccumulator(sampleRate);
        dcBlockerL = new DcBlocker(sampleRate, HIGHPASS_CUTOFF);
        dcBlockerR = new DcBlocker(sampleRate, HIGHPASS_CUTOFF);
    }

    /** Returns enough bytes for {@link #render} for this clock and source tick count. */
    public int maximumPcmBytes(int ticks, ClockSpec clockSpec) {
        if (ticks < 0) {
            throw new IllegalArgumentException("Tick count must not be negative");
        }
        ClockSpec clock = Objects.requireNonNull(clockSpec, "clockSpec");
        return Math.toIntExact(Math.multiplyExact(
                clock.maximumOutputUnits(ticks, sampleRate), BYTES_PER_STEREO_FRAME));
    }

    /**
     * Converts interleaved left/right mixer values into {@code target} and returns written bytes.
     * The target must have at least {@link #maximumPcmBytes(int, ClockSpec)} bytes available.
     */
    public int render(int[] source, ClockSpec clockSpec, int masterVolume, boolean muted,
                      byte[] target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (masterVolume < 0 || masterVolume > 100) {
            throw new IllegalArgumentException("Master volume must be between 0 and 100");
        }
        selectClock(Objects.requireNonNull(clockSpec, "clockSpec"));
        int ticks = source.length / 2;
        int required = maximumPcmBytes(ticks, activeClock);
        if (target.length < required) {
            throw new IllegalArgumentException("PCM target is smaller than the maximum output");
        }

        int offset = 0;
        for (int tick = 0; tick < ticks; tick++) {
            sumL += source[tick * 2];
            sumR += source[tick * 2 + 1];
            count++;
            long produced = sampleAccumulator.advanceOne();
            if (produced > 1) {
                throw new IllegalStateException("Audio output rate exceeds the emulated tick rate");
            }
            if (produced == 1) {
                int total = previousCount + count;
                double rawL = (double) (previousSumL + sumL) / total;
                double rawR = (double) (previousSumR + sumR) / total;
                int left = outputSample(dcBlockerL.filter(rawL), masterVolume, muted);
                int right = outputSample(dcBlockerR.filter(rawR), masterVolume, muted);
                target[offset++] = (byte) left;
                target[offset++] = (byte) (left >> 8);
                target[offset++] = (byte) right;
                target[offset++] = (byte) (right >> 8);
                previousSumL = sumL;
                previousSumR = sumR;
                previousCount = count;
                sumL = 0;
                sumR = 0;
                count = 0;
            }
        }
        return offset;
    }

    /** Clears all filter and fractional-resampling phase. Use only for a deliberate new stream. */
    public void reset() {
        resetRateState();
        dcBlockerL.reset();
        dcBlockerR.reset();
    }

    /** Exposes only fractional phase for deterministic continuity tests. */
    public long samplePhase() {
        return sampleAccumulator.remainder();
    }

    private void selectClock(ClockSpec clockSpec) {
        if (activeClock.equals(clockSpec)) {
            return;
        }
        activeClock = clockSpec;
        // Desktop output has always retained capacitor/DC state across a hardware-clock change;
        // only the fractional clock accumulator and its pending box-filter period restart.
        resetRateState();
    }

    private void resetRateState() {
        sampleAccumulator = activeClock.newTickRateAccumulator(sampleRate);
        sumL = 0;
        sumR = 0;
        previousSumL = 0;
        previousSumR = 0;
        count = 0;
        previousCount = 0;
    }

    private static int outputSample(double filtered, int masterVolume, boolean muted) {
        if (muted || masterVolume == 0) {
            return 0;
        }
        double scaled = filtered * VOLUME_SCALE;
        if (masterVolume != 100) {
            scaled = scaled * masterVolume / 100.0;
        }
        if (scaled >= Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (scaled <= Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (int) scaled;
    }

}
