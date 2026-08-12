package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;

/**
 * Gowin's MBC1-compatible protection mapper. A write to its protection latch is exposed through
 * a four-bit combinational network in the otherwise absent external-RAM window. The response
 * function is an inferred model constrained by every known challenge, not a physical schematic.
 */
public class Gowin implements MemoryController {

    private static final int PROTECTION_WRITE = 0x6080;

    private static final int PROTECTION_READ = 0xa080;

    private final Mbc1 delegate;

    private int protectionResponse = 0xff;

    public Gowin(Rom rom, Battery battery) {
        delegate = new Mbc1(rom, battery);
    }

    @Override
    public boolean accepts(int address) {
        return delegate.accepts(address);
    }

    @Override
    public void setByte(int address, int value) {
        if (address == PROTECTION_WRITE) {
            protectionResponse = protectionResponse(value);
        }
        delegate.setByte(address, value);
    }

    @Override
    public int getByte(int address) {
        if (address == PROTECTION_READ) {
            return protectionResponse;
        }
        return delegate.getByte(address);
    }

    private static int protectionResponse(int value) {
        int bit0 = value & 1;
        int bit1 = (value >> 1) & 1;
        int bit2 = (value >> 2) & 1;
        int bit3 = (value >> 3) & 1;
        int bit5 = (value >> 5) & 1;

        int responseBit3 = 1 ^ bit5;
        int responseBit2 = (bit3 & bit2) | bit0;
        int responseBit1 = 1 ^ ((bit1 & bit5) | responseBit2);
        int responseBit0 = (1 ^ (bit3 | bit2)) | bit5;
        return 0xf0
                | responseBit0
                | (responseBit1 << 1)
                | (responseBit2 << 2)
                | (responseBit3 << 3);
    }

    @Override
    public void flushRam() {
        delegate.flushRam();
    }

    @Override
    public void setDebugHooks(DebugHooks hooks) {
        delegate.setDebugHooks(hooks);
    }

    @Override
    public ComponentState<MemoryController> captureState() {
        return new GowinState(delegate.captureState(), protectionResponse);
    }

    @Override
    public ComponentState<MemoryController> captureState(MachineStateCapture capture) {
        return new GowinState(delegate.captureState(capture), protectionResponse);
    }

    @Override
    public void declareMachineStatePayloads(MachineStateCapture capture) {
        delegate.declareMachineStatePayloads(capture);
    }

    @Override
    public void restoreState(ComponentState<MemoryController> state) {
        if (!(state instanceof GowinState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        delegate.restoreState(mem.delegateMemento);
        protectionResponse = mem.protectionResponse;
    }

    private record GowinState(ComponentState<MemoryController> delegateMemento,
                              int protectionResponse) implements ComponentState<MemoryController> {
    }
}
