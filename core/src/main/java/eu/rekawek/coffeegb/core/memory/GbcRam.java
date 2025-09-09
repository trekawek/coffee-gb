package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public class GbcRam implements AddressSpace, Serializable, Originator<GbcRam> {

    private final int[] ram = new int[7 * 0x1000];

    private int svbk;

    @Override
    public boolean accepts(int address) {
        return address == 0xff70 || (address >= 0xd000 && address < 0xe000);
    }

    @Override
    public void setByte(int address, int value) {
        if (address == 0xff70) {
            this.svbk = value;
        } else {
            ram[translate(address)] = value;
        }
    }

    @Override
    public int getByte(int address) {
        if (address == 0xff70) {
            return svbk;
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
