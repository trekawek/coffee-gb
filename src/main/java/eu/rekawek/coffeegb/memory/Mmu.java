package eu.rekawek.coffeegb.memory;

import eu.rekawek.coffeegb.AddressSpace;

public class Mmu implements AddressSpace {

    private final AddressSpace bootRom = new Rom(BootRom.GAMEBOY_CLASSIC, 0);

    private final AddressSpace ram = new Ram();

    @Override
    public void setByte(int address, int value) {
        getSpace(address).setByte(address, value);
    }

    @Override
    public int getByte(int address) {
        return getSpace(address).getByte(address);
    }

    private AddressSpace getSpace(int address) {
        if (address >= 0x00 && address <= 0xff) {
            return bootRom;
        } else {
            return ram;
        }
    }

}
