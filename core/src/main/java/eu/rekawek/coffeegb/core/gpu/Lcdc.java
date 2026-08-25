package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

import java.util.Arrays;

import static com.google.common.base.Preconditions.checkArgument;

public class Lcdc implements AddressSpace, StatefulComponent<Lcdc> {

    private static final int CONFLICT_HISTORY_LENGTH = 8;

    // Keep the fixed-point proof O(1) on PERFORMANCE horizons. A write conservatively
    // reopens the history window; tickConflicts drains it one dot at a time.
    private static final int PERFORMANCE_FIXED_POINT_DRAIN = CONFLICT_HISTORY_LENGTH + 1;

    private boolean gbc;

    private final boolean mealybugDmgBlob;

    private int value = 0x91;

    // DMG write conflict (mealybug m3_lcdc_*_change): during the T-cycle in which a CPU
    // write lands, the LCD output stage sees the old value with the new BG_EN bit OR-ed
    // in; the full new value settles one T-cycle later. -1 = no write this tick.
    private int mixValue = -1;

    private int pendingMixValue = -1;

    // Shootout's Mealybug DMG-blob reference observes LCDC.0 one dot later than the
    // CPU B photo. Keep that narrow, measured timing difference separate from the
    // conflict mix used by the rest of LCDC.
    private boolean dmgBlobBackgroundEnable = true;

    private boolean pendingDmgBlobBackgroundEnable = true;

    // A CGB LCDC.4 write can collide with a background tile-data read. The write strobe
    // is sampled once into the fetcher's register-view history below; the history cells,
    // rather than a second duration counter, retain the pulse for delayed consumers.
    private boolean tileSelectGlitchWrite;

    private final boolean[] tileSelectGlitchHistory = new boolean[CONFLICT_HISTORY_LENGTH];

    // LCDC.2 reaches the OAM reader through its own clock-domain latch. Keep this
    // history running for the complete scanline, including HBlank, because writes at
    // the end of one line can still be pending when entry 0 is read on the next line.
    private final int[] oamSizeHistory = new int[CONFLICT_HISTORY_LENGTH];

    // Both histories advance together in tickConflicts(). Logical index 0 is the newest value;
    // this cursor maps it to the corresponding physical slot in the two circular buffers.
    private int historyHead;

    // PERFORMANCE-only eligibility metadata; the portable memento restores conservatively.
    private transient int performanceFixedPointDrain;

    // The transient machine-state path borrows primitive arrays synchronously. Linearize the
    // circular histories into these owner-thread scratch arrays so the state schema remains
    // newest-first without allocating on that path. The ordinary capture path clones them.
    private final boolean[] tileSelectGlitchHistoryCapture = new boolean[CONFLICT_HISTORY_LENGTH];

    private final int[] oamSizeHistoryCapture = new int[CONFLICT_HISTORY_LENGTH];

    public Lcdc() {
        this(false);
    }

    public Lcdc(boolean mealybugDmgBlob) {
        this.mealybugDmgBlob = mealybugDmgBlob;
        Arrays.fill(oamSizeHistory, value);
    }

    public boolean isBgAndWindowDisplay() {
        return (value & 0x01) != 0;
    }

    public boolean isObjDisplay() {
        return (value & 0x02) != 0;
    }

    /** BG/window enable as seen by the LCD output stage (with the write-conflict mix). */
    public boolean isBgAndWindowDisplayEffective() {
        if (!gbc && mealybugDmgBlob) {
            return dmgBlobBackgroundEnable;
        }
        return ((mixValue >= 0 ? mixValue : value) & 0x01) != 0;
    }

    /** Object enable as seen by the LCD output stage (with the write-conflict mix). */
    public boolean isObjDisplayEffective() {
        return ((mixValue >= 0 ? mixValue : value) & 0x02) != 0;
    }

    /** Whether an LCDC conflict or tile-select strobe is still waiting for a PPU tick. */
    boolean hasPendingConflictLatches() {
        return mixValue >= 0 || pendingMixValue >= 0 || tileSelectGlitchWrite;
    }

    /**
     * Whether every per-dot LCDC latch is already at the native-CGB mode-2 fixed point.
     *
     * <p>{@link #hasPendingConflictLatches()} intentionally ignores history that is still
     * visible to delayed consumers.  A coarse OAM scan must additionally prove that the
     * tile-select pulse has drained and every delayed LCDC.2 sample equals the live value.
     * Once this predicate holds, rotating the uniform circular histories is behaviorally a
     * no-op (the cursor itself is neither serialized nor otherwise observable).</p>
     */
    boolean isPerformanceMode2FixedPoint() {
        return gbc && isPerformanceQuietSpanFixedPoint();
    }

