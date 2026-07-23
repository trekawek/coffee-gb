package eu.rekawek.coffeegb.core.memory.cart.battery;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;
import eu.rekawek.coffeegb.core.memory.cart.battery.FileBattery.FileBatteryMemento;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public class MemoryBattery implements Battery, Originator<Battery> {

    private byte[] buffer;

    public MemoryBattery(byte[] buffer) {
        this.buffer = buffer.clone();
    }

    @Override
    public void loadRam(int[] ram) {
        ensureCapacity(ram.length);
        for (int i = 0; i < ram.length; i++) {
            ram[i] = buffer[i] & 0xff;
        }
    }

    @Override
    public void saveRam(int[] ram) {
        ensureCapacity(ram.length);
        for (int i = 0; i < ram.length; i++) {
            buffer[i] = (byte) (ram[i]);
        }
    }

    @Override
    public void loadRamWithClock(int[] ram, long[] clockData) {
        ensureCapacity(ram.length + clockSize(clockData));
        loadRam(ram);

        if (clockData != null) {
            ByteBuffer buff = ByteBuffer.wrap(buffer, ram.length, clockSize(clockData));
            buff.order(ByteOrder.LITTLE_ENDIAN);
            int i = 0;
            while (buff.hasRemaining()) {
                clockData[i++] = buff.getInt();
            }
        }
    }

    @Override
    public void saveRamWithClock(int[] ram, long[] clockData) {
        ensureCapacity(ram.length + clockSize(clockData));
        saveRam(ram);

        if (clockData != null) {
            ByteBuffer buff = ByteBuffer.wrap(buffer, ram.length, clockSize(clockData));
            buff.order(ByteOrder.LITTLE_ENDIAN);
            for (long d : clockData) {
                buff.putInt((int) d);
            }
        }
    }

    @Override
    public void flush() {
    }

    private void ensureCapacity(int capacity) {
        // Netplay mirrors the save file byte-for-byte, including an existing zero-length or
        // truncated file. Match FileBattery by treating bytes missing from that file as zeroes.
        if (buffer.length < capacity) {
            buffer = Arrays.copyOf(buffer, capacity);
        }
    }

    private static int clockSize(long[] clockData) {
        return clockData == null ? 0 : clockData.length * Integer.BYTES;
    }

    @Override
    public Memento<Battery> saveToMemento() {
        return new MemoryBatteryMemento(buffer.clone());
    }

    @Override
    public void restoreFromMemento(Memento<Battery> memento) {
        if (memento instanceof MemoryBatteryMemento mem) {
            if (this.buffer.length != mem.buffer.length) {
                throw new IllegalArgumentException("Memento buffer length doesn't match");
            }
            System.arraycopy(mem.buffer, 0, this.buffer, 0, this.buffer.length);
        } else if (memento instanceof FileBatteryMemento mem) {
            System.arraycopy(mem.ramBuffer(), 0, this.buffer, 0, mem.ramBuffer().length);
            if (mem.isClockPresent()) {
                System.arraycopy(mem.clockBuffer(), 0, this.buffer, mem.ramBuffer().length, mem.clockBuffer().length);
            }
        } else {
            throw new IllegalArgumentException("Invalid memento type");
        }
    }

    private record MemoryBatteryMemento(byte[] buffer) implements Memento<Battery> {
    }
}
