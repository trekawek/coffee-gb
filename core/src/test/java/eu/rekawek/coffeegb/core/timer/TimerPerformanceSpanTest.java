package eu.rekawek.coffeegb.core.timer;

import eu.rekawek.coffeegb.core.TestDebugHooks;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TimerPerformanceSpanTest {

    @Test
    public void randomizedEligibleSpansMatchScalarTicks() {
        Random random = new Random(0x71a5e2bL);
        int matched = 0;
        for (int i = 0; i < 4_000; i++) {
            boolean gbc = random.nextBoolean();
            InterruptManager scalarInterrupts = new InterruptManager(gbc);
            InterruptManager bulkInterrupts = new InterruptManager(gbc);
            Timer scalar = new Timer(scalarInterrupts, new SpeedMode(gbc));
            Timer bulk = new Timer(bulkInterrupts, new SpeedMode(gbc));

            int div = random.nextInt(0x10000);
            int tac = random.nextInt(8);
            scalar.presetDiv(div);
            bulk.presetDiv(div);
            scalar.setByte(0xff06, random.nextInt(0x100));
            bulk.setByte(0xff06, scalar.getDebugTma());
            scalar.setByte(0xff05, random.nextInt(0x100));
            bulk.setByte(0xff05, scalar.getDebugTima());
            scalar.setByte(0xff07, tac);
            bulk.setByte(0xff07, tac);

            int limit = bulk.performanceQuietSpanLimit(3);
            if (limit == 0) {
                continue;
            }
            int span = 1 + random.nextInt(limit);
            for (int tick = 0; tick < span; tick++) {
                scalar.tick();
            }
            assertTrue("eligible span was rejected", bulk.tickPerformanceQuietSpan(span));
            assertEquals(scalar.captureState(), bulk.captureState());
            assertEquals(scalarInterrupts.captureState(), bulkInterrupts.captureState());
            matched++;
        }
        assertTrue("randomized setup produced no eligible spans", matched > 500);
    }

    @Test
    public void quietSpanStopsBeforeOverflowAndFrameSequencerEdges() {
        Timer timer = new Timer(new InterruptManager(false), new SpeedMode(false));
        timer.presetDiv(0x000e);
        timer.setByte(0xff05, 0xff);
        timer.setByte(0xff07, 0x05);
        timer.tick();
        var beforeTimerEdge = timer.captureState();
        assertEquals(0, timer.performanceQuietSpanLimit(3));
        assertFalse(timer.tickPerformanceQuietSpan(1));
        assertEquals(beforeTimerEdge, timer.captureState());

        timer = new Timer(new InterruptManager(false), new SpeedMode(false));
        timer.presetDiv(0x1fff);
        var beforeFrameEdge = timer.captureState();
        assertEquals(0, timer.performanceQuietSpanLimit(3));
        assertFalse(timer.tickPerformanceQuietSpan(1));
        assertEquals(beforeFrameEdge, timer.captureState());
    }

    @Test
    public void quietSpanFailsClosedForOverflowRippleDebugAndDoubleSpeed() {
        Timer overflow = new Timer(new InterruptManager(true), new SpeedMode(true));
        overflow.presetDiv(0x000f);
        overflow.setByte(0xff05, 0xff);
        overflow.setByte(0xff07, 0x05);
        overflow.tick();
        assertTrue(overflow.isDebugOverflowPending());
        var overflowState = overflow.captureState();
        assertFalse(overflow.tickPerformanceQuietSpan(1));
        assertEquals(overflowState, overflow.captureState());

        Timer ripple = new Timer(new InterruptManager(false), new SpeedMode(false));
        ripple.setByte(0xff04, 0);
        for (int i = 0; i < 4; i++) {
            ripple.tick();
        }
        ripple.onHaltBug();
        for (int i = 0; i < 252; i++) {
            ripple.tick();
        }
        assertEquals(0, ripple.performanceQuietSpanLimit(3));

        Timer debug = new Timer(new InterruptManager(false), new SpeedMode(false));
        debug.setDebugHooks(new TestDebugHooks());
        assertEquals(0, debug.performanceQuietSpanLimit(1));

    }

    @Test
    public void pendingTimerAcknowledgeMatchesScalarBeginningOfTick() {
        InterruptManager scalarInterrupts = new InterruptManager(false);
        InterruptManager bulkInterrupts = new InterruptManager(false);
        Timer scalar = new Timer(scalarInterrupts, new SpeedMode(false));
        Timer bulk = new Timer(bulkInterrupts, new SpeedMode(false));
        scalarInterrupts.requestInterrupt(InterruptManager.InterruptType.Timer);
        scalarInterrupts.clearInterrupt(InterruptManager.InterruptType.Timer);
        bulkInterrupts.requestInterrupt(InterruptManager.InterruptType.Timer);
        bulkInterrupts.clearInterrupt(InterruptManager.InterruptType.Timer);

        scalar.tick();
        assertTrue(bulk.tickPerformanceQuietSpan(1));
        assertEquals(scalar.captureState(), bulk.captureState());
        assertEquals(scalarInterrupts.captureState(), bulkInterrupts.captureState());
    }

    @Test
    public void ordinaryTimaIncrementsDoNotFenceQuietOrSettledHaltSpans() {
        int[] periods = {1024, 16, 64, 256};
        for (boolean gbc : new boolean[]{false, true}) {
            for (int rate = 0; rate < periods.length; rate++) {
                InterruptManager scalarInterrupts = new InterruptManager(gbc);
                InterruptManager bulkInterrupts = new InterruptManager(gbc);
                Timer scalar = new Timer(scalarInterrupts, new SpeedMode(gbc));
                Timer bulk = new Timer(bulkInterrupts, new SpeedMode(gbc));
                for (Timer timer : new Timer[]{scalar, bulk}) {
                    timer.presetDiv(periods[rate] - 2);
                    timer.setByte(0xff05, 0x30);
                    timer.setByte(0xff07, 4 | rate);
                    timer.tick();
                }
                assertEquals(3, bulk.performanceQuietSpanLimit(3));
                for (int tick = 0; tick < 3; tick++) {
                    scalar.tick();
                }
                assertTrue(bulk.tickPerformanceQuietSpan(3));
                assertEquals(0x31, bulk.getDebugTima());
                assertEquals(scalar.captureState(), bulk.captureState());

                int ticks = periods[rate] * 3;
                assertEquals(ticks, bulk.performanceSettledHaltSpanLimit(ticks));
                for (int tick = 0; tick < ticks; tick++) {
                    scalar.tick();
                }
                bulk.tickPerformanceQuietSpanTrusted(ticks);
                assertEquals(0x34, bulk.getDebugTima());
                assertEquals(scalar.captureState(), bulk.captureState());
                assertEquals(scalarInterrupts.captureState(), bulkInterrupts.captureState());
            }
        }
    }

    @Test
    public void settledHaltMatchesScalarThroughOverflowReloadWakeAndRestore() {
        Random random = new Random(0x71a54a17L);
        int crossedIncrements = 0;
        for (boolean gbc : new boolean[]{false, true}) {
            for (int rate = 0; rate < 4; rate++) {
                for (int phase = 0; phase < 256; phase++) {
                    InterruptManager scalarInterrupts = new InterruptManager(gbc);
                    InterruptManager bulkInterrupts = new InterruptManager(gbc);
                    Timer scalar = new Timer(scalarInterrupts, new SpeedMode(gbc));
                    Timer bulk = new Timer(bulkInterrupts, new SpeedMode(gbc));
                    for (Timer timer : new Timer[]{scalar, bulk}) {
                        timer.presetDiv(0x1f00 + phase);
                        timer.setByte(0xff05, 0xfc);
                        timer.setByte(0xff06, 0x80);
                        timer.setByte(0xff07, 4 | rate);
                    }
                    int elapsed = 0;
                    while (elapsed < 256) {
                        int requested = Math.min(256 - elapsed, 1 + random.nextInt(54));
                        int span = bulk.performanceSettledHaltSpanLimit(requested);
                        int beforeTima = bulk.getDebugTima();
                        if (span == 0) {
                            scalar.tick();
                            bulk.tick();
                            span = 1;
                        } else {
                            for (int tick = 0; tick < span; tick++) {
                                scalar.tick();
                            }
                            bulk.tickPerformanceQuietSpanTrusted(span);
                            if (bulk.getDebugTima() != beforeTima) {
                                crossedIncrements++;
                            }
                        }
                        elapsed += span;
                        assertEquals(scalar.captureState(), bulk.captureState());
                        assertEquals(scalarInterrupts.captureState(), bulkInterrupts.captureState());
                        // Restore an arbitrary partial span into the live destination, including
                        // overflow and wake latches. No derived admission state may survive it.
                        var state = bulk.captureState();
                        var interrupts = bulkInterrupts.captureState();
                        int horizon = bulk.performanceSettledHaltSpanLimit(54);
                        bulk.tick();
                        bulk.restoreState(state);
                        bulkInterrupts.restoreState(interrupts);
                        assertEquals(horizon, bulk.performanceSettledHaltSpanLimit(54));
                    }
                }
            }
        }
        assertTrue("the HALT test must actually batch TIMA increments", crossedIncrements > 1_000);
    }
}
