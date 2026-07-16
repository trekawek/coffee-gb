package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

public class Mbc1 implements MemoryController {

    private static final Logger LOG = LoggerFactory.getLogger(Mbc1.class);

    private static final int[] NINTENDO_LOGO = {0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B, 0x03, 0x73, 0x00, 0x83, 0x00, 0x0C, 0x00, 0x0D, 0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E, 0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99, 0xBB, 0xBB, 0x67, 0x63, 0x6E, 0x0E, 0xEC, 0xCC, 0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E};

    private final int romBanks;

    private final int ramBanks;

    private final int[] cartridge;

    private final int[] ram;

    private final Battery battery;

    private final boolean multicart;

    // This unlicensed translation has an MBC1 header but a different board: 0x2000 is
    // a complete 6-bit ROM-bank register and 0x4000 does not contribute ROM bank bits.
    // Its patched far-call stubs select bank 0x21 directly, while surviving original
    // code still writes 2 to 0x4000; treating that as MBC1 bit 6 maps data as code and
    // freezes on an illegal opcode at 0x4004 (issue #179).
    private final boolean hongKongPokemonRed;

    private int selectedRamBank;

    private int selectedRomBank = 1;

    private int memoryModel;

    private boolean ramWriteEnabled;

    private int cachedRomBankFor0x0000 = -1;

    private int cachedRomBankFor0x4000 = -1;

    private boolean ramUpdated;

    // GBTK flash carts carry an MBC1 header but wire a wide bank register: their
    // firmware streams banks 0x20, 0x21, ... straight into 0x2000-0x3FFF and never
    // touches the upper-bits register (issue #69, Armageddon video). A real MBC1 would
    // mask those writes to 5 bits and wrap. Detected at run time: a >512 KB ROM writing
    // bank bits 5-6 to the low register before ever using the upper register or mode 1.
    private boolean wideBank;

    private boolean upperRegisterUsed;

    public Mbc1(Rom rom, Battery battery) {
        this.cartridge = rom.getRom();
        this.multicart = rom.getRomBanks() == 64 && isMulticart(this.cartridge);
        this.hongKongPokemonRed = rom.getRomBanks() == 64
                && "POCKETMON BE".equals(rom.getTitle())
                && this.cartridge[0x014e] == 0x9c
                && this.cartridge[0x014f] == 0x8c;
        this.wideBank = hongKongPokemonRed;
        this.ramBanks = rom.getRamBanks();
        this.romBanks = rom.getRomBanks();
        this.ram = new int[0x2000 * this.ramBanks];
        Arrays.fill(ram, 0xff);
        this.battery = battery;
        battery.loadRam(ram);
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000) || (address >= 0xa000 && address < 0xc000);
    }

    @Override
    public void setByte(int address, int value) {
        if (address >= 0x0000 && address < 0x2000) {
            ramWriteEnabled = (value & 0b1111) == 0b1010;
            LOG.trace("RAM write: {}", ramWriteEnabled);
        } else if (address >= 0x2000 && address < 0x4000) {
            if (!wideBank && !multicart && !upperRegisterUsed && memoryModel == 0
                    && romBanks > 32 && (value & 0b01100000) != 0) {
                wideBank = true;
                LOG.info("GBTK-style wide bank register detected");
            }
            if (wideBank) {
                selectRomBank(value & 0x7f);
            } else {
                LOG.trace("Low 5 bits of ROM bank: {}", (value & 0b00011111));
                int bank = selectedRomBank & 0b01100000;
                bank = bank | (value & 0b00011111);
                selectRomBank(bank);
            }
            cachedRomBankFor0x0000 = cachedRomBankFor0x4000 = -1;
        } else if (address >= 0x4000 && address < 0x6000 && memoryModel == 0) {
            if (hongKongPokemonRed) {
                LOG.trace("Ignoring upper ROM bank write on Hong Kong Pokemon Red");
            } else {
                LOG.trace("High 2 bits of ROM bank: {}", ((value & 0b11) << 5));
                upperRegisterUsed = true;
                wideBank = false;
                int bank = selectedRomBank & 0b00011111;
                bank = bank | ((value & 0b11) << 5);
                selectRomBank(bank);
            }
            cachedRomBankFor0x0000 = cachedRomBankFor0x4000 = -1;
        } else if (address >= 0x4000 && address < 0x6000 && memoryModel == 1) {
            LOG.trace("RAM bank: {}", (value & 0b11));
            if (!hongKongPokemonRed) {
                upperRegisterUsed = true;
                wideBank = false;
            }
            selectedRamBank = value & 0b11;
            cachedRomBankFor0x0000 = cachedRomBankFor0x4000 = -1;
        } else if (address >= 0x6000 && address < 0x8000) {
            LOG.trace("Memory mode: {}", (value & 1));
            memoryModel = value & 1;
            cachedRomBankFor0x0000 = cachedRomBankFor0x4000 = -1;
        } else if (address >= 0xa000 && address < 0xc000 && ramWriteEnabled) {
            int ramAddress = getRamAddress(address);
            if (ramAddress < ram.length) {
                ram[ramAddress] = value;
                ramUpdated = true;
            }
        }
    }

    @Override
    public void flushRam() {
        if (ramUpdated) {
            battery.saveRam(ram);
            battery.flush();
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
            if (wideBank) {
                cachedRomBankFor0x4000 = Math.max(1, bank) % romBanks;
                return cachedRomBankFor0x4000;
            }
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

    @Override
    public Memento<MemoryController> saveToMemento() {
        return new Mbc1Memento(battery.saveToMemento(), ram.clone(), selectedRamBank, selectedRomBank, memoryModel, ramWriteEnabled, cachedRomBankFor0x0000, cachedRomBankFor0x4000, ramUpdated, wideBank, upperRegisterUsed);
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (!(memento instanceof Mbc1Memento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        if (this.ram.length != mem.ram.length) {
            throw new IllegalArgumentException("Memento ram length doesn't match");
        }
        battery.restoreFromMemento(mem.batteryMemento);
        System.arraycopy(mem.ram, 0, this.ram, 0, this.ram.length);
        this.selectedRamBank = mem.selectedRamBank;
        this.selectedRomBank = mem.selectedRomBank;
        this.memoryModel = mem.memoryModel;
        this.ramWriteEnabled = mem.ramWriteEnabled;
        this.cachedRomBankFor0x0000 = mem.cachedRomBankFor0x0000;
        this.cachedRomBankFor0x4000 = mem.cachedRomBankFor0x4000;
        this.ramUpdated = mem.ramUpdated;
        this.wideBank = mem.wideBank;
        this.upperRegisterUsed = mem.upperRegisterUsed;
    }

    private record Mbc1Memento(Memento<Battery> batteryMemento, int[] ram, int selectedRamBank, int selectedRomBank,
                               int memoryModel, boolean ramWriteEnabled, int cachedRomBankFor0x0000,
                               int cachedRomBankFor0x4000, boolean ramUpdated, boolean wideBank,
                               boolean upperRegisterUsed) implements Memento<MemoryController> {
    }
}
