package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;

import java.util.Arrays;

public class Mbc5 implements MemoryController {

    private final int romBanks;

    private final int ramBanks;

    private final int[] cartridge;

    private final int[] ram;

    private final Battery battery;

    private int selectedRamBank;

    private int selectedRomBank = 1;

    private boolean ramWriteEnabled;

    private boolean ramUpdated;

    public Mbc5(Rom rom, Battery battery) {
        this.cartridge = rom.getRom();
        this.romBanks = rom.getRomBanks();
        this.ramBanks = rom.getRamBanks();
        this.ram = new int[0x2000 * Math.max(this.ramBanks, 1)];
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
            ramWriteEnabled = (value & 0b1010) != 0;
        } else if (address >= 0x2000 && address < 0x3000) {
            selectedRomBank = (selectedRomBank & 0x100) | value;
        } else if (address >= 0x3000 && address < 0x4000) {
            selectedRomBank = (selectedRomBank & 0x0ff) | ((value & 1) << 8);
        } else if (address >= 0x4000 && address < 0x6000) {
            int bank = value & 0x0f;
            if (bank < ramBanks) {
                selectedRamBank = bank;
            }
        } else if (address >= 0xa000 && address < 0xc000 && ramWriteEnabled) {
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
            return getRomByte(0, address);
        } else if (address >= 0x4000 && address < 0x8000) {
            return getRomByte(selectedRomBank % romBanks, address - 0x4000);
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

    @Override
    public void flushRam() {
        if (ramUpdated) {
            battery.saveRam(ram);
            battery.flush();
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
        return selectedRamBank * 0x2000 + (address - 0xa000);
    }


    @Override
    public Memento<MemoryController> saveToMemento() {
        return new Mbc5Memento(battery.saveToMemento(), ram.clone(), selectedRamBank, selectedRomBank, ramWriteEnabled, ramUpdated);
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (!(memento instanceof Mbc5Memento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        if (this.ram.length != mem.ram.length) {
            throw new IllegalArgumentException("Memento ram length doesn't match");
        }
        battery.restoreFromMemento(mem.batteryMemento);
        System.arraycopy(mem.ram, 0, this.ram, 0, this.ram.length);
        this.selectedRamBank = mem.selectedRamBank;
        this.selectedRomBank = mem.selectedRomBank;
        this.ramWriteEnabled = mem.ramWriteEnabled;
        this.ramUpdated = mem.ramUpdated;
    }

    private record Mbc5Memento(Memento<Battery> batteryMemento, int[] ram, int selectedRamBank, int selectedRomBank,
                               boolean ramWriteEnabled, boolean ramUpdated) implements Memento<MemoryController> {
    }
}
