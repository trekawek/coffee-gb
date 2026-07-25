package eu.rekawek.coffeegb.core.genie;

import eu.rekawek.coffeegb.core.AddressSpace;

/** Active, non-serializable Game Genie patch. */
public record GameGenieCheat(int newData, int address, int oldData) implements CheatPatch {
    @Override
    public int getAddress() {
        return address;
    }

    @Override
    public boolean accepts(AddressSpace addressSpace, int ramBank, boolean gbc) {
        return oldData == -1 || addressSpace.getByte(address) == oldData;
    }

    @Override
    public int getValue() {
        return newData;
    }
}
