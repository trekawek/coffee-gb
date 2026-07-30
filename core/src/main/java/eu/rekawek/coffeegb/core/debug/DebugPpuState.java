package eu.rekawek.coffeegb.core.debug;

import java.util.Objects;

/** Detached LCD controller and scan-position state. */
public record DebugPpuState(
        boolean lcdEnabled,
        DebugPpuMode mode,
        int line,
        int dot,
        int lcdc,
        int stat,
        int scy,
        int scx,
        int lyc,
        int wy,
        int wx) {

    public DebugPpuState {
        Objects.requireNonNull(mode, "mode");
        DebugValueChecks.range("line", line, 0, 153);
        DebugValueChecks.nonNegative("dot", dot);
        DebugValueChecks.unsignedByte("lcdc", lcdc);
        DebugValueChecks.unsignedByte("stat", stat);
        DebugValueChecks.unsignedByte("scy", scy);
        DebugValueChecks.unsignedByte("scx", scx);
        DebugValueChecks.unsignedByte("lyc", lyc);
        DebugValueChecks.unsignedByte("wy", wy);
        DebugValueChecks.unsignedByte("wx", wx);
        if (lcdEnabled == (mode == DebugPpuMode.DISABLED)) {
            throw new IllegalArgumentException(
                    "DISABLED is the PPU mode exactly when the LCD is disabled");
        }
    }
}
