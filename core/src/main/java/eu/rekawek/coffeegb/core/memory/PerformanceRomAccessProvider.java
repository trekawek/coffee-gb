package eu.rekawek.coffeegb.core.memory;

/** Supplies a borrowed ROM reader only when every bypassed address-space layer is inert. */
public interface PerformanceRomAccessProvider {

    /**
     * Acquires an allocation-free ROM reader for one bounded PERFORMANCE transaction.
     *
     * @return a borrowed reader, or {@code null} when ordinary bus dispatch remains authoritative
     */
    PerformanceRomAccess acquirePerformanceRomAccess();
}
