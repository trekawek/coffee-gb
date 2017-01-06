package eu.rekawek.coffeegb.memory;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.gpu.Gpu;

public class Mmu implements AddressSpace {

    private final AddressSpace bootRom = new Rom(BootRom.GAMEBOY_CLASSIC, 0);

    private final AddressSpace ram = new Ram();

    private final Gpu gpu;

    public Mmu(Gpu gpu) {
        this.gpu = gpu;
    }

    @Override
    public void setByte(int address, int value) {
        getSpace(address).setByte(address, value);
    }

    @Override
    public int getByte(int address) {
        return getSpace(address).getByte(address);
    }

    private AddressSpace getSpace(int address) {
        if (address >= 0x0000 && address <= 0x00ff) {
            return bootRom;
        } else if (address == 0xff44) {
            return gpu;
        } else {
            return ram;
        }
    }

}
