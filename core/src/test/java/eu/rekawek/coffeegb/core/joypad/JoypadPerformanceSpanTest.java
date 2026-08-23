package eu.rekawek.coffeegb.core.joypad;

import eu.rekawek.coffeegb.core.TestDebugHooks;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.events.EventBus;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JoypadPerformanceSpanTest {

    @Test
    public void randomizedReleasedSpansMatchScalarTicks() {
        Random random = new Random(0x70a4adL);
        for (int i = 0; i < 1_000; i++) {
            InterruptManager scalarInterrupts = new InterruptManager(false);
            InterruptManager bulkInterrupts = new InterruptManager(false);
            Joypad scalar = new Joypad(
                    scalarInterrupts, EventBus.NULL_EVENT_BUS, false);
            Joypad bulk = new Joypad(
                    bulkInterrupts, EventBus.NULL_EVENT_BUS, false);
            int phaseTicks = random.nextInt(128);
            for (int tick = 0; tick < phaseTicks; tick++) {
                scalar.tick();
                bulk.tick();
            }
            int span = 1 + random.nextInt(3);
            assertTrue(bulk.canTickPerformanceQuietSpan(span));
            for (int tick = 0; tick < span; tick++) {
                scalar.tick();
            }
            assertTrue(bulk.tickPerformanceQuietSpan(span));
            assertEquivalent(scalar, bulk);
            assertEquals(scalarInterrupts.captureState(), bulkInterrupts.captureState());
        }
    }

    @Test
    public void quietSpanFailsClosedForInputSourcesMutationsObserversAndSgb() {
        Joypad customSource = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false,
                PlayerInputSnapshot::released);
        assertEquals(0, customSource.performanceQuietSpanLimit(1));

        Joypad pressed = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false);
        pressed.setPressedButtons(java.util.Set.of(Button.A));
        assertFalse(pressed.canTickPerformanceQuietSpan(1));
        assertFalse(pressed.tickPerformanceQuietSpan(1));
        assertEquals(0x00, pressed.getByte(0xff00) & 0x30);

        Joypad debug = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false);
        debug.setDebugHooks(new TestDebugHooks());
        assertEquals(0, debug.performanceQuietSpanLimit(1));

        Joypad timeline = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false);
        assertTrue(timeline.attachInputTimelineObserver((phase, player, mask, changed) -> {
        }));
        assertEquals(0, timeline.performanceQuietSpanLimit(1));

        Joypad sgb = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, true);
        assertEquals(0, sgb.performanceQuietSpanLimit(1));
    }

    @Test
    public void cachedHubSpanRejectsLegacyMutationUntilScalarReconciliation() {
        PlayerInputHub hub = new PlayerInputHub();
        Joypad joypad = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false, hub);
        joypad.tick();
        assertTrue(joypad.performanceSettledHaltSpanLimit(54) > 3);

        joypad.setPressedButtons(java.util.Set.of(Button.A));
        assertEquals(0, joypad.performanceSettledHaltSpanLimit(54));
        assertFalse(joypad.isPerformanceQuietSpanStillEligible());

        joypad.setPressedButtons(java.util.Set.of());
        assertEquals(0, joypad.performanceSettledHaltSpanLimit(54));
        joypad.tick();
        assertEquals("the mutation edge itself remains scalar", 0,
                joypad.performanceSettledHaltSpanLimit(54));
        joypad.tick();
        assertTrue(joypad.performanceSettledHaltSpanLimit(54) > 3);
    }

    private static void assertEquivalent(Joypad scalar, Joypad bulk) {
        assertEquals(scalar.getByte(0xff00), bulk.getByte(0xff00));
        assertEquals(scalar.getSampledInput(), bulk.getSampledInput());
        assertEquals(scalar.getLegacyPressedButtons(), bulk.getLegacyPressedButtons());
        assertEquals(
                scalar.captureDebugJoypadInspection(false),
                bulk.captureDebugJoypadInspection(false));
    }
}
