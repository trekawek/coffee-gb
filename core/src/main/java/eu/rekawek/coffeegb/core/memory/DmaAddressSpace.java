package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;

import java.io.Serializable;

public class DmaAddressSpace implements AddressSpace, Serializable {

    private final AddressSpace addressSpace;

    private final boolean gbc;

    public DmaAddressSpace(AddressSpace addressSpace, boolean gbc) {
        this.addressSpace = addressSpace;
        this.gbc = gbc;
    }

    @Override
    public boolean accepts(int address) {
        return true;
    }

    @Override
    public void setByte(int address, int value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getByte(int address) {
        return addressSpace.getByte(mapAddress(address, gbc));
    }

    static int mapAddress(int address, boolean gbc) {
        if (address < 0xe000) {
            return address;
        }
        // The DMG copy engine follows the normal echo mapping for the high
        // source range. CGB instead decodes those source pages onto cartridge
        // RAM, including while running in monochrome compatibility mode.
        return gbc ? address & 0xbfff : address - 0x2000;
    }
}
