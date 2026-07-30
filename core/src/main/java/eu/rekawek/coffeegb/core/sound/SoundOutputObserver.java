package eu.rekawek.coffeegb.core.sound;

/**
 * Transient owner-thread observation of the exact stereo sample produced by one master tick.
 *
 * <p>The observer is deliberately absent from machine state. A caller must attach it only after
 * setup or restore has completed and detach it before handing the machine to another owner.
 */
@FunctionalInterface
public interface SoundOutputObserver {

    void onSample(int left, int right);
}
