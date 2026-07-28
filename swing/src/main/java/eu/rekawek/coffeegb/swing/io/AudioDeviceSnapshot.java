package eu.rekawek.coffeegb.swing.io;

import java.util.Objects;

/**
 * Immutable, persistence-safe description of a host audio output.
 *
 * <p>The system default is intentionally represented by a symbolic ID instead of the mixer that
 * happens to be default while enumeration runs. Explicit Java Sound devices use a digest of their
 * stable descriptor fields; no platform path or device name needs to be persisted.
 */
public record AudioDeviceSnapshot(String stableId, String displayName, boolean systemDefault) {

    public static final String SYSTEM_DEFAULT_ID = "default";

    public AudioDeviceSnapshot {
        Objects.requireNonNull(stableId, "stableId");
        Objects.requireNonNull(displayName, "displayName");
        if (systemDefault) {
            if (!SYSTEM_DEFAULT_ID.equals(stableId)) {
                throw new IllegalArgumentException(
                        "The system-default audio device must use ID " + SYSTEM_DEFAULT_ID);
            }
        } else if (!AudioRuntimeConfiguration.isExplicitDeviceId(stableId)) {
            throw new IllegalArgumentException(
                    "Explicit audio device IDs must be java-sound- followed by 64 lowercase hex digits");
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("Audio device display name must not be blank");
        }
    }

    public static AudioDeviceSnapshot systemDefaultDevice() {
        return new AudioDeviceSnapshot(SYSTEM_DEFAULT_ID, "System Default", true);
    }
}
