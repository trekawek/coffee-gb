package eu.rekawek.coffeegb.core.debug;

/** Stable identifiers for memory views; availability is session-dependent. */
public enum DebugAddressSpace {
    SYSTEM_BUS,
    ROM,
    CARTRIDGE_RAM,
    VIDEO_RAM,
    WORK_RAM,
    OAM,
    IO_REGISTERS,
    HIGH_RAM
}
