package eu.rekawek.coffeegb.android;

/** Receives immutable {@link RuntimeState} snapshots on Android's main thread. */
@FunctionalInterface
public interface RuntimeObserver {

    void onStateChanged(RuntimeState state);
}
