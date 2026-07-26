package eu.rekawek.coffeegb.core.hardware;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Exact, immutable emulated-clock and controller-cadence specification.
 *
 * <p>Both the emulated master rate and host/controller cadence are exact rationals. Coffee GB
 * intentionally retains its historical floor tick budget per 60-Hz controller frame for the
 * portable profiles; SGB-family profiles instead describe the exact 70,224-tick LCD frame. Host
 * wall-clock pacing and emulated time remain separate consumers of the same immutable value.
 */
public final class ClockSpec {

    public enum Rounding {
        FLOOR,
        CEILING,
        NEAREST
    }

    /** Historical Coffee GB clock/cadence retained by DMG/CGB/CGB0. */
    public static final ClockSpec LEGACY = new ClockSpec(4_194_304L, 60L, 1L);

    /** NTSC SGB: (1,890,000,000 / 88) SNES master clock divided by five. */
    public static final ClockSpec SGB = new ClockSpec(
            47_250_000L, 11L, 47_250_000L, 772_464L);

    /** SGB2: its dedicated 20.971520 MHz crystal divided by five. */
    public static final ClockSpec SGB2 = new ClockSpec(
            4_194_304L, 1L, 4_194_304L, 70_224L);

    private final long ticksPerSecondNumerator;

    private final long ticksPerSecondDenominator;

    private final long controllerFramesPerSecondNumerator;

    private final long controllerFramesPerSecondDenominator;

    private final int controllerTicksPerFrame;

    public ClockSpec(long ticksPerSecond, long controllerFramesPerSecondNumerator,
                     long controllerFramesPerSecondDenominator) {
        this(ticksPerSecond, 1, controllerFramesPerSecondNumerator,
                controllerFramesPerSecondDenominator);
    }

    public ClockSpec(long ticksPerSecondNumerator, long ticksPerSecondDenominator,
                     long controllerFramesPerSecondNumerator,
                     long controllerFramesPerSecondDenominator) {
        if (ticksPerSecondNumerator <= 0 || ticksPerSecondNumerator > Integer.MAX_VALUE
                || ticksPerSecondDenominator <= 0) {
            throw new IllegalArgumentException(
                    "Master tick rate must be a positive rational with numerator in 1.."
                            + Integer.MAX_VALUE);
        }
        if (controllerFramesPerSecondNumerator <= 0 || controllerFramesPerSecondDenominator <= 0) {
            throw new IllegalArgumentException("Controller frame rate must be a positive rational");
        }
        long tickGcd = gcd(ticksPerSecondNumerator, ticksPerSecondDenominator);
        this.ticksPerSecondNumerator = ticksPerSecondNumerator / tickGcd;
        this.ticksPerSecondDenominator = ticksPerSecondDenominator / tickGcd;
        if (this.ticksPerSecondDenominator > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Master tick-rate denominator exceeds phase storage");
        }
        long frameGcd = gcd(controllerFramesPerSecondNumerator,
                controllerFramesPerSecondDenominator);
        this.controllerFramesPerSecondNumerator = controllerFramesPerSecondNumerator / frameGcd;
        this.controllerFramesPerSecondDenominator = controllerFramesPerSecondDenominator / frameGcd;
        long budget = multiplyDivide(
                this.ticksPerSecondNumerator,
                this.controllerFramesPerSecondDenominator,
                Math.multiplyExact(this.ticksPerSecondDenominator,
                        this.controllerFramesPerSecondNumerator),
                Rounding.FLOOR);
        if (budget <= 0 || budget > Integer.MAX_VALUE / 2L) {
            throw new IllegalArgumentException("Controller tick budget cannot back a stereo sample buffer: " + budget);
        }
        this.controllerTicksPerFrame = (int) budget;
    }

    /**
     * Returns the exact integer master rate.
     *
     * @throws IllegalStateException when this profile has a rational, non-integral rate
     * @deprecated Consumers that can encounter SGB must use the numerator/denominator or an
     *     exact conversion method.
     */
    @Deprecated
    public long ticksPerSecond() {
        if (ticksPerSecondDenominator != 1) {
            throw new IllegalStateException(
                    "The exact master tick rate is " + ticksPerSecondNumerator + "/"
                            + ticksPerSecondDenominator + "; use the rational accessors");
        }
        return ticksPerSecondNumerator;
    }

    /** @see #ticksPerSecond() */
    @Deprecated
    public int ticksPerSecondInt() {
        return Math.toIntExact(ticksPerSecond());
    }

    public long ticksPerSecondNumerator() {
        return ticksPerSecondNumerator;
    }

    public long ticksPerSecondDenominator() {
        return ticksPerSecondDenominator;
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
        if (ticksPerSecondDenominator != 1) {
            throw new IllegalStateException(
                    "A rational master clock needs an explicit rounding policy");
        }
        return Math.multiplyExact(seconds, ticksPerSecondNumerator);
    }

    public long ticksForSeconds(long seconds, Rounding rounding) {
        if (seconds < 0) {
            throw new IllegalArgumentException("Seconds cannot be negative");
        }
        return multiplyDivide(seconds, ticksPerSecondNumerator,
                ticksPerSecondDenominator, rounding);
    }

