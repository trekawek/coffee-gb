package eu.rekawek.coffeegb.core.genie;

import eu.rekawek.coffeegb.core.AddressSpace;

public record GameGeniePatch(int newData, int address, int oldData) implements Patch {
    @Override
    public int getAddress() {
        return address;
    }

    @Override
    public boolean accepts(AddressSpace addressSpace, boolean gbc) {
        if (oldData == -1) {
            return true;
        }
        return addressSpace.getByte(address) == oldData;
    }

    @Override
    public int getValue() {
        return newData;
    }
}
