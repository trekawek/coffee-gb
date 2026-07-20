package eu.rekawek.coffeegb.core.gpu.phase;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.*;
import eu.rekawek.coffeegb.core.gpu.phase.OamSearch.SpritePosition;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static eu.rekawek.coffeegb.core.gpu.GpuRegister.*;

/**
 * The mode-3 pixel pipeline, advancing one T-cycle per tick (modelled after SameBoy's dot
 * renderer). Each T-cycle pops at most one pixel from the FIFO and advances the fetcher by
 * one state. An object fetch suspends popping: it first waits until the background fetcher
 * has its tile data and the FIFO is not empty, then takes 6 T-cycles for the object's own
 * OAM and tile data reads (intr_2_mode0_timing_sprites).
 *
 * <p>The line starts at {@code position = -16} with 8 junk pixels in the FIFO. Popping
 * aligns to SCX in the first 8 T-cycles (positions -16..-9), then positions -8..-1 discard
 * the fractional-scroll pixels, and positions 0..159 reach the screen. Objects match when
 * their OAM X equals {@code position + 8} (clamped to 0), so objects hanging off the left
 * edge get fetched during the discard phase and their off-screen pixels are discarded
 * together with the background pixels they were merged with.
 */
public class PixelTransfer implements GpuPhase, Serializable, Originator<PixelTransfer> {

    private static final int[] JUNK_PIXEL_LINE = new int[8];

    private final PixelFifo fifo;

    private final Fetcher fetcher;

    private final GpuRegisterValues r;

    private final Lcdc lcdc;

    private final boolean gbc;

    private final SpeedMode speedMode;

    private final SpritePosition[] sprites;

    // ticks before the first dot of the line (mode-3 entry padding)
    private int entryTicks;

    // The shortened line immediately following LCD enable has a distinct fine-SCX
    // startup latch. Capture the line identity at mode-3 entry; LY alone cannot
    // distinguish it from an ordinary frame's line zero.
    private boolean lcdEnableFirstLine;

    // 0 for the timing-skeleton instance; 4 for the pixel-generating instance, whose
    // dot machine runs one machine cycle behind the mode/STAT skeleton so that its
    // register and VRAM reads land on the hardware dots (mealybug m3 tests)
    private final int entryDelay;

    // whether the dot machine is running (start() .. position 160); the pixel-machine
    // instance is ticked from the GPU every T-cycle since it outlives the skeleton's mode 3
    private boolean machineActive;

    private int position;

    private boolean window;

    private boolean windowBeingFetched;

    private int windowLineCounter = -1;

    // Latched when the window is enabled while LY equals WY. It remains set for the
    // frame even if LCDC.5 is later cleared; hardware uses this latch both for window
    // activation and for the disabled-window insertion glitch.
    private boolean windowYTriggered;

    // The PPU has two distinct vertical window signals: a frame-persistent master
    // sampled near the LY edge (windowYTriggered above), and a delayed copy of WY used
    // by the live mode-3 comparator. A WY write can therefore race the first WX match
    // without retroactively changing the persistent master.
    private int windowWy;

    private int pendingWindowWy;

    // -1 means that no secondary-latch update is pending. A value reaching zero is
    // committed on the following PPU tick, matching the GPU's before-edge call order.
    private int windowWyDelay = -1;

    // A CGB WY write first advances the PPU through the write edge and only then
    // replaces the primary WY register. Preserve the old primary value for a master
    // checkpoint that collides with that CPU tick.
    private int windowWyOldOnWriteTick = -1;

    // a WX comparator match holds the activation pending for one tick: the comparator
    // on hardware sees register writes one machine cycle before our CPU commits them,
    // so a WX write landing within the skew means the window never triggered. The
    // side effects (FIFO clear, fetcher restart) only happen when the match survives
    // the skew - a cancelled match must leave no trace on the pixel stream
    // (mealybug m3_wx_6_change: the WX=6/WX=LY/WX=80 rewrites race their own matches)
    private int windowPendingTicks;

    private int windowPendingWx;

    // position at the comparator match; a pixel popped during the pending tick is
    // rolled back at commit so the activation behaves as if it happened at the match
    private int windowPendingPos;

    // position of a WX==position+7 match that landed while the window was disabled by a
    // mid-line pulse, awaiting the window re-enable to activate (DMG desync); -1 = none
    private int windowCatchUpPos = -1;

    // whether the window already activated on this line: a RE-activation (after a
    // mid-line window drop, e.g. an LCDC.5 toggle) restarts its fetch one T-cycle
    // later than the line's first activation (m3_lcdc_win_en_change_multiple)
    private boolean windowActivatedThisLine;

    // Normal-speed CGB applies an LCDC.5 rising edge after updating the PPU through
    // the edge's comparator dot, so that same tick cannot start the window.
    private boolean previousWindowDisplay;

    // The timing skeleton consumes DMG window-control writes on the CPU edge, while the
    // pixel-producing machine sees them after crossing the four-dot LCD output latch.
    // A non-negative override preserves the old pixel-domain value until that crossing.
    // Writes remain ordered because games can toggle LCDC.5 or rewrite WX more than once
    // inside the crossing delay.
    private int windowDisplayOverride = -1;

    private final List<DelayedWindowWrite> pendingWindowDisplayWrites = new ArrayList<>();

    private int windowXOverride = -1;

    private final List<DelayedWindowWrite> pendingWindowXWrites = new ArrayList<>();

    // The CGB retains the old background shift register throughout StartWindowDraw's
    // six states. If LCDC.5 is cleared, the remaining states plot that register while
    // the fetcher changes back to the background source.
    private int cgbWindowStartTicks;

