package eu.rekawek.coffeegb.memory.cart;

import eu.rekawek.coffeegb.AddressSpace;

public interface MemoryController extends AddressSpace {
    default void flushRam() {}
}
