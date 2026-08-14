package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SerialPortTest {

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
