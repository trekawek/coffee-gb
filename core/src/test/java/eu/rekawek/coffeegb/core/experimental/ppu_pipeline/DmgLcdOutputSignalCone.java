package eu.rekawek.coffeegb.core.experimental.ppu_pipeline;

/**
 * Detached DMG LCD-interface hypothesis.
 *
 * <p>The fetcher hands this cone immutable raw background/object tokens. Three ordinary
 * scanout registers carry each token to the LCD interface. Only there do live LCDC enable
 * wires select background versus object and live BGP/OBP latches map the selected two-bit
 * index to a shade. Register writes therefore affect tokens already in flight without
 * rewriting, replaying, or otherwise reaching back into the scanout registers.
 *
 * <p>The DMG palette registers are represented as transparent write latches with an
 * asymmetric close: on the write dot the output cone sees {@code old | data}; at commit it
 * sees {@code data}. This is the digital envelope of the old and new latch nodes briefly
 * driving the palette mux together. LCDC.0 has the same set-before-reset envelope. LCDC.1
 * normally closes one dot later, while a separate asynchronous clear wire can suppress the
 * object mux immediately. That wire is driven by object-fetch control, not inferred here.
 *
 * <p>The first raw token opens the panel clock. Its palettes are captured on that opening
 * edge and its LCDC mux is evaluated on the next dot. Later tokens use both inputs on their
 * output edge. The behavior follows from one local clock-start latch; no raster position or
 * special first-X branch exists.
 *
 * <p>The signal boundary is also visible in the offline DMG-CPU-B gate netlist: LCDC.0
 * ({@code ff40_d0}) gates the two background pixel bits through {@code rajy}/{@code tade};
 * the BGP latch outputs feed the {@code nelo}/{@code nura} palette muxes; and only the final
 * {@code pero}/{@code paty} sums drive the two LCD data pads. No palette signal feeds back
 * into the pixel shift registers.
 */
final class DmgLcdOutputSignalCone {

    static final int RAW_TO_LCD_DOTS = 3;

    static final int OUTSIDE_CGB = 1;

    static final int OUTSIDE_LCD_DISABLE_WITH_TOKENS_IN_FLIGHT = 1 << 1;

    static final int OUTSIDE_SUB_DOT_ANALOG_PAD_WAVEFORM = 1 << 2;

    private static final int OUTPUT_CAPACITY = 512;

    private final RawPixel[] scanout = new RawPixel[RAW_TO_LCD_DOTS];

    private final boolean[] scanoutValid = new boolean[RAW_TO_LCD_DOTS];

    private final PaletteLatch bgp;

    private final PaletteLatch obp0;

    private final PaletteLatch obp1;

    private final LcdcEnableLatch lcdc;

    private final OutputPixel[] output = new OutputPixel[OUTPUT_CAPACITY];

    private RawPixel drivenRaw;

    private boolean rawDrive;

    /** Set by the first token reaching the consumer; this is the panel-clock start latch. */
    private boolean panelClockRunning;

    /** Consumer-local token and palette latches loaded by the panel-clock opening edge. */
    private RawPixel openingToken;

    private int openingBgp;

    private int openingObp0;

    private int openingObp1;

    private int dot;

    private int outputSize;

    DmgLcdOutputSignalCone(int bgp, int obp0, int obp1, int lcdc) {
        this.bgp = new PaletteLatch(bgp);
        this.obp0 = new PaletteLatch(obp0);
        this.obp1 = new PaletteLatch(obp1);
        this.lcdc = new LcdcEnableLatch(lcdc);
    }

    static int incompleteBehaviorMask() {
        return OUTSIDE_CGB
                | OUTSIDE_LCD_DISABLE_WITH_TOKENS_IN_FLIGHT
                | OUTSIDE_SUB_DOT_ANALOG_PAD_WAVEFORM;
    }

    /** Drives at most one immutable raw token onto the first scanout register this dot. */
    void driveRaw(RawPixel raw) {
        if (rawDrive) {
            throw new IllegalStateException("raw scanout input already driven this dot");
        }
        drivenRaw = java.util.Objects.requireNonNull(raw);
        rawDrive = true;
    }

    void writeBgp(int value) {
        bgp.driveWrite(value);
    }

    void writeObp0(int value) {
        obp0.driveWrite(value);
    }

    void writeObp1(int value) {
        obp1.driveWrite(value);
    }

    /** Drives the two enable bits of an FF40 write; CPU readback changes immediately. */
    void writeLcdcEnables(int value) {
        lcdc.driveWrite(value);
    }

    /**
     * Drives the independent asynchronous reset found on the object-enable output path.
     * The fetch-control cone decides when this wire is asserted.
     */
    void driveObjectEnableClear() {
        lcdc.driveObjectClear();
    }

