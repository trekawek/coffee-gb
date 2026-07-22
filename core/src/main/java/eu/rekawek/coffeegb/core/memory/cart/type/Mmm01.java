package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;

import java.util.Arrays;

/**
 * MMM01 multicart mapper (e.g. "Mani 4 in 1" / Taito Variety Pack, Momotarou Collection).
 * The menu program lives in the last 32 KB of the ROM, which is mapped at 0x0000-0x7FFF
 * until the menu "locks" the mapper onto the selected game (bit 6 of a 0x0000-0x1FFF
 * write). Before locking, the configuration writes set the base bank, bank masks and the
 * MBC1-compatibility options; after locking, the mapper behaves like an MBC1 restricted to
 * the selected game's window. Modelled after SameBoy.
 */
public class Mmm01 implements MemoryController {

    private final int[] cartridge;

    private final int[] ram;

    private final int romBanks;

    private final int ramBanks;

    private final Battery battery;

    private boolean ramEnabled;

    private int romBankLow;    // 5 bits

    private int romBankMid;    // 2 bits

    private int romBankHigh;   // 2 bits

    private int romBankMask;   // 4 bits

    private int ramBankLow;    // 2 bits

    private int ramBankHigh;   // 2 bits

    private int ramBankMask;   // 2 bits

    private boolean locked;

    private boolean mbc1Mode;

    private boolean mbc1ModeDisable;

    private boolean multiplexMode;

    private boolean ramUpdated;

