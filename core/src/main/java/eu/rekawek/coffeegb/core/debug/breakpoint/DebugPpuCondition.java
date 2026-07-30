package eu.rekawek.coffeegb.core.debug.breakpoint;

import eu.rekawek.coffeegb.core.debug.DebugPpuMode;

/**
 * PPU-state predicate combining any non-empty subset of frame, LY, and PPU mode.
 *
 * <p>{@link #ANY_FRAME} and {@link #ANY_LY} are explicit wildcards. A {@code null} mode is a
 * mode wildcard. LY is the physical scanline index and is limited to 0..153.
 */
public record DebugPpuCondition(long frame, int ly, DebugPpuMode mode)
        implements DebugBreakpointCondition {

    public static final long ANY_FRAME = -1;

    public static final int ANY_LY = -1;

    public DebugPpuCondition {
        if (frame < ANY_FRAME) {
            throw new IllegalArgumentException(
                    "frame must be non-negative or ANY_FRAME: " + frame);
        }
        DebugBreakpointChecks.range("ly", ly, ANY_LY, 153);
        if (frame == ANY_FRAME && ly == ANY_LY && mode == null) {
            throw new IllegalArgumentException(
                    "A PPU condition must constrain frame, LY, or mode");
        }
    }

    public static DebugPpuCondition atFrame(long frame) {
        return new DebugPpuCondition(frame, ANY_LY, null);
    }

    public static DebugPpuCondition atLy(int ly) {
        return new DebugPpuCondition(ANY_FRAME, ly, null);
    }

    public static DebugPpuCondition inMode(DebugPpuMode mode) {
        if (mode == null) {
            throw new NullPointerException("mode");
        }
        return new DebugPpuCondition(ANY_FRAME, ANY_LY, mode);
    }

    public static DebugPpuCondition at(long frame, int ly, DebugPpuMode mode) {
        if (mode == null) {
            throw new NullPointerException("mode");
        }
        return new DebugPpuCondition(frame, ly, mode);
    }

    public boolean constrainsFrame() {
        return frame != ANY_FRAME;
    }

    public boolean constrainsLy() {
        return ly != ANY_LY;
    }

    public boolean constrainsMode() {
        return mode != null;
    }

    @Override
    public DebugBreakpointKind kind() {
        return DebugBreakpointKind.PPU_STATE;
    }
}
