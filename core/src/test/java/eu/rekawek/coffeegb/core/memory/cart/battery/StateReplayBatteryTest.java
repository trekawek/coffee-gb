package eu.rekawek.coffeegb.core.memory.cart.battery;

import eu.rekawek.coffeegb.core.state.ComponentState;
import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class StateReplayBatteryTest {

    @Test
    public void capturesTheExactFileBatteryStateAndRestoresRamRtcAndFlags() {
        StateReplayBattery source = new StateReplayBattery(4);
        int[] ram = {0x01, 0x82, 0xfe, 0xff, 0x77};
        long[] clock = new long[12];
        for (int i = 0; i < clock.length; i++) {
            clock[i] = 0x10203040L + i;
        }
        source.saveRamWithClock(ram, clock);

        ComponentState<Battery> captured = source.captureState();
        assertTrue(captured instanceof FileBattery.FileBatteryState);
        FileBattery.FileBatteryState fileState = (FileBattery.FileBatteryState) captured;
        assertTrue(fileState.isClockPresent());
        assertTrue(fileState.isDirty());
        assertArrayEquals(new byte[] {0x01, (byte) 0x82, (byte) 0xfe, (byte) 0xff},
                fileState.ramBuffer());

        StateReplayBattery restored = new StateReplayBattery(4);
        restored.restoreState(captured);
        int[] restoredRam = new int[6];
        long[] restoredClock = new long[12];
        Arrays.fill(restoredRam, 0xaa);
        Arrays.fill(restoredClock, -1);
        restored.loadRamWithClock(restoredRam, restoredClock);

        assertArrayEquals(new int[] {0x01, 0x82, 0xfe, 0xff, 0, 0}, restoredRam);
        assertArrayEquals(
                new long[] {
                    0x10203040L,
                    0x10203041L,
                    0x10203042L,
                    0x10203043L,
                    0x10203044L,
                    0x10203045L,
                    0x10203046L,
                    0x10203047L,
                    0x10203048L,
                    0x10203049L,
                    0x1020304aL,
                    0,
                },
                restoredClock);
    }

    @Test
    public void absentClockZeroFillsCallerAndFlushNeverChangesCapturedState() {
        StateReplayBattery battery = new StateReplayBattery(2);
        battery.saveRam(new int[] {7, 8});
        battery.flush();

        long[] clock = {1, 2, 3};
        battery.loadRamWithClock(new int[2], clock);
        FileBattery.FileBatteryState state =
                (FileBattery.FileBatteryState) battery.captureState();

        assertArrayEquals(new long[3], clock);
        assertFalse(state.isClockPresent());
        assertTrue(state.isDirty());
    }

    @Test
    public void rejectsAFileStateWithDifferentPayloadDimensions() {
        StateReplayBattery battery = new StateReplayBattery(4);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        battery.restoreState(
                                new FileBattery.FileBatteryState(
                                        new byte[11 * Integer.BYTES],
                                        new byte[3],
                                        false,
                                        false)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        battery.restoreState(
                                new FileBattery.FileBatteryState(
                                        new byte[1],
                                        new byte[4],
                                        false,
                                        false)));
    }
}