    /**
     * @param menuFirst true when the dump has the menu in the first 32 KB (header declares
     *                  MMM01); the ROM is internally rotated so the menu is last, matching
     *                  the hardware wiring.
     */
    public Mmm01(Rom rom, Battery battery, boolean menuFirst) {
        int[] source = rom.getRom();
        if (menuFirst) {
            this.cartridge = new int[source.length];
            System.arraycopy(source, 0x8000, this.cartridge, 0, source.length - 0x8000);
            System.arraycopy(source, 0, this.cartridge, source.length - 0x8000, 0x8000);
        } else {
            this.cartridge = source;
        }
        // the header in the first game's bank describes only that game; use the
        // physical ROM size for the bank arithmetic
        this.romBanks = this.cartridge.length / 0x4000;
        this.ramBanks = Math.max(rom.getRamBanks(), 1);
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
            ramEnabled = (value & 0x0f) == 0x0a;
            if (!locked) {
                ramBankMask = (value >> 4) & 0b11;
                locked = (value & 0x40) != 0;
            }
        } else if (address >= 0x2000 && address < 0x4000) {
            if (!locked) {
                romBankMid = (value >> 5) & 0b11;
            }
            int lockedBits = romBankMask << 1;
            romBankLow = (romBankLow & lockedBits) | (value & ~lockedBits & 0b11111);
        } else if (address >= 0x4000 && address < 0x6000) {
            ramBankLow = (value | ~ramBankMask) & 0b11;
            if (!locked) {
                ramBankHigh = (value >> 2) & 0b11;
                romBankHigh = (value >> 4) & 0b11;
                mbc1ModeDisable = (value & 0x40) != 0;
            }
        } else if (address >= 0x6000 && address < 0x8000) {
            if (!mbc1ModeDisable) {
                mbc1Mode = (value & 1) != 0;
            }
            if (!locked) {
                romBankMask = (value >> 2) & 0b1111;
                multiplexMode = (value & 0x40) != 0;
            }
        } else if (address >= 0xa000 && address < 0xc000 && ramEnabled) {
            int ramAddress = getRamAddress(address);
            if (ramAddress < ram.length) {
                ram[ramAddress] = value;
                ramUpdated = true;
            }
        }
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x0000 && address < 0x4000) {
            return getRomByte(getRomBankFor0x0000(), address);
        } else if (address >= 0x4000 && address < 0x8000) {
            return getRomByte(getRomBankFor0x4000(), address - 0x4000);
        } else if (address >= 0xa000 && address < 0xc000) {
            if (!ramEnabled) {
                return 0xff;
            }
            int ramAddress = getRamAddress(address);
            return ramAddress < ram.length ? ram[ramAddress] : 0xff;
        } else {
            throw new IllegalArgumentException(Integer.toHexString(address));
        }
    }

    private int getRomBankFor0x0000() {
        if (!locked) {
            return Math.floorMod(-2, romBanks);
        }
        int bank;
        if (multiplexMode) {
            bank = (romBankLow & (romBankMask << 1))
                    | ((mbc1Mode ? 0 : ramBankLow) << 5)
                    | (romBankHigh << 7);
        } else {
            bank = (romBankLow & (romBankMask << 1))
                    | (romBankMid << 5)
                    | (romBankHigh << 7);
        }
        return bank % romBanks;
    }

    private int getRomBankFor0x4000() {
        if (!locked) {
            return Math.floorMod(-1, romBanks);
        }
        int bank;
        if (multiplexMode) {
            bank = romBankLow | (ramBankLow << 5) | (romBankHigh << 7);
        } else {
            bank = romBankLow | (romBankMid << 5) | (romBankHigh << 7);
        }
        if (bank % romBanks == getRomBankFor0x0000()) {
            bank++;
        }
        return bank % romBanks;
    }

    private int getRamBank() {
        if (multiplexMode) {
            return romBankMid | (ramBankHigh << 2);
        } else {
            return ramBankLow | (ramBankHigh << 2);
        }
    }

    private int getRamAddress(int address) {
        return (getRamBank() % ramBanks) * 0x2000 + (address - 0xa000);
    }

    private int getRomByte(int bank, int address) {
        int cartOffset = bank * 0x4000 + address;
        if (cartOffset < cartridge.length) {
            return cartridge[cartOffset];
        } else {
            return 0xff;
        }
    }

    @Override
    public void flushRam() {
        if (ramUpdated) {
            battery.saveRam(ram);
            battery.flush();
        }
    }

    @Override
    public int getRamByte(int bank, int offset) {
        int index = bank * 0x2000 + offset;
        return bank >= 0 && offset >= 0 && offset < 0x2000 && index < ram.length ? ram[index] : -1;
    }

    @Override
    public Memento<MemoryController> saveToMemento() {
        return new Mmm01Memento(battery.saveToMemento(), ram.clone(), ramEnabled, romBankLow, romBankMid,
                romBankHigh, romBankMask, ramBankLow, ramBankHigh, ramBankMask, locked, mbc1Mode,
                mbc1ModeDisable, multiplexMode, ramUpdated);
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (!(memento instanceof Mmm01Memento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        if (this.ram.length != mem.ram.length) {
            throw new IllegalArgumentException("Memento ram length doesn't match");
        }
        battery.restoreFromMemento(mem.batteryMemento);
        System.arraycopy(mem.ram, 0, this.ram, 0, this.ram.length);
        this.ramEnabled = mem.ramEnabled;
        this.romBankLow = mem.romBankLow;
        this.romBankMid = mem.romBankMid;
        this.romBankHigh = mem.romBankHigh;
        this.romBankMask = mem.romBankMask;
        this.ramBankLow = mem.ramBankLow;
        this.ramBankHigh = mem.ramBankHigh;
        this.ramBankMask = mem.ramBankMask;
        this.locked = mem.locked;
        this.mbc1Mode = mem.mbc1Mode;
        this.mbc1ModeDisable = mem.mbc1ModeDisable;
        this.multiplexMode = mem.multiplexMode;
        this.ramUpdated = mem.ramUpdated;
    }

    private record Mmm01Memento(Memento<Battery> batteryMemento, int[] ram, boolean ramEnabled, int romBankLow,
                                int romBankMid, int romBankHigh, int romBankMask, int ramBankLow, int ramBankHigh,
                                int ramBankMask, boolean locked, boolean mbc1Mode, boolean mbc1ModeDisable,
                                boolean multiplexMode, boolean ramUpdated) implements Memento<MemoryController> {
    }
}
