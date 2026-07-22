package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;

import java.util.Arrays;

/**
 * The unlicensed "Duz" Game Boy multicart mapper (e.g. Pokemon Red-Blue 2-in-1). The menu
 * program boots first; selecting a game programs the banking through a two-port register
 * interface (index at 0xA000, data at 0xA100) and then jumps into the chosen game, which
 * runs as an MBC3-style cartridge relocated to a base bank.
 *
 * <p>Register 0xA3 holds the base bank divided by two, so the game's own bank 0 lives at
 * ROM bank {@code 0xA3 * 2} and its switchable banks are addressed relative to that base.
 * Because the game code overlaps the menu (both live at 0x0000-0x7FFF), the base offset is
 * applied to the fixed bank-0 region as well as the switchable one.
 */
public class DuzMulticart implements MemoryController {

    private final int[] rom;

    private final int[] ram;

    private final Battery battery;

    private final int romBanks;

    private boolean ramWriteEnabled;

    private int selectedBank = 1;

    private int selectedRamBank;

    private int baseBank;

    // two-port register file: 0xA000 selects an index, 0xA100 writes its value
    private int regIndex;

    private final int[] regs = new int[0x100];

    public DuzMulticart(Rom rom, Battery battery) {
        this.rom = rom.getRom();
        this.romBanks = Math.max(2, this.rom.length / 0x4000);
        this.ram = new int[0x2000 * Math.max(rom.getRamBanks(), 1)];
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
        if (address < 0x2000) {
            ramWriteEnabled = (value & 0x0f) == 0x0a;
        } else if (address < 0x4000) {
            selectedBank = value & 0x7f;
            if (selectedBank == 0) {
                selectedBank = 1;
            }
        } else if (address < 0x6000) {
            selectedRamBank = value;
        } else if (address == 0xa000) {
            // register-index port
            regIndex = value;
        } else if (address == 0xa100) {
            // register-data port; 0xA3 = base bank / 2
            regs[regIndex & 0xff] = value;
            baseBank = (regs[0xa3] * 2) % romBanks;
        } else if (address >= 0xa000 && address < 0xc000 && ramWriteEnabled) {
            int ramAddress = selectedRamBank * 0x2000 + (address - 0xa000);
            if (ramAddress < ram.length) {
                ram[ramAddress] = value;
            }
        }
    }

    @Override
    public int getByte(int address) {
        if (address < 0x4000) {
            return rom[(baseBank % romBanks) * 0x4000 + address];
        } else if (address < 0x8000) {
            int bank = (baseBank + selectedBank) % romBanks;
            return rom[bank * 0x4000 + (address - 0x4000)];
        } else if (address >= 0xa000 && address < 0xc000) {
            int ramAddress = selectedRamBank * 0x2000 + (address - 0xa000);
            return ramWriteEnabled && ramAddress < ram.length ? ram[ramAddress] : 0xff;
        }
        return 0xff;
    }

    @Override
    public void flushRam() {
        battery.saveRam(ram);
        battery.flush();
    }

    @Override
    public Memento<MemoryController> saveToMemento() {
        return new DuzMulticartMemento(ram.clone(), regs.clone(), selectedBank, selectedRamBank,
                baseBank, regIndex, ramWriteEnabled);
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (!(memento instanceof DuzMulticartMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        System.arraycopy(mem.ram, 0, ram, 0, ram.length);
        System.arraycopy(mem.regs, 0, regs, 0, regs.length);
        selectedBank = mem.selectedBank;
        selectedRamBank = mem.selectedRamBank;
        baseBank = mem.baseBank;
        regIndex = mem.regIndex;
        ramWriteEnabled = mem.ramWriteEnabled;
    }

    private record DuzMulticartMemento(int[] ram, int[] regs, int selectedBank, int selectedRamBank,
                                       int baseBank, int regIndex, boolean ramWriteEnabled)
            implements Memento<MemoryController> {
    }
}
