package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.signal.ByteSignalDelayLine;

/**
 * A detached hypothesis for the DMG CPU-to-PPU register boundary.
 *
 * <p>A CPU write changes one source register. The timing/control plane observes that source
 * directly, while selected pixel consumers observe independent fixed-depth shift-register taps.
 * Every tap is clocked continuously, so rapid writes remain ordered without a pending-write
 * collection and without asking which renderer path happens to be active at write time.
 * The DMG netlist supports the single-source part directly: SCX is one eight-latch bank
 * ({@code daty/duzu/cyxu/gubo/bemy/cuzy/cabu/bake}) and WX is one bank
 * ({@code mypa/nofe/noke/meby/mypu/myce/muvo/nuku}). The delayed values here are therefore
 * receiver-boundary approximations, not a claim that the chip stores duplicate registers.
 *
 * <p>The five capture edges are the current Coffee boundary: four complete old-value dots lie
 * between the CPU source edge and the shifted pixel consumer, and the fifth edge publishes the
 * new value. A future half-dot scheduler should re-derive the source/capture phase before this is
 * considered production timing.
 */
final class DmgPpuRegisterFanout {

    static final int LCDC = 0xff40;

    static final int SCX = 0xff43;

    static final int WX = 0xff4b;

    static final int PIXEL_CAPTURE_EDGES = 5;

    private static final int LCD_ENABLE = 0x80;

    private static final int WINDOW_ENABLE = 0x20;

    private static final int FINE_SCX = 0x07;

    private int lcdcSource;

    private int scxSource;

    private int wxSource;

    private final ByteSignalDelayLine lcdcWindowTap;

    private final ByteSignalDelayLine scxFineTap;

    private final ByteSignalDelayLine wxPixelTap;

    DmgPpuRegisterFanout(int lcdc, int scx, int wx) {
        lcdcSource = byteValue(lcdc);
        scxSource = byteValue(scx);
        wxSource = byteValue(wx);
        lcdcWindowTap = new ByteSignalDelayLine(PIXEL_CAPTURE_EDGES, lcdcSource);
        scxFineTap = new ByteSignalDelayLine(PIXEL_CAPTURE_EDGES, scxSource);
        wxPixelTap = new ByteSignalDelayLine(PIXEL_CAPTURE_EDGES, wxSource);
    }

    /** Drives the CPU register write strobe. It has no mode, route, or raster input. */
    void cpuWrite(int address, int value) {
        value = byteValue(value);
        switch (address) {
            case LCDC -> writeLcdc(value);
            case SCX -> scxSource = value;
            case WX -> wxSource = value;
            default -> throw new IllegalArgumentException(
                    "unsupported PPU register " + Integer.toHexString(address));
        }
    }

    private void writeLcdc(int value) {
        boolean wasEnabled = (lcdcSource & LCD_ENABLE) != 0;
        lcdcSource = value;
        boolean enabled = (lcdcSource & LCD_ENABLE) != 0;

        // LCD reset opens/initializes the consumer latches. Re-enabling from that reset state
        // starts every path from the new source value; it does not replay pre-disable history.
        if (!enabled || !wasEnabled) {
            makePixelTapsTransparent();
        }
    }

    /** One PPU capture edge: all taps resolve from the same old vector, then commit together. */
    void clockPpu() {
        if ((lcdcSource & LCD_ENABLE) == 0) {
            makePixelTapsTransparent();
            return;
        }
        lcdcWindowTap.resolve(lcdcSource);
        scxFineTap.resolve(scxSource);
        wxPixelTap.resolve(wxSource);
        lcdcWindowTap.commit();
        scxFineTap.commit();
        wxPixelTap.commit();
    }

    int cpuRead(int address) {
        return switch (address) {
            case LCDC -> lcdcSource;
            case SCX -> scxSource;
            case WX -> wxSource;
            default -> throw new IllegalArgumentException(
                    "unsupported PPU register " + Integer.toHexString(address));
        };
    }

    int timingLcdc() {
        return lcdcSource;
    }

    int timingScx() {
        return scxSource;
    }

    int timingWx() {
        return wxSource;
    }

    /** LCDC.5 has its own pixel-domain tap; unrelated LCDC wires stay live. */
    int pixelLcdc() {
        return (lcdcSource & ~WINDOW_ENABLE)
                | (lcdcWindowTap.output() & WINDOW_ENABLE);
    }

    boolean pixelWindowEnabled() {
        return (pixelLcdc() & WINDOW_ENABLE) != 0;
    }

    /** Only the fine scroll counter crosses this tap; coarse tile addressing stays live. */
    int pixelScx() {
        return (scxSource & ~FINE_SCX) | (scxFineTap.output() & FINE_SCX);
    }

    int pixelWx() {
        return wxPixelTap.output();
    }

    State capture() {
        return new State(lcdcSource, scxSource, wxSource,
                lcdcWindowTap.state(), scxFineTap.state(), wxPixelTap.state());
    }

    void restore(State state) {
        lcdcSource = byteValue(state.lcdcSource());
        scxSource = byteValue(state.scxSource());
        wxSource = byteValue(state.wxSource());
        lcdcWindowTap.restore(state.lcdcWindowState());
        scxFineTap.restore(state.scxFineState());
        wxPixelTap.restore(state.wxPixelState());
    }

    private void makePixelTapsTransparent() {
        lcdcWindowTap.fill(lcdcSource);
        scxFineTap.fill(scxSource);
        wxPixelTap.fill(wxSource);
    }

    private static int byteValue(int value) {
        return value & 0xff;
    }

    record State(int lcdcSource, int scxSource, int wxSource,
                 long lcdcWindowState, long scxFineState, long wxPixelState) {
    }

}
