package eu.rekawek.coffeegb.core.memento;

public interface Originator<T> {
    Memento<T> saveToMemento();

    /**
     * Builds the transient, safe-point-only view used by in-process machine snapshots.
     *
     * <p>The default is valid only for originators whose memento contains no primitive arrays.
     * Array owners override it and register borrowed payloads through {@code capture}; the
     * snapshot consumer rejects unregistered primitive arrays.
     */
    default Memento<T> saveToMemento(MachineStateCapture capture) {
        return saveToMemento();
    }

    void restoreFromMemento(Memento<T> memento);
}
