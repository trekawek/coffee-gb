package eu.rekawek.coffeegb.core.hardware;

import java.util.Objects;

/** Immutable behavior identity resolved before construction of every Gameboy session. */
public final class HardwareProfile {

    public enum Family {
        DMG,
        CGB,
        SGB
    }

    private final String id;

    private final String displayName;

    private final Family family;

    private final String revision;

    private final HardwareCapabilities capabilities;

    private final ClockSpec clockSpec;

    private final BootSpec bootSpec;

    public HardwareProfile(String id, String displayName, Family family, String revision,
                           HardwareCapabilities capabilities, ClockSpec clockSpec,
                           BootSpec bootSpec) {
        this.id = requireStableId(id, "profile");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("Profile display name cannot be blank");
        }
        this.family = Objects.requireNonNull(family, "family");
        this.revision = requireStableId(revision, "revision");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.clockSpec = Objects.requireNonNull(clockSpec, "clockSpec");
        this.bootSpec = Objects.requireNonNull(bootSpec, "bootSpec");
        if ((family == Family.CGB) != capabilities.cgbMode()) {
            throw new IllegalArgumentException("CGB family and CGB capability disagree");
        }
        if ((family == Family.SGB) != capabilities.superGameboyCommands()) {
            throw new IllegalArgumentException("SGB family and SGB capability disagree");
        }
        String expectedBootRom = family.name().toLowerCase();
        if (!expectedBootRom.equals(bootSpec.bootRomId())) {
            throw new IllegalArgumentException(
                    family + " family requires the " + expectedBootRom + " boot ROM, not "
                            + bootSpec.bootRomId());
        }
        if (family != Family.CGB && bootSpec.cgbBootHandoffTicks() != 0) {
            throw new IllegalArgumentException("Only a CGB profile may define CGB boot handoff ticks");
        }
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public Family family() {
        return family;
    }

    public String revision() {
        return revision;
    }

    public HardwareCapabilities capabilities() {
        return capabilities;
    }

    public ClockSpec clockSpec() {
        return clockSpec;
    }

    public BootSpec bootSpec() {
        return bootSpec;
    }

    public HardwareProfileIdentity identity() {
        return new HardwareProfileIdentity(id, clockSpec);
    }

    @Override
    public String toString() {
        return id + " (" + displayName + ")";
    }

    private static String requireStableId(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!value.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException(label + " ID must be lowercase ASCII: " + value);
        }
        return value;
    }
}