    /** Advances drive, capture, and commit once, without revisiting an earlier stage. */
    void tick() {
        int liveBgp = bgp.panelValue();
        int liveObp0 = obp0.panelValue();
        int liveObp1 = obp1.panelValue();
        boolean liveBgEnable = lcdc.panelBackgroundEnable();
        boolean liveObjEnable = lcdc.panelObjectEnable();

        // The opening token's palette latches are already closed, but LCDC still enters the
        // final mux live. A following token can reach the ordinary output edge on this dot too.
        if (openingToken != null) {
            emit(openingToken, openingBgp, openingObp0, openingObp1,
                    liveBgEnable, liveObjEnable);
            openingToken = null;
        }

        int last = RAW_TO_LCD_DOTS - 1;
        if (scanoutValid[last]) {
            RawPixel raw = scanout[last];
            if (!panelClockRunning) {
                panelClockRunning = true;
                openingToken = raw;
                openingBgp = liveBgp;
                openingObp0 = liveObp0;
                openingObp1 = liveObp1;
            } else {
                emit(raw, liveBgp, liveObp0, liveObp1,
                        liveBgEnable, liveObjEnable);
            }
        }

        for (int stage = last; stage > 0; stage--) {
            scanout[stage] = scanout[stage - 1];
            scanoutValid[stage] = scanoutValid[stage - 1];
        }
        scanout[0] = drivenRaw;
        scanoutValid[0] = rawDrive;
        drivenRaw = null;
        rawDrive = false;

        bgp.commit();
        obp0.commit();
        obp1.commit();
        lcdc.commit();
        dot++;
    }

    /**
     * Refuses to invent an LCD-off behavior for already launched tokens. A later experiment
     * needs the LCD pin clocks and panel reset cone, rather than a token-repair branch here.
     */
    void disableLcd() {
        if (openingToken != null || rawDrive || hasScanoutToken()) {
            throw new UnsupportedOperationException(
                    "LCD disable with tokens in flight needs the panel-clock reset cone");
        }
    }

    private boolean hasScanoutToken() {
        for (boolean valid : scanoutValid) {
            if (valid) {
                return true;
            }
        }
        return false;
    }

    private void emit(RawPixel token, int bgp, int obp0, int obp1,
                      boolean bgEnable, boolean objEnable) {
        int background = bgEnable ? token.background() : 0;
        boolean objectSelected = token.object() != 0
                && objEnable
                && !(token.objectBehindBackground() && background != 0);
        int raw = objectSelected ? token.object() : background;
        int palette = objectSelected ? (token.objectPalette1() ? obp1 : obp0) : bgp;
        int shade = (palette >>> (raw * 2)) & 3;
        if (outputSize == output.length) {
            throw new IllegalStateException("LCD output capture overflow");
        }
        output[outputSize] = new OutputPixel(
                outputSize, dot, token, objectSelected, raw, palette, shade);
        outputSize++;
    }

    int dot() {
        return dot;
    }

    int cpuBgp() {
        return bgp.cpuValue();
    }

    int cpuObp0() {
        return obp0.cpuValue();
    }

    int cpuObp1() {
        return obp1.cpuValue();
    }

    int cpuLcdcEnables() {
        return lcdc.cpuValue();
    }

    int panelBgp() {
        return bgp.panelValue();
    }

    boolean panelBackgroundEnable() {
        return lcdc.panelBackgroundEnable();
    }

    boolean panelObjectEnable() {
        return lcdc.panelObjectEnable();
    }

    boolean panelClockRunning() {
        return panelClockRunning;
    }

    boolean openingTokenPending() {
        return openingToken != null;
    }

    int outputSize() {
        return outputSize;
    }

    OutputPixel output(int index) {
        if (index < 0 || index >= outputSize) {
            throw new IllegalArgumentException("LCD output not emitted: " + index);
        }
        return output[index];
    }

    /** Raw PPU data. Records are immutable by construction and never replaced in flight. */
    record RawPixel(int background, int object, boolean objectPalette1,
                    boolean objectBehindBackground) {

        RawPixel {
            if (background < 0 || background > 3 || object < 0 || object > 3) {
                throw new IllegalArgumentException("raw color index must be in 0..3");
            }
        }
    }

    record OutputPixel(int x, int dot, RawPixel source, boolean objectSelected,
                       int raw, int palette, int shade) {
    }

    /** A transparent byte latch whose set path reaches the mux one delta before reset. */
    private static final class PaletteLatch {

        private int stored;

        private int cpu;

        private boolean write;

        private int writeData;

        private PaletteLatch(int initial) {
            stored = byteValue(initial);
            cpu = stored;
        }

        private void driveWrite(int value) {
            if (write) {
                throw new IllegalStateException("palette latch already written this dot");
            }
            writeData = byteValue(value);
            cpu = writeData;
            write = true;
        }

        private int panelValue() {
            return write ? stored | writeData : stored;
        }

        private int cpuValue() {
            return cpu;
        }

        private void commit() {
            if (write) {
                stored = writeData;
                write = false;
            }
        }
    }

    /** Two FF40 output paths: set-before-reset BG and independently clearable OBJ. */
    private static final class LcdcEnableLatch {

        private int stored;

        private int cpu;

        private boolean write;

        private int writeData;

        private boolean objectClear;

        private LcdcEnableLatch(int initial) {
            stored = enableBits(initial);
            cpu = stored;
        }

        private void driveWrite(int value) {
            if (write) {
                throw new IllegalStateException("LCDC latch already written this dot");
            }
            writeData = enableBits(value);
            cpu = writeData;
            write = true;
        }

        private void driveObjectClear() {
            objectClear = true;
        }

        private boolean panelBackgroundEnable() {
            return ((write ? stored | writeData : stored) & 1) != 0;
        }

        private boolean panelObjectEnable() {
            return !objectClear && (stored & 2) != 0;
        }

        private int cpuValue() {
            return cpu;
        }

        private void commit() {
            if (write) {
                stored = writeData;
                write = false;
            }
            objectClear = false;
        }
    }

    private static int byteValue(int value) {
        if ((value & ~0xff) != 0) {
            throw new IllegalArgumentException("byte value");
        }
        return value;
    }

    private static int enableBits(int value) {
        return byteValue(value) & 3;
    }
}
