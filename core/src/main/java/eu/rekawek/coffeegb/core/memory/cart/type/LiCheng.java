package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;

/**
 * Li Cheng's MBC5-compatible mapper. Its low ROM-bank register only decodes addresses
 * through 0x2100; writes to the rest of the usual MBC5 low-bank window are ignored.
 */
public class LiCheng implements MemoryController {

    private final Mbc5 delegate;

    public LiCheng(Rom rom, Battery battery) {
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
        if (address > 0x2100 && address < 0x3000) {
            return;
        }
        delegate.setByte(address, value);
    }

    @Override
    public int getByte(int address) {
        return delegate.getByte(address);
    }

    @Override
    public void flushRam() {
        delegate.flushRam();
    }

    @Override
    public ComponentState<MemoryController> captureState() {
        return delegate.captureState();
    }

    @Override
    public ComponentState<MemoryController> captureState(MachineStateCapture capture) {
        return delegate.captureState(capture);
    }

    @Override
    public void declareMachineStatePayloads(MachineStateCapture capture) {
        delegate.declareMachineStatePayloads(capture);
    }

    @Override
    public void restoreState(ComponentState<MemoryController> state) {
        delegate.restoreState(state);
    }
}