    /** Whether all delayed LCDC histories are uniform at the current value. */
    boolean isPerformanceQuietSpanFixedPoint() {
        return performanceFixedPointDrain == 0 && !hasPendingConflictLatches();
    }

    /** The fixed-point histories remain identical for an arbitrary positive quiet span. */
    void advancePerformanceMode2FixedPointSpanTrusted(int ticks) {
        if (ticks < 0 || !isPerformanceMode2FixedPoint()) {
            throw new IllegalStateException("LCDC is not at its PERFORMANCE mode-2 fixed point");
        }
    }

    /** Physical-DMG counterpart of the native-CGB fixed-point span commit. */
    void advancePerformancePhysicalDmgMode2FixedPointSpanTrusted(int ticks) {
        if (ticks < 0 || !isPerformanceQuietSpanFixedPoint()) {
            throw new IllegalStateException(
                    "LCDC is not at its physical-DMG PERFORMANCE mode-2 fixed point");
        }
    }

    /** Called once per GPU tick: the mix value lives for the single tick after the write. */
    void tickConflicts() {
        dmgBlobBackgroundEnable = pendingDmgBlobBackgroundEnable;
        pendingDmgBlobBackgroundEnable = (value & 0x01) != 0;
        mixValue = pendingMixValue;
        pendingMixValue = -1;
        historyHead = (historyHead - 1) & (CONFLICT_HISTORY_LENGTH - 1);
        tileSelectGlitchHistory[historyHead] = tileSelectGlitchWrite;
        tileSelectGlitchWrite = false;
        oamSizeHistory[historyHead] = value;
        if (performanceFixedPointDrain > 0) {
            performanceFixedPointDrain--;
        }
    }

    void triggerTileSelectGlitch() {
        performanceFixedPointDrain = PERFORMANCE_FIXED_POINT_DRAIN;
        tileSelectGlitchWrite = true;
    }

    public boolean isTileSelectGlitch() {
        return isTileSelectGlitch(0);
    }

    public boolean isTileSelectGlitch(int dotsAgo) {
        checkArgument(dotsAgo >= 0 && dotsAgo < tileSelectGlitchHistory.length);
        return tileSelectGlitchHistory[historyIndex(dotsAgo)];
    }

    public int getSpriteHeight() {
        return (value & 0x04) == 0 ? 8 : 16;
    }

    public int getOamSpriteHeight(int dotsAgo) {
        checkArgument(dotsAgo >= 0 && dotsAgo < oamSizeHistory.length);
        return (oamSizeHistory[historyIndex(dotsAgo)] & 0x04) == 0 ? 8 : 16;
    }

    public int getBgTileMapDisplay() {
        return (value & 0x08) == 0 ? 0x9800 : 0x9c00;
    }

    public int getBgWindowTileData() {
        return (value & 0x10) == 0 ? 0x9000 : 0x8000;
    }

    public boolean isBgWindowTileDataSigned() {
        return (value & 0x10) == 0;
    }

    public boolean isWindowDisplay() {
        return (value & 0x20) != 0;
    }

    public int getWindowTileMapDisplay() {
        return (value & 0x40) == 0 ? 0x9800 : 0x9c00;
    }

    public boolean isLcdEnabled() {
        return (value & 0x80) != 0;
    }

    @Override
    public boolean accepts(int address) {
        return address == 0xff40;
    }

    @Override
    public void setByte(int address, int value) {
        checkArgument(address == 0xff40);
        set(value);
    }

    @Override
    public int getByte(int address) {
        checkArgument(address == 0xff40);
        return value;
    }

    public void set(int value) {
        set(value, false);
    }

    /**
     * @param dropObjEnInMix DMG special case: when objects are being disabled while an
     *     object fetch is in progress or at position 0, the OBJ_EN bit turns off already
     *     in the conflict-mix T-cycle instead of one T-cycle later
     */
    public void set(int value, boolean dropObjEnInMix) {
        performanceFixedPointDrain = PERFORMANCE_FIXED_POINT_DRAIN;
        if (gbc) {
            // the CGB applies LCDC writes cleanly, without the DMG's conflict mix
            this.value = value;
            if (!isLcdEnabled()) {
                clearTileSelectGlitch();
            }
            return;
        }
        int mix = this.value | (value & 0x01);
        if (dropObjEnInMix) {
            mix &= ~0x02;
        }
        pendingMixValue = mix;
        this.value = value;
    }

