package eu.rekawek.coffeegb.core.memory;

/**
 * Borrowed ROM reader for one bounded, owner-thread PERFORMANCE transaction.
 *
 * <p>An access may expose either of two tiers. Immutable mapper backings can return physical
 * offsets and read those bytes directly. More complex mappers may instead override
 * {@link #readCpuByte(int)} to preserve transformed data, mutable flash, pass-through routing, or
 * read-side effects while still bypassing the inert outer bus layers.</p>
 *
 * <p>The provider may reuse or mutate the returned object on the next acquisition. Callers must
 * neither retain it beyond their transaction nor assume that a later acquisition has the same
 * mapping.</p>
 */
public interface PerformanceRomAccess {

    /**
     * Returns an offset in the mapper's immutable ROM backing, or {@code -1} when the address is
     * unmapped or this access supplies only authoritative logical CPU reads.
     */
    int physicalOffset(int cpuAddress);

    /**
     * Reads one immutable mapper-backing byte. Callers use only non-negative offsets returned by
     * {@link #physicalOffset(int)}; implementations return the cartridge open value past EOF.
     */
    int readPhysicalByte(int physicalOffset);

    /**
     * Reads one CPU ROM byte, returning {@code -1} only when the full bus must remain authoritative.
     * Logical mapper-direct accesses override this method when no physical offset exists.
     */
    default int readCpuByte(int cpuAddress) {
        int offset = physicalOffset(cpuAddress);
        return offset < 0 ? -1 : readPhysicalByte(offset);
    }

    /**
     * Whether the current cartridge data window is plain RAM or an inert disabled/open view.
     * The CPU snapshots this proof once per epoch and still performs each access through the
     * real bus. Mapper-control writes end the epoch; RTC, EEPROM and other device windows must
     * retain the default false. This grants no permission to execute cartridge RAM.
     */
    default boolean canAccessRam() {
        return false;
    }

    /**
     * Optional side-effect-free lookahead within this lease. A logical mapper must explicitly
     * prove this capability; ordinary authoritative reads alone do not permit speculation.
     */
    default int peekCpuByte(int cpuAddress) {
        int offset = physicalOffset(cpuAddress);
        return offset < 0 ? -1 : readPhysicalByte(offset);
    }
}
