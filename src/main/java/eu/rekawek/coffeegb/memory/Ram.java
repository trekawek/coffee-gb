package eu.rekawek.coffeegb.memory;

import eu.rekawek.coffeegb.AddressSpace;

public class Ram implements AddressSpace {

    private int[] space = new int[0x10000];

    @Override
    public void setByte(int address, int value) {
        space[address] = value;
    }

    @Override
    public int getByte(int address) {
        return space[address];
    }
}