    // a WX re-match while the window is already active and its fetch has settled
    // inserts one synthetic blank pixel at the FIFO's read end (SameBoy's
    // insert_bg_pixel; mealybug m3_wx_5_change rows where WX=LY re-matches)
    private boolean insertBgPixel;

    // the WX=0 window activation costs one extra dot (SameBoy sleeps 1 cycle there)
    private int machineStall;

    // WX value seen on the previous tick: the DMG's secondary WX==position+6 activation
    // is suppressed for the single tick right after a WX write (SameBoy wx_just_changed)


    private final int[] spriteOrder = new int[10];

    private int spriteCount;

    private int spriteHead;

    // -1 = no object fetch in progress, 0..5 = T-cycle within the fixed object sequence
    private int objStep = -1;

    private int objTileId;

    private TileAttributes objAttributes = TileAttributes.EMPTY;

    private int objData0;

    private int objTileLine;

    private boolean objData1Pending;

    // hardware samples the object's high data byte two dots later than our +4-shifted
    // machine's read dot: the byte is re-read 2 dots after the overlay with the live
    // registers (a mid-fetch LCDC.2 write changes the tile row it comes from), and the
    // FIFO-resident pixels re-resolve; pixels already popped keep the data they were
    // popped with, exactly as the photos show (m3_lcdc_obj_size_change[_scx])
    private int objRefreshAge = -1;

    private int objRefreshPops;


    private int objRefreshD0;

    private int objRefreshTileId;

    private int objRefreshLine;

    private TileAttributes objRefreshAttrs = TileAttributes.EMPTY;

    private final int[] objRefreshZip = new int[8];

    private final int[] objRefreshNewZip = new int[8];

    // window first-tile D1 refresh (see Fetcher.takeWindowRefresh)
    private int wdwRefreshAge = -1;

    private int wdwRefreshPops;

    private final int[] wdwRefreshZip = new int[8];

    private final int[] wdwRefreshNewZip = new int[8];

    // the first window tile's high byte re-read lands 3 dots after the push (the
    // compressed empty-FIFO push reads it that many dots before hardware does)
    private static final int WDW_D1_AGE = 3;

    // an object match is waiting for the background fetcher (counts as an object fetch
    // for the DMG LCDC.1 write conflict)
    private boolean objWaiting;

    // Net number of output dots contributed by object timing on this line. Ordinary
    // wait/load states add one; the DMG's mid-fetch abort subtracts each pipeline dot
    // it catches up. The timing skeleton and shifted pixel machine can therefore expose
    // whether an LCDC write made their object paths diverge without inspecting a ROM.
    private int objectTimingPenalty;

    // In CGB double speed, an SCX write can share a PPU output tick. The LCD side
    // consumes the value sampled on the preceding tick (SameBoy's
    // GB_CONFLICT_SCX_DMG_AND_CGB_DOUBLE). This matters at mode-3 entry: a write just
    // before the shifted pixel machine starts either does or does not change the first
    // tile's phase, and therefore the first object's fetch penalty.
    private int previousScx;

    private int outputScx;

    // Once the startup fine-scroll equality has been crossed, moving SCX backwards
    // makes the pixel counter traverse the skipped phases again. Keep this history
    // separate from the transient stall so the CPU-visible mode latch can follow the
    // dynamically extended transfer instead of its steady-line fixed-dot shortcut.
    private boolean fineScxRephasedThisLine;

    public PixelTransfer(
            Display display,
            AddressSpace videoRam0,
            AddressSpace videoRam1,
            AddressSpace oemRam,
            Lcdc lcdc,
            GpuRegisterValues r,
            boolean gbc,
            ColorPalette bgPalette,
            ColorPalette oamPalette,
            SpritePosition[] sprites,
            VRamTransfer vRamTransfer,
            SpeedMode speedMode,
            int entryDelay) {
        this.entryDelay = entryDelay;

        this.r = r;
        this.lcdc = lcdc;
        this.gbc = gbc;
        this.speedMode = speedMode;
        if (gbc) {
            this.fifo = new ColorPixelFifo(display, lcdc, bgPalette, oamPalette, r, speedMode);
        } else {
            this.fifo = new DmgPixelFifo(display, lcdc, r, vRamTransfer);
        }
        this.fetcher = new Fetcher(fifo, videoRam0, videoRam1, oemRam, lcdc, r, gbc);
        this.sprites = sprites;
    }

    public PixelTransfer start() {
        return start(0, false);
    }

    public PixelTransfer start(int extraEntryDelay) {
        return start(extraEntryDelay, false);
    }

    public PixelTransfer start(int extraEntryDelay, boolean lcdEnableFirstLine) {
        this.lcdEnableFirstLine = lcdEnableFirstLine;
        entryTicks = entryDelay + extraEntryDelay;
        machineActive = true;
        position = -16;
        // Comparator state is local to the scanline. A CGB WX=166 match is normally
        // settled at the end of tick(); this reset is the defensive boundary that keeps
        // any cancelled/serialized pending match from jumping the next line to X=159.
        windowPendingTicks = 0;
        windowActivatedThisLine = false;
        previousWindowDisplay = isWindowDisplay();
        cgbWindowStartTicks = 0;
        fifo.discardClearedBg();
        windowCatchUpPos = -1;
        insertBgPixel = false;
        machineStall = 0;
        fifo.startLine();
        window = false;
        windowBeingFetched = false;
        objStep = -1;
        objData1Pending = false;
        objWaiting = false;
        objectTimingPenalty = 0;
        previousScx = outputScx = r.get(SCX);
        fineScxRephasedThisLine = false;
        objRefreshAge = -1;
        wdwRefreshAge = -1;

        spriteCount = 0;
        for (int i = 0; i < sprites.length; i++) {
            if (sprites[i].isEnabled()) {
                spriteOrder[spriteCount++] = i;
            }
        }
        // stable sort by OAM X; ties keep OAM order (DMG priority, fetch order)
        for (int i = 1; i < spriteCount; i++) {
            int v = spriteOrder[i];
            int j = i - 1;
            while (j >= 0 && sprites[spriteOrder[j]].getX() > sprites[v].getX()) {
                spriteOrder[j + 1] = spriteOrder[j];
                j--;
            }
            spriteOrder[j + 1] = v;
        }
        spriteHead = 0;

        fetcher.startLine();
        fifo.clear();
        fifo.enqueue8Pixels(JUNK_PIXEL_LINE, TileAttributes.EMPTY);
        return this;
    }

