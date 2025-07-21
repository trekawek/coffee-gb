package eu.rekawek.coffeegb.memory.cart.battery;

import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MemoryBattery implements Battery, Originator<Battery> {

    private final byte[] buffer;

    private MemoryBattery(byte[] buffer) {
        this.buffer = buffer.clone();
    }

    @Override
    public void loadRam(int[] ram) {
        for (int i = 0; i < ram.length; i++) {
            ram[i] = buffer[i] & 0xff;
        }
    }

    @Override
    public void saveRam(int[] ram) {
        for (int i = 0; i < ram.length; i++) {
            buffer[i] = (byte) (ram[i]);
        }
    }

    @Override
    public void loadRamWithClock(int[] ram, long[] clockData) {
        loadRam(ram);

        ByteBuffer buff = ByteBuffer.wrap(buffer, ram.length, 4 * clockData.length);
        buff.order(ByteOrder.LITTLE_ENDIAN);
        int i = 0;
        while (buff.hasRemaining()) {
            clockData[i++] = buff.getInt();
        }
    }

    @Override
    public void saveRamWithClock(int[] ram, long[] clockData) {
        saveRam(ram);

        ByteBuffer buff = ByteBuffer.wrap(buffer, ram.length, 4 * clockData.length);
        buff.order(ByteOrder.LITTLE_ENDIAN);
        for (long d : clockData) {
            buff.putInt((int) d);
        }
    }

    @Override
    public void flush() {
    }

    @Override
    public Memento<Battery> saveToMemento() {
        return new MemoryBatteryMemento(buffer.clone());
    }

    @Override
    public void restoreFromMemento(Memento<Battery> memento) {
        if (!(memento instanceof MemoryBatteryMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        if (this.buffer.length != mem.buffer.length) {
            throw new IllegalArgumentException("Memento buffer length doesn't match");
        }
        System.arraycopy(mem.buffer, 0, this.buffer, 0, this.buffer.length);
    }

    private record MemoryBatteryMemento(byte[] buffer) implements Memento<Battery> {
    }
}
