package eu.rekawek.coffeegb.core.joypad;

import eu.rekawek.coffeegb.core.TestDebugHooks;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.sgb.Commands;
import eu.rekawek.coffeegb.core.sgb.SgbPacketTestBuilder;
import eu.rekawek.coffeegb.core.state.ComponentState;
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
    public void quietSpanFailsClosedForInputSourcesMutationsAndObservers() {
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
        assertTrue(sgb.performanceQuietSpanLimit(1) > 0);
        assertTrue(sgb.performanceSettledHaltSpanLimit(54) > 3);
    }

    @Test
    public void sgbReceiverCompletesIdenticallyAcrossASettledBulkGap() {
        InterruptManager scalarInterrupts = new InterruptManager(false);
        InterruptManager bulkInterrupts = new InterruptManager(false);
        Joypad scalar = new Joypad(scalarInterrupts, EventBus.NULL_EVENT_BUS, true);
        Joypad bulk = new Joypad(bulkInterrupts, EventBus.NULL_EVENT_BUS, true);
        int[] packet = patternedPacket();
        writeSelector(scalar, 0x30);
        writeSelector(bulk, 0x30);
        writeSelector(scalar, 0x00);
        writeSelector(bulk, 0x00);
        writeSelector(scalar, 0x30);
        writeSelector(bulk, 0x30);
        writeBits(scalar, packet, 0, 37);
        writeBits(bulk, packet, 0, 37);

        // The selector writes themselves are CPU-bus mutations. Let both receivers consume the
        // same settled released-input filter before asking for a HALT horizon; the SGB packet
        // state remains mid-transfer throughout this reconciliation.
        for (int i = 0; i < 4 * Joypad.JOYP_CLOCK_TICKS; i++) {
            scalar.tick();
            bulk.tick();
        }

        int span = bulk.performanceSettledHaltSpanLimit(54);
        assertTrue("SGB settled receiver did not expose its exact idle horizon", span > 3);
        for (int i = 0; i < span; i++) {
            scalar.tick();
        }
        bulk.tickPerformanceQuietSpanTrusted(span);

        writeBits(scalar, packet, 37, 128);
        writeBits(bulk, packet, 37, 128);
        writeSelector(scalar, 0x20);
        writeSelector(bulk, 0x20);
        writeSelector(scalar, 0x30);
        writeSelector(bulk, 0x30);

        assertEquivalent(scalar, bulk);
        assertEquals(scalar.captureDebugJoypadInspection(true),
                bulk.captureDebugJoypadInspection(true));
        assertEquals(scalarInterrupts.captureState(), bulkInterrupts.captureState());
    }

    @Test
    public void sgbMultiplayerControlInvalidatesSettledHaltUntilScalarReconciliation() {
        try (EventBusImpl sgbBus = new EventBusImpl(null, null, false)) {
            Joypad joypad = new Joypad(new InterruptManager(false), sgbBus, true);
            assertTrue(joypad.performanceSettledHaltSpanLimit(54) > 3);

            sgbBus.post(mltReq(1));
            assertEquals("MLT_REQ players must reject a settled packet", 0,
                    joypad.performanceSettledHaltSpanLimit(54));

            // Returning to one player is also an event edge. It becomes eligible only after the
            // next scalar JOYP reconciliation, matching the ordinary input fast-path contract.
            sgbBus.post(mltReq(0));
            assertEquals("MLT_REQ reset must still reject before reconciliation", 0,
                    joypad.performanceSettledHaltSpanLimit(54));
            joypad.tick();
            assertTrue("MLT_REQ reset did not restore the settled horizon",
                    joypad.performanceSettledHaltSpanLimit(54) > 3);
        }
    }

    @Test
    public void sgbMultiplayerReleasedInputSettlesForControlsAndPlayerIds() {
        int[][] cases = {{1, 0}, {1, 1}, {2, 0}, {3, 0}, {3, 1}, {3, 2}, {3, 3}};
        for (int[] testCase : cases) {
            int players = testCase[0];
            int currentPlayer = testCase[1];
            try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder()) {
                Joypad joypad = fixture.joypad();
                fixture.sendCommand(0x11, 1, players);
                for (int player = 0; player < currentPlayer; player++) {
                    selectNextPlayer(joypad);
                }

                assertEquals("players=" + players + ", player=" + currentPlayer
                                + " must remain scalar immediately after MLT_REQ",
                        0, joypad.performanceQuietSpanLimit(54));
                settleSgbPerformanceSpan(joypad);

                int expectedLines = 0x0f - currentPlayer;
                assertEquals("players=" + players + ", player=" + currentPlayer
                                + " filtered lines", expectedLines,
                        filteredInputLines(joypad.captureState()));
                assertEquals("players=" + players + ", player=" + currentPlayer
                                + " input history", settledHistory(expectedLines),
                        inputHistory(joypad.captureState()));
                assertEquals("players=" + players + ", player=" + currentPlayer
                                + " player ID", 0x0f - currentPlayer,
                        joypad.getByte(0xff00) & 0x0f);
                assertEquals("players=" + players + ", player=" + currentPlayer
                                + " quiet horizon", 3,
                        joypad.performanceQuietSpanLimit(54));
                assertEquals("players=" + players + ", player=" + currentPlayer
                                + " halt horizon", 54,
                        joypad.performanceSettledHaltSpanLimit(54));
            }
        }
    }

    @Test
    public void sgbMultiplayerReleasedInputSettlesForNonIdSelectors() {
        for (int selector : new int[]{0x00, 0x10, 0x20, 0x30}) {
            try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder()) {
                Joypad joypad = fixture.joypad();
                fixture.sendCommand(0x11, 1, 1);
                selectNextPlayer(joypad);
                writeSelector(joypad, selector);

                assertEquals("selector 0x" + Integer.toHexString(selector)
                                + " must remain scalar immediately after the write",
                        0, joypad.performanceQuietSpanLimit(54));
                settleSgbPerformanceSpan(joypad);

                int expectedLines = selector == 0x30 ? 0x0e : 0x0f;
                assertEquals("selector 0x" + Integer.toHexString(selector)
                                + " filtered lines", expectedLines,
                        filteredInputLines(joypad.captureState()));
                assertEquals("selector 0x" + Integer.toHexString(selector)
                                + " input history", settledHistory(expectedLines),
                        inputHistory(joypad.captureState()));
                assertEquals("selector 0x" + Integer.toHexString(selector)
                                + " quiet horizon", 3,
                        joypad.performanceQuietSpanLimit(54));
            }
        }
    }

    @Test
    public void sgbMultiplayerReleasedTrustedSpansMatchScalarForEveryBudget() {
        for (int players = 1; players <= 3; players++) {
            try (EventBusImpl scalarBus = new EventBusImpl(null, null, false);
                 EventBusImpl bulkBus = new EventBusImpl(null, null, false)) {
                InterruptManager scalarInterrupts = new InterruptManager(false);
                InterruptManager bulkInterrupts = new InterruptManager(false);
                Joypad scalar = new Joypad(scalarInterrupts, scalarBus, true);
                Joypad bulk = new Joypad(bulkInterrupts, bulkBus, true);
                scalarBus.post(mltReq(players));
                bulkBus.post(mltReq(players));
                settleSgbPerformancePair(scalar, bulk);

                for (int budget = 1; budget <= 54; budget++) {
                    int span = bulk.performanceQuietSpanLimit(budget);
                    assertEquals("players=" + players + ", budget=" + budget
                                    + " quiet horizon", Math.min(budget, 3), span);
                    for (int tick = 0; tick < span; tick++) {
                        scalar.tick();
                    }
                    bulk.tickPerformanceQuietSpanTrusted(span);
                    assertEquivalent(scalar, bulk);
                    assertEquals(scalarInterrupts.captureState(), bulkInterrupts.captureState());
                    assertEquals("players=" + players + ", budget=" + budget
                                    + " tick", tick(scalar.captureState()),
                            tick(bulk.captureState()));
                    assertEquals("players=" + players + ", budget=" + budget
                                    + " input history", inputHistory(scalar.captureState()),
                            inputHistory(bulk.captureState()));
                    assertEquals("players=" + players + ", budget=" + budget
                                    + " filtered lines", filteredInputLines(scalar.captureState()),
                            filteredInputLines(bulk.captureState()));
                }
            }
        }
    }

    @Test
    public void sgbMultiplayerQuietEligibilityRevokesOnStateAndObserverChanges() {
        try (EventBusImpl sgbBus = new EventBusImpl(null, null, false)) {
            Joypad joypad = new Joypad(new InterruptManager(false), sgbBus, true);
            sgbBus.post(mltReq(1));
            settleSgbPerformanceSpan(joypad);
            ComponentState<Joypad> settled = joypad.captureState();
            assertTrue(joypad.isPerformanceQuietSpanStillEligible());

            joypad.setByte(0xff00, 0x10);
            assertFalse(joypad.isPerformanceQuietSpanStillEligible());
            assertEquals(0, joypad.performanceQuietSpanLimit(54));

            joypad.restoreState(settled);
            assertFalse("restore must invalidate the derived eligibility",
                    joypad.isPerformanceQuietSpanStillEligible());
            settleSgbPerformanceSpan(joypad);

            joypad.setPressedButtons(java.util.Set.of(Button.A));
            assertFalse(joypad.isPerformanceQuietSpanStillEligible());
            joypad.setPressedButtons(java.util.Set.of());
            assertFalse(joypad.isPerformanceQuietSpanStillEligible());

            InputTimelineObserver observer = (phase, player, mask, changed) -> {
            };
            assertTrue(joypad.attachInputTimelineObserver(observer));
            assertFalse(joypad.isPerformanceQuietSpanStillEligible());
            assertEquals(0, joypad.performanceQuietSpanLimit(54));
            assertTrue(joypad.detachInputTimelineObserver(observer));
            assertFalse(joypad.isPerformanceQuietSpanStillEligible());

            joypad.setDebugHooks(new TestDebugHooks());
            assertFalse(joypad.isPerformanceQuietSpanStillEligible());
            joypad.setDebugHooks(null);
            assertFalse(joypad.isPerformanceQuietSpanStillEligible());

            sgbBus.post(mltReq(3));
            assertFalse("MLT_REQ must revoke the settled multiplayer span",
                    joypad.isPerformanceQuietSpanStillEligible());
        }
    }

    private static Commands.MltReqCmd mltReq(int players) {
        int[] packet = new int[Commands.PACKET_SIZE];
        packet[0] = 0x11 * 8 + 1;
        packet[1] = players;
        return (Commands.MltReqCmd) Commands.toCommand(packet);
    }

    private static int[] patternedPacket() {
        int[] packet = new int[16];
        for (int i = 0; i < packet.length; i++) {
            packet[i] = 0x31 + i * 17;
        }
        return packet;
    }

    private static void writeBits(Joypad joypad, int[] packet, int from, int to) {
        for (int bit = from; bit < to; bit++) {
            writeSelector(joypad, ((packet[bit / 8] >>> (bit & 7)) & 1) == 0 ? 0x20 : 0x10);
            writeSelector(joypad, 0x30);
        }
    }

    private static void writeSelector(Joypad joypad, int selector) {
        joypad.setByte(0xff00, selector);
    }

    private static void selectNextPlayer(Joypad joypad) {
        writeSelector(joypad, 0x10);
        writeSelector(joypad, 0x30);
    }

    private static void settleSgbPerformanceSpan(Joypad joypad) {
        for (int ticks = 0; ticks < 4 * Joypad.JOYP_CLOCK_TICKS + 2; ticks++) {
            joypad.tick();
            if (joypad.performanceQuietSpanLimit(54) > 0) {
                return;
            }
        }
        throw new AssertionError("SGB multiplayer input filter did not settle");
    }

    private static void settleSgbPerformancePair(Joypad scalar, Joypad bulk) {
        for (int ticks = 0; ticks < 4 * Joypad.JOYP_CLOCK_TICKS + 2; ticks++) {
            scalar.tick();
            bulk.tick();
            if (bulk.performanceQuietSpanLimit(54) > 0) {
                assertEquals(scalar.getByte(0xff00), bulk.getByte(0xff00));
                return;
            }
        }
        throw new AssertionError("SGB multiplayer input filter did not settle");
    }

    private static int settledHistory(int inputLines) {
        int history = 0;
        for (int line = 0; line < 4; line++) {
            if ((inputLines & 1 << line) == 0) {
                history |= 0x0f << (line * 4);
            }
        }
        return history;
    }

    private static long tick(ComponentState<Joypad> state) {
        return (long) stateField(state, "tick");
    }

    private static int inputHistory(ComponentState<Joypad> state) {
        return (int) stateField(state, "inputHistory");
    }

    private static int filteredInputLines(ComponentState<Joypad> state) {
        return (int) stateField(state, "filteredInputLines");
    }

    private static Object stateField(ComponentState<Joypad> state, String name) {
        try {
            var accessor = state.getClass().getDeclaredMethod(name);
            accessor.setAccessible(true);
            return accessor.invoke(state);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Joypad checkpoint has no " + name + " state", e);
        }
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