    /** Advances the LCD output stage; called by the GPU every tick regardless of mode. */
    public void outputTick() {
        fifo.outputTick();
    }

    /** Ticks the pixel-machine instance; called by the GPU every T-cycle. */
    public void machineTick() {
        if (machineActive && !tick()) {
            machineActive = false;
        }
    }

    public void stop() {
        machineActive = false;
    }

    public int getPosition() {
        return position;
    }

    public boolean isObjectFetchInProgress() {
        return objStep >= 0 || objWaiting;
    }

    public int getObjectTimingPenalty() {
        return objectTimingPenalty;
    }

    public boolean hasObjectsOnLine() {
        return spriteCount > 0;
    }

    /**
     * Whether a selected object can extend the physical transfer beyond the independently
     * predicted mode-0 STAT edge at PPU X=166. Objects at X=166 are included in that
     * prediction; objects at X=167 are fetched after it, but both need the separate latch.
     */
    public boolean hasSpriteAtMode0PredictionEdge() {
        for (int i = 0; i < spriteCount; i++) {
            int x = sprites[spriteOrder[i]].getX();
            if (x >= 166 && x < 168) {
                return true;
            }
        }
        return false;
    }

    public boolean isWindowBeingFetched() {
        return windowBeingFetched;
    }

    public void disableWindowInsertionGlitch() {
        fetcher.disableInsertionGlitch();
    }

    /** Drops pixels still in the output delay line (LCD disable). */
    public void clearOutput() {
        fifo.clearOutput();
    }

    public void resetWindowLineCounter() {
        // pre-incremented at window activation: the first activated line renders row 0
        windowLineCounter = -1;
        setWindowYTriggered(false);
    }

    public void scheduleWindowDisplayWrite(boolean windowDisplay, int delayDots) {
        if (pendingWindowDisplayWrites.isEmpty()) {
            windowDisplayOverride = isWindowDisplay() ? 1 : 0;
        }
        pendingWindowDisplayWrites.add(new DelayedWindowWrite(
                windowDisplay ? 1 : 0, Math.max(0, delayDots)));
    }

    public void scheduleWindowXWrite(int windowX, int delayDots) {
        if (pendingWindowXWrites.isEmpty()) {
            windowXOverride = getWindowX();
        }
        pendingWindowXWrites.add(new DelayedWindowWrite(
                windowX & 0xff, Math.max(0, delayDots)));
    }

    public void cancelDelayedWindowDisplayWrite() {
        pendingWindowDisplayWrites.clear();
        windowDisplayOverride = -1;
    }

    public void cancelDelayedWindowXWrite() {
        pendingWindowXWrites.clear();
        windowXOverride = -1;
    }

    public void advanceDelayedWindowWrites() {
        for (int i = 0; i < pendingWindowDisplayWrites.size(); ) {
            DelayedWindowWrite pending = pendingWindowDisplayWrites.get(i);
            if (pending.remainingDots() == 0) {
                boolean wasWindowDisplay = isWindowDisplay();
                windowDisplayOverride = pending.value();
                pendingWindowDisplayWrites.remove(i);
                if (wasWindowDisplay && !isWindowDisplay() && windowBeingFetched) {
                    disableWindowInsertionGlitch();
                }
            } else {
                pendingWindowDisplayWrites.set(i, pending.advance());
                i++;
            }
        }
        if (pendingWindowDisplayWrites.isEmpty()) {
            windowDisplayOverride = -1;
        }

        for (int i = 0; i < pendingWindowXWrites.size(); ) {
            DelayedWindowWrite pending = pendingWindowXWrites.get(i);
            if (pending.remainingDots() == 0) {
                windowXOverride = pending.value();
                pendingWindowXWrites.remove(i);
            } else {
                pendingWindowXWrites.set(i, pending.advance());
                i++;
            }
        }
        if (pendingWindowXWrites.isEmpty()) {
            windowXOverride = -1;
        }
    }

    public boolean hasDelayedWindowDisplayWrite() {
        return !pendingWindowDisplayWrites.isEmpty();
    }

    boolean isWindowDisplay() {
        return windowDisplayOverride >= 0
                ? windowDisplayOverride != 0 : lcdc.isWindowDisplay();
    }

    public boolean isWindowDisplayVisible() {
        return isWindowDisplay();
    }

    int getWindowX() {
        return windowXOverride >= 0 ? windowXOverride : r.get(WX);
    }

    public int getWindowXVisible() {
        return getWindowX();
    }

    /** Legacy/direct sampler used by focused phase tests that do not own a GPU clock. */
    public void checkWindowY() {
        windowWy = r.get(WY);
        if (!windowYTriggered && isWindowDisplay() && r.get(LY) == r.get(WY)) {
            setWindowYTriggered(true);
        }
    }

