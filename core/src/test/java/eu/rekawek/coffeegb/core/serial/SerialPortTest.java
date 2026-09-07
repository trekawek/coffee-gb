package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SerialPortTest {

    @Test
    public void transferRoleQueriesUseRawScControlBits() {
        SerialPort serialPort = new SerialPort(
                new InterruptManager(false), false, new SpeedMode(false));

        assertFalse(serialPort.isInternalClockTransferActive());
        assertFalse(serialPort.isExternalClockTransferActive());

        serialPort.setByte(0xff02, 0x81);
        assertTrue(serialPort.isInternalClockTransferActive());
        assertFalse(serialPort.isExternalClockTransferActive());

        serialPort.setByte(0xff02, 0x80);
        assertFalse(serialPort.isInternalClockTransferActive());
        assertTrue(serialPort.isExternalClockTransferActive());

        serialPort.setByte(0xff02, 0x01);
        assertFalse(serialPort.isInternalClockTransferActive());
        assertFalse(serialPort.isExternalClockTransferActive());
    }

    @Test
    public void peerClockShiftsAnExternalPortSynchronously() {
        InterruptManager firstInterrupts = new InterruptManager(true);
        InterruptManager secondInterrupts = new InterruptManager(true);
        SerialPort firstPort = new SerialPort(firstInterrupts, true, new SpeedMode(true));
        SerialPort secondPort = new SerialPort(secondInterrupts, true, new SpeedMode(true));
        Peer2PeerSerialEndpoint firstEndpoint = new Peer2PeerSerialEndpoint();
        Peer2PeerSerialEndpoint secondEndpoint = new Peer2PeerSerialEndpoint();
        firstEndpoint.init(secondEndpoint);
        firstPort.init(firstEndpoint);
        secondPort.init(secondEndpoint);
        firstPort.setByte(0xff01, 0xa5);
        secondPort.setByte(0xff01, 0x3c);
        secondPort.setByte(0xff02, 0x82);
        firstPort.setByte(0xff02, 0x83);

        int remainingTicks = 1024;
        while (firstPort.isInternalClockTransferActive() && remainingTicks-- > 0) {
            firstPort.tick();
        }

        assertTrue("serial transfer did not complete", remainingTicks > 0);
        assertEquals(0x3c, firstPort.getByte(0xff01));
        assertEquals(0xa5, secondPort.getByte(0xff01));
        assertFalse(secondPort.isExternalClockTransferActive());
        assertTrue(firstInterrupts.isInterruptFlagSet(InterruptManager.InterruptType.Serial));
        assertTrue(secondInterrupts.isInterruptFlagSet(InterruptManager.InterruptType.Serial));
    }

    @Test
    public void peerCableUsesTheLiveShiftRegisterOnAConsecutiveTransfer() {
        SerialPort firstPort = new SerialPort(
                new InterruptManager(true), true, new SpeedMode(true));
        SerialPort secondPort = new SerialPort(
                new InterruptManager(true), true, new SpeedMode(true));
        Peer2PeerSerialEndpoint firstEndpoint = new Peer2PeerSerialEndpoint();
        Peer2PeerSerialEndpoint secondEndpoint = new Peer2PeerSerialEndpoint();
        firstEndpoint.init(secondEndpoint);
        firstPort.init(firstEndpoint);
        secondPort.init(secondEndpoint);
        firstPort.setByte(0xff01, 0xa5);
        secondPort.setByte(0xff01, 0x3c);
        secondPort.setByte(0xff02, 0x82);
        firstPort.setByte(0xff02, 0x83);

        tickUntilComplete(firstPort);
        assertEquals(0x3c, firstPort.getByte(0xff01));
        assertEquals(0xa5, secondPort.getByte(0xff01));

        // Reverse the clock roles without rewriting SB. Each peer must transmit the byte that
        // actually occupies its shift register after the previous transfer.
        firstPort.setByte(0xff02, 0x82);
        secondPort.setByte(0xff02, 0x83);
        tickUntilComplete(secondPort);

        assertEquals(0xa5, firstPort.getByte(0xff01));
        assertEquals(0x3c, secondPort.getByte(0xff01));
    }

    @Test
    public void peerExchangeContinuesAfterBothPortsRestoreMidByte() {
        SerialPort firstPort = new SerialPort(
                new InterruptManager(true), true, new SpeedMode(true));
        SerialPort secondPort = new SerialPort(
                new InterruptManager(true), true, new SpeedMode(true));
        Peer2PeerSerialEndpoint firstEndpoint = new Peer2PeerSerialEndpoint();
        Peer2PeerSerialEndpoint secondEndpoint = new Peer2PeerSerialEndpoint();
        firstEndpoint.init(secondEndpoint);
        firstPort.init(firstEndpoint);
        secondPort.init(secondEndpoint);
        firstPort.setByte(0xff01, 0xa5);
        secondPort.setByte(0xff01, 0x3c);
        secondPort.setByte(0xff02, 0x82);
        firstPort.setByte(0xff02, 0x83);
        for (int i = 0; i < 52; i++) {
            firstPort.tick();
        }
        assertEquals(3, firstPort.captureDebugSerialInspection().receivedBits());
        assertEquals(3, secondPort.captureDebugSerialInspection().receivedBits());
        ComponentState<SerialPort> firstPortState = firstPort.captureState();
        ComponentState<SerialPort> secondPortState = secondPort.captureState();
        ComponentState<SerialEndpoint> firstEndpointState = firstEndpoint.captureState();
        ComponentState<SerialEndpoint> secondEndpointState = secondEndpoint.captureState();

        tickUntilComplete(firstPort);
        int expectedFirst = firstPort.getByte(0xff01);
        int expectedSecond = secondPort.getByte(0xff01);

        // Controller state restore applies both machines before their endpoint payloads.
        firstPort.restoreState(firstPortState);
        secondPort.restoreState(secondPortState);
        firstEndpoint.restoreState(firstEndpointState);
        secondEndpoint.restoreState(secondEndpointState);
        tickUntilComplete(firstPort);

        assertEquals(expectedFirst, firstPort.getByte(0xff01));
        assertEquals(expectedSecond, secondPort.getByte(0xff01));
        assertEquals(0x3c, expectedFirst);
        assertEquals(0xa5, expectedSecond);
    }

    @Test
    public void replacingAnEndpointDisconnectsTheOldCableCallback() {
        SerialPort firstPort = new SerialPort(
                new InterruptManager(true), true, new SpeedMode(true));
        SerialPort secondPort = new SerialPort(
                new InterruptManager(true), true, new SpeedMode(true));
        Peer2PeerSerialEndpoint firstEndpoint = new Peer2PeerSerialEndpoint();
        Peer2PeerSerialEndpoint secondEndpoint = new Peer2PeerSerialEndpoint();
        firstEndpoint.init(secondEndpoint);
        firstPort.init(firstEndpoint);
        secondPort.init(secondEndpoint);
        firstPort.setByte(0xff01, 0x55);
        firstPort.setByte(0xff02, 0x82);

        firstPort.init(SerialEndpoint.NULL_ENDPOINT);
        secondPort.setByte(0xff01, 0x80);
        secondPort.setByte(0xff02, 0x83);
        for (int i = 0; i < 20; i++) {
            secondPort.tick();
        }

        assertEquals(0x55, firstPort.getByte(0xff01));
        assertEquals(0, firstPort.captureDebugSerialInspection().receivedBits());
    }

    @Test
    public void dormantPeerSuppliesZeroWithoutReceivingOrReplayingAClock() {
        SerialPort firstPort = new SerialPort(
                new InterruptManager(true), true, new SpeedMode(true));
        SerialPort secondPort = new SerialPort(
                new InterruptManager(true), true, new SpeedMode(true));
        Peer2PeerSerialEndpoint firstEndpoint = new Peer2PeerSerialEndpoint();
        Peer2PeerSerialEndpoint secondEndpoint = new Peer2PeerSerialEndpoint();
        firstEndpoint.init(secondEndpoint);
        firstPort.init(firstEndpoint);
        secondPort.init(secondEndpoint);
        firstPort.setByte(0xff01, 0xa5);
        secondPort.setByte(0xff01, 0xff);
        firstPort.setByte(0xff02, 0x83);

        int remainingTicks = 1024;
        while (firstPort.isInternalClockTransferActive() && remainingTicks-- > 0) {
            firstPort.tick();
        }
        secondPort.setByte(0xff02, 0x82);
        for (int i = 0; i < 32; i++) {
            secondPort.tick();
        }

        assertEquals(0x00, firstPort.getByte(0xff01));
        assertEquals(0xff, secondPort.getByte(0xff01));
        assertTrue(secondPort.isExternalClockTransferActive());
        assertEquals(0, secondPort.captureDebugSerialInspection().receivedBits());
    }

    @Test
    public void internallyClockedPeerSuppliesOneWithoutReceivingTheOtherClock() {
        SerialPort firstPort = new SerialPort(
                new InterruptManager(true), true, new SpeedMode(true));
        SerialPort secondPort = new SerialPort(
                new InterruptManager(true), true, new SpeedMode(true));
        Peer2PeerSerialEndpoint firstEndpoint = new Peer2PeerSerialEndpoint();
        Peer2PeerSerialEndpoint secondEndpoint = new Peer2PeerSerialEndpoint();
        firstEndpoint.init(secondEndpoint);
        firstPort.init(firstEndpoint);
        secondPort.init(secondEndpoint);
        firstPort.setByte(0xff01, 0x00);
        secondPort.setByte(0xff01, 0x00);
        firstPort.setByte(0xff02, 0x83);
        secondPort.setByte(0xff02, 0x83);

        for (int i = 0; i < 20; i++) {
            firstPort.tick();
        }

        assertEquals(0x01, firstPort.getByte(0xff01));
        assertEquals(1, firstPort.captureDebugSerialInspection().receivedBits());
        assertEquals(0x00, secondPort.getByte(0xff01));
        assertEquals(0, secondPort.captureDebugSerialInspection().receivedBits());
    }

    @Test
    public void restoringAnExternalTransferPreservesThePeerCableRole() {
        SerialPort firstPort = new SerialPort(
                new InterruptManager(true), true, new SpeedMode(true));
        SerialPort secondPort = new SerialPort(
                new InterruptManager(true), true, new SpeedMode(true));
        Peer2PeerSerialEndpoint firstEndpoint = new Peer2PeerSerialEndpoint();
        Peer2PeerSerialEndpoint secondEndpoint = new Peer2PeerSerialEndpoint();
        firstEndpoint.init(secondEndpoint);
        firstPort.init(firstEndpoint);
        secondPort.init(secondEndpoint);
        secondPort.setByte(0xff01, 0x00);
        secondPort.setByte(0xff02, 0x82);
        ComponentState<SerialPort> externalState = secondPort.captureState();
        secondPort.setByte(0xff02, 0x02);
        secondPort.restoreState(externalState);
        firstPort.setByte(0xff01, 0x80);
        firstPort.setByte(0xff02, 0x83);

        for (int i = 0; i < 20; i++) {
            firstPort.tick();
        }

        assertEquals(1, secondPort.captureDebugSerialInspection().receivedBits());
        assertEquals(0x01, secondPort.getByte(0xff01));
    }

    @Test
    public void nonPeerEndpointReceivesOnlyTheCpuWrittenByte() {
        AtomicInteger received = new AtomicInteger(-1);
        SerialPort serialPort = new SerialPort(
                new InterruptManager(true), true, new SpeedMode(true));
        serialPort.init(new ByteReceivingSerialEndpoint(received::set));
        serialPort.setByte(0xff01, 0xa5);
        serialPort.setByte(0xff02, 0x83);

        tickUntilComplete(serialPort);

        assertEquals(0xa5, received.get());
        assertEquals(0xff, serialPort.getByte(0xff01));
    }

    private static void tickUntilComplete(SerialPort clockMaster) {
        int remainingTicks = 1024;
        while (clockMaster.isInternalClockTransferActive() && remainingTicks-- > 0) {
            clockMaster.tick();
        }
        assertTrue("serial transfer did not complete", remainingTicks > 0);
    }

    @Test
    public void dmgCompatibilityUsesDmgScReadMask() {
        SpeedMode speedMode = new SpeedMode(true);
        InterruptManager interruptManager = new InterruptManager(true);
        SerialPort serialPort = new SerialPort(interruptManager, true, speedMode);
        serialPort.setByte(0xff02, 0x00);

        assertEquals(0x7c, serialPort.getByte(0xff02));

        speedMode.setDmgCompat(true);

        assertEquals(0x7e, serialPort.getByte(0xff02));
    }

    @Test
    public void dmgCompatibilityIgnoresCgbFastClockSelect() {
        assertEquals(1, clockFastSerialEdge(false));
        assertEquals(0, clockFastSerialEdge(true));
    }

    @Test
    public void fastClockSelectorQueryRequiresAnActiveNativeCgbTransfer() {
        SpeedMode speedMode = new SpeedMode(true);
        SerialPort cgb = new SerialPort(new InterruptManager(true), true, speedMode);

        cgb.setByte(0xff02, 0x02);
        assertFalse(cgb.isFastClockSelectedForActiveTransfer());
        cgb.setByte(0xff02, 0x80);
        assertFalse(cgb.isFastClockSelectedForActiveTransfer());
        cgb.setByte(0xff02, 0x82);
        assertTrue(cgb.isFastClockSelectedForActiveTransfer());

        speedMode.setDmgCompat(true);
        assertFalse(cgb.isFastClockSelectedForActiveTransfer());

        SerialPort dmg = new SerialPort(
                new InterruptManager(false), false, new SpeedMode(false));
        dmg.setByte(0xff02, 0x82);
        assertFalse(dmg.isFastClockSelectedForActiveTransfer());
    }

    @Test
    public void switchingFromExternalClockDoesNotReplayAnOldDividerEdge() {
        SpeedMode speedMode = new SpeedMode(false);
        InterruptManager interruptManager = new InterruptManager(false);
        SerialPort serialPort = new SerialPort(interruptManager, false, speedMode);
        CountingEndpoint endpoint = new CountingEndpoint();
        serialPort.init(endpoint);

        serialPort.setByte(0xff02, 0x81);
        serialPort.tick();

        serialPort.setByte(0xff02, 0x80);
        serialPort.tick();

        serialPort.setByte(0xff02, 0x81);
        serialPort.tick();

        assertEquals(0, endpoint.sentBits);
    }

    @Test
    public void divResetRephasesIdleInternalClock() {
        SpeedMode speedMode = new SpeedMode(false);
        InterruptManager interruptManager = new InterruptManager(false);
        SerialPort serialPort = new SerialPort(interruptManager, false, speedMode);
        CountingEndpoint endpoint = new CountingEndpoint();
        serialPort.init(endpoint);

        for (int i = 0; i < 37; i++) {
            serialPort.tick();
        }
        serialPort.onDivReset();
        serialPort.setByte(0xff02, 0x81);

        for (int i = 0; i < 511; i++) {
            serialPort.tick();
        }
        assertEquals(0, endpoint.sentBits);
        serialPort.tick();
        assertEquals(1, endpoint.sentBits);
    }

    @Test
    public void divResetCanSupplyImmediateFastSerialFallingEdge() {
        SpeedMode speedMode = new SpeedMode(true);
        InterruptManager interruptManager = new InterruptManager(true);
        SerialPort serialPort = new SerialPort(interruptManager, true, speedMode);
        CountingEndpoint endpoint = new CountingEndpoint();
        serialPort.init(endpoint);
        serialPort.setByte(0xff02, 0x83);

        for (int i = 0; i < 13; i++) {
            serialPort.tick();
        }
        serialPort.onDivReset();

        assertEquals(1, endpoint.sentBits);
    }

    @Test
    public void divResetCanDeferFastSerialFallingEdgeByHalfPeriod() {
        SpeedMode speedMode = new SpeedMode(true);
        InterruptManager interruptManager = new InterruptManager(true);
        SerialPort serialPort = new SerialPort(interruptManager, true, speedMode);
        CountingEndpoint endpoint = new CountingEndpoint();
        serialPort.init(endpoint);
        serialPort.setByte(0xff02, 0x83);

        for (int i = 0; i < 11; i++) {
            serialPort.tick();
        }
        serialPort.onDivReset();

        for (int i = 0; i < 7; i++) {
            serialPort.tick();
        }
        assertEquals(0, endpoint.sentBits);
        serialPort.tick();
        assertEquals(1, endpoint.sentBits);
    }

    @Test
    public void divResetIsASelectedRippleTapTransitionAtEveryFastPhase() {
        assertDivResetRippleRule(true, 0x83, 16, 4);
    }

    @Test
    public void divResetIsASelectedRippleTapTransitionAtEveryDmgPhase() {
        assertDivResetRippleRule(false, 0x81, 512, 128);
    }

    @Test
    public void cgbInterruptAcknowledgeClearsSerialCompletionEightClocksAhead() {
        SpeedMode speedMode = new SpeedMode(true);
        InterruptManager interruptManager = new InterruptManager(true);
        SerialPort serialPort = new SerialPort(interruptManager, true, speedMode);
        CountingEndpoint endpoint = new CountingEndpoint();
        serialPort.init(endpoint);
        interruptManager.setByte(0xff0f, 0);
        serialPort.setByte(0xff02, 0x83);

        for (int i = 0; i < 120; i++) {
            serialPort.tick();
        }
        assertEquals(7, endpoint.sentBits);
        interruptManager.requestInterrupt(InterruptManager.InterruptType.Serial);
        interruptManager.clearInterrupt(InterruptManager.InterruptType.Serial);

        serialPort.tick();

        assertEquals(8, endpoint.sentBits);
        assertEquals(0, serialPort.getByte(0xff02) & 0x80);
        assertFalse(interruptManager.isInterruptFlagSet(InterruptManager.InterruptType.Serial));
    }

    @Test
    public void cgbInterruptAcknowledgeDoesNotClearSerialCompletionNineClocksAhead() {
        SpeedMode speedMode = new SpeedMode(true);
        InterruptManager interruptManager = new InterruptManager(true);
        SerialPort serialPort = new SerialPort(interruptManager, true, speedMode);
        CountingEndpoint endpoint = new CountingEndpoint();
        serialPort.init(endpoint);
        interruptManager.setByte(0xff0f, 0);
        serialPort.setByte(0xff02, 0x83);

        for (int i = 0; i < 119; i++) {
            serialPort.tick();
        }
        assertEquals(7, endpoint.sentBits);
        interruptManager.requestInterrupt(InterruptManager.InterruptType.Serial);
        interruptManager.clearInterrupt(InterruptManager.InterruptType.Serial);

        for (int i = 0; i < 8; i++) {
            serialPort.tick();
        }
        assertEquals(7, endpoint.sentBits);
        assertFalse(interruptManager.isInterruptFlagSet(InterruptManager.InterruptType.Serial));
        serialPort.tick();

        assertEquals(8, endpoint.sentBits);
        assertTrue(interruptManager.isInterruptFlagSet(InterruptManager.InterruptType.Serial));
    }

    @Test
    public void eighthBitReachesRunningCpuBeforeHaltWakeInput() {
        SpeedMode speedMode = new SpeedMode(false);
        InterruptManager interruptManager = new InterruptManager(false);
        SerialPort serialPort = new SerialPort(interruptManager, false, speedMode);
        serialPort.init(SerialEndpoint.NULL_ENDPOINT);
        interruptManager.setByte(0xff0f, 0);
        serialPort.setByte(0xff02, 0x81);

        interruptManager.setByte(0xffff, 1 << InterruptManager.InterruptType.Serial.ordinal());
        int remainingTicks = 5000;
        while (!interruptManager.isInterruptFlagSet(InterruptManager.InterruptType.Serial)
                && remainingTicks-- > 0) {
            serialPort.tick();
        }

        assertTrue("serial transfer did not complete", remainingTicks > 0);
        assertTrue(interruptManager.isInterruptRequested());
        assertFalse(interruptManager.isInterruptRequestedForHalt());

        ComponentState<InterruptManager> interruptMemento = interruptManager.captureState();
        ComponentState<SerialPort> serialMemento = serialPort.captureState();
        for (int i = 0; i < 4; i++) {
            serialPort.tick();
        }
        assertTrue(interruptManager.isInterruptRequestedForHalt());

        interruptManager.restoreState(interruptMemento);
        serialPort.restoreState(serialMemento);
        for (int i = 0; i < 3; i++) {
            serialPort.tick();
            assertFalse(interruptManager.isInterruptRequestedForHalt());
        }
        serialPort.tick();
        assertTrue(interruptManager.isInterruptRequestedForHalt());
    }

    private static int clockFastSerialEdge(boolean dmgCompat) {
        SpeedMode speedMode = new SpeedMode(true);
        speedMode.setDmgCompat(dmgCompat);
        InterruptManager interruptManager = new InterruptManager(true);
        SerialPort serialPort = new SerialPort(interruptManager, true, speedMode);
        CountingEndpoint endpoint = new CountingEndpoint();
        serialPort.init(endpoint);
        serialPort.setByte(0xff02, 0x83);

        for (int i = 0; i < 20; i++) {
            serialPort.tick();
        }

        return endpoint.sentBits;
    }

    private static void assertDivResetRippleRule(
            boolean gbc, int sc, int elapsedClockCount, int precedingTapMask) {
        for (int elapsed = 0; elapsed < elapsedClockCount; elapsed++) {
            SpeedMode speedMode = new SpeedMode(gbc);
            InterruptManager interruptManager = new InterruptManager(gbc);
            SerialPort serialPort = new SerialPort(interruptManager, gbc, speedMode);
            CountingEndpoint endpoint = new CountingEndpoint();
            serialPort.init(endpoint);
            serialPort.setByte(0xff02, sc);
            for (int i = 0; i < elapsed; i++) {
                serialPort.tick();
            }

            var before = serialPort.captureDebugSerialInspection();
            int sentBitsBeforeReset = endpoint.sentBits;
            boolean tapHigh = (before.clockPhase() & precedingTapMask) != 0;
            boolean expectedClock = before.clockSignal() ^ tapHigh;
            int expectedShiftCount = tapHigh && before.clockSignal() ? 1 : 0;

            serialPort.onDivReset();

            var after = serialPort.captureDebugSerialInspection();
            assertEquals("divider phase at elapsed=" + elapsed, 0, after.clockPhase());
            assertEquals("clock signal at elapsed=" + elapsed,
                    expectedClock, after.clockSignal());
            assertEquals("shift count at elapsed=" + elapsed,
                    sentBitsBeforeReset + expectedShiftCount, endpoint.sentBits);
        }
    }

    private static class CountingEndpoint implements SerialEndpoint {

        private int sentBits;

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
            sentBits++;
            return 1;
        }

        @Override
        public ComponentState<SerialEndpoint> captureState() {
            return null;
        }

        @Override
        public void restoreState(ComponentState<SerialEndpoint> memento) {
        }
    }
}
