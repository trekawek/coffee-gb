package eu.rekawek.coffeegb.core.memory;

/** Supplies a borrowed ROM mapping only when every bypassed address-space layer is inert. */
public interface PerformanceRomAccessProvider {

    /**
     * Acquires an allocation-free ROM mapping for one bounded PERFORMANCE transaction.
     *
     * @return a borrowed mapping, or {@code null} when ordinary bus dispatch remains authoritative
     */
    PerformanceRomAccess acquirePerformanceRomAccess();
}
