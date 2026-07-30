package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.DebugInterruptType;
import eu.rekawek.coffeegb.core.debug.trace.SerialIrTrace;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class SerialPortDebugHooksTest {

    @Test
    public void finalShiftAndBytePrecedeTheInterruptWithFinalStateVisible() {
        SpeedMode speedMode = new SpeedMode(true);
        InterruptManager interrupts = new InterruptManager(true);
        SerialPort serial = new SerialPort(interrupts, true, speedMode);
        serial.init(new BitEndpoint(0xa5));
        serial.setByte(0xff01, 0x3c);
        RecordingHooks hooks = new RecordingHooks(serial);
        serial.setDebugHooks(hooks);
        interrupts.setDebugHooks(hooks);

        serial.setByte(0xff02, 0x83);
        for (int i = 0; i < 1024 && !hooks.events.contains("IRQ:SERIAL"); i++) {
            serial.tick();
        }

        assertEquals("TRANSFER_STARTED:3C", hooks.events.get(0));
        assertEquals(8, hooks.events.stream().filter(e -> e.startsWith("BIT_SHIFTED:")).count());
        assertEquals(List.of(
                "BIT_SHIFTED:A5",
                "BYTE_TRANSFERRED:A5:SB=A5:RUNNING=false",
                "IRQ:SERIAL"), hooks.events.subList(hooks.events.size() - 3, hooks.events.size()));
    }

    @Test
    public void attachmentAndRestoreDoNotInventTransferEvents() {
        SerialPort serial = new SerialPort(
                new InterruptManager(false), false, new SpeedMode(false));
        serial.setByte(0xff01, 0x66);
        ComponentState<SerialPort> state = serial.captureState();
        RecordingHooks hooks = new RecordingHooks(serial);

        serial.setDebugHooks(hooks);
        serial.restoreState(state);

        assertEquals(List.of(), hooks.events);
    }

    private static final class RecordingHooks implements DebugHooks {

        private final SerialPort serial;

        private final List<String> events = new ArrayList<>();

        private RecordingHooks(SerialPort serial) {
            this.serial = serial;
        }

        @Override
        public void onSerialIrEvent(
                SerialIrTrace.Endpoint endpoint, SerialIrTrace.Kind kind, int value) {
            String event = kind + ":" + String.format("%02X", value);
            if (kind == SerialIrTrace.Kind.BYTE_TRANSFERRED) {
                event += ":SB=" + String.format("%02X", serial.getByte(0xff01))
                        + ":RUNNING=" + ((serial.getByte(0xff02) & 0x80) != 0);
            }
            events.add(event);
        }

        @Override
        public void onInterruptRequested(DebugInterruptType interrupt) {
            events.add("IRQ:" + interrupt);
        }

        @Override
        public void onInstructionFetch(int programCounter) {
        }

        @Override
        public void onOpcodeFetched(int programCounter, boolean cbPrefixed, int opcode) {
        }

        @Override
        public void onInstructionRetired(
                boolean instructionKnown, int programCounter, int opcode, int prefixedOpcode) {
        }

        @Override
        public void onInterruptAccepted(DebugInterruptType interrupt) {
        }
    }

    private static final class BitEndpoint implements SerialEndpoint {

        private final int value;

        private int bit;

        private BitEndpoint(int value) {
            this.value = value;
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
            bit = 0;
        }

        @Override
        public int sendBit() {
            return (value >>> (7 - bit++)) & 1;
        }

        @Override
        public ComponentState<SerialEndpoint> captureState() {
            return null;
        }

        @Override
        public void restoreState(ComponentState<SerialEndpoint> state) {
        }
    }
}