    /**
     * Advances the delayed WY copy and samples the persistent window master at the
     * PPU's line-edge checkpoints. Gambatte's CGB checkpoints at 450/454 map to
     * Coffee's normal-speed skeleton at 446/450; double speed observes them at
     * 449/453, and DMG uses 450/454. Normal-speed CGB and DMG sample frame line zero
     * on line 153 dot 454, while double-speed CGB samples it on line zero dot 1.
     */
    public void checkWindowY(int line, int ticksInLine) {
        advanceWindowWy();
        int primaryWy = windowWyOldOnWriteTick >= 0
                ? windowWyOldOnWriteTick : r.get(WY);
        windowWyOldOnWriteTick = -1;

        boolean earlyFrameCheckpoint = !gbc || speedMode.getSpeedMode() == 1;
        if (earlyFrameCheckpoint && line == 153 && ticksInLine == 454) {
            setWindowYTriggered(isWindowDisplay() && primaryWy == 0);
        } else if (!earlyFrameCheckpoint && line == 0 && ticksInLine == 1) {
            setWindowYTriggered(isWindowDisplay() && primaryWy == 0);
        }
        int currentLineCheckpoint = gbc
                ? speedMode.getSpeedMode() == 1 ? 446 : 449
                : 450;
        if (line < 143 && ticksInLine == currentLineCheckpoint
                && isWindowDisplay() && r.get(LY) == primaryWy) {
            setWindowYTriggered(true);
        }
        int upcomingLineCheckpoint = gbc
                ? speedMode.getSpeedMode() == 1 ? 450 : 453
                : 454;
        if (line < 143 && ticksInLine == upcomingLineCheckpoint
                && isWindowDisplay() && r.get(LY) + 1 == primaryWy) {
            setWindowYTriggered(true);
        }
    }

    /** Schedules the secondary WY comparator latch after a WY write. */
    public void scheduleWindowYWrite(int value, int delayDots) {
        pendingWindowWy = value & 0xff;
        if (gbc && delayDots > 0) {
            windowWyOldOnWriteTick = r.get(WY);
        }
        if (delayDots <= 0) {
            windowWy = pendingWindowWy;
            windowWyDelay = -1;
        } else {
            windowWyDelay = delayDots;
        }
    }

    private void advanceWindowWy() {
        if (windowWyDelay == 0) {
            windowWy = pendingWindowWy;
            windowWyDelay = -1;
        } else if (windowWyDelay > 0) {
            windowWyDelay--;
        }
    }

    private void setWindowYTriggered(boolean triggered) {
        windowYTriggered = triggered;
        fetcher.setWindowYTriggered(triggered);
    }

    boolean isWindowYMatch() {
        return windowYTriggered || windowWy == r.get(LY);
    }

    boolean isWindowYTriggered() {
        return windowYTriggered;
    }

    boolean isWindowActivationPending() {
        return windowPendingTicks > 0;
    }

    int getWindowLineCounter() {
        return windowLineCounter;
    }

    public boolean isCgbWindowStartActive() {
        return cgbWindowStartTicks > 0;
    }

    public boolean isWindowActive() {
        return window;
    }

    /** Whether this line has paid the window activation/startup cost. */
    public boolean hasActivatedWindowOnLine() {
        return windowActivatedThisLine;
    }

    public boolean hasFineScxRephaseOnLine() {
        return fineScxRephasedThisLine;
    }

