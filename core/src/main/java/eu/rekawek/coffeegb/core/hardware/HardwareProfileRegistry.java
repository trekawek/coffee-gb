package eu.rekawek.coffeegb.core.hardware;

import eu.rekawek.coffeegb.core.GameboyType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Authoritative immutable registry of permanent built-in profile IDs. */
public final class HardwareProfileRegistry {

    private static final HardwareCapabilities DMG_CAPABILITIES =
            new HardwareCapabilities(false, false, false, false, false, false, true);

    private static final HardwareCapabilities CGB_CAPABILITIES =
            new HardwareCapabilities(true, true, true, true, false, false, true);

    private static final HardwareCapabilities SGB_CAPABILITIES =
            new HardwareCapabilities(false, false, false, false, true, true, true);

    private static final BootSpec DMG_BOOT = monochromeBoot("dmg", 0x01b0);

    private static final BootSpec MGB_BOOT = monochromeBoot("mgb", 0xffb0);

    private static final BootSpec CGB_BOOT =
            new BootSpec("cgb", 10, 0xb644, 0x1180, 0x0000, 0xff56, 0x000d, 12);

    private static final BootSpec CGB0_BOOT =
            new BootSpec("cgb", 536, 0xb644, 0x1180, 0x0000, 0xff56, 0x000d, 0);

    private static final BootSpec SGB_BOOT =
            new BootSpec("sgb", 4, 0xabcc, 0x0100, 0x0014, 0x0000, 0xc060, 0);

    private static final BootSpec SGB2_BOOT =
            new BootSpec("sgb2", 4, 0xabcc, 0xff00, 0x0014, 0x0000, 0xc060, 0);

    public static final HardwareProfile DMG =
            new HardwareProfile("dmg", "Game Boy (DMG)", HardwareProfile.Family.DMG, "dmg",
                    DMG_CAPABILITIES, ClockSpec.LEGACY, DMG_BOOT);

    public static final HardwareProfile CGB =
            new HardwareProfile("cgb", "Game Boy Color (CGB)", HardwareProfile.Family.CGB, "cgb",
                    CGB_CAPABILITIES, ClockSpec.LEGACY, CGB_BOOT);

    public static final HardwareProfile CGB0 =
            new HardwareProfile("cgb0", "Game Boy Color (CGB revision 0)",
                    HardwareProfile.Family.CGB, "cgb0", CGB_CAPABILITIES,
                    ClockSpec.LEGACY, CGB0_BOOT);

    public static final HardwareProfile SGB =
            new HardwareProfile("sgb", "Super Game Boy", HardwareProfile.Family.SGB, "sgb",
                    SGB_CAPABILITIES, ClockSpec.SGB, SGB_BOOT);

    public static final HardwareProfile SGB2 =
            new HardwareProfile("sgb2", "Super Game Boy 2", HardwareProfile.Family.SGB, "sgb2",
                    SGB_CAPABILITIES, ClockSpec.SGB2, SGB2_BOOT);

    public static final HardwareProfile MGB =
            new HardwareProfile("mgb", "Game Boy Pocket (MGB)", HardwareProfile.Family.DMG, "mgb",
                    DMG_CAPABILITIES, ClockSpec.LEGACY, MGB_BOOT);

    private static final List<HardwareProfile> SUPPORTED = List.of(DMG, CGB, CGB0, SGB, SGB2, MGB);

    private static final Map<String, HardwareProfile> BY_ID;

    private static final Map<String, HardwareProfile> LEGACY_ALIASES;

    static {
        Map<String, HardwareProfile> profiles = new LinkedHashMap<>();
        for (HardwareProfile profile : SUPPORTED) {
            if (profiles.put(profile.id(), profile) != null) {
                throw new ExceptionInInitializerError("Duplicate hardware profile " + profile.id());
            }
        }
        BY_ID = Map.copyOf(profiles);
        LEGACY_ALIASES = Map.of(
                "DMG", DMG,
                "CGB", CGB,
                "CGB0", CGB0,
                "SGB", SGB);
    }

    private HardwareProfileRegistry() {
    }

    /** Canonical IDs only; aliases are deliberately not accepted on portable identity paths. */
    public static HardwareProfile resolve(String id) {
        HardwareProfile result = id == null ? null : BY_ID.get(id);
        if (result == null) {
            throw unknown(id);
        }
        return result;
    }

    /** Finite compatibility parser for historical persisted GameboyType names. */
    public static HardwareProfile resolveSetting(String value) {
        HardwareProfile canonical = value == null ? null : BY_ID.get(value);
        if (canonical != null) {
            return canonical;
        }
        HardwareProfile alias = value == null ? null : LEGACY_ALIASES.get(value);
        if (alias != null) {
            return alias;
        }
        throw unknown(value);
    }

    public static HardwareProfile fromGameboyType(GameboyType type) {
        return switch (Objects.requireNonNull(type, "type")) {
            case DMG -> DMG;
            case CGB -> CGB;
            case SGB -> SGB;
        };
    }

    public static GameboyType toGameboyType(HardwareProfile profile) {
        requireRegistered(profile);
        return switch (profile.family()) {
            case DMG -> GameboyType.DMG;
            case CGB -> GameboyType.CGB;
            case SGB -> GameboyType.SGB;
        };
    }

    public static HardwareProfile cgbRevision(boolean revisionZero) {
        return revisionZero ? CGB0 : CGB;
    }

    public static HardwareProfile requireRegistered(HardwareProfile profile) {
        Objects.requireNonNull(profile, "profile");
        HardwareProfile registered = BY_ID.get(profile.id());
        if (registered != profile) {
            throw new IllegalArgumentException(
                    "Hardware profile '" + profile.id() + "' is not the registered built-in instance; supported: "
                            + supportedIds());
        }
        return profile;
    }

    public static List<HardwareProfile> supportedProfiles() {
        return SUPPORTED;
    }

    public static List<String> supportedIds() {
        return SUPPORTED.stream().map(HardwareProfile::id).collect(Collectors.toUnmodifiableList());
    }

    /** Shared evidenced monochrome policy; MGB differs only in boot-ROM identity and A. */
    private static BootSpec monochromeBoot(String bootRomId, int postBootAf) {
        return new BootSpec(bootRomId, 4, 0xabcc, postBootAf, 0x0013, 0x00d8, 0x014d, 0);
    }

    private static IllegalArgumentException unknown(String id) {
        return new IllegalArgumentException(
                "Unknown hardware profile '" + id + "'; supported profiles: " + supportedIds());
    }
}
