package eu.rekawek.coffeegb.memory.cart.type;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.memory.cart.CartridgeType;

public class Mbc2 implements AddressSpace {

    private final CartridgeType type;

    private final int romBanks;

    private final int[] cartridge;

    private final int[] ram;

    private final Battery battery;

    private int selectedRomBank = 1;

    private boolean ramWriteEnabled;

    public Mbc2(int[] cartridge, CartridgeType type, Battery battery, int romBanks) {
        this.cartridge = cartridge;
        this.romBanks = romBanks;
        this.ram = new int[0x0200];
        for (int i = 0; i < ram.length; i++) {
            ram[i] = 0xff;
        }
        this.type = type;
        this.battery = battery;
        battery.loadRam(ram);
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000) ||
               (address >= 0xa000 && address < 0xc000);
    }

    @Override
    public void setByte(int address, int value) {
        if (address >= 0x0000 && address < 0x2000) {
            if ((address & 0x0100) == 0) {
                ramWriteEnabled = (value & 0b1010) != 0;
                if (!ramWriteEnabled) {
                    battery.saveRam(ram);
                }
            }
        } else if (address >= 0x2000 && address < 0x4000) {
            if ((address & 0x0100) != 0) {
                selectedRomBank = value & 0b00001111;
            }
        } else if (address >= 0xa000 && address < 0xc000 && ramWriteEnabled) {
            int ramAddress = getRamAddress(address);
            if (ramAddress < ram.length) {
                ram[ramAddress] = value & 0x0f;
            }
        }
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x0000 && address < 0x4000) {
            return getRomByte(0, address);
        } else if (address >= 0x4000 && address < 0x8000) {
            return getRomByte(selectedRomBank, address - 0x4000);
        } else if (address >= 0xa000 && address < 0xb000) {
            int ramAddress = getRamAddress(address);
            if (ramAddress < ram.length) {
                return ram[ramAddress];
            } else {
                return 0xff;
            }
        } else {
            return 0xff;
        }
    }

    private int getRomByte(int bank, int address) {
        int cartOffset = bank * 0x4000 + address;
        if (cartOffset < cartridge.length) {
            return cartridge[cartOffset];
        } else {
            return 0xff;
        }
    }

    private int getRamAddress(int address) {
        return address - 0xa000;
    }
}
