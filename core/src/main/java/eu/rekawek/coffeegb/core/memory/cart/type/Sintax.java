package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;

/**
 * The Sintax unlicensed mapper used by Crash II Advance and other bootlegs. Its base is
 * MBC5, with a programmable ROM-bank bit permutation and four XOR values selected by
 * the low two bits of the unpermuted bank number.
 */
public class Sintax implements MemoryController {

    private static final int[] IDENTITY = {0, 1, 2, 3, 4, 5, 6, 7};

    private static final int[][] BANK_REORDERING = {
            {2, 1, 4, 3, 6, 5, 0, 7},
            {3, 2, 5, 4, 7, 6, 1, 0},
            IDENTITY,
            IDENTITY,
            IDENTITY,
            {4, 5, 2, 3, 0, 1, 6, 7},
            IDENTITY,
            {6, 7, 4, 5, 1, 3, 0, 2},
            IDENTITY,
            {7, 6, 1, 0, 3, 2, 5, 4},
            IDENTITY,
            {5, 4, 7, 6, 1, 0, 3, 2},
            IDENTITY,
            {2, 3, 4, 5, 6, 7, 0, 1},
            IDENTITY,
            IDENTITY
    };

    private final Mbc5 delegate;

    private final int[] xorValues = new int[4];

    private int mode;

    private int bankNo;

    private int romBankXor;

    public Sintax(Rom rom, Battery battery) {
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
        if ((address & 0xf0f0) == 0x5010) {
            mode = value & 0x0f;
            selectBank();
            return;
        }
        if (address >= 0x2000 && address < 0x3000) {
            bankNo = value;
            selectBank();
            return;
        }
        if (address >= 0x7000 && address < 0x8000) {
            int xorIndex = (address & 0x00f0) >> 4;
            if (xorIndex >= 2 && xorIndex <= 5) {
                xorValues[xorIndex - 2] = value;
                romBankXor = xorValues[bankNo & 0x03];
            }
        }
        delegate.setByte(address, value);
    }

    private void selectBank() {
        delegate.setByte(0x2000, reorderBits(bankNo, BANK_REORDERING[mode]));
        romBankXor = xorValues[bankNo & 0x03];
    }

    @Override
    public int getByte(int address) {
        int value = delegate.getByte(address);
        return address >= 0x4000 && address < 0x8000 ? value ^ romBankXor : value;
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
        return new SintaxMemento(delegate.saveToMemento(), xorValues.clone(), mode, bankNo,
                romBankXor);
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (!(memento instanceof SintaxMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        if (mem.xorValues.length != xorValues.length) {
            throw new IllegalArgumentException("Memento XOR values length doesn't match");
        }
        delegate.restoreFromMemento(mem.delegateMemento);
        System.arraycopy(mem.xorValues, 0, xorValues, 0, xorValues.length);
        mode = mem.mode;
        bankNo = mem.bankNo;
        romBankXor = mem.romBankXor;
    }

    private record SintaxMemento(Memento<MemoryController> delegateMemento, int[] xorValues,
                                 int mode, int bankNo, int romBankXor)
            implements Memento<MemoryController> {
    }
}
