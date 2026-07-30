package eu.rekawek.coffeegb.core.debug;

import java.util.Objects;

/**
 * Detached cartridge mapper selection state. A bank of {@code -1} or a feature value of
 * {@link DebugFeatureState#UNKNOWN} means that the mapper does not expose that value through the
 * safe debug view.
 */
public record DebugMapperState(
        String mapperId,
        int romBank,
        int ramBank,
        DebugFeatureState ramEnabled,
        DebugFeatureState rtcSelected,
        DebugFeatureState rumbleEnabled) {

    public DebugMapperState {
        Objects.requireNonNull(mapperId, "mapperId");
        Objects.requireNonNull(ramEnabled, "ramEnabled");
        Objects.requireNonNull(rtcSelected, "rtcSelected");
        Objects.requireNonNull(rumbleEnabled, "rumbleEnabled");
        if (mapperId.isBlank()) {
            throw new IllegalArgumentException("Mapper ID must not be blank");
        }
        if (romBank < -1) {
            throw new IllegalArgumentException("ROM bank must be non-negative or -1: " + romBank);
        }
        if (ramBank < -1) {
            throw new IllegalArgumentException("RAM bank must be non-negative or -1: " + ramBank);
        }
    }
}
