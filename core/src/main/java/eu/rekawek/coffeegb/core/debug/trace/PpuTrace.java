package eu.rekawek.coffeegb.core.debug.trace;

import eu.rekawek.coffeegb.core.debug.DebugPpuMode;

import java.util.Objects;

/**
 * A PPU mode, scanline, or completed physical-frame boundary.
 *
 * <p>{@code ppuFrame} counts physical PPU frame-ready events. It is deliberately not the desktop
 * controller frame lattice used by {@code DebugSnapshot.frame}; it does not advance while an
 * LCD-off PPU produces no frame-ready boundary.
 */
public record PpuTrace(Kind kind, long ppuFrame, int line, int dot, DebugPpuMode mode)
        implements TraceEvent {

    public enum Kind {
        MODE_CHANGED,
        SCANLINE_STARTED,
        FRAME_READY
    }

    public PpuTrace {
        Objects.requireNonNull(kind, "kind");
        TraceChecks.nonNegative("ppuFrame", ppuFrame);
        TraceChecks.range("line", line, 0, 153);
        TraceChecks.range("dot", dot, 0, 455);
        Objects.requireNonNull(mode, "mode");
    }

    @Override
    public TraceCategory category() {
        return TraceCategory.PPU;
    }
}
