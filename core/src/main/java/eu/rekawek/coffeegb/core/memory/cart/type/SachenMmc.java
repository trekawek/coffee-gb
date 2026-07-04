package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;

/**
 * The Sachen MMC1/MMC2 multicart mapper ("4B-xxx" 4-in-1 collections and the two-game
 * MMC2 carts, issues #73/#74/#75). Three registers control the mapping:
 *
 * <ul>
 *   <li>{@code 0x2000-0x3FFF} - ROM bank of the switchable 0x4000-0x7FFF window;</li>
 *   <li>{@code 0x4000-0x5FFF} - ROM bank mask: bits set in the mask come from the base
 *       register, the rest from the bank register (the multicart "cage");</li>
 *   <li>{@code 0x6000-0x7FFF} - base ROM bank, also mapped at 0x0000-0x3FFF.</li>
 * </ul>
 *
 * <p>The header-scrambling lockout of the real cart (which presents a fake logo to the
 * boot ROM) is not modelled; the FAST_FORWARD boot timeout falls back to the post-boot
 * presets instead. MMC2 carts boot with a base of 0x10, where their (CGB, half-size)
 * boot logo lives.
 */
public class SachenMmc implements MemoryController {

    private final int[] rom;

    private final int romBanks;

    private int bank = 1;

    private int mask;

    private int base;

    public SachenMmc(Rom rom, int initialBase) {
        this.rom = rom.getRom();
        this.romBanks = Math.max(2, this.rom.length / 0x4000);
        this.base = initialBase;
    }

    @Override
    public boolean accepts(int address) {
        return address >= 0x0000 && address < 0x8000;
    }

    @Override
    public void setByte(int address, int value) {
        if (address >= 0x2000 && address < 0x4000) {
            bank = value & 0xff;
        } else if (address >= 0x4000 && address < 0x6000) {
            mask = value & 0xff;
        } else if (address >= 0x6000 && address < 0x8000) {
            base = value & 0xff;
        }
    }

    @Override
    public int getByte(int address) {
        if (address < 0x4000) {
            return rom[(base % romBanks) * 0x4000 + address];
        } else if (address < 0x8000) {
            int b = bank == 0 ? 1 : bank;
            int effective = ((base & mask) | (b & ~mask)) % romBanks;
            return rom[effective * 0x4000 + (address - 0x4000)];
        }
        return 0xff;
    }

    @Override
    public void flushRam() {
    }

    @Override
    public Memento<MemoryController> saveToMemento() {
        return new SachenMemento(bank, mask, base);
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (!(memento instanceof SachenMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.bank = mem.bank;
        this.mask = mem.mask;
        this.base = mem.base;
    }

    private record SachenMemento(int bank, int mask, int base) implements Memento<MemoryController> {
    }
}
