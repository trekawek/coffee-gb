package eu.rekawek.coffeegb.core.debug;

/** Platform-neutral rendering phase exposed by {@link DebugPpuState}. */
public enum DebugPpuMode {
    DISABLED,
    HBLANK,
    VBLANK,
    OAM_SEARCH,
    PIXEL_TRANSFER
}
