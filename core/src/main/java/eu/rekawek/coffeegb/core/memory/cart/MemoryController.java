package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

public interface MemoryController extends AddressSpace, StatefulComponent<MemoryController> {
    default void tick() {
    }

    default void setClockPaused(boolean paused) {
    }

    /** Applies mapper state normally reached while the console boot ROM reads the cartridge. */
    default void skipBoot() {
    }

    default void flushRam() {
    }

    default void init(EventBus eventBus) {
    }
}
