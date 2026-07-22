package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;

import java.util.Arrays;

/**
 * The SL multi-game cartridge controller used by large Chinese MBC1/MBC5 collections.
 * The menu starts in configuration mode, where writes to 0x5000 and 0x7000 select a
 * command and its argument. It assigns a ROM base and mask and an independent SRAM
 * partition to the selected game, then pulses the console reset line. Those assignments
 * survive the reset and the selected game sees an ordinary MBC1- or MBC5-like interface.
 */
public class SlMulticart implements MemoryController {

    public record ResetEvent(boolean nonCgbGame) implements Event {
    }

    private final int[] rom;

    private final int romBanks;

    private final int[] ram = new int[0x20000];

    private final Battery battery;

    private int configCommand = 0x100;

    private int baseRomBank;

    private int selectedRomBank = 1;

    private int romBankMask = 1;

    private int zeroRemap;

    private boolean configurationMode = true;

    private boolean mbc5Mode;

    private boolean ramAllowed;

    private boolean ramEnabled;

    private int baseRamBank;

    private int selectedRamBank;

    private int ramBankMask;

    private transient EventBus eventBus = EventBus.NULL_EVENT_BUS;

    public SlMulticart(Rom rom, Battery battery) {
        this.rom = rom.getRom();
        this.romBanks = Math.max(2, this.rom.length / 0x4000);
        this.battery = battery;
        Arrays.fill(ram, 0xff);
        battery.loadRam(ram);
    }

    @Override
    public void init(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000)
                || (address >= 0xa000 && address < 0xc000);
    }

    @Override
    public void setByte(int address, int value) {
        value &= 0xff;
        if (address < 0x2000) {
            ramEnabled = ramAllowed && (value & 0x0f) == 0x0a;
        } else if (address < 0x3000) {
            selectedRomBank = value;
        } else if (address < 0x4000) {
            if (!mbc5Mode) {
                selectedRomBank = value;
            }
        } else if (address < 0x5000) {
            selectedRamBank = value & 0x0f;
        } else if (address < 0x6000) {
            if (configurationMode) {
                configCommand = value;
            } else {
                selectedRamBank = value & 0x0f;
            }
        } else if (address >= 0x7000 && address < 0x8000 && configurationMode) {
            executeConfiguration(value);
        } else if (address >= 0xa000 && address < 0xc000 && ramEnabled) {
            ram[ramAddress(address)] = value;
        }
    }

    private void executeConfiguration(int value) {
        switch (configCommand) {
            case 0x55 -> configureMapping(value);
            case 0xaa -> baseRomBank = (baseRomBank & ~0x01fe) | (value << 1);
            case 0xbb -> {
                ramAllowed = (value & 0x20) != 0;
                baseRamBank = value & 0x0f;
                ramBankMask = (value & 0x10) != 0 ? 0 : 3;
                if (!ramAllowed) {
                    ramEnabled = false;
                }
            }
            default -> {
                // Vast Fame menus write a stray 0x07 after setting the base bank.
            }
        }
        configCommand = 0x100;
    }

    private void configureMapping(int value) {
        baseRomBank = (baseRomBank & ~0x0200) | (((value >>> 3) & 1) << 9);
        romBankMask = (2 << ((~value) & 7)) - 1;
        int mode = (value >>> 5) & 3;
        mbc5Mode = mode == 0;
        zeroRemap = mode == 3 ? 1 : 0;
        configurationMode = false;
        if ((value & 0x80) != 0) {
            selectedRomBank = 1;
            ramEnabled = false;
            int cgbFlag = rom[romAddress(lowRomBank(), 0x0143)];
            eventBus.post(new ResetEvent((cgbFlag & 0x80) == 0));
        }
    }

    private int lowRomBank() {
        return baseRomBank & ~romBankMask;
    }

    private int highRomBank() {
        int bank = selectedRomBank & romBankMask;
        return (baseRomBank & ~romBankMask) | (bank != 0 ? bank : zeroRemap & romBankMask);
    }

    private int ramBank() {
        return (baseRamBank & ~ramBankMask) | (selectedRamBank & ramBankMask);
    }

    private int romAddress(int bank, int offset) {
        return Math.floorMod(bank, romBanks) * 0x4000 + offset;
    }

    private int ramAddress(int address) {
        return (ramBank() & 0x0f) * 0x2000 + address - 0xa000;
    }

    @Override
    public int getByte(int address) {
        if (address < 0x4000) {
            return rom[romAddress(lowRomBank(), address)];
        } else if (address < 0x8000) {
            return rom[romAddress(highRomBank(), address - 0x4000)];
        } else if (address >= 0xa000 && address < 0xc000) {
            return ramEnabled ? ram[ramAddress(address)] : 0xff;
        }
        return 0xff;
    }

    @Override
    public void flushRam() {
        battery.saveRam(ram);
        battery.flush();
    }

    @Override
    public int getRamByte(int bank, int offset) {
        int index = bank * 0x2000 + offset;
        return bank >= 0 && offset >= 0 && offset < 0x2000 && index < ram.length ? ram[index] : -1;
    }

    @Override
    public Memento<MemoryController> saveToMemento() {
        return new SlMulticartMemento(ram.clone(), configCommand, baseRomBank,
                selectedRomBank, romBankMask, zeroRemap, configurationMode, mbc5Mode,
                ramAllowed, ramEnabled, baseRamBank, selectedRamBank, ramBankMask);
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (!(memento instanceof SlMulticartMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        System.arraycopy(mem.ram, 0, ram, 0, ram.length);
        configCommand = mem.configCommand;
        baseRomBank = mem.baseRomBank;
        selectedRomBank = mem.selectedRomBank;
        romBankMask = mem.romBankMask;
        zeroRemap = mem.zeroRemap;
        configurationMode = mem.configurationMode;
        mbc5Mode = mem.mbc5Mode;
        ramAllowed = mem.ramAllowed;
        ramEnabled = mem.ramEnabled;
        baseRamBank = mem.baseRamBank;
        selectedRamBank = mem.selectedRamBank;
        ramBankMask = mem.ramBankMask;
    }

    private record SlMulticartMemento(int[] ram, int configCommand, int baseRomBank,
                                      int selectedRomBank, int romBankMask, int zeroRemap,
                                      boolean configurationMode, boolean mbc5Mode,
                                      boolean ramAllowed, boolean ramEnabled, int baseRamBank,
                                      int selectedRamBank, int ramBankMask)
            implements Memento<MemoryController> {
    }
}