    public long ticksForMilliseconds(long milliseconds, Rounding rounding) {
        if (milliseconds < 0) {
            throw new IllegalArgumentException("Milliseconds cannot be negative");
        }
        return multiplyDivide(milliseconds, ticksPerSecondNumerator,
                Math.multiplyExact(ticksPerSecondDenominator, 1_000L), rounding);
    }

    /** Converts one unit at {@code unitsPerSecond} to master ticks. */
    public long ticksPerRateUnit(long unitsPerSecond, Rounding rounding) {
        if (unitsPerSecond <= 0) {
            throw new IllegalArgumentException("Rate must be positive");
        }
        return divide(ticksPerSecondNumerator,
                Math.multiplyExact(ticksPerSecondDenominator, unitsPerSecond), rounding);
    }

    public long ticksForRateUnits(long units, long unitsPerSecond, Rounding rounding) {
        if (units < 0 || unitsPerSecond <= 0) {
            throw new IllegalArgumentException("Rate units must be non-negative and rate positive");
        }
        return multiplyDivide(units, ticksPerSecondNumerator,
                Math.multiplyExact(ticksPerSecondDenominator, unitsPerSecond), rounding);
    }

    /** Exact output-unit accumulator driven by master-clock input ticks. */
    public RateAccumulator newTickRateAccumulator(long outputUnitsPerSecond) {
        return new RateAccumulator(
                Math.multiplyExact(outputUnitsPerSecond, ticksPerSecondDenominator),
                ticksPerSecondNumerator);
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
                .multiply(BigInteger.valueOf(ticksPerSecondDenominator))
                .add(BigInteger.valueOf(ticksPerSecondNumerator - 1));
        return numerator.divide(BigInteger.valueOf(ticksPerSecondNumerator)).longValueExact();
    }

    /** Complete exact clock/cadence identity used before linked execution. */
    public boolean hasCompatibleClockIdentity(ClockSpec other) {
        return equals(other);
    }

    /**
     * @deprecated The old name implied that comparing a floored frame budget was sufficient.
     *     Use {@link #hasCompatibleClockIdentity(ClockSpec)}.
     */
    @Deprecated
    public boolean hasCompatibleControllerBudget(ClockSpec other) {
        return hasCompatibleClockIdentity(other);
    }

    /** True when the frame budget is an exact quotient rather than a legacy floor. */
    public boolean hasExactControllerTickBudget() {
        BigInteger numerator = BigInteger.valueOf(ticksPerSecondNumerator)
                .multiply(BigInteger.valueOf(controllerFramesPerSecondDenominator));
        BigInteger denominator = BigInteger.valueOf(ticksPerSecondDenominator)
                .multiply(BigInteger.valueOf(controllerFramesPerSecondNumerator));
        return numerator.remainder(denominator).signum() == 0;
    }

    /** Phase units added by one emulated master tick to a one-second rational accumulator. */
    public int secondPhaseUnitsPerTick() {
        return Math.toIntExact(ticksPerSecondDenominator);
    }

    /** Exclusive bound for a one-second rational phase accumulator. */
    public int secondPhaseLimit() {
        return Math.toIntExact(ticksPerSecondNumerator);
    }

    /** Converts wall-clock milliseconds into the same phase units used by {@link #secondPhaseLimit()}. */
    public long secondPhaseUnitsForMilliseconds(long milliseconds, Rounding rounding) {
        if (milliseconds < 0) {
            throw new IllegalArgumentException("Milliseconds cannot be negative");
        }
        return multiplyDivide(milliseconds, ticksPerSecondNumerator, 1_000L, rounding);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClockSpec that)) return false;
        return ticksPerSecondNumerator == that.ticksPerSecondNumerator
                && ticksPerSecondDenominator == that.ticksPerSecondDenominator
                && controllerFramesPerSecondNumerator == that.controllerFramesPerSecondNumerator
                && controllerFramesPerSecondDenominator == that.controllerFramesPerSecondDenominator;
    }

    @Override
    public int hashCode() {
        return Objects.hash(ticksPerSecondNumerator, ticksPerSecondDenominator,
                controllerFramesPerSecondNumerator,
                controllerFramesPerSecondDenominator);
    }

    @Override
    public String toString() {
        return "ClockSpec{" + ticksPerSecondNumerator + "/" + ticksPerSecondDenominator
                + " ticks/s, "
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
            try {
                long total = Math.addExact(
                        remainder, Math.multiplyExact(inputUnits, numeratorPerInput));
                long output = total / denominator;
                remainder = total % denominator;
                return output;
            } catch (ArithmeticException overflow) {
                // Bulk analytical/test consumers may advance years at once. The per-tick hot path
                // stays allocation-free; only a value that actually overflows long uses BigInteger.
                BigInteger total = BigInteger.valueOf(inputUnits)
                        .multiply(BigInteger.valueOf(numeratorPerInput))
                        .add(BigInteger.valueOf(remainder));
                BigInteger[] result = total.divideAndRemainder(BigInteger.valueOf(denominator));
                remainder = result[1].longValueExact();
                return result[0].longValueExact();
            }
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
