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
        this.ramBanks = ramBanks;
        this.romBanks = romBanks;
        this.ram = new int[0x2000 * Math.max(this.ramBanks, 1)];
        this.type = type;
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
            selectRomBank(bank);
        } else if (address >= 0x4000 && address < 0x6000 && memoryModel == 0) {
            int bank = selectedRomBank & 0b00011111;
            bank = bank | ((value & 0b11) << 5);
            selectRomBank(bank);
        } else if (address >= 0x4000 && address < 0x6000 && memoryModel == 1) {
            int bank = value & 0b11;
            if (bank < ramBanks) {
                selectedRamBank = bank;
            }
        } else if (address >= 0x6000 && address < 0x8000) {
            memoryModel = value & 1;
        } else if (address >= 0xa000 && address < 0xc000 && ramWriteEnabled) {
            int ramAddress = getRamAddress(address);
            if (ramAddress < ram.length) {
                ram[ramAddress] = value;
            }
        }
    }

    private void selectRomBank(int bank) {
        if (bank % 0x20 == 0) {
            bank = bank + 1;
        }
        if (bank < romBanks) {
            selectedRomBank = bank;
        }
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x0000 && address < 0x4000) {
            return getRomByte(0, address);
        } else if (address >= 0x4000 && address < 0x8000) {
            return getRomByte(selectedRomBank, address - 0x4000);
        } else if (address >= 0xa000 && address < 0xc000) {
            int ramAddress = getRamAddress(address);
            if (ramAddress < ram.length) {
                return ram[ramAddress];
            } else {
                return 0;
            }
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
