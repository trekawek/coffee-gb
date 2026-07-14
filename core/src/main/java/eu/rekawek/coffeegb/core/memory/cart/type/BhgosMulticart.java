package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;

import java.util.Arrays;

/**
 * Blue Hippo's Game Boy Operating System multicart mapper. Its menu is followed by
 * unmodified games aligned to 32 KiB boundaries. The second write to 0x6000-0x7FFF
 * selects one of those blocks and relocates both ROM windows; bank writes made by the
 * selected game are then relative to that block.
 */
public class BhgosMulticart implements MemoryController {

    private final int[] rom;

    private final int romBanks;

    private final int[] ram = new int[4 * 0x2000];

    private final Battery battery;

    private int selectedRomBank = 1;

    private int selectedRamBank;

    private int baseRomBank;

    private int blockSelectWrites;

    private boolean ramUpdated;

    public BhgosMulticart(Rom rom, Battery battery) {
        this.rom = rom.getRom();
        this.romBanks = Math.max(2, (this.rom.length + 0x3fff) / 0x4000);
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
        if (address >= 0x2000 && address < 0x4000) {
            selectedRomBank = value == 0 ? 1 : value;
        } else if (address >= 0x4000 && address < 0x6000) {
            selectedRamBank = value & 0x03;
        } else if (address >= 0x6000 && address < 0x8000) {
            blockSelectWrites++;
            if (blockSelectWrites == 2) {
                baseRomBank = (value * 2) % romBanks;
                selectedRomBank = 1;
            }
        } else if (address >= 0xa000 && address < 0xc000) {
            ram[selectedRamBank * 0x2000 + address - 0xa000] = value;
            ramUpdated = true;
        }
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x0000 && address < 0x4000) {
            return getRomByte(baseRomBank, address);
        } else if (address >= 0x4000 && address < 0x8000) {
            return getRomByte(baseRomBank + selectedRomBank, address - 0x4000);
        } else if (address >= 0xa000 && address < 0xc000) {
            return ram[selectedRamBank * 0x2000 + address - 0xa000];
        }
        throw new IllegalArgumentException(Integer.toHexString(address));
    }

    private int getRomByte(int bank, int address) {
        int offset = (bank % romBanks) * 0x4000 + address;
        return offset < rom.length ? rom[offset] : 0xff;
    }

    @Override
    public void flushRam() {
        if (ramUpdated) {
            battery.saveRam(ram);
            battery.flush();
        }
    }

    @Override
    public Memento<MemoryController> saveToMemento() {
        return new BhgosMulticartMemento(battery.saveToMemento(), ram.clone(), selectedRomBank,
                selectedRamBank, baseRomBank, blockSelectWrites, ramUpdated);
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (!(memento instanceof BhgosMulticartMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        battery.restoreFromMemento(mem.batteryMemento);
        System.arraycopy(mem.ram, 0, ram, 0, ram.length);
        selectedRomBank = mem.selectedRomBank;
        selectedRamBank = mem.selectedRamBank;
        baseRomBank = mem.baseRomBank;
        blockSelectWrites = mem.blockSelectWrites;
        ramUpdated = mem.ramUpdated;
    }

    private record BhgosMulticartMemento(Memento<Battery> batteryMemento, int[] ram,
                                         int selectedRomBank, int selectedRamBank,
                                         int baseRomBank, int blockSelectWrites,
                                         boolean ramUpdated) implements Memento<MemoryController> {
    }
}
