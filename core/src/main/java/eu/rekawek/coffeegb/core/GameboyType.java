package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;

/**
 * @deprecated Use the stable-ID {@link HardwareProfile} registry. This coarse enum remains as a
 * source and protocol-v8 compatibility adapter; CGB revision 0 maps to {@link #CGB}.
 */
@Deprecated
public enum GameboyType {
    DMG, CGB, SGB;

    /** @deprecated Resolve/store a HardwareProfile instead. */
    @Deprecated
    public HardwareProfile toHardwareProfile() {
        return HardwareProfileRegistry.fromGameboyType(this);
    }

    /** @deprecated Use HardwareProfile directly. */
    @Deprecated
    public static GameboyType fromHardwareProfile(HardwareProfile profile) {
        return HardwareProfileRegistry.toGameboyType(profile);
    }
}
