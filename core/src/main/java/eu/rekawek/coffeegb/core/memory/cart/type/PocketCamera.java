package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;

/**
 * The Pocket Camera cartridge (type 0xFC): MBC5-like ROM banking, 128 KB of banked RAM,
 * and the camera's register file mapped in place of RAM when RAM-bank bit 4 is set.
 * There is no real sensor here: a capture completes instantly (the busy bit always reads
 * 0) and the sensor image area reads back as a flat mid-gray, which keeps the software
 * fully navigable.
 */
public class PocketCamera implements MemoryController {

    private final int[] rom;

    private final int[] ram;

    private final Battery battery;

    private final int[] cameraRegisters = new int[0x36];

    private int romBank = 1;

    private int ramBank;

    private boolean ramEnabled;

    private boolean cameraMapped;

    public PocketCamera(Rom rom, Battery battery) {
        this.rom = rom.getRom();
        this.ram = new int[0x20000];
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
            ramEnabled = (value & 0x0f) == 0x0a;
        } else if (address < 0x4000) {
            romBank = value & 0x3f;
        } else if (address < 0x6000) {
            ramBank = value & 0x0f;
            cameraMapped = (value & 0x10) != 0;
        } else if (address >= 0xa000 && address < 0xc000) {
            if (cameraMapped) {
                int reg = address & 0x7f;
                if (reg < cameraRegisters.length) {
                    // the shoot bit completes instantly - never store the busy flag
                    cameraRegisters[reg] = reg == 0 ? (value & 0x06) : value;
                }
            } else if (ramEnabled) {
                ram[(ramBank * 0x2000 + (address - 0xa000)) % ram.length] = value;
            }
        }
    }

    @Override
    public int getByte(int address) {
        if (address < 0x4000) {
            return rom[address % rom.length];
        } else if (address < 0x8000) {
            int bank = romBank % Math.max(1, rom.length / 0x4000);
            return rom[bank * 0x4000 + (address - 0x4000)];
        } else if (address >= 0xa000 && address < 0xc000) {
            if (cameraMapped) {
                // only register 0 is readable; the rest read as 0
                return (address & 0x7f) == 0 ? cameraRegisters[0] : 0;
            }
            if (ramEnabled) {
                return ram[(ramBank * 0x2000 + (address - 0xa000)) % ram.length];
            }
            return 0xff;
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
        return new PocketCameraMemento(
                ram.clone(), cameraRegisters.clone(), romBank, ramBank, ramEnabled, cameraMapped);
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (!(memento instanceof PocketCameraMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        System.arraycopy(mem.ram, 0, ram, 0, ram.length);
        System.arraycopy(mem.cameraRegisters, 0, cameraRegisters, 0, cameraRegisters.length);
        this.romBank = mem.romBank;
        this.ramBank = mem.ramBank;
        this.ramEnabled = mem.ramEnabled;
        this.cameraMapped = mem.cameraMapped;
    }

    private record PocketCameraMemento(int[] ram, int[] cameraRegisters, int romBank, int ramBank,
                                       boolean ramEnabled, boolean cameraMapped)
            implements Memento<MemoryController> {
    }
}
