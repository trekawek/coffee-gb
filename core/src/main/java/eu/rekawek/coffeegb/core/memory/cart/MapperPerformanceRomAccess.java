package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.memory.PerformanceRomAccess;
import java.util.Objects;

/**
 * Exact logical ROM reader used when a mapper cannot expose one immutable physical mapping.
 *
 * <p>This preserves the mapper's own transformations, overlays, mutable flash, pass-through
 * routing, and read-side effects. The PERFORMANCE entrance proof has already established that the
 * cartridge's outer bus layers are inert for the bounded transaction.</p>
 */
final class MapperPerformanceRomAccess implements PerformanceRomAccess {

    private final MemoryController mapper;

    MapperPerformanceRomAccess(MemoryController mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public int physicalOffset(int cpuAddress) {
        return -1;
    }

    @Override
    public int readPhysicalByte(int physicalOffset) {
        return 0xff;
    }

    @Override
    public int readCpuByte(int cpuAddress) {
        return cpuAddress >= 0 && cpuAddress < 0x8000
                ? mapper.getByte(cpuAddress)
                : -1;
    }
}
