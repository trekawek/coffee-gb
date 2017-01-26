package eu.rekawek.coffeegb.memory.cart.type;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.memory.cart.CartridgeType;

public class Mbc1 implements AddressSpace {

    private final CartridgeType type;

    private final int romBanks;

    private final int ramBanks;

    private final int[] cartridge;

    private final int[] ram;

    private int selectedRamBank;

    private int selectedRomBank = 1;

    private int memoryModel;

    private boolean ramWriteEnabled;

    public Mbc1(int[] cartridge, CartridgeType type, int romBanks, int ramBanks) {
        this.cartridge = cartridge;
        this.ram = new int[0x2000 * ramBanks];
        this.type = type;
        this.romBanks = romBanks;
        this.ramBanks = ramBanks;
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000) ||
               (address >= 0xa000 && address < 0xc000);
    }

    @Override
    public void setByte(int address, int value) {
        if (address >= 0x0000 && address < 0x2000) {
            ramWriteEnabled = (value & 0b1010) != 0;
        } else if (address >= 0x2000 && address < 0x4000) {
            int bank = value & 0b00011111;
            if (bank == 0) {
                bank = 1;
            }
            if (bank < romBanks) {
                selectedRomBank = bank;
            }
        } else if (address >= 0x4000 && address < 0x6000 && memoryModel == 0) {
            // FIXME
            int bank = value & 0b11;
            if (bank == 0) {
                bank = 1;
            }
            if (bank < romBanks) {
                selectedRomBank = bank;
            }
        } else if (address >= 0x4000 && address < 0x6000 && memoryModel == 1) {
            int bank = value & 0b11;
            if (bank < ramBanks) {
                selectedRamBank = bank;
            }
        } else if (address >= 0x6000 && address < 0x8000) {
            memoryModel = value & 1;
        } else if (address >= 0xa000 && address < 0xc000 && ramWriteEnabled) {
            ram[getRamAddress(address)] = value;
        }
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x0000 && address < 0x4000) {
            return getRomByte(0, address);
        } else if (address >= 0x4000 && address < 0x8000) {
            return getRomByte(selectedRomBank, address - 0x4000);
        } else if (address >= 0xa000 && address < 0xc000) {
            return ram[getRamAddress(address)];
        } else {
            throw new IllegalArgumentException(Integer.toHexString(address));
        }
    }

    private int getRomByte(int bank, int address) {
        return cartridge[bank * 0x4000 + address];
    }

    private int getRamAddress(int address) {
        return selectedRamBank * 0x2000 + (address - 0xa000);
    }
}
