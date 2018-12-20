package eu.rekawek.coffeegb.memory.cart.type;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.memory.cart.CartridgeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Mbc1 implements AddressSpace {

    private static final Logger LOG = LoggerFactory.getLogger(Mbc1.class);

    private static int[] NINTENDO_LOGO = {
            0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B, 0x03, 0x73, 0x00, 0x83, 0x00, 0x0C, 0x00, 0x0D,
            0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E, 0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99,
            0xBB, 0xBB, 0x67, 0x63, 0x6E, 0x0E, 0xEC, 0xCC, 0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E
    };

    private final CartridgeType type;

    private final int romBanks;

    private final int ramBanks;

    private final int[] cartridge;

    private final int[] ram;

    private final Battery battery;

    private final boolean multicart;

    private int selectedRamBank;

    private int selectedRomBank = 1;

    private int memoryModel;

    private boolean ramWriteEnabled;

    private int cachedRomBankFor0x0000 = -1;

    private int cachedRomBankFor0x4000 = -1;

    public Mbc1(int[] cartridge, CartridgeType type, Battery battery, int romBanks, int ramBanks) {
        this.multicart = romBanks == 64 && isMulticart(cartridge);
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
            ramWriteEnabled = (value & 0b1111) == 0b1010;
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
            if (ramWriteEnabled) {
                int ramAddress = getRamAddress(address);
                if (ramAddress < ram.length) {
                    return ram[ramAddress];
                } else {
                    return 0xff;
                }
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
                int bank = (selectedRamBank << 5);
                if (multicart) {
                    bank >>= 1;
                }
                bank %= romBanks;
                cachedRomBankFor0x0000 = bank;
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
            if (multicart) {
                bank = ((bank >> 1) & 0x30) | (bank & 0x0f);
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

    private static boolean isMulticart(int[] rom) {
        int logoCount = 0;
        for (int i = 0; i < rom.length; i += 0x4000) {
            boolean logoMatches = true;
            for (int j = 0; j < NINTENDO_LOGO.length; j++) {
                if (rom[i + 0x104 + j] != NINTENDO_LOGO[j]) {
                    logoMatches = false;
                    break;
                }
            }
            if (logoMatches) {
                logoCount++;
            }
        }
        return logoCount > 1;
    }
}
