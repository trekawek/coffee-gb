package eu.rekawek.coffeegb.memory;

import eu.rekawek.coffeegb.AddressSpace;

public class ShadowAddressSpace implements AddressSpace {

    private final AddressSpace addressSpace;

    private final int echoStart;

    private final int targetStart;

    private final int length;

    public ShadowAddressSpace(AddressSpace addressSpace, int echoStart, int targetStart, int length) {
        this.addressSpace = addressSpace;
        this.echoStart = echoStart;
        this.targetStart = targetStart;
        this.length = length;
    }

    @Override
    public boolean accepts(int address) {
        return address >= echoStart && address < echoStart + length;
    }

    @Override
    public void setByte(int address, int value) {
        addressSpace.setByte(translate(address), value);
    }

    @Override
    public int getByte(int address) {
        return addressSpace.getByte(translate(address));
    }

    private int translate(int address) {
        return getRelative(address) + targetStart;
    }

    private int getRelative(int address) {
        int i = address - echoStart;
        if (i < 0 || i >= length) {
            throw new IllegalArgumentException();
        }
        return i;
    }
}
