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
        SerialPort transfer = new SerialPort(
                new InterruptManager(false), false, new SpeedMode(false));
        transfer.setByte(0xff02, 0x81);
        var transferState = transfer.captureState();
        assertFalse(transfer.canTickPerformanceQuietSpan(1));
        assertFalse(transfer.tickPerformanceQuietSpan(1));
        assertEquals(transferState, transfer.captureState());

        SerialPort endpoint = new SerialPort(
                new InterruptManager(false), false, new SpeedMode(false));
        endpoint.init(new NoopEndpoint());
        assertEquals(0, endpoint.performanceQuietSpanLimit(1));

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
}
