package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.TestDebugHooks;
import eu.rekawek.coffeegb.core.timer.Timer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Coverage for the strict native-CGB PPU/LCDC owner timer horizon.
 *
 * <p>The strict API changes only the caller's requested master-tick ceiling from 54 to 63.
 * Its event distances and trusted arithmetic must remain the same as the ordinary native x2
 * epoch. These tests deliberately compare an accepted strict endpoint with scalar Timer.tick()
 * and keep the existing ordinary-54 API in the same phase matrix.</p>
 */
public final class TimerStrictPpuEpochSpanTest {

    @Test
    public void acceptedStrictHorizonMatchesScalarAcrossDividerAndTacPhases() {
        int accepted = 0;
        for (int tac = 0; tac < 8; tac++) {
            // 0x2000 covers the full frame-sequencer phase; all TAC periods divide this domain.
            for (int div = 0; div < 0x2000; div++) {
                TimerPair pair = pair(div, tac, 0x40, 0xa5);
                Timer scalar = pair.scalar;
                Timer batched = pair.batched;

                int strict63 = batched.performanceStrictPpuEpochSpanLimit(63);
                int strict54 = batched.performanceStrictPpuEpochSpanLimit(54);
                int ordinary54 = batched.performanceEpochSpanLimit(54);
                assertTrue("strict horizon must be within 0..63", strict63 >= 0 && strict63 <= 63);
                assertEquals("strict request 54 must retain ordinary-54 horizon",
                        ordinary54, strict54);
                assertEquals("ordinary API must retain its 54-dot ceiling",
                        ordinary54, batched.performanceEpochSpanLimit(63));
                assertTrue("ordinary API must never exceed 54 master ticks",
                        batched.performanceEpochSpanLimit(63) <= 54);

                if (strict63 == 0) {
                    continue;
                }
                for (int tick = 0; tick < strict63; tick++) {
                    scalar.tick();
                }
                batched.tickPerformanceEpochTrusted(strict63);
                assertEquals("timer state at accepted strict endpoint tac=" + tac
                                + " div=" + div,
                        scalar.captureState(), batched.captureState());
                assertEquals("interrupt state at accepted strict endpoint tac=" + tac
                                + " div=" + div,
                        pair.scalarInterrupts.captureState(), pair.batchedInterrupts.captureState());
                accepted++;
            }
        }
        assertTrue("phase matrix produced too few accepted strict spans: " + accepted,
                accepted > 50_000);
    }

    @Test
    public void strictHorizonCapsBeforeOverflowAndFrameEdges() {
        // First falling edge is 1 + (0x100 - 0xfe - 1) * 16 = 17 CPU clocks;
        // native x2 excludes that edge, so (17 - 1) / 2 = 8 master ticks are accepted.
        assertExactEndpoint("overflow pre-edge", 0x000f, 0x05, 0xfe, 0xa5, 8);
        assertEquals(0, configured(0x000f, 0x05, 0xff, 0xa5)
                .performanceStrictPpuEpochSpanLimit(63));

        // The ordinary DIV tap is 16 CPU clocks away and the CGB +2 tap is 14 clocks away;
        // the latter yields the tighter (14 - 1) / 2 = 6 master-tick cap.
        assertExactEndpoint("frame-sequencer pre-edge", 0x1ff0, 0, 0x40, 0xa5, 6);
        // Here only the +2 candidate is at a two-CPU-clock boundary; the ordinary tap is not.
        assertEquals(0, configured(0x1ffc, 0, 0x40, 0xa5)
                .performanceStrictPpuEpochSpanLimit(63));
        assertEquals(0, configured(0x1fff, 0, 0x40, 0xa5)
                .performanceStrictPpuEpochSpanLimit(63));
    }