    @Override
    public boolean tick() {
        boolean windowDisplayRising = gbc
                && isWindowDisplay() && !previousWindowDisplay;
        boolean windowEnabledOnThisTick = windowDisplayRising
                && speedMode.getSpeedMode() == 1;
        previousWindowDisplay = isWindowDisplay();
        int currentScx = r.get(SCX);
        int fineScxAdvance = (currentScx & 7) - (previousScx & 7);
        if (lcdEnableFirstLine
                && fineScxAdvance > 0
                && position >= -8
                && position < (gbc ? -4 : -3)) {
            // Crossing the startup comparator restarts its two-dot sampler before
            // traversing the newly exposed fine-scroll phases.
            machineStall += fineScxAdvance + 2;
            fineScxRephasedThisLine = true;
        }
        int fineScxRewind = (previousScx & 7) - (currentScx & 7);
        if (position >= -8 && position < 160 && fineScxRewind > 0) {
            machineStall += fineScxRewind + 6;
            fineScxRephasedThisLine = true;
        }
        outputScx = gbc && speedMode.getSpeedMode() == 2 ? previousScx : currentScx;
        previousScx = currentScx;

        if (cgbWindowStartTicks > 0 && --cgbWindowStartTicks == 0) {
            fifo.discardClearedBg();
        }
        if (machineStall > 0) {
            machineStall--;
            return machineStall > 0 || position != 160;
        }
        if (entryTicks > 0) {
            entryTicks--;
            return true;
        }

        if (objData1Pending) {
            objData1Pending = false;
            // hardware reads the object's high tile byte one dot after the fetch's last
            // cycle, so a mid-mode-3 LCDC.2 (sprite height) or tile write during that dot
            // affects the high byte only (mealybug m3_lcdc_obj_size_change[_scx])
            int objData1 = fetcher.readSpriteData(objTileId, objTileLine, 1, objAttributes);
            int[] line = fetcher.zip(objData0, objData1, objAttributes.isXflip());
            fifo.setOverlay(line, 0, objAttributes, spriteOrder[spriteHead]);
            spriteHead++;
            if (!gbc) {
                System.arraycopy(line, 0, objRefreshZip, 0, 8);
                objRefreshAge = 0;
                objRefreshPops = 0;
                objRefreshD0 = objData0;
                objRefreshTileId = objTileId;
                objRefreshLine = objTileLine;
                objRefreshAttrs = objAttributes;
            }
        } else if (objRefreshAge >= 0) {
            objRefreshAge++;
            if (objRefreshAge == 2) {
                int d1 = fetcher.readSpriteData(objRefreshTileId, objRefreshLine, 1, objRefreshAttrs);
                Fetcher.zip(objRefreshD0, d1, objRefreshAttrs.isXflip(), objRefreshNewZip);
                boolean changed = false;
                for (int i = 0; i < 8; i++) {
                    if (objRefreshNewZip[i] != objRefreshZip[i]) {
                        changed = true;
                        break;
                    }
                }
                if (changed) {
                    fifo.refreshOverlay(objRefreshZip, objRefreshNewZip, objRefreshPops, objRefreshAttrs);
                }
                objRefreshAge = -1;
            }
        }

        if (wdwRefreshAge >= 0) {
            wdwRefreshAge++;
            if (wdwRefreshAge == WDW_D1_AGE) {
                int d1 = fetcher.readWindowRefreshData1();
                Fetcher.zip(fetcher.getWindowRefreshD0(), d1,
                        fetcher.getWindowRefreshAttrs().isXflip(), wdwRefreshNewZip);
                boolean changed = false;
                for (int i = 0; i < 8; i++) {
                    if (wdwRefreshNewZip[i] != wdwRefreshZip[i]) {
                        changed = true;
                        break;
                    }
                }
                if (changed) {
                    fifo.refreshBgPixels(wdwRefreshZip, wdwRefreshNewZip, wdwRefreshPops);
                }
                wdwRefreshAge = -1;
            }
        }

        // commit or drop a pending window activation (see windowPendingTicks)
        if (windowPendingTicks > 0) {
            windowPendingTicks--;
            // a disable committing during the pending tick cancels the activation: the
            // hardware comparator saw the window still enabled at the match dot, but the
            // fetch never starts, so no side effects remain (wewx rows whose activation
            // dot collides with the LCDC.5-off pulse)
            if (getWindowX() == windowPendingWx && (gbc || isWindowDisplay())) {
                if (position != windowPendingPos) {
                    // roll back the pixel that popped during the pending tick: on
                    // hardware the FIFO was already cleared at the match dot
                    if (windowPendingPos >= 0) {
                        fifo.rewindOnePixel();
                    }
                    position = windowPendingPos;
                }
                window = true;
                windowBeingFetched = true;
                windowLineCounter++;
                fifo.clearBg();
                cgbWindowStartTicks = gbc ? 6 : 0;
                fetcher.startWindow();
                if (windowPendingWx == 0 && (r.get(SCX) & 7) != 0) {
                    // the WX=0 activation costs the extra dot only with fractional
                    // scrolling (SameBoy sleeps 1 there gated on SCX&7; wt_wx0's
                    // SCX%8==0 rows)
                    machineStall = 1;
                }
                if (!windowActivatedThisLine) {
                    // the fetch restarted at the match dot on hardware: advance one
                    // state so its reads land on the original dots despite the
                    // pending tick; a re-activation's fetch starts one T-cycle later
                    advanceFetcher(true, false);
                }
                windowActivatedThisLine = true;
            }
        }

        // Clearing LCDC.5 clears Gambatte's win_draw_started bit. On CGB this happens
        // immediately even inside StartWindowDraw: the six-state sequence continues,
        // plotting its retained background, while subsequent fetch states use the BG
        // source. DMG changes source at its next early tile-fetch state and retains its
        // separate restart/catch-up behaviour below.
        if (gbc && window && !isWindowDisplay()) {
            if (speedMode.getSpeedMode() == 2 && (r.get(SCX) & 7) == 5
                    && ((entryDelay == 0 && fetcher.getState() == Fetcher.PUSH)
                    || (entryDelay == 4
                    && fetcher.getState() == Fetcher.GET_TILE_DATA_LOW_T2
                    && position == 2))) {
                // Gambatte's StartWindowDraw::f5 is the last pure startup state.
                // Disabling after it leaves one final predicted X cycle; the +4
                // output copy sees that same edge at its LOW_T2 state.
                machineStall++;
            }
            window = false;
            windowBeingFetched = false;
        } else if (!gbc
                && window
                && !isWindowDisplay()
                && objStep < 0
                && fetcher.getState() <= Fetcher.GET_TILE_DATA_LOW_T2) {
            // the write disabling the window catches the fetch through GET_TILE_DATA_LOW_T2
            // (the map read): our LCDC-pulse commit lands a couple of dots later on the
            // fetcher timeline than hardware, so a window that flickers on and straight
            // back off produces no window pixels only if we still abort the fetch here.
            // Restarting from T2 onwards recomputes the map offset as background
            // (m3_lcdc_win_en_change_multiple, and the stray 8-px window tiles in wewx).
            if (fetcher.getState() >= Fetcher.GET_TILE_T2) {
                fetcher.restartTile();
            }
            window = false;
            windowBeingFetched = false;
        }

        // record a WX==position+7 match that lands while the window is disabled by a
        // mid-line pulse: the window will "catch up" when it is re-enabled (see the
        // desync branch below). Only for regular WX>=7 positions so the discard-phase
        // WX=0..6 activation is untouched.
        if (!gbc && !window && !isWindowDisplay() && !windowActivatedThisLine
                && windowYTriggered) {
            int wxNow = getWindowX();
            if (wxNow >= 7 && wxNow < 166 && !r.isWxJustChanged()
                    && wxNow == ((position + 7) & 0xff)) {
                windowCatchUpPos = position;
            }
        }

        // window activation check (SameBoy model): the comparison wraps in 8 bits, so
        // WX values 0..6 can trigger during the discard phase; WX=0 has its own window,
        // and the CGB also accepts WX=166. The window line counter increments at the
        // activation itself, once per line (mealybug m3_wx_*_change)
        if (!window
                && isWindowDisplay()
                && !windowEnabledOnThisTick
                && (gbc || lcdc.isBgAndWindowDisplay())
                && isWindowYMatch()) {
            int wx = getWindowX();
            boolean activate;
            if (wx == 0) {
                activate = (position >= -15 && position <= -7)
                        || (position == -16 && (r.get(SCX) & 7) != 0);
            } else if (wx < (gbc ? 167 : 166)) {
                activate = wx == ((position + 7) & 0xff);
                if (!activate && gbc && speedMode.getSpeedMode() == 2
                        && windowDisplayRising && (r.get(SCX) & 7) == 5) {
                    // Fine SCX=5 holds X=WX across Tile::f4. The shifted output
                    // machine's public position has advanced, but the comparator
                    // still consumes the newly enabled window on this dot.
                    activate = wx == ((position + 6) & 0xff);
                }
            } else {
                activate = false;
            }
            if (!gbc && windowCatchUpPos >= 0 && windowPendingTicks == 0
                    && !windowActivatedThisLine
                    && position - windowCatchUpPos <= DESYNC_MAX_GAP) {
                // DMG LCD-PPU horizontal desync: the WX==position+7 match dot passed while
                // the window was disabled (a mid-line LCDC.5-off pulse); the window catches
                // up on re-enable, rendered as if it had triggered at the match dot. This is
                // SameBoy's WX==position+6 + lcd_x-- generalised to our pulse/position phase
                // (mealybug m3_lcdc_win_en_change_multiple_wx). DESYNC_MAX_GAP encodes the
                // phase between our LCDC-pulse commit and the pixel position axis.
                window = true;
                windowBeingFetched = true;
                windowLineCounter++;
                for (int p = windowCatchUpPos; p < position; p++) {
                    fifo.rewindOnePixel();
                }
                position = windowCatchUpPos;
                windowCatchUpPos = -1;
                fifo.clearBg();
                cgbWindowStartTicks = gbc ? 6 : 0;
                fetcher.startWindow();
                advanceFetcher(true, false);
                windowActivatedThisLine = true;
            } else if (activate && windowPendingTicks == 0) {
                windowPendingTicks = 1;
                windowPendingWx = wx;
                windowPendingPos = position;
            } else if (!gbc && wx == 166 && wx == ((position + 7) & 0xff)) {
                // DMG: WX=166 increments the counter without activating the window
                windowLineCounter++;
            }
        }

        if (window
                && !windowBeingFetched
                && (!gbc || getWindowX() == 0)
                && isWindowDisplay()
                && fetcher.getState() == Fetcher.GET_TILE_T1
                && fifo.getLength() == 8
                && getWindowX() == ((position + 7) & 0xff)) {
            insertBgPixel = true;
        }

        // object fetch in progress occupies the whole T-cycle; on the DMG, clearing
        // LCDC.1 mid-fetch aborts it and the object is skipped (m3_lcdc_obj_en_change)
        if (objStep >= 0) {
            if (!gbc && !lcdc.isObjDisplayEffective()) {
                objStep = -1;
                // the write that aborts the fetch is a CPU-timeline event: the hardware
                // machine (which runs 4 dots ahead of our +4-shifted pixel machine) had
                // already aborted 3 dots of pipeline progress ago, so the machine catches
                // up those dots at once (m3_lcdc_obj_en_change_variant, the BGP band edge
                // on the rows whose object fetch the pulse truncates)
                int catchUpDots = 0;
                for (int i = 0; i < 3 && position != 160; i++) {
                    renderPixelIfPossible();
                    advanceFetcher(window, false);
                    catchUpDots++;
                }
                objectTimingPenalty -= catchUpDots;
            } else {
                objTick();
                objectTimingPenalty++;
                return true;
            }
        }

        int match = position + 8;
        if (match < 0) {
            match = 0;
        }
        while (spriteHead < spriteCount && sprites[spriteOrder[spriteHead]].getX() < match) {
            spriteHead++;
        }
        // Window activation wins when its comparator and an object match on the same
        // dot. The activation is held pending for one tick in this model so CPU writes
        // can cancel it; do not let the object start against the old background FIFO in
        // that tick. Once the activation commits, the object waits for fresh window tile
        // data just as it does on hardware.
        if (spriteHead < spriteCount
                && sprites[spriteOrder[spriteHead]].getX() == match
                && windowPendingTicks == 0
                && (lcdc.isObjDisplayEffective() || gbc)) {
            if (fetcher.getState() < Fetcher.GET_TILE_DATA_HIGH_T2 || fifo.getLength() == 0) {
                // the object fetch waits for the background fetcher's tile data; the wait
                // already counts as "during object fetch" for the LCDC.1 conflict special
                objWaiting = true;
                advanceFetcher(window, true);
                objectTimingPenalty++;
                return true;
            }
            objWaiting = false;
            objStep = 0;
            objTick();
            objectTimingPenalty++;
            return true;
        }
        objWaiting = false;

        renderPixelIfPossible();
        advanceFetcher(window, false);
        if (fetcher.takeWindowRefresh()) {
            wdwRefreshAge = 0;
            wdwRefreshPops = 0;
            System.arraycopy(fetcher.getWindowRefreshZip(), 0, wdwRefreshZip, 0, 8);
        }
        boolean active = position != 160;
        if (!active && gbc && windowPendingTicks > 0 && windowPendingWx == 166) {
            // WX=166 matches only after the final visible CGB pixel. Hardware performs
            // the activation in HBlank: it advances the window's internal Y counter but
            // draws no window pixel. Committing the ordinary delayed activation on the
            // next scanline would instead skip that line to X=159 (Cardcaptor Sakura).
            windowLineCounter++;
            windowPendingTicks = 0;
            // The terminal CGB comparator enters the ordinary six-state window-start
            // machine even though no pixel can be drawn. Four of those states still
            // occupy the physical transfer; the final two belong to the independently
            // readable STAT latch in HBlank.
            cgbWindowStartTicks = 6;
            machineStall = 4;
            return true;
        }
        return active;
    }


