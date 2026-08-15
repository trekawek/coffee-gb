package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

public class SerialPortReplayTest {

    @Test
    public void everyReachableActiveClockPhaseReplaysExactly() {
        assertEveryActivePhaseReplays(false, 0x81, 512);
        assertEveryActivePhaseReplays(true, 0x81, 512);
        assertEveryActivePhaseReplays(true, 0x83, 16);
    }

    private static void assertEveryActivePhaseReplays(boolean gbc, int sc, int period) {
        SpeedMode speedMode = new SpeedMode(gbc);
        InterruptManager interrupts = new InterruptManager(gbc);
        SerialPort serial = new SerialPort(interrupts, gbc, speedMode);
        serial.setByte(0xff01, 0x5a);
        serial.setByte(0xff02, sc);

        int elapsed = 0;
        while ((serial.getByte(0xff02) & 0x80) != 0) {
            ComponentState<SerialPort> serialState = serial.captureState();
            ComponentState<InterruptManager> interruptState = interrupts.captureState();
            long[] expected = trace(serial, interrupts, period + 8, false);

            interrupts.restoreState(interruptState);
            serial.restoreState(serialState);
            assertArrayEquals("ordinary continuation gbc=" + gbc + " sc=" + sc
                            + " elapsed=" + elapsed,
                    expected, trace(serial, interrupts, period + 8, false));

            interrupts.restoreState(interruptState);
            serial.restoreState(serialState);
            long[] resetExpected = trace(serial, interrupts, 20, true);
            interrupts.restoreState(interruptState);
            serial.restoreState(serialState);
            assertArrayEquals("DIV-reset continuation gbc=" + gbc + " sc=" + sc
                            + " elapsed=" + elapsed,
                    resetExpected, trace(serial, interrupts, 20, true));

            interrupts.restoreState(interruptState);
            serial.restoreState(serialState);
            serial.tick();
            elapsed++;
        }
        assertTrue("transfer completed before covering a full byte", elapsed >= period * 7);
    }

    private static long[] trace(
            SerialPort serial, InterruptManager interrupts, int ticks, boolean resetFirst) {
        if (resetFirst) {
            serial.onDivReset();
        }
        long[] trace = new long[ticks + 1];
        for (int i = 0; i <= ticks; i++) {
            var state = serial.captureDebugSerialInspection();
            trace[i] = state.sb()
                    | (long) state.sc() << 8
                    | (long) state.receivedBits() << 16
                    | (long) state.clockPhase() << 20
                    | (state.clockSignal() ? 1L : 0L) << 28
                    | (long) state.haltWakeDelay() << 29
                    | (long) interrupts.getByte(0xff0f) << 33
                    | (interrupts.isInterruptRequestedForHalt() ? 1L : 0L) << 41;
            if (i < ticks) {
                serial.tick();
            }
        }
        return trace;
    }
}
