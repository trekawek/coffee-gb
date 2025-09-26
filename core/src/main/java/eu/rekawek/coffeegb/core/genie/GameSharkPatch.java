package eu.rekawek.coffeegb.core.genie;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.memory.GbcRam;

public record GameSharkPatch(int mode, int bank, int address, int data) implements Patch {
    @Override
    public int getAddress() {
        return address;
    }

    @Override
    public boolean accepts(AddressSpace addressSpace, int ramBank, boolean gbc) {
        if (mode == 0 && bank == 1) {
            return true;
        }
        if (mode == 8 && bank == ramBank) {
            return true;
        }
        if (mode == 9) {
            return Math.max(1, bank) == (gbc ? Math.max(addressSpace.getByte(GbcRam.SVBK) & 0x07, 1) : 1);
        }
        return false;
    }

    @Override
    public int getValue() {
        return data;
    }
}
