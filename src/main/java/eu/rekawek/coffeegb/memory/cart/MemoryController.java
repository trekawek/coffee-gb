package eu.rekawek.coffeegb.memory.cart;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.memento.Originator;

import java.io.Serializable;

public interface MemoryController extends AddressSpace, Serializable, Originator<MemoryController> {
    default void flushRam() {
    }
}