    private void clearTileSelectGlitch() {
        tileSelectGlitchWrite = false;
        Arrays.fill(tileSelectGlitchHistory, false);
    }


    public void setGbc(boolean gbc) {
        this.gbc = gbc;
        performanceFixedPointDrain = PERFORMANCE_FIXED_POINT_DRAIN;
    }

    public int get() {
        return value;
    }

    public boolean isMealybugDmgBlob() {
        return !gbc && mealybugDmgBlob;
    }

    @Override
    public ComponentState<Lcdc> captureState() {
        copyHistoriesForCapture();
        return new LcdcState(value, mixValue, pendingMixValue,
                dmgBlobBackgroundEnable, pendingDmgBlobBackgroundEnable,
                0, tileSelectGlitchWrite ? 1 : 0,
                tileSelectGlitchHistoryCapture.clone(),
                oamSizeHistoryCapture.clone());
    }

    @Override
    public ComponentState<Lcdc> captureState(MachineStateCapture capture) {
        copyHistoriesForCapture();
        return new LcdcState(value, mixValue, pendingMixValue,
                dmgBlobBackgroundEnable, pendingDmgBlobBackgroundEnable,
                0, tileSelectGlitchWrite ? 1 : 0,
                capture.booleans(tileSelectGlitchHistoryCapture),
                capture.ints(oamSizeHistoryCapture));
    }

    @Override
    public void restoreState(ComponentState<Lcdc> state) {
        if (!(state instanceof LcdcState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        this.value = mem.value;
        this.mixValue = mem.mixValue;
        this.pendingMixValue = mem.pendingMixValue;
        this.dmgBlobBackgroundEnable = mem.dmgBlobBackgroundEnable;
        this.pendingDmgBlobBackgroundEnable = mem.pendingDmgBlobBackgroundEnable;
        this.tileSelectGlitchWrite = mem.pendingTileSelectGlitchTicks != 0;
        if (mem.tileSelectGlitchHistory.length != tileSelectGlitchHistory.length) {
            throw new IllegalArgumentException("ComponentState tile-select history length doesn't match");
        }
        System.arraycopy(mem.tileSelectGlitchHistory, 0, this.tileSelectGlitchHistory,
                0, this.tileSelectGlitchHistory.length);
        if (mem.oamSizeHistory.length != oamSizeHistory.length) {
            throw new IllegalArgumentException("ComponentState OAM-size history length doesn't match");
        }
        System.arraycopy(mem.oamSizeHistory, 0, oamSizeHistory, 0, oamSizeHistory.length);
        this.historyHead = 0;
        this.performanceFixedPointDrain = PERFORMANCE_FIXED_POINT_DRAIN;
    }

    private int historyIndex(int dotsAgo) {
        return (historyHead + dotsAgo) & (CONFLICT_HISTORY_LENGTH - 1);
    }

    private void copyHistoriesForCapture() {
        for (int dotsAgo = 0; dotsAgo < CONFLICT_HISTORY_LENGTH; dotsAgo++) {
            int index = historyIndex(dotsAgo);
            tileSelectGlitchHistoryCapture[dotsAgo] = tileSelectGlitchHistory[index];
            oamSizeHistoryCapture[dotsAgo] = oamSizeHistory[index];
        }
    }

    private record LcdcState(
            int value, int mixValue, int pendingMixValue,
            boolean dmgBlobBackgroundEnable, boolean pendingDmgBlobBackgroundEnable,
            // Retain the two integer slots used by released snapshots. The active pulse
            // is already represented by tileSelectGlitchHistory; only the pending write
            // strobe needs an authoritative live field.
            int tileSelectGlitchTicks, int pendingTileSelectGlitchTicks,
            boolean[] tileSelectGlitchHistory,
            int[] oamSizeHistory)
            implements ComponentState<Lcdc> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record LcdcMemento(
            int value, int mixValue, int pendingMixValue,
            boolean dmgBlobBackgroundEnable, boolean pendingDmgBlobBackgroundEnable,
            int tileSelectGlitchTicks, int pendingTileSelectGlitchTicks,
            boolean[] tileSelectGlitchHistory,
            int[] oamSizeHistory)
            implements Memento<Lcdc> {
    }
}
