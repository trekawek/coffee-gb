package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public interface MemoryController extends AddressSpace, Serializable, Originator<MemoryController> {
    default void flushRam() {
    }
}
