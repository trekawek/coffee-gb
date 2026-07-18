package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;

/**
 * The unlicensed BBD mapper used by Garou/Fatal Fury bootlegs. It is MBC5-compatible,
 * with additional registers that permute ROM-bank bits and data bits in the switchable
 * window. The game programs both permutations before reading its protected code.
 */
public class Bbd implements MemoryController {

    private static final int[] IDENTITY = {0, 1, 2, 3, 4, 5, 6, 7};

    private static final int[][] DATA_REORDERING = {
            IDENTITY,
            IDENTITY,
            IDENTITY,
            IDENTITY,
            {0, 5, 1, 3, 4, 2, 6, 7},
            {0, 4, 2, 3, 1, 5, 6, 7},
            IDENTITY,
            {0, 1, 5, 3, 4, 2, 6, 7}
    };

    private static final int[][] BANK_REORDERING = {
            IDENTITY,
            IDENTITY,
            IDENTITY,
            {3, 4, 2, 0, 1, 5, 6, 7},
            IDENTITY,
            {1, 2, 3, 4, 0, 5, 6, 7},
            IDENTITY,
            IDENTITY
    };

    private final Mbc5 delegate;

    private int dataSwapMode;

    private int bankSwapMode;

    public Bbd(Rom rom, Battery battery) {
        delegate = new Mbc5(rom, battery);
    }

    @Override
    public void init(EventBus eventBus) {
        delegate.init(eventBus);
    }

    @Override
    public boolean accepts(int address) {
        return delegate.accepts(address);
    }

    @Override
    public void setByte(int address, int value) {
        switch (address & 0xf0ff) {
            case 0x2000 -> value = reorderBits(value, BANK_REORDERING[bankSwapMode]);
            case 0x2001 -> dataSwapMode = value & 0x07;
            case 0x2080 -> bankSwapMode = value & 0x07;
            default -> {
            }
        }
        delegate.setByte(address, value);
    }

    @Override
    public int getByte(int address) {
        int value = delegate.getByte(address);
        if (address >= 0x4000 && address < 0x8000) {
            return reorderBits(value, DATA_REORDERING[dataSwapMode]);
        }
        return value;
    }

    private static int reorderBits(int value, int[] reorder) {
        int result = 0;
        for (int newBit = 0; newBit < 8; newBit++) {
            result |= ((value >> reorder[newBit]) & 1) << newBit;
        }
        return result;
    }

    @Override
    public void flushRam() {
        delegate.flushRam();
    }

    @Override
    public Memento<MemoryController> saveToMemento() {
        return new BbdMemento(delegate.saveToMemento(), dataSwapMode, bankSwapMode);
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (!(memento instanceof BbdMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        delegate.restoreFromMemento(mem.delegateMemento);
        dataSwapMode = mem.dataSwapMode;
        bankSwapMode = mem.bankSwapMode;
    }

    private record BbdMemento(Memento<MemoryController> delegateMemento, int dataSwapMode,
                              int bankSwapMode) implements Memento<MemoryController> {
    }
}
