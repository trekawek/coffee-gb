package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.memento.Memento;
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

        Memento<InterruptManager> interruptMemento = interruptManager.saveToMemento();
        Memento<SerialPort> serialMemento = serialPort.saveToMemento();
        for (int i = 0; i < 4; i++) {
            serialPort.tick();
        }
        assertTrue(interruptManager.isInterruptRequestedForHalt());

        interruptManager.restoreFromMemento(interruptMemento);
        serialPort.restoreFromMemento(serialMemento);
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
        public Memento<SerialEndpoint> saveToMemento() {
            return null;
        }

        @Override
        public void restoreFromMemento(Memento<SerialEndpoint> memento) {
        }
    }
}
