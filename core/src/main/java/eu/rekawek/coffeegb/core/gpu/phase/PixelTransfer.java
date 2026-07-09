package eu.rekawek.coffeegb.core.gpu.phase;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.gpu.*;
import eu.rekawek.coffeegb.core.gpu.phase.OamSearch.SpritePosition;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

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

    private final SpritePosition[] sprites;

    // ticks before the first dot of the line (mode-3 entry padding)
    private int entryTicks;

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

    // an object match is waiting for the background fetcher (counts as an object fetch
    // for the DMG LCDC.1 write conflict)
    private boolean objWaiting;

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
            eu.rekawek.coffeegb.core.cpu.SpeedMode speedMode,
            int entryDelay) {
        this.entryDelay = entryDelay;

        this.r = r;
        this.lcdc = lcdc;
        this.gbc = gbc;
        if (gbc) {
            this.fifo = new ColorPixelFifo(display, lcdc, bgPalette, oamPalette, r, speedMode);
        } else {
            this.fifo = new DmgPixelFifo(display, lcdc, r, vRamTransfer);
        }
        this.fetcher = new Fetcher(fifo, videoRam0, videoRam1, oemRam, lcdc, r, gbc);
        this.sprites = sprites;
    }

    public PixelTransfer start() {
        return start(0);
    }

    public PixelTransfer start(int extraEntryDelay) {
        entryTicks = entryDelay + extraEntryDelay;
        machineActive = true;
        position = -16;
        windowActivatedThisLine = false;
        windowCatchUpPos = -1;
        machineStall = 0;
        fifo.startLine();
        window = false;
        windowBeingFetched = false;
        objStep = -1;
        objData1Pending = false;
        objWaiting = false;
        objRefreshAge = -1;

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
    }

    @Override
    public boolean tick() {
        if (machineStall > 0) {
            machineStall--;
            return true;
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

        // commit or drop a pending window activation (see windowPendingTicks)
        if (windowPendingTicks > 0) {
            windowPendingTicks--;
            if (r.get(WX) == windowPendingWx) {
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
                    fetcher.advance(position, true, windowLineCounter, false);
                }
                windowActivatedThisLine = true;
            }
        }

        // the fetcher drops back to the background at its next tile fetch when the
        // window gets disabled mid-line (SameBoy clears wx_triggered at GET_TILE_T1);
        // a later WX match with the window re-enabled can activate it again
        if (window
                && !lcdc.isWindowDisplay()
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
        if (!gbc && !window && !lcdc.isWindowDisplay() && !windowActivatedThisLine
                && r.get(LY) >= r.get(WY)) {
            int wxNow = r.get(WX);
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
                && lcdc.isWindowDisplay()
                && (gbc || lcdc.isBgAndWindowDisplay())
                && r.get(LY) >= r.get(WY)) {
            int wx = r.get(WX);
            boolean activate;
            if (wx == 0) {
                activate = (position >= -15 && position <= -7)
                        || (position == -16 && (r.get(SCX) & 7) != 0);
            } else if (wx < (gbc ? 167 : 166)) {
                activate = wx == ((position + 7) & 0xff);
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
                fetcher.startWindow();
                fetcher.advance(position, true, windowLineCounter, false);
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
                && (!gbc || r.get(WX) == 0)
                && lcdc.isWindowDisplay()
                && fetcher.getState() == Fetcher.GET_TILE_T1
                && fifo.getLength() == 8
                && r.get(WX) == ((position + 7) & 0xff)) {
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
                for (int i = 0; i < 3 && position != 160; i++) {
                    renderPixelIfPossible();
                    fetcher.advance(position, window, windowLineCounter, false);
                }
            } else {
                objTick();
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
        if (spriteHead < spriteCount
                && sprites[spriteOrder[spriteHead]].getX() == match
                && (lcdc.isObjDisplayEffective() || gbc)) {
            if (fetcher.getState() < Fetcher.GET_TILE_DATA_HIGH_T2 || fifo.getLength() == 0) {
                // the object fetch waits for the background fetcher's tile data; the wait
                // already counts as "during object fetch" for the LCDC.1 conflict special
                objWaiting = true;
                fetcher.advance(position, window, windowLineCounter, true);
                return true;
            }
            objWaiting = false;
            objStep = 0;
            objTick();
            return true;
        }
        objWaiting = false;

        renderPixelIfPossible();
        fetcher.advance(position, window, windowLineCounter, false);
        return position != 160;
    }


    // gap (in dots) between our LCDC-pulse re-enable commit and the pixel position axis:
    // a window whose WX match was masked by a pulse catches up on re-enable only if the
    // match is within this many dots of the re-enable position (mealybug wewx)
    private static final int DESYNC_MAX_GAP = 3;

    private void objTick() {
        if (objStep <= 1) {
            fetcher.advance(position, window, windowLineCounter, true);
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
        // rendering does not occur as long as an object at X=0 is pending
        if (spriteHead < spriteCount
                && sprites[spriteOrder[spriteHead]].getX() == 0
                && (lcdc.isObjDisplay() || gbc)) {
            return;
        }
        if (fifo.getLength() == 0) {
            return;
        }

        if (position >= -16 && position <= -9) {
            if ((position & 7) == (r.get(SCX) & 7)) {
                position = -8;
            } else if (windowBeingFetched && (position & 7) == 6 && (r.get(SCX) & 7) == 7) {
                position = -8;
            } else if (position == -9) {
                fifo.dropPixel();
                if (objRefreshAge >= 0) {
                    objRefreshPops++;
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
                fifo.dropPixel();
                if (objRefreshAge >= 0) {
                    objRefreshPops++;
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
            fifo.putPixelToScreen();
            if (objRefreshAge >= 0) {
                objRefreshPops++;
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
                machineActive,
                windowPendingTicks,
                windowPendingWx,
                windowPendingPos,
                windowActivatedThisLine,
                insertBgPixel,
                machineStall);
    }

    @Override
    public void restoreFromMemento(Memento<PixelTransfer> memento) {
        if (!(memento instanceof PixelTransferMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }

        // transient intra-fetch state (like objData1Pending, not part of the memento):
        // never let a pre-restore refresh window leak into the restored timeline
        objRefreshAge = -1;

        fetcher.restoreFromMemento(mem.fetcherMemento);

        if (fifo instanceof DmgPixelFifo && mem.fifoMemento != null) {
            ((DmgPixelFifo) fifo).restoreFromMemento((Memento<DmgPixelFifo>) mem.fifoMemento);
        } else if (fifo instanceof ColorPixelFifo && mem.fifoMemento != null) {
            ((ColorPixelFifo) fifo).restoreFromMemento((Memento<ColorPixelFifo>) mem.fifoMemento);
        }

        this.entryTicks = mem.entryTicks;
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
        this.machineActive = mem.machineActive;
        this.windowPendingTicks = mem.windowPendingTicks;
        this.windowPendingWx = mem.windowPendingWx;
        this.windowPendingPos = mem.windowPendingPos;
        this.windowActivatedThisLine = mem.windowActivatedThisLine;
        this.insertBgPixel = mem.insertBgPixel;
        this.machineStall = mem.machineStall;
    }

    private record PixelTransferMemento(
            Memento<Fetcher> fetcherMemento,
            Memento<?> fifoMemento,
            int entryTicks,
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
            boolean machineActive,
            int windowPendingTicks,
            int windowPendingWx,
            int windowPendingPos,
            boolean windowActivatedThisLine,
            boolean insertBgPixel,
            int machineStall)
            implements Memento<PixelTransfer> {
    }
}
