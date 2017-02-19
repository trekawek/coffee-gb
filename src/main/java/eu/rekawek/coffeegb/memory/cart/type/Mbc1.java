package eu.rekawek.coffeegb.memory.cart.type;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.memory.cart.CartridgeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Mbc1 implements AddressSpace {

    private static final Logger LOG = LoggerFactory.getLogger(Mbc1.class);

    private final CartridgeType type;

    private final int romBanks;

    private final int ramBanks;

    private final int[] cartridge;

    private final int[] ram;

    private final Battery battery;

    private int selectedRamBank;

    private int selectedRomBank = 1;

    private int memoryModel;

    private boolean ramWriteEnabled;

    private int cachedRomBankFor0x0000 = -1;

    private int cachedRomBankFor0x4000 = -1;

    public Mbc1(int[] cartridge, CartridgeType type, Battery battery, int romBanks, int ramBanks) {
        this.cartridge = cartridge;
        this.ramBanks = ramBanks;
        this.romBanks = romBanks;
        this.ram = new int[0x2000 * this.ramBanks];
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
            ramWriteEnabled = (value & 0b1010) != 0;
            if (!ramWriteEnabled) {
                battery.saveRam(ram);
            }
            LOG.trace("RAM write: {}", ramWriteEnabled);
        } else if (address >= 0x2000 && address < 0x4000) {
            LOG.trace("Low 5 bits of ROM bank: {}", (value & 0b00011111));
            int bank = selectedRomBank & 0b01100000;
            bank = bank | (value & 0b00011111);
            selectRomBank(bank);
            cachedRomBankFor0x0000 = cachedRomBankFor0x4000 = -1;
        } else if (address >= 0x4000 && address < 0x6000 && memoryModel == 0) {
            LOG.trace("High 2 bits of ROM bank: {}", ((value & 0b11) << 5));
            int bank = selectedRomBank & 0b00011111;
            bank = bank | ((value & 0b11) << 5);
            selectRomBank(bank);
            cachedRomBankFor0x0000 = cachedRomBankFor0x4000 = -1;
        } else if (address >= 0x4000 && address < 0x6000 && memoryModel == 1) {
            LOG.trace("RAM bank: {}", (value & 0b11));
            int bank = value & 0b11;
            selectedRamBank = bank;
            cachedRomBankFor0x0000 = cachedRomBankFor0x4000 = -1;
        } else if (address >= 0x6000 && address < 0x8000) {
            LOG.trace("Memory mode: {}", (value & 1));
            memoryModel = value & 1;
            cachedRomBankFor0x0000 = cachedRomBankFor0x4000 = -1;
        } else if (address >= 0xa000 && address < 0xc000 && ramWriteEnabled) {
            int ramAddress = getRamAddress(address);
            if (ramAddress < ram.length) {
                ram[ramAddress] = value;
            }
        }
    }

    private void selectRomBank(int bank) {
        selectedRomBank = bank;
        LOG.trace("Selected ROM bank: {}", selectedRomBank);
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x0000 && address < 0x4000) {
            return getRomByte(getRomBankFor0x0000(), address);
        } else if (address >= 0x4000 && address < 0x8000) {
            return getRomByte(getRomBankFor0x4000(), address - 0x4000);
        } else if (address >= 0xa000 && address < 0xc000) {
            int ramAddress = getRamAddress(address);
            if (ramAddress < ram.length) {
                return ram[ramAddress];
            } else {
                return 0xff;
            }
        } else {
            throw new IllegalArgumentException(Integer.toHexString(address));
        }
    }

    private int getRomBankFor0x0000() {
        if (cachedRomBankFor0x0000 == -1) {
            if (memoryModel == 0) {
                cachedRomBankFor0x0000 = 0;
            } else {
                int bank = selectedRomBank;
                bank &= 0b00011111;
                bank |= (selectedRamBank << 5);
                cachedRomBankFor0x0000 = (bank / 0x20 * 0x20) % romBanks;
            }
        }
        return cachedRomBankFor0x0000;
    }

    private int getRomBankFor0x4000() {
        if (cachedRomBankFor0x4000 == -1) {
            int bank = selectedRomBank;
            if (bank % 0x20 == 0) {
                bank++;
            }
            if (memoryModel == 1) {
                bank &= 0b00011111;
                bank |= (selectedRamBank << 5);
            }
            bank %= romBanks;
            cachedRomBankFor0x4000 = bank;
        }
        return cachedRomBankFor0x4000;
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
        if (memoryModel == 0) {
            return address - 0xa000;
        } else {
            return (selectedRamBank % ramBanks) * 0x2000 + (address - 0xa000);
        }
    }
}
