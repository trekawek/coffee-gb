package eu.rekawek.coffeegb.core.state;

/** Explicit internal capture/restore contract for one live emulator component. */
public interface StatefulComponent<T> {

    ComponentState<T> captureState();

    /**
     * Declares dominant live primitive payloads for incremental in-process snapshot capture.
     * Implementations recurse only through owned machine-state children and do not allocate a
     * state graph.
     */
    default void declareMachineStatePayloads(MachineStateCapture capture) {}

    /**
     * Builds the transient safe-point view consumed by {@code MachineSnapshot}. Array owners
     * register borrowed payloads through {@code capture}; unregistered arrays are rejected.
     */
    default ComponentState<T> captureState(MachineStateCapture capture) {
        return captureState();
    }

    void restoreState(ComponentState<T> state);
}
