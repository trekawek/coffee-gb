package eu.rekawek.coffeegb.core.genie;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.memory.GbcRam;

public record GameSharkPatch(int bank, int address, int data) implements Patch {
    @Override
    public int getAddress() {
        return address;
    }

    @Override
    public boolean accepts(AddressSpace addressSpace, boolean gbc) {
        var currentBank = gbc ? Math.max(addressSpace.getByte(GbcRam.SVBK) & 0x07, 1) : 1;
        return currentBank == bank;
    }

    @Override
    public int getValue() {
        return data;
    }
}
