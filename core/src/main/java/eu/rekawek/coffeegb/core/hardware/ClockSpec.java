package eu.rekawek.coffeegb.core.hardware;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Exact, immutable emulated-clock and controller-cadence specification.
 *
 * <p>The emulated master rate is an integer number of ticks per second. The host/controller
 * cadence is an exact rational number of frames per second. Coffee GB intentionally retains its
 * historical floor tick budget per 60-Hz controller frame; this is not a claim about the physical
 * LCD refresh rate. Host wall-clock pacing and emulated time remain separate consumers of the same
 * immutable value.
 */
public final class ClockSpec {

    public enum Rounding {
        FLOOR,
        CEILING,
        NEAREST
    }

    /** Current Coffee GB clock/cadence, shared by all Phase-3 built-in profiles. */
    public static final ClockSpec LEGACY = new ClockSpec(4_194_304L, 60L, 1L);

    private final long ticksPerSecond;

    private final long controllerFramesPerSecondNumerator;

    private final long controllerFramesPerSecondDenominator;

    private final int controllerTicksPerFrame;

    public ClockSpec(long ticksPerSecond, long controllerFramesPerSecondNumerator,
                     long controllerFramesPerSecondDenominator) {
        if (ticksPerSecond <= 0 || ticksPerSecond > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Master ticks per second must be in 1.." + Integer.MAX_VALUE);
        }
        if (controllerFramesPerSecondNumerator <= 0 || controllerFramesPerSecondDenominator <= 0) {
            throw new IllegalArgumentException("Controller frame rate must be a positive rational");
        }
        long gcd = gcd(controllerFramesPerSecondNumerator, controllerFramesPerSecondDenominator);
        this.ticksPerSecond = ticksPerSecond;
        this.controllerFramesPerSecondNumerator = controllerFramesPerSecondNumerator / gcd;
        this.controllerFramesPerSecondDenominator = controllerFramesPerSecondDenominator / gcd;
        long budget = multiplyDivide(
                ticksPerSecond,
                this.controllerFramesPerSecondDenominator,
                this.controllerFramesPerSecondNumerator,
                Rounding.FLOOR);
        if (budget <= 0 || budget > Integer.MAX_VALUE / 2L) {
            throw new IllegalArgumentException("Controller tick budget cannot back a stereo sample buffer: " + budget);
        }
        this.controllerTicksPerFrame = (int) budget;
    }

    public long ticksPerSecond() {
        return ticksPerSecond;
    }

    public int ticksPerSecondInt() {
        return Math.toIntExact(ticksPerSecond);
    }

    public long controllerFramesPerSecondNumerator() {
        return controllerFramesPerSecondNumerator;
    }

    public long controllerFramesPerSecondDenominator() {
        return controllerFramesPerSecondDenominator;
    }

    /** Historical integer budget: floor(ticks/second divided by controller frames/second). */
    public int controllerTicksPerFrame() {
        return controllerTicksPerFrame;
    }

    public long ticksForSeconds(long seconds) {
        if (seconds < 0) {
            throw new IllegalArgumentException("Seconds cannot be negative");
        }
        return Math.multiplyExact(seconds, ticksPerSecond);
    }

    public long ticksForMilliseconds(long milliseconds, Rounding rounding) {
        if (milliseconds < 0) {
            throw new IllegalArgumentException("Milliseconds cannot be negative");
        }
        return multiplyDivide(milliseconds, ticksPerSecond, 1_000L, rounding);
    }

    /** Converts one unit at {@code unitsPerSecond} to master ticks. */
    public long ticksPerRateUnit(long unitsPerSecond, Rounding rounding) {
        if (unitsPerSecond <= 0) {
            throw new IllegalArgumentException("Rate must be positive");
        }
        return divide(ticksPerSecond, unitsPerSecond, rounding);
    }

    public long ticksForRateUnits(long units, long unitsPerSecond, Rounding rounding) {
        if (units < 0 || unitsPerSecond <= 0) {
            throw new IllegalArgumentException("Rate units must be non-negative and rate positive");
        }
        return multiplyDivide(units, ticksPerSecond, unitsPerSecond, rounding);
    }

    /** Exact output-unit accumulator driven by master-clock input ticks. */
    public RateAccumulator newTickRateAccumulator(long outputUnitsPerSecond) {
        return new RateAccumulator(outputUnitsPerSecond, ticksPerSecond);
    }

