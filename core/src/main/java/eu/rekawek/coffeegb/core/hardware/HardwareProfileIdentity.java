package eu.rekawek.coffeegb.core.hardware;

import java.util.Objects;

/** Small service-free identity seam reserved for state diagnostics and future replay metadata. */
public record HardwareProfileIdentity(String profileId, ClockSpec clockSpec) {
    public HardwareProfileIdentity {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(clockSpec, "clockSpec");
        if (!profileId.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("Profile ID must be lowercase ASCII: " + profileId);
        }
    }
}
