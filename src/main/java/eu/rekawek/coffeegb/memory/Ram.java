package eu.rekawek.coffeegb.memory;

import eu.rekawek.coffeegb.AddressSpace;

public class Ram implements AddressSpace {

    private int[] space;

    private int length;

    private int offset;

    public Ram(int offset, int length) {
        this.space = new int[length];
        this.length = length;
        this.offset = offset;
    }

    private Ram(int offset, int length, Ram ram) {
        this.offset = offset;
        this.length = length;
        this.space = ram.space;
    }

    public static Ram createShadow(int offset, int length, Ram ram) {
        return new Ram(offset, length, ram);
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
}
