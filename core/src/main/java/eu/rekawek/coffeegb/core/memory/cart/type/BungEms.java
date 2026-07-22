package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;

import java.util.Arrays;

/**
 * Bung/EMS flashcart mapper. The selected ROM bank is ORed with a configurable
 * base mask, allowing a menu to expose one embedded game as a complete cartridge.
 */
public class BungEms implements MemoryController {

    private static final int RAM_BANKS = 4;

    private final int[] cartridge;

    private final int romBanks;

    private final int[] ram = new int[RAM_BANKS * 0x2000];

    private final Battery battery;

    private int romBankLow = 1;

    private int romBankHigh;

    private int romBankMask;

    private int romBankLatch = 1;

    private int selectedRamBank;

    private boolean configureMode;

    private boolean ramEnabled = true;

    private boolean ramUpdated;

    public BungEms(Rom rom, Battery battery) {
        cartridge = rom.getRom();
        romBanks = Math.max(1, (cartridge.length + 0x3fff) / 0x4000);
        this.battery = battery;
        Arrays.fill(ram, 0xff);
        battery.loadRam(ram);
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000)
                || (address >= 0xa000 && address < 0xc000);
    }

    @Override
    public void setByte(int address, int value) {
        if (address >= 0x0000 && address < 0x2000) {
            if (value == 0xa5) {
                configureMode = true;
            } else if (value == 0x98) {
                configureMode = false;
            } else {
                ramEnabled = (value & 0x0f) == 0x0a;
            }
        } else if (address >= 0x2000 && address < 0x3000) {
            romBankLow = value;
            romBankLatch = value;
        } else if (address >= 0x3000 && address < 0x4000) {
            romBankHigh = value & 1;
        } else if (address >= 0x4000 && address < 0x6000) {
            selectedRamBank = value & (RAM_BANKS - 1);
        } else if (address >= 0x7000 && address < 0x8000 && configureMode) {
            romBankMask = romBankLatch;
        } else if (address >= 0xa000 && address < 0xc000 && ramEnabled) {
            ram[getRamAddress(address)] = value;
            ramUpdated = true;
        }
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x0000 && address < 0x4000) {
            return getRomByte(normalizeRomBank(romBankMask), address);
        } else if (address >= 0x4000 && address < 0x8000) {
            int selected = romBankLow | (romBankHigh << 8) | romBankMask;
            return getRomByte(normalizeRomBank(selected), address - 0x4000);
        } else if (address >= 0xa000 && address < 0xc000) {
            return ramEnabled ? ram[getRamAddress(address)] : 0xff;
        } else {
            throw new IllegalArgumentException(Integer.toHexString(address));
        }
    }

    @Override
    public void flushRam() {
        if (ramUpdated) {
            battery.saveRam(ram);
            battery.flush();
        }
    }

    private int normalizeRomBank(int bank) {
        return bank % romBanks;
    }

    private int getRomByte(int bank, int address) {
        int offset = bank * 0x4000 + address;
        return offset < cartridge.length ? cartridge[offset] : 0xff;
    }

    private int getRamAddress(int address) {
        return selectedRamBank * 0x2000 + address - 0xa000;
    }

    @Override
    public Memento<MemoryController> saveToMemento() {
        return new BungEmsMemento(battery.saveToMemento(), ram.clone(), romBankLow, romBankHigh,
                romBankMask, romBankLatch, selectedRamBank, configureMode, ramEnabled, ramUpdated);
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (!(memento instanceof BungEmsMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        if (ram.length != mem.ram.length) {
            throw new IllegalArgumentException("Memento ram length doesn't match");
        }
        battery.restoreFromMemento(mem.batteryMemento);
        System.arraycopy(mem.ram, 0, ram, 0, ram.length);
        romBankLow = mem.romBankLow;
        romBankHigh = mem.romBankHigh;
        romBankMask = mem.romBankMask;
        romBankLatch = mem.romBankLatch;
        selectedRamBank = mem.selectedRamBank;
        configureMode = mem.configureMode;
        ramEnabled = mem.ramEnabled;
        ramUpdated = mem.ramUpdated;
    }

    private record BungEmsMemento(Memento<Battery> batteryMemento, int[] ram,
                                  int romBankLow, int romBankHigh, int romBankMask,
                                  int romBankLatch, int selectedRamBank, boolean configureMode,
                                  boolean ramEnabled, boolean ramUpdated)
            implements Memento<MemoryController> {
    }
}