    // gap (in dots) between our LCDC-pulse re-enable commit and the pixel position axis:
    // a window whose WX match was masked by a pulse catches up on re-enable only if the
    // match is within this many dots of the re-enable position (mealybug wewx)
    private static final int DESYNC_MAX_GAP = 3;

    private void advanceFetcher(boolean fetchingWindow, boolean duringObjectFetch) {
        fetcher.setWindowRegisterView(getWindowX(), isWindowDisplay());
        fetcher.advance(position, fetchingWindow, windowLineCounter, duringObjectFetch);
    }

    private void objTick() {
        if (objStep <= 1) {
            advanceFetcher(window, true);
        }
        if (objStep == 1) {
            SpritePosition sprite = sprites[spriteOrder[spriteHead]];
            objTileId = fetcher.readSpriteTileId(sprite);
            objAttributes = fetcher.readSpriteAttributes(sprite);
            objTileLine = r.get(LY) + 16 - sprite.getY();
        }
        if (objStep == 5) {
            // low tile byte on the fetch's last cycle; the high byte is read one dot
            // later (objData1Pending, handled at the top of the next tick)
            objData0 = fetcher.readSpriteData(objTileId, objTileLine, 0, objAttributes);
            objData1Pending = true;
            objStep = -1;
            return;
        }
        objStep++;
    }

