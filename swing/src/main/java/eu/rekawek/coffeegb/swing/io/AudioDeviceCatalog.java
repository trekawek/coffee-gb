package eu.rekawek.coffeegb.swing.io;

import java.util.List;
import java.util.Objects;

/**
 * Synchronous device catalog intended to be called by a cancellable background worker.
 *
 * <p>No Swing component is touched here. Each call returns an immutable snapshot and reflects
 * devices available at that moment.
 */
public final class AudioDeviceCatalog {

    private final AudioBackend backend;

    public AudioDeviceCatalog() {
        this(new JavaSoundAudioBackend());
    }

    AudioDeviceCatalog(AudioBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    public List<AudioDeviceSnapshot> snapshot() {
        return List.copyOf(backend.devices());
    }
}
