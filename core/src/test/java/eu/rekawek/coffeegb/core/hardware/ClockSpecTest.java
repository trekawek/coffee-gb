package eu.rekawek.coffeegb.core.hardware;

import org.junit.Test;

import java.math.BigInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ClockSpecTest {

    @Test
    public void legacyClockMakesTheHistoricalIntegerCadenceExplicit() {
        ClockSpec clock = ClockSpec.LEGACY;

        assertEquals(4_194_304L, clock.ticksPerSecond());
        assertEquals(60L, clock.controllerFramesPerSecondNumerator());
        assertEquals(1L, clock.controllerFramesPerSecondDenominator());
        assertEquals(69_905, clock.controllerTicksPerFrame());
        assertEquals(clock, new ClockSpec(4_194_304, 120, 2));
    }

    @Test
    public void exactAccumulatorsHaveNoLongRunConversionDrift() {
        ClockSpec clock = ClockSpec.LEGACY;
        ClockSpec.RateAccumulator audio = clock.newTickRateAccumulator(48_000);
        long output = 0;
        long input = 0;
        for (int frame = 0; frame < 216_000; frame++) { // one hour at the legacy cadence
            input += clock.controllerTicksPerFrame();
            output += audio.advance(clock.controllerTicksPerFrame());
        }

        long expected = BigInteger.valueOf(input)
                .multiply(BigInteger.valueOf(48_000))
                .divide(BigInteger.valueOf(clock.ticksPerSecond()))
                .longValueExact();
        assertEquals(expected, output);
        assertEquals(
                BigInteger.valueOf(input).multiply(BigInteger.valueOf(48_000))
                        .remainder(BigInteger.valueOf(clock.ticksPerSecond())).longValueExact(),
                audio.remainder());

        ClockSpec.RateAccumulator frameNanos = clock.newFrameNanosecondAccumulator();
        long nanos = 0;
        for (int i = 0; i < 60; i++) {
            nanos += frameNanos.advance(1);
        }
        assertEquals(1_000_000_000L, nanos);
        assertEquals(0L, frameNanos.remainder());
    }

    @Test
    public void conversionsUseDocumentedRoundingAndCheckedArithmetic() {
        ClockSpec clock = new ClockSpec(10, 3, 1);

        assertEquals(3, clock.controllerTicksPerFrame());
        assertEquals(3, clock.ticksForMilliseconds(250, ClockSpec.Rounding.CEILING));
        assertEquals(2, clock.ticksForMilliseconds(250, ClockSpec.Rounding.FLOOR));
        assertEquals(3, clock.ticksForMilliseconds(250, ClockSpec.Rounding.NEAREST));
        assertEquals(5, clock.maximumOutputUnits(9, 5));

        assertThrows(ArithmeticException.class, () -> clock.ticksForSeconds(Long.MAX_VALUE));
        assertThrows(ArithmeticException.class,
                () -> clock.newTickRateAccumulator(Long.MAX_VALUE).advance(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new ClockSpec(0, 60, 1));
        assertThrows(IllegalArgumentException.class, () -> new ClockSpec(1, 2, 1));
        assertTrue(clock.hasCompatibleControllerBudget(new ClockSpec(10, 6, 2)));
    }
}