    private void renderPixelIfPossible() {
        renderOnePixelIfPossible();
    }

    private void renderOnePixelIfPossible() {
        // rendering does not occur as long as an object at X=0 is pending
        if (spriteHead < spriteCount
                && sprites[spriteOrder[spriteHead]].getX() == 0
                && (lcdc.isObjDisplay() || gbc)) {
            return;
        }
        boolean useClearedCgbBackground = gbc
                && cgbWindowStartTicks > 0
                && !isWindowDisplay();
        if (gbc && cgbWindowStartTicks > 0 && !useClearedCgbBackground) {
            return;
        }
        int sourceLength = useClearedCgbBackground
                ? fifo.getClearedBgLength()
                : fifo.getLength();
        if (sourceLength == 0) {
            return;
        }

        if (position >= -16 && position <= -9) {
            if ((position & 7) == (outputScx & 7)) {
                position = -8;
            } else if (windowBeingFetched && (position & 7) == 6 && (outputScx & 7) == 7) {
                position = -8;
            } else if (position == -9) {
                if (useClearedCgbBackground) {
                    fifo.dropClearedBgPixel();
                } else {
                    fifo.dropPixel();
                }
                if (objRefreshAge >= 0) {
                    objRefreshPops++;
                }
                if (wdwRefreshAge >= 0) {
                    wdwRefreshPops++;
                }
                position = -16;
                return;
            }
        }
        windowBeingFetched = false;

        if (position < 0 || position >= 160) {
            if (insertBgPixel) {
                insertBgPixel = false;
            } else {
                if (useClearedCgbBackground) {
                    fifo.dropClearedBgPixel();
                } else {
                    fifo.dropPixel();
                }
                if (objRefreshAge >= 0) {
                    objRefreshPops++;
                }
                if (wdwRefreshAge >= 0) {
                    wdwRefreshPops++;
                }
            }
            position++;
            return;
        }
        if (insertBgPixel) {
            // the synthetic blank pixel replaces this pop; the FIFO keeps its content
            insertBgPixel = false;
            fifo.putInsertedPixel();
        } else {
            if (useClearedCgbBackground) {
                fifo.putClearedBgToScreen();
            } else {
                fifo.putPixelToScreen();
            }
            if (objRefreshAge >= 0) {
                objRefreshPops++;
            }
            if (wdwRefreshAge >= 0) {
                wdwRefreshPops++;
            }
        }
        position++;
    }

    @Override
    public Memento<PixelTransfer> saveToMemento() {
        Memento<?> fifoMemento = null;
        if (fifo instanceof DmgPixelFifo) {
            fifoMemento = ((DmgPixelFifo) fifo).saveToMemento();
        } else if (fifo instanceof ColorPixelFifo) {
            fifoMemento = ((ColorPixelFifo) fifo).saveToMemento();
        }

        return new PixelTransferMemento(
                fetcher.saveToMemento(),
                fifoMemento,
                entryTicks,
                lcdEnableFirstLine,
                position,
                window,
                windowBeingFetched,
                windowLineCounter,
                spriteOrder.clone(),
                spriteCount,
                spriteHead,
                objStep,
                objTileId,
                objAttributes == null ? -1 : objAttributes.getValue(),
                objData0,
                objTileLine,
                objData1Pending,
                objRefreshAge,
                objRefreshPops,
                objRefreshD0,
                objRefreshTileId,
                objRefreshLine,
                objRefreshAttrs == null ? -1 : objRefreshAttrs.getValue(),
                objRefreshZip.clone(),
                objWaiting,
                objectTimingPenalty,
                previousScx,
                fineScxRephasedThisLine,
                machineActive,
                windowPendingTicks,
                windowPendingWx,
                windowPendingPos,
                windowActivatedThisLine,
                previousWindowDisplay,
                cgbWindowStartTicks,
                insertBgPixel,
                machineStall,
                windowYTriggered,
                windowWy,
                pendingWindowWy,
                windowWyDelay,
                windowWyOldOnWriteTick,
                windowDisplayOverride,
                new ArrayList<>(pendingWindowDisplayWrites),
                windowXOverride,
                new ArrayList<>(pendingWindowXWrites));
    }

