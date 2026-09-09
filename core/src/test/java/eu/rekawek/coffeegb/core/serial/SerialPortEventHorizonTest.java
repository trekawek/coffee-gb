package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.TestDebugHooks;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SerialPortEventHorizonTest {

    /** Sweep every divider phase, including odd phases retained when entering double speed. */
    @Test
    public void internalTransfersPreserveEveryBitCallbackAndInterruptAtBothSpeeds() throws Exception {
        for (Mode mode : Mode.values()) {
            for (int phase = 0; phase < 256; phase++) {
                Fixture scalar = new Fixture(mode, phase, false);
                Fixture bulk = new Fixture(mode, phase, true);
                scalar.start(0xa6);
                bulk.start(0xa6);
                int elapsed = 0;
                int batched = 0;
                int tail = 8;
                while (bulk.port.isInternalClockTransferActive() || tail > 0) {
                    int span = bulk.port.performanceEventHorizon(
                            bulk.port.isInternalClockTransferActive() ? 54 : tail);
                    if (span == 0) {
                        scalar.port.tick();
                        bulk.port.tick();
                        span = 1;
                    } else {
                        for (int tick = 0; tick < span; tick++) {
                            scalar.port.tick();
                        }
                        bulk.port.tickPerformanceEventSpanTrusted(span);
                        batched += span;
                    }
                    elapsed += span;
                    assertEquivalent(scalar, bulk);
                    if (!bulk.port.isInternalClockTransferActive()) {
                        tail -= span;
                    }
                    assertTrue("serial transfer did not finish", elapsed < 5_000);
                }
                assertEquals(8, bulk.endpoint.exchanges.size());
                assertEquals(8, bulk.interrupts.getByte(0xff0f) & 8);
                assertTrue(mode + " phase=" + phase + " lost sustained serial batching",
                        batched > elapsed * 3 / 5);
            }
        }
    }

    @Test
    public void divResetScChangesAndAcknowledgeWindowsMatchAcrossPartialTransferRestore()
            throws Exception {
        for (Mode mode : Mode.values()) {
            Fixture scalar = new Fixture(mode, 29, false);
            Fixture bulk = new Fixture(mode, 29, true);
            scalar.start(0x62);
            bulk.start(0x62);
            for (int tick = 0; tick < 5_000;) {
                // Every operation is an owner-thread boundary. Short chunks must neither hide
                // an acknowledge pulled ahead of the last bit nor lose a DIV-induced bit shift.
                if (tick % 113 == 0) {
                    scalar.port.onDivReset();
                    bulk.port.onDivReset();
                }
                if (tick % 79 == 0) {
                    scalar.interrupts.clearInterrupt(InterruptManager.InterruptType.Serial);
                    bulk.interrupts.clearInterrupt(InterruptManager.InterruptType.Serial);
                }
                if (tick % 197 == 0) {
                    int sc = mode.sc ^ ((tick / 197 & 1) * 2);
                    scalar.port.setByte(0xff02, sc);
                    bulk.port.setByte(0xff02, sc);
                }
                if (tick % 137 == 0) {
                    scalar.port.setByte(0xff01, tick & 0xff);
                    bulk.port.setByte(0xff01, tick & 0xff);
                }
                if (tick % 251 == 0) {
                    ComponentState<SerialPort> serialState = bulk.port.captureState();
                    ComponentState<InterruptManager> interruptState = bulk.interrupts.captureState();
                    ComponentState<SerialEndpoint> endpointState = bulk.endpoint.captureState();
                    int horizon = bulk.port.performanceEventHorizon(54);
                    bulk.port.tick();
                    bulk.port.restoreState(serialState);
                    bulk.interrupts.restoreState(interruptState);
                    bulk.endpoint.restoreState(endpointState);
                    assertEquals(horizon, bulk.port.performanceEventHorizon(54));
                }
                int budget = Math.min(5_000 - tick, 54);
                for (int period : new int[]{113, 79, 197, 137, 251}) {
                    budget = Math.min(budget, period - tick % period);
                }
                int span = bulk.port.performanceEventHorizon(budget);
                if (span == 0) {
                    scalar.port.tick();
                    bulk.port.tick();
                    span = 1;
                } else {
                    for (int offset = 0; offset < span; offset++) {
                        scalar.port.tick();
                    }
                    bulk.port.tickPerformanceEventSpanTrusted(span);
                }
                tick += span;
                assertEquivalent(scalar, bulk);
            }
        }
    }

    @Test
    public void endpointDeadlinesAndUnknownCapabilitiesRemainScalar() {
        Fixture bulk = new Fixture(Mode.CGB, 0, true);
        bulk.start(0xaa);
        assertEquals("the next falling bit is later than the endpoint pin change", 36,
                bulk.port.performanceEventHorizon(54));
        bulk.port.tickPerformanceEventSpanTrusted(36);
        assertEquals(0, bulk.port.performanceEventHorizon(54));

        Fixture unknown = new Fixture(Mode.CGB, 0, false);
        unknown.start(0xaa);
        assertEquals(0, unknown.port.performanceEventHorizon(54));
        bulk.port.setDebugHooks(new TestDebugHooks());
        assertEquals(0, bulk.port.performanceEventHorizon(54));
    }

    @Test
    public void diagnosticCapabilitiesDistinguishDueEventsAndUnsupportedPortStates() {
        Fixture known = new Fixture(Mode.CGB, 0, true);
        Fixture unknown = new Fixture(Mode.CGB, 0, false);
        known.start(0xaa);
        unknown.start(0xaa);
        assertTrue(known.port.performanceEndpointClockCapabilityKnown());
        assertFalse(unknown.port.performanceEndpointClockCapabilityKnown());
        known.port.tickPerformanceEventSpanTrusted(36);
        var portState = known.port.captureState();
        var endpointState = known.endpoint.captureState();
        assertEquals(0, known.port.performanceEventHorizon(54));
        assertTrue("a due event still has a bounded clock contract",
                known.port.performanceEndpointClockCapabilityKnown());
        assertEquals(portState, known.port.captureState());
        assertEquals(endpointState, known.endpoint.captureState());

        SerialPort port = new SerialPort(new InterruptManager(false), false, new SpeedMode(false));
        port.init(new BarcodeBoySerialEndpoint());
        port.setByte(0xff02, 0x81);
        var internal = port.captureState();
        assertTrue(port.performanceEndpointClockCapabilityKnown());
        port.setByte(0xff02, 0x80);
        assertFalse("external scanner polling has no bounded input contract",
                port.performanceEndpointClockCapabilityKnown());
        port.restoreState(internal);
        assertTrue("metadata follows restored SC without a stale cached verdict",
                port.performanceEndpointClockCapabilityKnown());

        Peer2PeerSerialEndpoint disconnected = new Peer2PeerSerialEndpoint();
        port.init(disconnected);
        port.setByte(0xff02, 0x80);
        assertTrue(port.performanceEndpointClockCapabilityKnown());
        disconnected.init(new Peer2PeerSerialEndpoint());
        assertFalse("connected peers must not advertise independent deadlines",
                port.performanceEndpointClockCapabilityKnown());
    }

    @Test
    public void inertByteAndPrinterEndpointsAndBarcodeInternalHandshakeOptInExplicitly() {
        List<Integer> bytes = new ArrayList<>();
        SerialEndpoint receiver = new ByteReceivingSerialEndpoint(bytes::add);
        SerialEndpoint printer = new GameboyPrinterSerialEndpoint((pixels, width, height,
                topMargin, bottomMargin, exposure) -> {
            throw new AssertionError("an idle span must not print");
        });
        for (SerialEndpoint endpoint : new SerialEndpoint[]{receiver, printer}) {
            int deliveredBefore = bytes.size();
            SerialPort port = new SerialPort(new InterruptManager(false), false,
                    new SpeedMode(false));
            port.init(endpoint);
            assertEquals(54, port.performanceEventHorizon(54));
            port.setByte(0xff01, 0x35);
            port.setByte(0xff02, 0x81);
            assertEquals(54, port.performanceEventHorizon(54));
            port.tickPerformanceEventSpanTrusted(54);
            assertEquals("bit callbacks must remain outside admitted spans", deliveredBefore,
                    bytes.size());
            while (port.isInternalClockTransferActive()) {
                int span = port.performanceEventHorizon(54);
                if (span == 0) {
                    port.tick();
                } else {
                    port.tickPerformanceEventSpanTrusted(span);
                }
            }
        }
        assertEquals(List.of(0x35), bytes);

        BarcodeBoySerialEndpoint barcode = new BarcodeBoySerialEndpoint();
        SerialPort port = new SerialPort(new InterruptManager(false), false, new SpeedMode(false));
        port.init(barcode);
        port.setByte(0xff02, 0x81);
        assertEquals(54, port.performanceEventHorizon(54));
        port.tickPerformanceEventSpanTrusted(54);
        port.setByte(0xff02, 0x80);
        assertEquals("external barcode polling must retain its host-input sampling", 0,
                port.performanceEventHorizon(54));
    }

    private static void assertEquivalent(Fixture scalar, Fixture bulk) {
        assertEquals(scalar.port.captureState(), bulk.port.captureState());
        assertEquals(scalar.interrupts.captureState(), bulk.interrupts.captureState());
        assertEquals(scalar.endpoint.captureState(), bulk.endpoint.captureState());
    }

    private enum Mode {
        DMG(false, false, false, 0x81),
        CGB(true, false, false, 0x81),
        CGB_FAST(true, false, false, 0x83),
        CGB_DOUBLE(true, true, false, 0x81),
        CGB_DOUBLE_FAST(true, true, false, 0x83),
        CGB_COMPAT(true, false, true, 0x83);

        final boolean color;
        final boolean doubleSpeed;
        final boolean compatibility;
        final int sc;

        Mode(boolean color, boolean doubleSpeed, boolean compatibility, int sc) {
            this.color = color;
            this.doubleSpeed = doubleSpeed;
            this.compatibility = compatibility;
            this.sc = sc;
        }
    }

    private static final class Fixture {
        final Mode mode;
        final InterruptManager interrupts;
        final SerialPort port;
        final ClockedEndpoint endpoint;

        Fixture(Mode mode, int phase, boolean quiet) {
            this.mode = mode;
            interrupts = new InterruptManager(mode.color);
            SpeedMode speed = new SpeedMode(mode.color);
            speed.setDmgCompat(mode.compatibility);
            port = new SerialPort(interrupts, mode.color, speed);
            endpoint = new ClockedEndpoint(quiet);
            port.init(endpoint);
            for (int tick = 0; tick < phase; tick++) {
                port.tick();
            }
            if (mode.doubleSpeed) {
                try {
                    speed.setByte(0xff4d, 1);
                    var onStop = SpeedMode.class.getDeclaredMethod("onStop");
                    onStop.setAccessible(true);
                    assertTrue((boolean) onStop.invoke(speed));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            }
        }

        void start(int sb) {
            port.setByte(0xff01, sb);
            port.setByte(0xff02, mode.sc);
        }
    }

    /** A real master-clock deadline plus synchronous exchange callbacks, independent of the port. */
    private static final class ClockedEndpoint implements SerialEndpoint {
        final boolean quiet;
        final List<Long> exchanges = new ArrayList<>();
        long ticks;
        int bits;
        int sb;
        boolean pin = true;

        ClockedEndpoint(boolean quiet) {
            this.quiet = quiet;
        }

        @Override
        public void tick() {
            if (++ticks % 37 == 0) {
                pin = !pin;
            }
        }

        @Override
        public boolean isSerialInputHigh() {
            return pin;
        }

        @Override
        public int performanceQuietSpanLimit(int requested) {
            return quiet && requested > 0 ? (int) Math.min(requested, 36 - ticks % 37) : 0;
        }

        @Override
        public int performanceClockCapabilities() {
            return quiet ? PERFORMANCE_CLOCK_IDLE | PERFORMANCE_CLOCK_INTERNAL : 0;
        }

        @Override
        public void tickPerformanceQuietSpanTrusted(int ticks) {
            this.ticks += ticks;
        }

        @Override
        public void setSb(int sb) {
            this.sb = sb;
        }

        @Override
        public int recvBit() {
            return -1;
        }

        @Override
        public void startSending() {
            bits = 0;
        }

        @Override
        public int sendBit() {
            exchanges.add(ticks);
            return (0xa5 >>> (bits++ & 7)) & 1;
        }

        @Override
        public ComponentState<SerialEndpoint> captureState() {
            return new EndpointState(ticks, bits, sb, pin, List.copyOf(exchanges));
        }

        @Override
        public void restoreState(ComponentState<SerialEndpoint> state) {
            EndpointState saved = (EndpointState) state;
            ticks = saved.ticks;
            bits = saved.bits;
            sb = saved.sb;
            pin = saved.pin;
            exchanges.clear();
            exchanges.addAll(saved.exchanges);
        }
    }

    private record EndpointState(long ticks, int bits, int sb, boolean pin, List<Long> exchanges)
            implements ComponentState<SerialEndpoint> {
    }
}