    /** Exact nanosecond accumulator driven once per controller frame. */
    public RateAccumulator newFrameNanosecondAccumulator() {
        return new RateAccumulator(
                Math.multiplyExact(1_000_000_000L, controllerFramesPerSecondDenominator),
                controllerFramesPerSecondNumerator);
    }

    public long maximumOutputUnits(long inputTicks, long outputUnitsPerSecond) {
        if (inputTicks < 0 || outputUnitsPerSecond <= 0) {
            throw new IllegalArgumentException("Tick count and output rate must be non-negative/positive");
        }
        // Include the largest valid prior phase so a caller can allocate before conversion.
        BigInteger numerator = BigInteger.valueOf(inputTicks)
                .multiply(BigInteger.valueOf(outputUnitsPerSecond))
                .add(BigInteger.valueOf(ticksPerSecond - 1));
        return numerator.divide(BigInteger.valueOf(ticksPerSecond)).longValueExact();
    }

    public boolean hasCompatibleControllerBudget(ClockSpec other) {
        return other != null
                && controllerTicksPerFrame == other.controllerTicksPerFrame
                && ticksPerSecond == other.ticksPerSecond;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClockSpec that)) return false;
        return ticksPerSecond == that.ticksPerSecond
                && controllerFramesPerSecondNumerator == that.controllerFramesPerSecondNumerator
                && controllerFramesPerSecondDenominator == that.controllerFramesPerSecondDenominator;
    }

    @Override
    public int hashCode() {
        return Objects.hash(ticksPerSecond, controllerFramesPerSecondNumerator,
                controllerFramesPerSecondDenominator);
    }

    @Override
    public String toString() {
        return "ClockSpec{" + ticksPerSecond + " ticks/s, "
                + controllerFramesPerSecondNumerator + "/"
                + controllerFramesPerSecondDenominator + " controller frames/s, "
                + controllerTicksPerFrame + " ticks/frame}";
    }

    static long multiplyDivide(long left, long right, long divisor, Rounding rounding) {
        if (left < 0 || right < 0 || divisor <= 0) {
            throw new IllegalArgumentException("Checked rational conversion requires non-negative values");
        }
        BigInteger product = BigInteger.valueOf(left).multiply(BigInteger.valueOf(right));
        BigInteger[] quotient = product.divideAndRemainder(BigInteger.valueOf(divisor));
        BigInteger result = quotient[0];
        if (quotient[1].signum() != 0) {
            if (rounding == Rounding.CEILING
                    || (rounding == Rounding.NEAREST
                    && quotient[1].shiftLeft(1).compareTo(BigInteger.valueOf(divisor)) >= 0)) {
                result = result.add(BigInteger.ONE);
            }
        }
        return result.longValueExact();
    }

    private static long divide(long value, long divisor, Rounding rounding) {
        return multiplyDivide(value, 1, divisor, rounding);
    }

    private static long gcd(long left, long right) {
        while (right != 0) {
            long next = left % right;
            left = right;
            right = next;
        }
        return left;
    }

    /**
     * Mutable phase owned by one consumer, never by the immutable ClockSpec or global state.
     * Both values are reduced only by exact integer operations, so long runs cannot accumulate
     * floating-point conversion drift.
     */
    public static final class RateAccumulator {

        private final long numeratorPerInput;

        private final long denominator;

        private long remainder;

        private RateAccumulator(long numeratorPerInput, long denominator) {
            if (numeratorPerInput <= 0 || denominator <= 0) {
                throw new IllegalArgumentException("Accumulator ratio must be positive");
            }
            this.numeratorPerInput = numeratorPerInput;
            this.denominator = denominator;
        }

        public long advance(long inputUnits) {
            if (inputUnits < 0) {
                throw new IllegalArgumentException("Input units cannot be negative");
            }
            long total = Math.addExact(remainder, Math.multiplyExact(inputUnits, numeratorPerInput));
            long output = total / denominator;
            remainder = total % denominator;
            return output;
        }

        public long remainder() {
            return remainder;
        }

        public void restoreRemainder(long remainder) {
            if (remainder < 0 || remainder >= denominator) {
                throw new IllegalArgumentException("Accumulator remainder is outside its denominator");
            }
            this.remainder = remainder;
        }

        public void reset() {
            remainder = 0;
        }
    }
}
