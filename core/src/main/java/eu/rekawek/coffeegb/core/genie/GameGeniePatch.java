package eu.rekawek.coffeegb.core.genie;

import eu.rekawek.coffeegb.core.AddressSpace;

/**
 * Immutable cheat value shared with the bounded historical snapshot importer.
 *
 * <p>The live cheat owner is not serializable; this value retains its released descriptor only
 * because it can occur inside the two explicitly supported legacy fixture graphs.
 */
public record GameGeniePatch(int newData, int address, int oldData) implements Patch {
    @Override
    public int getAddress() {
        return address;
    }

    @Override
    public boolean accepts(AddressSpace addressSpace, int ramBank, boolean gbc) {
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
