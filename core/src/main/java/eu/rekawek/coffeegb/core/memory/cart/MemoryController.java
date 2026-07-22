package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public interface MemoryController extends AddressSpace, Serializable, Originator<MemoryController> {
    default void tick() {
    }

    default void setClockPaused(boolean paused) {
    }

    default void flushRam() {
    }

    default void init(EventBus eventBus) {
    }

    /**
     * Reads a physical external-RAM bank without changing mapper registers. A negative value
     * means that the bank is unavailable. This is intentionally read-only and is used by
     * debugger-style integrations such as RetroAchievements.
     */
    default int getRamByte(int bank, int offset) {
        return -1;
    }
}
