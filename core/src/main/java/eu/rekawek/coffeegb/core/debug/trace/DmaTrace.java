package eu.rekawek.coffeegb.core.debug.trace;

import java.util.Objects;

/** Progress of an OAM, general VRAM, or HBlank VRAM DMA transfer. */
public record DmaTrace(
        Engine engine,
        Kind kind,
        int sourceAddress,
        int destinationAddress,
        int length,
        int bytesTransferred) implements TraceEvent {

    public enum Engine {
        OAM,
        VRAM_GENERAL,
        VRAM_HBLANK
    }

    public enum Kind {
        STARTED,
        BYTE_TRANSFERRED,
        COMPLETED,
        CANCELLED
    }

    public DmaTrace {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(kind, "kind");
        TraceChecks.range("sourceAddress", sourceAddress, 0, 0xffff);
        TraceChecks.range("destinationAddress", destinationAddress, 0, 0xffff);
        TraceChecks.range("length", length, 1, 0x10000);
        TraceChecks.range("bytesTransferred", bytesTransferred, 0, length);
    }

    @Override
    public TraceCategory category() {
        return TraceCategory.DMA;
    }
}