    @Override
    public void restoreFromMemento(Memento<PixelTransfer> memento) {
        if (!(memento instanceof PixelTransferMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }

        fetcher.restoreFromMemento(mem.fetcherMemento);

        if (fifo instanceof DmgPixelFifo && mem.fifoMemento != null) {
            ((DmgPixelFifo) fifo).restoreFromMemento((Memento<DmgPixelFifo>) mem.fifoMemento);
        } else if (fifo instanceof ColorPixelFifo && mem.fifoMemento != null) {
            ((ColorPixelFifo) fifo).restoreFromMemento((Memento<ColorPixelFifo>) mem.fifoMemento);
        }

        this.entryTicks = mem.entryTicks;
        this.lcdEnableFirstLine = mem.lcdEnableFirstLine;
        this.position = mem.position;
        this.window = mem.window;
        this.windowBeingFetched = mem.windowBeingFetched;
        this.windowLineCounter = mem.windowLineCounter;
        System.arraycopy(mem.spriteOrder, 0, this.spriteOrder, 0, this.spriteOrder.length);
        this.spriteCount = mem.spriteCount;
        this.spriteHead = mem.spriteHead;
        this.objStep = mem.objStep;
        this.objTileId = mem.objTileId;
        if (mem.objAttributesValue != -1) {
            this.objAttributes = TileAttributes.valueOf(mem.objAttributesValue);
        } else {
            this.objAttributes = TileAttributes.EMPTY;
        }
        this.objData0 = mem.objData0;
        this.objTileLine = mem.objTileLine;
        this.objData1Pending = mem.objData1Pending;
        this.objRefreshAge = mem.objRefreshAge;
        this.objRefreshPops = mem.objRefreshPops;
        this.objRefreshD0 = mem.objRefreshD0;
        this.objRefreshTileId = mem.objRefreshTileId;
        this.objRefreshLine = mem.objRefreshLine;
        if (mem.objRefreshAttrsValue != -1) {
            this.objRefreshAttrs = TileAttributes.valueOf(mem.objRefreshAttrsValue);
        } else {
            this.objRefreshAttrs = TileAttributes.EMPTY;
        }
        System.arraycopy(mem.objRefreshZip, 0, this.objRefreshZip, 0,
                this.objRefreshZip.length);
        this.objWaiting = mem.objWaiting;
        this.objectTimingPenalty = mem.objectTimingPenalty;
        this.previousScx = mem.previousScx;
        this.outputScx = previousScx;
        this.fineScxRephasedThisLine = mem.fineScxRephasedThisLine;
        this.machineActive = mem.machineActive;
        this.windowPendingTicks = mem.windowPendingTicks;
        this.windowPendingWx = mem.windowPendingWx;
        this.windowPendingPos = mem.windowPendingPos;
        this.windowActivatedThisLine = mem.windowActivatedThisLine;
        this.previousWindowDisplay = mem.previousWindowDisplay;
        this.cgbWindowStartTicks = mem.cgbWindowStartTicks;
        this.insertBgPixel = mem.insertBgPixel;
        this.machineStall = mem.machineStall;
        this.windowYTriggered = mem.windowYTriggered;
        this.windowWy = mem.windowWy;
        this.pendingWindowWy = mem.pendingWindowWy;
        this.windowWyDelay = mem.windowWyDelay;
        this.windowWyOldOnWriteTick = mem.windowWyOldOnWriteTick;
        this.pendingWindowDisplayWrites.clear();
        this.pendingWindowXWrites.clear();
        if (mem.pendingWindowDisplayWrites != null) {
            this.windowDisplayOverride = mem.windowDisplayOverride;
            this.pendingWindowDisplayWrites.addAll(mem.pendingWindowDisplayWrites);
        } else {
            this.windowDisplayOverride = -1;
        }
        if (mem.pendingWindowXWrites != null) {
            this.windowXOverride = mem.windowXOverride;
            this.pendingWindowXWrites.addAll(mem.pendingWindowXWrites);
        } else {
            this.windowXOverride = -1;
        }
        fetcher.setWindowYTriggered(windowYTriggered);
    }

    private record PixelTransferMemento(
            Memento<Fetcher> fetcherMemento,
            Memento<?> fifoMemento,
            int entryTicks,
            boolean lcdEnableFirstLine,
            int position,
            boolean window,
            boolean windowBeingFetched,
            int windowLineCounter,
            int[] spriteOrder,
            int spriteCount,
            int spriteHead,
            int objStep,
            int objTileId,
            int objAttributesValue,
            int objData0,
            int objTileLine,
            boolean objData1Pending,
            int objRefreshAge,
            int objRefreshPops,
            int objRefreshD0,
            int objRefreshTileId,
            int objRefreshLine,
            int objRefreshAttrsValue,
            int[] objRefreshZip,
            boolean objWaiting,
            int objectTimingPenalty,
            int previousScx,
            boolean fineScxRephasedThisLine,
            boolean machineActive,
            int windowPendingTicks,
            int windowPendingWx,
            int windowPendingPos,
            boolean windowActivatedThisLine,
            boolean previousWindowDisplay,
            int cgbWindowStartTicks,
            boolean insertBgPixel,
            int machineStall,
            boolean windowYTriggered,
            int windowWy,
            int pendingWindowWy,
            int windowWyDelay,
            int windowWyOldOnWriteTick,
            int windowDisplayOverride,
            List<DelayedWindowWrite> pendingWindowDisplayWrites,
            int windowXOverride,
            List<DelayedWindowWrite> pendingWindowXWrites)
            implements Memento<PixelTransfer> {
    }

    private record DelayedWindowWrite(int value, int remainingDots) implements Serializable {

        private DelayedWindowWrite advance() {
            return new DelayedWindowWrite(value, remainingDots - 1);
        }
    }
}
