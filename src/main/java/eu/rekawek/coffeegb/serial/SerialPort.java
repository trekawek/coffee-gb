package eu.rekawek.coffeegb.serial;

import eu.rekawek.coffeegb.AddressSpace;

public class SerialPort implements AddressSpace {

    @Override
    public boolean accepts(int address) {
        return address == 0xff01 || address == 0xff02;
    }

    @Override
    public void setByte(int address, int value) {
    }

    @Override
    public int getByte(int address) {
        return 0;
    }
}
