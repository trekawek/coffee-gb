package eu.rekawek.coffeegb.core.memento;

import java.io.Serializable;

/**
 * Data-only compatibility marker for the bounded local 1.7.13/1.7.14 snapshot importer.
 *
 * <p>Live emulator components neither implement nor invoke this interface. It remains at its
 * historical binary name solely so ObjectInputStream can materialize the explicitly allowlisted
 * legacy record graph before conversion to detached state.
 */
public interface Memento<T> extends Serializable {}
