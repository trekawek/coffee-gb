package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;

import java.util.Arrays;

/**
 * Hudson HuC-1 mapper (used by e.g. Chousoku Spinner). MBC1-like banking without the mode
 * register; writing 0x0E to 0x0000-0x1FFF selects the infrared mode instead of the cart RAM
 * (reads return 0xC0/0xC1 depending on the received IR light; without a link partner no
 * light is ever seen).
 */
public class Huc1 implements MemoryController {

    private final int[] cartridge;

    private final int[] ram;

    private final int romBanks;

    private final int ramBanks;

    private final Battery battery;

    private int romBank = 1;

    private int ramBank;

    private boolean irMode;

    private boolean ramUpdated;

    public Huc1(Rom rom, Battery battery) {
        this.cartridge = rom.getRom();
        this.romBanks = rom.getRomBanks();
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
            irMode = (value & 0x0f) == 0x0e;
        } else if (address >= 0x2000 && address < 0x4000) {
            romBank = value & 0b00111111;
        } else if (address >= 0x4000 && address < 0x6000) {
            ramBank = value & 0b111;
        } else if (address >= 0xa000 && address < 0xc000) {
            if (irMode) {
                // IR transmitter; nothing to do without a link partner
            } else {
                ram[getRamAddress(address)] = value;
                ramUpdated = true;
            }
        }
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x0000 && address < 0x4000) {
            return getRomByte(0, address);
        } else if (address >= 0x4000 && address < 0x8000) {
            return getRomByte(romBank % romBanks, address - 0x4000);
        } else if (address >= 0xa000 && address < 0xc000) {
            if (irMode) {
                return 0xc0; // no IR light seen
            }
            return ram[getRamAddress(address)];
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

    private int getRamAddress(int address) {
        return (ramBank % ramBanks) * 0x2000 + (address - 0xa000);
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
    public int getRamByte(int bank, int offset) {
        int index = bank * 0x2000 + offset;
        return bank >= 0 && offset >= 0 && offset < 0x2000 && index < ram.length ? ram[index] : -1;
    }

    @Override
    public Memento<MemoryController> saveToMemento() {
        return new Huc1Memento(battery.saveToMemento(), ram.clone(), romBank, ramBank, irMode, ramUpdated);
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (!(memento instanceof Huc1Memento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        if (this.ram.length != mem.ram.length) {
            throw new IllegalArgumentException("Memento ram length doesn't match");
        }
        battery.restoreFromMemento(mem.batteryMemento);
        System.arraycopy(mem.ram, 0, this.ram, 0, this.ram.length);
        this.romBank = mem.romBank;
        this.ramBank = mem.ramBank;
        this.irMode = mem.irMode;
        this.ramUpdated = mem.ramUpdated;
    }

    private record Huc1Memento(Memento<Battery> batteryMemento, int[] ram, int romBank, int ramBank,
                               boolean irMode, boolean ramUpdated) implements Memento<MemoryController> {
    }
}
