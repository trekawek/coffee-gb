package eu.rekawek.coffeegb.android;

/** Receives immutable {@link RuntimeState} snapshots on Android's main thread. */
@FunctionalInterface
public interface RuntimeObserver {

    void onStateChanged(RuntimeState state);

    /**
     * Receives a short-lived host message for a successful current-session state operation.
     * Implementations may ignore it when their surface is not attached or visible.
     */
    default void onTransientMessage(String message) {
        // Optional host feedback; state snapshots remain the required observer contract.
    }
}
