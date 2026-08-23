package eu.rekawek.coffeegb.core.memory;

/**
 * Borrowed, read-only view of one stable CPU ROM mapping for a bounded PERFORMANCE transaction.
 *
 * <p>The provider may reuse and mutate the returned object on the next acquisition. Callers must
 * therefore neither retain it beyond their transaction nor assume that a later acquisition has
 * the same bank mapping. ROM contents themselves are immutable.</p>
 */
public interface PerformanceRomAccess {

    /** Returns the immutable ROM-image offset for {@code cpuAddress}, or {@code -1}. */
    int physicalOffset(int cpuAddress);

    /** Reads one immutable ROM-image byte. Implementations return the cartridge open value past EOF. */
    int readPhysicalByte(int physicalOffset);

    /** Maps and reads one CPU ROM byte, returning {@code -1} when the address is not mapped. */
    default int readCpuByte(int cpuAddress) {
        int offset = physicalOffset(cpuAddress);
        return offset < 0 ? -1 : readPhysicalByte(offset);
    }
}
