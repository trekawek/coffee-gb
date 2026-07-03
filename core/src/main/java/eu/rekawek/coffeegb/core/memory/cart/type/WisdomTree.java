package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;

/**
 * The Wisdom Tree mapper (unlicensed): the whole 0x0000-0x7FFF window is one switchable
 * 32 KB bank. A write anywhere in the ROM space selects the bank, with the bank number
 * taken from the write's address (the value is ignored). Carts report cartridge type 0
 * ("ROM only") but exceed 32 KB.
 */
public class WisdomTree implements MemoryController {

    private final int[] rom;

    private final int bankMask;

    private int bank;

    public WisdomTree(Rom rom) {
        this.rom = rom.getRom();
        this.bankMask = Math.max(1, this.rom.length / 0x8000) - 1;
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000) || (address >= 0xa000 && address < 0xc000);
    }

    @Override
    public void setByte(int address, int value) {
        if (address >= 0x0000 && address < 0x8000) {
            bank = address & bankMask;
        }
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x0000 && address < 0x8000) {
            int offset = bank * 0x8000 + address;
            return offset < rom.length ? rom[offset] : 0xff;
        }
        return 0xff;
    }

    @Override
    public Memento<MemoryController> saveToMemento() {
        return new WisdomTreeMemento(bank);
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (!(memento instanceof WisdomTreeMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.bank = mem.bank;
    }

    private record WisdomTreeMemento(int bank) implements Memento<MemoryController> {
    }
}
