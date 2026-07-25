package eu.rekawek.coffeegb.core.genie;

import eu.rekawek.coffeegb.core.AddressSpace;

/**
 * Importer-only historical cheat value.
 *
 * <p>This class retains its released binary descriptor only for the two explicitly supported
 * legacy fixture graphs. Normal execution uses {@link GameGenieCheat}.
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
