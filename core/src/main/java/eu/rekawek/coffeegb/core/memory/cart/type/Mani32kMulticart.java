package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;

/**
 * The Mani "TETRIS SET" style 4-in-1 multicart (issue #74): a menu program in the first
 * 32 KB followed by unmodified 32 KB games, each with its own header. The menu selects a
 * game by writing its 32 KB block number to 0x4000-0x5FFF; the register drives the upper
 * ROM address lines directly, so the whole 0x0000-0x7FFF window switches to the chosen
 * block and the plain-ROM game runs unmapped. Power-on maps block 0 (the menu).
 */
public class Mani32kMulticart implements MemoryController {

    private final int[] rom;

    private final int blocks;

    private int block;

    public Mani32kMulticart(Rom rom) {
        this.rom = rom.getRom();
        this.blocks = Math.max(1, this.rom.length / 0x8000);
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000) || (address >= 0xa000 && address < 0xc000);
    }

    @Override
    public void setByte(int address, int value) {
        if (address >= 0x4000 && address < 0x6000) {
            block = value % blocks;
        }
    }

    @Override
    public int getByte(int address) {
        if (address < 0x8000) {
            return rom[block * 0x8000 + address];
        }
        return 0xff;
    }

    @Override
    public Memento<MemoryController> saveToMemento() {
        return new Mani32kMemento(block);
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (!(memento instanceof Mani32kMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.block = mem.block;
    }

    private record Mani32kMemento(int block) implements Memento<MemoryController> {
    }
}
