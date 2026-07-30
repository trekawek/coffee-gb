package eu.rekawek.coffeegb.core.memory.cart.battery;

import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Service-free battery used by isolated state replay.
 *
 * <p>It deliberately captures {@link FileBattery.FileBatteryState}, rather than introducing a
 * replay-only state record, so a scratch machine has the same state ownership shape as the live
 * file-backed machine whose checkpoint it restores. No method in this implementation consults or
 * mutates the host filesystem.
 */
public final class StateReplayBattery implements Battery {

    private static final int CLOCK_WORDS = 11;

    private final byte[] clockBuffer = new byte[CLOCK_WORDS * Integer.BYTES];

    private final byte[] ramBuffer;

    private boolean isClockPresent;

    private boolean isDirty;

    public StateReplayBattery(int ramSize) {
        if (ramSize < 0) {
            throw new IllegalArgumentException("RAM size must not be negative");
        }
        this.ramBuffer = new byte[ramSize];
    }

    @Override
    public synchronized void loadRam(int[] ram) {
        loadRamWithClock(ram, null);
    }

    @Override
    public synchronized void saveRam(int[] ram) {
        saveRamWithClock(ram, null);
    }

    @Override
    public synchronized void loadRamWithClock(int[] ram, long[] clockData) {
        Arrays.fill(ram, 0);
        int ramBytes = Math.min(ram.length, ramBuffer.length);
        for (int i = 0; i < ramBytes; i++) {
            ram[i] = ramBuffer[i] & 0xff;
        }

        if (clockData == null) {
            return;
        }
        Arrays.fill(clockData, 0);
        if (!isClockPresent) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(clockBuffer).order(ByteOrder.LITTLE_ENDIAN);
        int words = Math.min(clockData.length, CLOCK_WORDS);
        for (int i = 0; i < words; i++) {
            clockData[i] = buffer.getInt();
        }
    }

    @Override
    public synchronized void saveRamWithClock(int[] ram, long[] clockData) {
        int ramBytes = Math.min(ram.length, ramBuffer.length);
        for (int i = 0; i < ramBytes; i++) {
            ramBuffer[i] = (byte) ram[i];
        }
        if (clockData != null) {
            ByteBuffer buffer = ByteBuffer.wrap(clockBuffer).order(ByteOrder.LITTLE_ENDIAN);
            int words = Math.min(clockData.length, CLOCK_WORDS);
            for (int i = 0; i < words; i++) {
                buffer.putInt((int) clockData[i]);
            }
            isClockPresent = true;
        }
        isDirty = true;
    }

    @Override
    public void flush() {
        // Scratch replay must never persist speculative battery contents.
    }

    @Override
    public synchronized DebugHistoryReplayShape debugHistoryReplayShape() {
        return new DebugHistoryReplayShape(DebugHistoryReplayKind.FILE_STATE, ramBuffer.length);
    }

    @Override
    public synchronized ComponentState<Battery> captureState() {
        return new FileBattery.FileBatteryState(
                clockBuffer.clone(), ramBuffer.clone(), isClockPresent, isDirty);
    }

    @Override
    public synchronized ComponentState<Battery> captureState(MachineStateCapture capture) {
        return new FileBattery.FileBatteryState(
                capture.bytes(clockBuffer),
                capture.bytes(ramBuffer),
                isClockPresent,
                isDirty);
    }

    @Override
    public synchronized void declareMachineStatePayloads(MachineStateCapture capture) {
        capture.declareBytes(clockBuffer);
        capture.declareBytes(ramBuffer);
    }

    @Override
    public synchronized void restoreState(ComponentState<Battery> state) {
        if (!(state instanceof FileBattery.FileBatteryState fileState)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        if (clockBuffer.length != fileState.clockBuffer().length) {
            throw new IllegalArgumentException("ComponentState clockBuffer length doesn't match");
        }
        if (ramBuffer.length != fileState.ramBuffer().length) {
            throw new IllegalArgumentException("ComponentState ramBuffer length doesn't match");
        }
        System.arraycopy(fileState.clockBuffer(), 0, clockBuffer, 0, clockBuffer.length);
        System.arraycopy(fileState.ramBuffer(), 0, ramBuffer, 0, ramBuffer.length);
        isClockPresent = fileState.isClockPresent();
        isDirty = fileState.isDirty();
    }
}
