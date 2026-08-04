package eu.rekawek.coffeegb.core.hardware;

import org.junit.Test;

import java.math.BigInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
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
    public void singleUnitHotPathMatchesBulkAccumulatorForEveryRatioShape() {
        assertAdvanceOneMatchesBulk(new ClockSpec(4_194_304, 60, 1), 44_100, 1_000_000);
        assertAdvanceOneMatchesBulk(new ClockSpec(32_768, 60, 1), 44_100, 100_000);
        assertAdvanceOneMatchesBulk(new ClockSpec(44_100, 60, 1), 44_100, 100_000);
        assertAdvanceOneMatchesBulk(ClockSpec.SGB, 44_100, 1_000_000);
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
                () -> ClockSpec.multiplyDivide(Long.MAX_VALUE, 2, 1, ClockSpec.Rounding.FLOOR));
        assertThrows(ArithmeticException.class,
                () -> clock.newTickRateAccumulator(Long.MAX_VALUE).advance(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new ClockSpec(0, 60, 1));
        assertThrows(IllegalArgumentException.class, () -> new ClockSpec(1, 2, 1));
        assertTrue(clock.hasCompatibleClockIdentity(new ClockSpec(10, 6, 2)));
    }

    @Test
    public void sgbAndSgb2ExposeExactEvidenceBackedRatesAndFrameBudgets() {
        ClockSpec sgb = ClockSpec.SGB;
        assertEquals(47_250_000L, sgb.ticksPerSecondNumerator());
        assertEquals(11L, sgb.ticksPerSecondDenominator());
        assertEquals(70_224, sgb.controllerTicksPerFrame());
        assertTrue(sgb.hasExactControllerTickBudget());
        assertThrows(IllegalStateException.class, sgb::ticksPerSecond);

        ClockSpec sgb2 = ClockSpec.SGB2;
        assertEquals(4_194_304L, sgb2.ticksPerSecond());
        assertEquals(70_224, sgb2.controllerTicksPerFrame());
        assertTrue(sgb2.hasExactControllerTickBudget());
        assertTrue(!sgb.hasCompatibleClockIdentity(sgb2));
    }

    @Test
    public void sgbFamilyLongRunAudioAndFrameConversionsHaveExactRemainders() {
        for (ClockSpec clock : new ClockSpec[]{ClockSpec.SGB, ClockSpec.SGB2}) {
            BigInteger seconds = BigInteger.valueOf(24L * 60 * 60);
            BigInteger tickNumerator = seconds.multiply(
                    BigInteger.valueOf(clock.ticksPerSecondNumerator()));
            BigInteger tickDenominator = BigInteger.valueOf(clock.ticksPerSecondDenominator());
            // Ceiling is deliberate: the exercised span is at least 24 physical hours even when
            // a rational clock has no integer tick exactly at that wall-time boundary.
            long inputTicks = tickNumerator.add(tickDenominator.subtract(BigInteger.ONE))
                    .divide(tickDenominator).longValueExact();

            ClockSpec.RateAccumulator audio = clock.newTickRateAccumulator(44_100);
            long samples = audio.advance(inputTicks);
            BigInteger exactAudioNumerator = BigInteger.valueOf(inputTicks)
                    .multiply(BigInteger.valueOf(44_100))
                    .multiply(BigInteger.valueOf(clock.ticksPerSecondDenominator()));
            BigInteger[] expectedAudio = exactAudioNumerator.divideAndRemainder(
                    BigInteger.valueOf(clock.ticksPerSecondNumerator()));
            assertEquals(expectedAudio[0].longValueExact(), samples);
            assertEquals(expectedAudio[1].longValueExact(), audio.remainder());

            long frames = inputTicks / clock.controllerTicksPerFrame();
            ClockSpec.RateAccumulator nanos = clock.newFrameNanosecondAccumulator();
            long actualNanos = nanos.advance(frames);
            BigInteger exactNanos = BigInteger.valueOf(frames)
                    .multiply(BigInteger.valueOf(1_000_000_000L))
                    .multiply(BigInteger.valueOf(clock.controllerFramesPerSecondDenominator()));
            BigInteger[] expectedNanos = exactNanos.divideAndRemainder(
                    BigInteger.valueOf(clock.controllerFramesPerSecondNumerator()));
            assertEquals(expectedNanos[0].longValueExact(), actualNanos);
            assertEquals(expectedNanos[1].longValueExact(), nanos.remainder());
        }
    }

    @Test
    public void mgbSharesTheExactDmgClockAndLongRunConversionIdentity() {
        ClockSpec dmg = HardwareProfileRegistry.DMG.clockSpec();
        ClockSpec mgb = HardwareProfileRegistry.MGB.clockSpec();
        assertSame(dmg, mgb);

        long ticks = Math.multiplyExact(24L * 60 * 60, 4_194_304L);
        ClockSpec.RateAccumulator dmgAudio = dmg.newTickRateAccumulator(44_100);
        ClockSpec.RateAccumulator mgbAudio = mgb.newTickRateAccumulator(44_100);
        assertEquals(dmgAudio.advance(ticks), mgbAudio.advance(ticks));
        assertEquals(dmgAudio.remainder(), mgbAudio.remainder());
        assertEquals(dmg.controllerTicksPerFrame(), mgb.controllerTicksPerFrame());
    }

    private static void assertAdvanceOneMatchesBulk(
            ClockSpec clock, long outputRate, int inputUnits) {
        assertAdvanceOneSequenceMatchesBulk(clock, outputRate, inputUnits, 0);
        assertAdvanceOneSequenceMatchesBulk(
                clock, outputRate, Math.min(inputUnits, 10_000),
                clock.ticksPerSecondNumerator() - 1);
    }

    private static void assertAdvanceOneSequenceMatchesBulk(
            ClockSpec clock, long outputRate, int inputUnits, long initialRemainder) {
        ClockSpec.RateAccumulator hotPath = clock.newTickRateAccumulator(outputRate);
        ClockSpec.RateAccumulator bulk = clock.newTickRateAccumulator(outputRate);
        hotPath.restoreRemainder(initialRemainder);
        bulk.restoreRemainder(initialRemainder);
        for (int input = 0; input < inputUnits; input++) {
            assertEquals(bulk.advance(1), hotPath.advanceOne());
            assertEquals(bulk.remainder(), hotPath.remainder());
        }
    }
}
