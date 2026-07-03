package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;

import java.util.Arrays;

/**
 * The Datel Action Replay / GameShark cartridge mapper (reverse-engineered from the
 * Action Replay Online / Action Replay Xtreme ROMs, issue #66).
 *
 * <p>The ASIC maps two independent 16 KB windows through registers at the top of the
 * ROM space; banks are numbered from 1:
 *
 * <ul>
 *   <li>{@code 0x7FE0} - bank of the 0x0000-0x3FFF window ({@code value-1});
 *       the boot code writes 1 to keep bank 0 mapped.</li>
 *   <li>{@code 0x7FE1} - bank of the 0x4000-0x7FFF window; values 1-8 select a ROM
 *       bank ({@code value-1}), the special value 9 maps the battery-backed SRAM
 *       (cheat-code storage) into the window, readable and writable.</li>
 *   <li>{@code 0x7FE2}-{@code 0x7FE7} - control registers of the (unemulated) link
 *       hardware; the values are stored and read back.</li>
 * </ul>
 *
 * <p>The register file itself wins over the mapped window content for reads of
 * 0x7FE0-0x7FE7 only when the SRAM is not mapped there; the software always accesses
 * the registers with a ROM bank in the window.
 */
public class Datel implements MemoryController {

    private final int[] rom;

    private final int romBanks;

    // 16 KB of battery-backed SRAM, mapped into the switchable window by bank 9
    private final int[] ram = new int[0x4000];

    // the ASIC shadows CPU writes to the top of the window (0x7800-0x7FDF) even while a
    // ROM bank is mapped: the software builds its cheat/game lists there and executes
    // code from the shadow. A byte reads back from the shadow only once written; the
    // untouched bytes keep serving the mapped ROM.
    private final boolean[] shadowWritten = new boolean[0x800];

    private final Battery battery;

    private final int[] regs = new int[8];

    public Datel(Rom rom, Battery battery) {
        this.rom = rom.getRom();
        this.romBanks = Math.max(1, this.rom.length / 0x4000);
        this.battery = battery;
        Arrays.fill(ram, 0xff);
        battery.loadRam(ram);
        regs[0] = 1; // identity mapping at power-on
        regs[1] = 2;
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000) || (address >= 0xa000 && address < 0xc000);
    }

    private boolean isRamMapped() {
        return regs[1] == 9;
    }

    private int lowBank() {
        return (Math.max(1, regs[0]) - 1) % romBanks;
    }

    private int highBank() {
        return (Math.max(1, regs[1]) - 1) % romBanks;
    }


    @Override
    public void setByte(int address, int value) {
        if (address >= 0x7fe0 && address <= 0x7fe7) {
            regs[address - 0x7fe0] = value & 0xff;
        } else if (address >= 0x4000 && address < 0x8000 && isRamMapped()) {
            ram[address - 0x4000] = value & 0xff;
        } else if (address >= 0x7800 && address < 0x8000) {
            ram[address - 0x4000] = value & 0xff;
            shadowWritten[address - 0x7800] = true;
        }
        // other ROM-space writes hit mask ROM and are ignored
    }

    @Override
    public int getByte(int address) {
        if (address < 0x4000) {
            return rom[lowBank() * 0x4000 + address];
        } else if (address < 0x8000) {
            if (address >= 0x7fe0 && address <= 0x7fe7) {
                return regs[address - 0x7fe0];
            }
            if (isRamMapped() || (address >= 0x7800 && shadowWritten[address - 0x7800])) {
                return ram[address - 0x4000];
            }
            int offset = highBank() * 0x4000 + (address - 0x4000);
            return offset < rom.length ? rom[offset] : 0xff;
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
        return new DatelMemento(ram.clone(), regs.clone(), shadowWritten.clone());
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (!(memento instanceof DatelMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        System.arraycopy(mem.ram, 0, ram, 0, ram.length);
        System.arraycopy(mem.regs, 0, regs, 0, regs.length);
        System.arraycopy(mem.shadowWritten, 0, shadowWritten, 0, shadowWritten.length);
    }

    private record DatelMemento(int[] ram, int[] regs, boolean[] shadowWritten) implements Memento<MemoryController> {
    }
}
