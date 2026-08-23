package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.TestDebugHooks;
import eu.rekawek.coffeegb.core.timer.Timer;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Focused contract tests for the native-CGB double-speed timer epoch. */
public final class TimerPerformanceEpochTest {

    @Test
    public void randomizedEligibleEpochsMatchScalarDoubleSpeedTicks() {
        Random random = new Random(0x8e701L);
        int matched = 0;
        for (int i = 0; i < 8_000; i++) {
            InterruptManager scalarInterrupts = new InterruptManager(true);
            InterruptManager bulkInterrupts = new InterruptManager(true);
            SpeedMode scalarSpeed = doubleSpeed();
            SpeedMode bulkSpeed = doubleSpeed();
            Timer scalar = new Timer(scalarInterrupts, scalarSpeed);
            Timer bulk = new Timer(bulkInterrupts, bulkSpeed);
            int div = random.nextInt(0x10000);
            int tma = random.nextInt(0x100);
            int tima = random.nextInt(0x100);
            int tac = random.nextInt(8);
            scalar.presetDiv(div);
            bulk.presetDiv(div);
            scalar.setByte(0xff06, tma);
            bulk.setByte(0xff06, tma);
            scalar.setByte(0xff05, tima);
            bulk.setByte(0xff05, tima);
            scalar.setByte(0xff07, tac);
            bulk.setByte(0xff07, tac);

            assertEquals(0, bulk.performancePhysicalDmgEpochSpanLimit(54));
            int limit = bulk.performanceEpochSpanLimit(54);
            if (limit == 0) {
                continue;
            }
            int span = 1 + random.nextInt(limit);
            for (int tick = 0; tick < span; tick++) {
                scalar.tick();
            }
            assertTrue("eligible epoch was rejected", bulk.tickPerformanceEpoch(span));
            assertEquals(scalar.captureState(), bulk.captureState());
            assertEquals(scalarInterrupts.captureState(), bulkInterrupts.captureState());
            matched++;
        }
        assertTrue("randomized setup produced no eligible epochs", matched > 2_000);
    }

    @Test
    public void randomizedPhysicalDmgEpochsMatchScalarNormalSpeedTicks() {
        Random random = new Random(0xd06e701L);
        int matched = 0;
        for (int i = 0; i < 4_000; i++) {
            InterruptManager scalarInterrupts = new InterruptManager(false);
            InterruptManager bulkInterrupts = new InterruptManager(false);
            Timer scalar = new Timer(scalarInterrupts, new SpeedMode(false));
            Timer bulk = new Timer(bulkInterrupts, new SpeedMode(false));
            int div = random.nextInt(0x10000);
            int tma = random.nextInt(0x100);
            int tima = random.nextInt(0x100);
            int tac = random.nextInt(8);
            scalar.presetDiv(div);
            bulk.presetDiv(div);
            scalar.setByte(0xff06, tma);
            bulk.setByte(0xff06, tma);
            scalar.setByte(0xff05, tima);
            bulk.setByte(0xff05, tima);
            scalar.setByte(0xff07, tac);
            bulk.setByte(0xff07, tac);

            assertEquals(0, bulk.performanceEpochSpanLimit(54));
            int limit = bulk.performancePhysicalDmgEpochSpanLimit(54);
            if (limit == 0) {
                continue;
            }
            int span = 1 + random.nextInt(limit);
            for (int tick = 0; tick < span; tick++) {
                scalar.tick();
            }
            assertTrue("eligible physical-DMG epoch was rejected",
                    bulk.tickPerformancePhysicalDmgEpoch(span));
            assertEquals(scalar.captureState(), bulk.captureState());
            assertEquals(scalarInterrupts.captureState(), bulkInterrupts.captureState());
            matched++;
        }
        assertTrue("randomized DMG setup produced no eligible epochs", matched > 1_000);
    }

    @Test
    public void nonOverflowTimerEdgesAreCountedInsideEpoch() {
        Timer scalar = new Timer(new InterruptManager(true), doubleSpeed());
        Timer bulk = new Timer(new InterruptManager(true), doubleSpeed());
        scalar.presetDiv(0);
        bulk.presetDiv(0);
        scalar.setByte(0xff05, 1);
        bulk.setByte(0xff05, 1);
        scalar.setByte(0xff07, 0x05); // enable, 16 CPU-clock period
        bulk.setByte(0xff07, 0x05);

        assertTrue(bulk.performanceEpochSpanLimit(54) >= 8);
        for (int i = 0; i < 8; i++) {
            scalar.tick();
        }
        assertTrue(bulk.tickPerformanceEpoch(8));
        assertEquals(scalar.captureState(), bulk.captureState());
        assertEquals(2, bulk.getDebugTima());
    }

    @Test
    public void physicalDmgCountsMultipleNonOverflowTimerEdgesInsideEpoch() {
        Timer scalar = new Timer(new InterruptManager(false), new SpeedMode(false));
        Timer bulk = new Timer(new InterruptManager(false), new SpeedMode(false));
        scalar.presetDiv(0);
        bulk.presetDiv(0);
        scalar.setByte(0xff05, 1);
        bulk.setByte(0xff05, 1);
        scalar.setByte(0xff07, 0x05); // enable, 16 CPU-clock period
        bulk.setByte(0xff07, 0x05);

        assertTrue(bulk.performancePhysicalDmgEpochSpanLimit(54) >= 32);
        for (int i = 0; i < 32; i++) {
            scalar.tick();
        }
        assertTrue(bulk.tickPerformancePhysicalDmgEpoch(32));
        assertEquals(scalar.captureState(), bulk.captureState());
        assertEquals(3, bulk.getDebugTima());
    }

    @Test
    public void rejectsWrongSpeedTransientStateAndOverflow() {
        Timer normal = new Timer(new InterruptManager(true), new SpeedMode(true));
        assertEquals(0, normal.performanceEpochSpanLimit(54));
        assertEquals(0, normal.performancePhysicalDmgEpochSpanLimit(54));

        Timer debug = new Timer(new InterruptManager(true), doubleSpeed());
        debug.setDebugHooks(new TestDebugHooks());
        assertEquals(0, debug.performanceEpochSpanLimit(54));

        Timer reset = new Timer(new InterruptManager(true), doubleSpeed());
        reset.setByte(0xff04, 0);
        assertEquals(0, reset.performanceEpochSpanLimit(54));

        Timer overflow = new Timer(new InterruptManager(true), doubleSpeed());
        overflow.presetDiv(0x000f);
        overflow.setByte(0xff05, 0xff);
        overflow.setByte(0xff07, 0x05);
        overflow.tick();
        assertTrue(overflow.isDebugOverflowPending());
        assertEquals(0, overflow.performanceEpochSpanLimit(54));
        assertFalse(overflow.tickPerformanceEpoch(1));
    }


    private static SpeedMode doubleSpeed() {
        SpeedMode speed = new SpeedMode(true);
        speed.setByte(0xff4d, 1);
        assertTrue(speed.onStop());
        return speed;
    }
}
