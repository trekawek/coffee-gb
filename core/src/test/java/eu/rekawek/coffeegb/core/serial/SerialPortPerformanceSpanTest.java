package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.TestDebugHooks;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SerialPortPerformanceSpanTest {

    @Test
    public void physicalDmgIdleEpochMatchesScalarNormalSpeedTicks() {
        Random random = new Random(0xd06e51a1L);
        for (int iteration = 0; iteration < 1_000; iteration++) {
            InterruptManager scalarInterrupts = new InterruptManager(false);
            InterruptManager bulkInterrupts = new InterruptManager(false);
            SerialPort scalar = new SerialPort(
                    scalarInterrupts, false, new SpeedMode(false));
            SerialPort bulk = new SerialPort(
                    bulkInterrupts, false, new SpeedMode(false));
            int phaseTicks = random.nextInt(0x100);
            for (int tick = 0; tick < phaseTicks; tick++) {
                scalar.tick();
                bulk.tick();
            }
            int sc = random.nextInt(0x80);
            scalar.setByte(0xff02, sc);
            bulk.setByte(0xff02, sc);
            int span = 1 + random.nextInt(54);

            assertFalse(bulk.performanceEpochIdle(span));
            assertTrue(bulk.performancePhysicalDmgEpochIdle(span));
            for (int tick = 0; tick < span; tick++) {
                scalar.tick();
            }
            bulk.tickPerformancePhysicalDmgEpochIdle(span);

            assertEquals(scalar.captureState(), bulk.captureState());
            assertEquals(scalarInterrupts.captureState(), bulkInterrupts.captureState());
        }
    }

    @Test
    public void nativeCgbNormalSpeedUsesOnlyTheFixedX1EpochIdleContract() {
        SerialPort cgbNormal = new SerialPort(
                new InterruptManager(true), true, new SpeedMode(true));
        assertFalse(cgbNormal.performanceEpochIdle(54));
        assertFalse(cgbNormal.performancePhysicalDmgEpochIdle(54));
        assertTrue(cgbNormal.performanceNormalSpeedEpochIdle(54, true));
    }

    @Test
    public void cgbCompatibilityIdleEpochMatchesScalarNormalSpeedTicks() {
        SpeedMode scalarSpeed = new SpeedMode(true);
        SpeedMode bulkSpeed = new SpeedMode(true);
        scalarSpeed.setDmgCompat(true);
        bulkSpeed.setDmgCompat(true);
        InterruptManager scalarInterrupts = new InterruptManager(true);
        InterruptManager bulkInterrupts = new InterruptManager(true);
        SerialPort scalar = new SerialPort(scalarInterrupts, true, scalarSpeed);
        SerialPort bulk = new SerialPort(bulkInterrupts, true, bulkSpeed);
        for (int tick = 0; tick < 37; tick++) {
            scalar.tick();
            bulk.tick();
        }
        assertTrue(bulk.performanceNormalSpeedEpochIdle(54, true));
        for (int tick = 0; tick < 54; tick++) {
            scalar.tick();
        }
        bulk.tickPerformanceNormalSpeedEpochIdle(54);
        assertEquals(scalar.captureState(), bulk.captureState());
        assertEquals(scalarInterrupts.captureState(), bulkInterrupts.captureState());
    }

    @Test
    public void nativeCgbIdleEpochMatchesScalarNormalSpeedTicks() {
        InterruptManager scalarInterrupts = new InterruptManager(true);
        InterruptManager bulkInterrupts = new InterruptManager(true);
        SerialPort scalar = new SerialPort(
                scalarInterrupts, true, new SpeedMode(true));
        SerialPort bulk = new SerialPort(
                bulkInterrupts, true, new SpeedMode(true));
        for (int tick = 0; tick < 37; tick++) {
            scalar.tick();
            bulk.tick();
        }
        assertTrue(bulk.performanceNormalSpeedEpochIdle(54, true));
        for (int tick = 0; tick < 54; tick++) {
            scalar.tick();
        }
        bulk.tickPerformanceNormalSpeedEpochIdle(54);
        assertEquals(scalar.captureState(), bulk.captureState());
        assertEquals(scalarInterrupts.captureState(), bulkInterrupts.captureState());
    }

    @Test
    public void nativeCgbExternalClockTransferMatchesScalarNormalSpeedEpoch() {
        InterruptManager scalarInterrupts = new InterruptManager(true);
        InterruptManager bulkInterrupts = new InterruptManager(true);
        SerialPort scalar = new SerialPort(
                scalarInterrupts, true, new SpeedMode(true));
        SerialPort bulk = new SerialPort(
                bulkInterrupts, true, new SpeedMode(true));
        for (int tick = 0; tick < 37; tick++) {
            scalar.tick();
            bulk.tick();
        }
        scalar.setByte(0xff02, 0x80);
        bulk.setByte(0xff02, 0x80);

        assertEquals(3, bulk.performanceQuietSpanLimit(3));
        assertEquals(54, bulk.performanceSettledHaltSpanLimit(54));
        assertTrue(bulk.performanceNormalSpeedEpochIdle(54, true));
        assertFalse(bulk.performancePhysicalDmgEpochIdle(54));
        for (int tick = 0; tick < 54; tick++) {
            scalar.tick();
        }
        bulk.tickPerformanceNormalSpeedEpochIdle(54);

        assertEquals(scalar.captureState(), bulk.captureState());
        assertEquals(scalarInterrupts.captureState(), bulkInterrupts.captureState());
    }

    @Test
    public void disconnectedPeerExternalClockTransferMatchesScalarNormalSpeedEpoch() {
        InterruptManager scalarInterrupts = new InterruptManager(true);
        InterruptManager bulkInterrupts = new InterruptManager(true);
        SerialPort scalar = new SerialPort(
                scalarInterrupts, true, new SpeedMode(true));
        SerialPort bulk = new SerialPort(
                bulkInterrupts, true, new SpeedMode(true));
        scalar.init(new Peer2PeerSerialEndpoint());
        bulk.init(new Peer2PeerSerialEndpoint());
        scalar.setByte(0xff02, 0x80);
        bulk.setByte(0xff02, 0x80);

        assertTrue(bulk.performanceNormalSpeedEpochIdle(54, true));
        for (int tick = 0; tick < 54; tick++) {
            scalar.tick();
        }
        bulk.tickPerformanceNormalSpeedEpochIdle(54);

        assertEquals(scalar.captureState(), bulk.captureState());
        assertEquals(scalarInterrupts.captureState(), bulkInterrupts.captureState());
    }

    @Test
    public void nativeCgbExternalClockTransferMatchesScalarDoubleSpeedEpoch()
            throws ReflectiveOperationException {
        InterruptManager scalarInterrupts = new InterruptManager(true);
        InterruptManager bulkInterrupts = new InterruptManager(true);
        SerialPort scalar = new SerialPort(scalarInterrupts, true, doubleSpeed());
        SerialPort bulk = new SerialPort(bulkInterrupts, true, doubleSpeed());
        scalar.setByte(0xff02, 0x80);
        bulk.setByte(0xff02, 0x80);

        assertTrue(bulk.performanceEpochIdle(54));
        assertEquals(0, bulk.performanceQuietSpanLimit(3));
        assertEquals(0, bulk.performanceSettledHaltSpanLimit(54));
        for (int tick = 0; tick < 54; tick++) {
            scalar.tick();
        }
        bulk.tickPerformanceEpochIdle(54);

        assertEquals(scalar.captureState(), bulk.captureState());
        assertEquals(scalarInterrupts.captureState(), bulkInterrupts.captureState());
    }

    @Test
    public void nativeCgbIdleEpochMatchesScalarDoubleSpeedTicks()
            throws ReflectiveOperationException {
        Random random = new Random(0xc6b5e71a1L);
        for (int iteration = 0; iteration < 1_000; iteration++) {
            InterruptManager scalarInterrupts = new InterruptManager(true);
            InterruptManager bulkInterrupts = new InterruptManager(true);
            SerialPort scalar = new SerialPort(scalarInterrupts, true, doubleSpeed());
            SerialPort bulk = new SerialPort(bulkInterrupts, true, doubleSpeed());
            int phaseTicks = random.nextInt(0x100);
            for (int tick = 0; tick < phaseTicks; tick++) {
                scalar.tick();
                bulk.tick();
            }
            int sc = random.nextInt(0x80);
            scalar.setByte(0xff02, sc);
            bulk.setByte(0xff02, sc);
            int span = 1 + random.nextInt(54);

            assertTrue(bulk.performanceEpochIdle(span));
            assertFalse(bulk.performancePhysicalDmgEpochIdle(span));
            for (int tick = 0; tick < span; tick++) {
                scalar.tick();
            }
            bulk.tickPerformanceEpochIdle(span);

            assertEquals(scalar.captureState(), bulk.captureState());
            assertEquals(scalarInterrupts.captureState(), bulkInterrupts.captureState());
        }
    }

    @Test
    public void randomizedIdleNullEndpointSpansMatchScalarTicks() {
        Random random = new Random(0x5e71a1L);
        for (int i = 0; i < 1_000; i++) {
            InterruptManager scalarInterrupts = new InterruptManager(false);
            InterruptManager bulkInterrupts = new InterruptManager(false);
            SerialPort scalar = new SerialPort(scalarInterrupts, false, new SpeedMode(false));
            SerialPort bulk = new SerialPort(bulkInterrupts, false, new SpeedMode(false));
            int phaseTicks = random.nextInt(0x100);
            for (int tick = 0; tick < phaseTicks; tick++) {
                scalar.tick();
                bulk.tick();
            }
            int sc = random.nextInt(0x80);
            scalar.setByte(0xff02, sc);
            bulk.setByte(0xff02, sc);
            int span = 1 + random.nextInt(3);
            assertTrue(bulk.canTickPerformanceQuietSpan(span));
            for (int tick = 0; tick < span; tick++) {
                scalar.tick();
            }
            assertTrue(bulk.tickPerformanceQuietSpan(span));
            assertEquals(scalar.captureState(), bulk.captureState());
            assertEquals(scalarInterrupts.captureState(), bulkInterrupts.captureState());
        }
    }

    @Test
    public void pendingSerialAcknowledgeMatchesScalarBeginningOfTick() {
        InterruptManager scalarInterrupts = new InterruptManager(false);
        InterruptManager bulkInterrupts = new InterruptManager(false);
        SerialPort scalar = new SerialPort(scalarInterrupts, false, new SpeedMode(false));
        SerialPort bulk = new SerialPort(bulkInterrupts, false, new SpeedMode(false));
        scalarInterrupts.requestInterrupt(InterruptManager.InterruptType.Serial);
        scalarInterrupts.clearInterrupt(InterruptManager.InterruptType.Serial);
        bulkInterrupts.requestInterrupt(InterruptManager.InterruptType.Serial);
        bulkInterrupts.clearInterrupt(InterruptManager.InterruptType.Serial);

        scalar.tick();
        assertTrue(bulk.tickPerformanceQuietSpan(1));
        assertEquals(scalar.captureState(), bulk.captureState());
        assertEquals(scalarInterrupts.captureState(), bulkInterrupts.captureState());
    }

    @Test
    public void quietSpanFailsClosedForTransfersEndpointsAndDebugHooks() {
        SerialPort internalTransfer = new SerialPort(
                new InterruptManager(true), true, new SpeedMode(true));
        internalTransfer.setByte(0xff02, 0x81);
        var transferState = internalTransfer.captureState();
        assertFalse(internalTransfer.canTickPerformanceQuietSpan(1));
        assertFalse(internalTransfer.tickPerformanceQuietSpan(1));
        assertEquals(0, internalTransfer.performanceSettledHaltSpanLimit(54));
        assertFalse(internalTransfer.performanceNormalSpeedEpochIdle(54, true));
        assertEquals(transferState, internalTransfer.captureState());

        SerialPort endpoint = new SerialPort(
                new InterruptManager(true), true, new SpeedMode(true));
        endpoint.init(new NoopEndpoint());
        endpoint.setByte(0xff02, 0x80);
        assertEquals(0, endpoint.performanceQuietSpanLimit(1));
        assertEquals(0, endpoint.performanceSettledHaltSpanLimit(54));
        assertFalse(endpoint.performanceNormalSpeedEpochIdle(54, true));

        LegacyQuietEndpoint legacyEndpoint = new LegacyQuietEndpoint();
        SerialPort legacy = new SerialPort(
                new InterruptManager(true), true, new SpeedMode(true));
        legacy.init(legacyEndpoint);
        legacy.setByte(0xff02, 0x80);
        assertEquals(0, legacy.performanceQuietSpanLimit(1));
        assertEquals(0, legacy.performanceSettledHaltSpanLimit(54));
        assertFalse(legacy.performanceNormalSpeedEpochIdle(54, true));
        legacy.tick();
        assertTrue("legacy quiet endpoint did not observe the active external wait",
                legacyEndpoint.activeExternalWaitObserved);

        SerialPort debug = new SerialPort(
                new InterruptManager(false), false, new SpeedMode(false));
        debug.setDebugHooks(new TestDebugHooks());
        assertEquals(0, debug.performanceQuietSpanLimit(1));
    }

    @Test
    public void disconnectedPeerCableIsQuietButConnectedCableIsNot() {
        Peer2PeerSerialEndpoint disconnected = new Peer2PeerSerialEndpoint();
        SerialPort idle = new SerialPort(
                new InterruptManager(false), false, new SpeedMode(false));
        idle.init(disconnected);
        assertEquals(3, idle.performanceQuietSpanLimit(3));

        Peer2PeerSerialEndpoint first = new Peer2PeerSerialEndpoint();
        Peer2PeerSerialEndpoint second = new Peer2PeerSerialEndpoint();
        first.init(second);
        SerialPort connected = new SerialPort(
                new InterruptManager(false), false, new SpeedMode(false));
        connected.init(first);
        assertEquals(0, connected.performanceQuietSpanLimit(3));
        assertEquals(0, first.performanceQuietSpanLimit(3));
    }

    @Test
    public void disconnectedPeerScalarIdlePathMatchesNullEndpointAtBothSpeeds()
            throws ReflectiveOperationException {
        for (int speed = 1; speed <= 2; speed++) {
            SpeedMode nullSpeed = new SpeedMode(true);
            SpeedMode peerSpeed = new SpeedMode(true);
            if (speed == 2) {
                nullSpeed.setByte(0xff4d, 1);
                peerSpeed.setByte(0xff4d, 1);
                var onStop = SpeedMode.class.getDeclaredMethod("onStop");
                onStop.setAccessible(true);
                assertTrue((boolean) onStop.invoke(nullSpeed));
                assertTrue((boolean) onStop.invoke(peerSpeed));
            }
            InterruptManager nullInterrupts = new InterruptManager(true);
            InterruptManager peerInterrupts = new InterruptManager(true);
            SerialPort nullPort = new SerialPort(nullInterrupts, true, nullSpeed);
            SerialPort peerPort = new SerialPort(peerInterrupts, true, peerSpeed);
            peerPort.init(new Peer2PeerSerialEndpoint());

            for (int tick = 0; tick < 1_000; tick++) {
                nullPort.tick();
                peerPort.tick();
            }
            assertEquals(nullPort.captureState(), peerPort.captureState());
            assertEquals(nullInterrupts.captureState(), peerInterrupts.captureState());
        }
    }

    private static final class NoopEndpoint implements SerialEndpoint {
        @Override
        public void setSb(int sb) {
        }

        @Override
        public int recvBit() {
            return -1;
        }

        @Override
        public void startSending() {
        }

        @Override
        public int sendBit() {
            return 1;
        }

        @Override
        public eu.rekawek.coffeegb.core.state.ComponentState<SerialEndpoint> captureState() {
            return null;
        }

        @Override
        public void restoreState(
                eu.rekawek.coffeegb.core.state.ComponentState<SerialEndpoint> state) {
        }
    }

    /** Models an old quiet-capability implementer which observes active external waits. */
    private static final class LegacyQuietEndpoint implements SerialEndpoint {

        private boolean activeExternalWaitObserved;

        @Override
        public int performanceQuietSpanLimit(int requested) {
            return requested > 0 ? requested : 0;
        }

        @Override
        public void setExternalTransfer(boolean inProgress) {
            activeExternalWaitObserved |= inProgress;
        }

        @Override
        public void setSb(int sb) {
        }

        @Override
        public int recvBit() {
            return -1;
        }

        @Override
        public void startSending() {
        }

        @Override
        public int sendBit() {
            return 1;
        }

        @Override
        public eu.rekawek.coffeegb.core.state.ComponentState<SerialEndpoint> captureState() {
            return null;
        }

        @Override
        public void restoreState(
                eu.rekawek.coffeegb.core.state.ComponentState<SerialEndpoint> state) {
        }
    }

    private static SpeedMode doubleSpeed() throws ReflectiveOperationException {
        SpeedMode speed = new SpeedMode(true);
        speed.setByte(0xff4d, 1);
        var onStop = SpeedMode.class.getDeclaredMethod("onStop");
        onStop.setAccessible(true);
        assertTrue((boolean) onStop.invoke(speed));
        return speed;
    }
}
