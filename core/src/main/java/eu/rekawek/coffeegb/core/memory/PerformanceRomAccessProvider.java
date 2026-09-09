package eu.rekawek.coffeegb.core.memory;

/** Supplies a borrowed ROM reader only when every bypassed address-space layer is inert. */
public interface PerformanceRomAccessProvider {

    /**
     * Acquires an allocation-free ROM reader for one bounded PERFORMANCE transaction.
     *
     * @return a borrowed reader, or {@code null} when ordinary bus dispatch remains authoritative
     */
    PerformanceRomAccess acquirePerformanceRomAccess();

    /**
     * ROM reader for a native-x2 detailed-PPU packet whose CPU is restricted to ROM/HRAM.
     * DMA wrappers may admit their independently proven WRAM-source interior here without
     * weakening the ordinary lease's all-buses-idle requirement.
     */
    default PerformanceRomAccess acquirePerformanceDetailedPpuRomAccess() {
        return acquirePerformanceRomAccess();
    }

    /**
     * Optional live HRAM read view for one native-x2 detailed packet. Every intervening
     * wrapper must explicitly prove inert reads throughout the requested master-dot span;
     * unknown/observable wrappers retain canonical dispatch through the default null.
     */
    default PerformanceHramReadAccess acquirePerformanceDetailedPpuHramReadAccess(int requestedMasterTicks) {
        return null;
    }
}
