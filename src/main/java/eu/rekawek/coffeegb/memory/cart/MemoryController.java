package eu.rekawek.coffeegb.memory.cart;

import eu.rekawek.coffeegb.AddressSpace;

import java.io.Serializable;

public interface MemoryController extends AddressSpace, Serializable {
    default void flushRam() {}
}
