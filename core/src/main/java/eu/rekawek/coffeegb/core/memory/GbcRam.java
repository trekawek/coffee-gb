package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public class GbcRam implements AddressSpace, Serializable, Originator<GbcRam> {

    public static final int SVBK = 0xff70;

    private final int[] ram = new int[7 * 0x1000];

    private int svbk;

    private eu.rekawek.coffeegb.core.cpu.SpeedMode speedMode;

    public void setSpeedMode(eu.rekawek.coffeegb.core.cpu.SpeedMode speedMode) {
        this.speedMode = speedMode;
    }

    private boolean isDmgCompat() {
        return speedMode != null && speedMode.isDmgCompat();
    }

    /** Power-on garbage for the banked WRAM (see Mmu). */
    public void fillWithGarbage(java.util.Random garbage) {
        for (int i = 0; i < ram.length; i++) {
            ram[i] = garbage.nextInt(0x100);
        }
    }

    @Override
    public boolean accepts(int address) {
        return address == SVBK || (address >= 0xd000 && address < 0xe000);
    }

    @Override
    public void setByte(int address, int value) {
        if (address == SVBK) {
            if (!isDmgCompat()) {
                this.svbk = value;
            }
        } else {
            ram[translate(address)] = value;
        }
    }

    @Override
    public int getByte(int address) {
        if (address == SVBK) {
            // reads FF on a CGB in DMG compatibility mode (boot_hwio-C)
            return isDmgCompat() ? 0xff : 0xf8 | (svbk & 0x07);
        } else {
            return ram[translate(address)];
        }
    }

    private int translate(int address) {
        int ramBank = svbk & 0x7;
        if (ramBank == 0) {
            ramBank = 1;
        }
        int result = address - 0xd000 + (ramBank - 1) * 0x1000;
        if (result < 0 || result >= ram.length) {
            throw new IllegalArgumentException();
        }
        return result;
    }

    /** Reads a physical WRAM bank without changing SVBK (used by debugger-style clients). */
    public int getBankByte(int bank, int offset) {
        if (bank < 1 || bank > 7 || offset < 0 || offset >= 0x1000) {
            return -1;
        }
        return ram[(bank - 1) * 0x1000 + offset];
    }

    @Override
    public Memento<GbcRam> saveToMemento() {
        return new GbcRamMemento(ram.clone(), svbk);
    }

    @Override
    public void restoreFromMemento(Memento<GbcRam> memento) {
        if (!(memento instanceof GbcRamMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        if (this.ram.length != mem.ram.length) {
            throw new IllegalArgumentException("Memento ram length doesn't match");
        }
        System.arraycopy(mem.ram, 0, this.ram, 0, this.ram.length);
        this.svbk = mem.svbk;
    }

    public record GbcRamMemento(int[] ram, int svbk) implements Memento<GbcRam> {
    }

}