    @Test
    public void strictHorizonRefusesReloadWakeRippleResetAndDebugTransients() {
        InterruptManager interrupts = new InterruptManager(true);
        Timer overflow = new Timer(interrupts, doubleSpeed());
        overflow.presetDiv(0x000f);
        overflow.setByte(0xff05, 0xff);
        overflow.setByte(0xff06, 0xf0);
        overflow.setByte(0xff07, 0x05);
        overflow.tick();
        assertTrue("overflow must be the refused state", overflow.isDebugOverflowPending());
        assertEquals(0, overflow.performanceStrictPpuEpochSpanLimit(63));

        // Two more native-x2 ticks reach the reload/IRQ gate, where both reload and wake
        // state are still scalar-visible and the strict owner must remain refused.
        tick(overflow, 2);
        assertEquals(0xf0, overflow.getDebugTima());
        assertTrue(interrupts.isInterruptFlagSet(InterruptManager.InterruptType.Timer));
        assertEquals(0, overflow.performanceStrictPpuEpochSpanLimit(63));
        // One more native-x2 tick drains the four-clock wake delay and the remaining reload
        // tail. The same state is eligible again once the transient has actually settled.
        overflow.tick();
        assertTrue("strict horizon must recover after reload/wake settles",
                overflow.performanceStrictPpuEpochSpanLimit(63) > 0);

        Timer reset = configured(0, 0, 0x40, 0xa5);
        reset.setByte(0xff04, 0);
        assertEquals(0, reset.performanceStrictPpuEpochSpanLimit(63));

        Timer debug = configured(0, 0, 0x40, 0xa5);
        debug.setDebugHooks(new TestDebugHooks());
        assertEquals(0, debug.performanceStrictPpuEpochSpanLimit(63));

        // Reach the DMG HALT-bug ripple state, then enter x2 using the test-only legacy switch
        // permission. This exercises the pending-carry distance and the visible-ripple refusal
        // without manufacturing a TimerState that production can never create.
        SpeedMode speed = new SpeedMode(false, true);
        Timer ripple = new Timer(new InterruptManager(false), speed);
        ripple.setByte(0xff04, 0);
        tick(ripple, 4);
        ripple.onHaltBug();
        speed.setByte(0xff4d, 1);
        assertTrue(speed.onStop());
        tick(ripple, 124); // div=0xfc: carry is four CPU clocks away, one x2 tick is safe.
        assertEquals(1, ripple.performanceStrictPpuEpochSpanLimit(63));
        ripple.tick(); // div=0xfe: the pending carry is two CPU clocks away; cap is zero.
        assertEquals(0, ripple.performanceStrictPpuEpochSpanLimit(63));
        ripple.tick(); // crosses the carry and exposes the one-tick visible ripple.
        assertEquals(0, ripple.performanceStrictPpuEpochSpanLimit(63));
    }

    private static void assertExactEndpoint(
            String label, int div, int tac, int tima, int tma, int expected) {
        TimerPair pair = pair(div, tac, tima, tma);
        Timer scalar = pair.scalar;
        Timer batched = pair.batched;
        int actual = batched.performanceStrictPpuEpochSpanLimit(63);
        assertEquals(label + " strict cap", expected, actual);
        assertEquals(label + " ordinary-54 cap", Math.min(54, expected),
                batched.performanceEpochSpanLimit(54));
        for (int tick = 0; tick < expected; tick++) {
            scalar.tick();
        }
        batched.tickPerformanceEpochTrusted(expected);
        assertEquals(label + " timer endpoint", scalar.captureState(), batched.captureState());
        assertEquals(label + " interrupt endpoint", pair.scalarInterrupts.captureState(),
                pair.batchedInterrupts.captureState());
    }

    private static Timer configured(int div, int tac, int tima, int tma) {
        Timer timer = new Timer(new InterruptManager(true), doubleSpeed());
        configure(timer, div, tac, tima, tma);
        return timer;
    }

    private static TimerPair pair(int div, int tac, int tima, int tma) {
        InterruptManager scalarInterrupts = new InterruptManager(true);
        InterruptManager batchedInterrupts = new InterruptManager(true);
        Timer scalar = new Timer(scalarInterrupts, doubleSpeed());
        Timer batched = new Timer(batchedInterrupts, doubleSpeed());
        configure(scalar, div, tac, tima, tma);
        configure(batched, div, tac, tima, tma);
        return new TimerPair(scalar, batched, scalarInterrupts, batchedInterrupts);
    }

    private static void configure(Timer timer, int div, int tac, int tima, int tma) {
        timer.presetDiv(div);
        timer.setByte(0xff06, tma);
        timer.setByte(0xff05, tima);
        timer.setByte(0xff07, tac);
    }

    private record TimerPair(
            Timer scalar, Timer batched,
            InterruptManager scalarInterrupts, InterruptManager batchedInterrupts) {
    }

    private static SpeedMode doubleSpeed() {
        SpeedMode speed = new SpeedMode(true);
        speed.setByte(0xff4d, 1);
        assertTrue(speed.onStop());
        return speed;
    }

    private static void tick(Timer timer, int ticks) {
        for (int i = 0; i < ticks; i++) {
            timer.tick();
        }
    }
}
