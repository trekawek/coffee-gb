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
    public void quietSpanStopsBeforeTimerAndFrameSequencerEdges() {
        Timer timer = new Timer(new InterruptManager(false), new SpeedMode(false));
        timer.presetDiv(0x000f);
        timer.setByte(0xff07, 0x05);
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
}
