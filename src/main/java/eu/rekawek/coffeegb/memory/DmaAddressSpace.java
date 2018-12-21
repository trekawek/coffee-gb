package eu.rekawek.coffeegb.memory;

import eu.rekawek.coffeegb.AddressSpace;

public class DmaAddressSpace implements AddressSpace {

    private final AddressSpace addressSpace;

    public DmaAddressSpace(AddressSpace addressSpace) {
        this.addressSpace = addressSpace;
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
        if (address < 0xe000) {
            return addressSpace.getByte(address);
        } else {
            return addressSpace.getByte(address - 0x2000);
        }
    }
}
