package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;

/**
 * The unlicensed HiTek mapper used by Gaoke/Ruanxin cartridges. It is MBC5-compatible,
 * with power-on data and bank permutations that the game reprograms through BBD-like
 * protection registers.
 */
public class Hitek implements MemoryController {

    private static final int[] IDENTITY = {0, 1, 2, 3, 4, 5, 6, 7};

    private static final int[][] DATA_REORDERING = {
            IDENTITY,
            {0, 6, 5, 3, 4, 1, 2, 7},
            {0, 5, 6, 3, 4, 2, 1, 7},
            {0, 6, 2, 3, 4, 5, 1, 7},
            {0, 6, 1, 3, 4, 5, 2, 7},
            {0, 1, 6, 3, 4, 5, 2, 7},
            {0, 2, 6, 3, 4, 1, 5, 7},
            {0, 6, 2, 3, 4, 1, 5, 7}
    };

    private static final int[][] BANK_REORDERING = {
            IDENTITY,
            {3, 2, 1, 0, 4, 5, 6, 7},
            {2, 1, 0, 3, 4, 5, 6, 7},
            {1, 0, 3, 2, 4, 5, 6, 7},
            {0, 3, 2, 1, 4, 5, 6, 7},
            {2, 3, 0, 1, 4, 5, 6, 7},
            {3, 0, 1, 2, 4, 5, 6, 7},
            {2, 0, 3, 1, 4, 5, 6, 7}
    };

    private final Mbc5 delegate;

    private int dataSwapMode = 7;

    private int bankSwapMode = 7;

    public Hitek(Rom rom, Battery battery) {
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
        if (address >= 0x3000 && address < 0x4000) {
            return;
        }
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
    public boolean isRumbleActive() {
        return delegate.isRumbleActive();
    }

    @Override
    public void setDebugHooks(DebugHooks hooks) {
        delegate.setDebugHooks(hooks);
    }

    @Override
    public ComponentState<MemoryController> captureState() {
        return new HitekState(delegate.captureState(), dataSwapMode, bankSwapMode);
    }

    @Override
    public ComponentState<MemoryController> captureState(MachineStateCapture capture) {
        return new HitekState(
                delegate.captureState(capture), dataSwapMode, bankSwapMode);
    }

    @Override
    public void declareMachineStatePayloads(MachineStateCapture capture) {
        delegate.declareMachineStatePayloads(capture);
    }

    @Override
    public void restoreState(ComponentState<MemoryController> state) {
        if (!(state instanceof HitekState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        delegate.restoreState(mem.delegateMemento);
        dataSwapMode = mem.dataSwapMode;
        bankSwapMode = mem.bankSwapMode;
    }

    private record HitekState(ComponentState<MemoryController> delegateMemento, int dataSwapMode,
                              int bankSwapMode) implements ComponentState<MemoryController> {
    }
}
