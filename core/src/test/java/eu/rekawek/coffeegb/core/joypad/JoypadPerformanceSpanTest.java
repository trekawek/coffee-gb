package eu.rekawek.coffeegb.core.joypad;

import eu.rekawek.coffeegb.core.TestDebugHooks;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.sgb.Commands;
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
