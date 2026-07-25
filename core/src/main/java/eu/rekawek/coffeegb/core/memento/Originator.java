package eu.rekawek.coffeegb.core.memento;

public interface Originator<T> {
    Memento<T> saveToMemento();

    /**
     * Declares dominant live primitive payloads for the internal incremental machine capture.
     *
     * <p>Implementations recurse only through owned machine-state children and must not allocate or
     * build a memento. Smaller payloads may rely on token registration alone, but dominant memory
     * owners use this independent declaration so registering a helper-created copy is rejected.
     */
    default void declareMachineStatePayloads(MachineStateCapture capture) {}

    /**
     * Builds the transient, safe-point-only view used by in-process machine snapshots.
     *
     * <p>The default is valid only for originators whose memento contains no primitive arrays.
     * Array owners override it and register borrowed payloads through {@code capture}; the snapshot
     * consumer rejects unregistered primitive arrays.
     */
    default Memento<T> saveToMemento(MachineStateCapture capture) {
        return saveToMemento();
    }

    void restoreFromMemento(Memento<T> memento);
}
