package eu.rekawek.coffeegb.memory;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;

import java.io.Serializable;

public class Ram implements AddressSpace, Serializable, Originator<Ram> {

    private final int[] space;

    private final int length;

    private final int offset;

    public Ram(int offset, int length) {
        this.space = new int[length];
        this.length = length;
        this.offset = offset;
    }

    @Override
    public boolean accepts(int address) {
        return address >= offset && address < offset + length;
    }

    @Override
    public void setByte(int address, int value) {
        space[address - offset] = value;
    }

    @Override
    public int getByte(int address) {
        int index = address - offset;
        if (index < 0 || index >= space.length) {
            throw new IndexOutOfBoundsException("Address: " + address);
        }
        return space[index];
    }

    public int[] getSpace() {
        return space;
    }

    @Override
    public Memento<Ram> saveToMemento() {
        return new RamMemento(space.clone());
    }

    @Override
    public void restoreFromMemento(Memento<Ram> memento) {
        if (!(memento instanceof RamMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        if (this.space.length != mem.space.length) {
            throw new IllegalArgumentException("Memento space length doesn't match");
        }
        System.arraycopy(mem.space, 0, this.space, 0, this.space.length);
    }

    public record RamMemento(int[] space) implements Memento<Ram> {
    }
}
