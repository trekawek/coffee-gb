package eu.rekawek.coffeegb.memory.cart.type;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.memory.cart.CartridgeType;

public class Rom implements AddressSpace {

    private final int[] rom;

    public Rom(int[] rom, CartridgeType type, int romBanks, int ramBanks) {
        this.rom = rom;
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000) ||
               (address >= 0xa000 && address < 0xc000);
    }

    @Override
    public void setByte(int address, int value) {
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x0000 && address < 0x8000) {
            return rom[address];
        } else {
            return 0;
        }
    }
}
