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

    private final int[] spriteOrder = new int[10];

    private int spriteCount;

    private int spriteHead;

    // -1 = no object fetch in progress, 0..5 = T-cycle within the fixed object sequence
    private int objStep = -1;

    private int objTileId;

    private TileAttributes objAttributes = TileAttributes.EMPTY;

    private int objData0;

    private int objTileLine;

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
        entryTicks = entryDelay;
        machineActive = true;
        position = -16;
        window = false;
        windowBeingFetched = false;
        objStep = -1;

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
        return objStep >= 0;
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
        if (entryTicks > 0) {
            entryTicks--;
            return true;
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
            if (activate) {
                window = true;
                windowBeingFetched = true;
                windowLineCounter++;
                fifo.clear();
                fetcher.startWindow();
            } else if (!gbc && wx == 166 && wx == ((position + 7) & 0xff)) {
                // DMG: WX=166 increments the counter without activating the window
                windowLineCounter++;
            }
        }

        // object fetch in progress occupies the whole T-cycle; on the DMG, clearing
        // LCDC.1 mid-fetch aborts it and the object is skipped (m3_lcdc_obj_en_change)
        if (objStep >= 0) {
            if (!gbc && !lcdc.isObjDisplayEffective()) {
                objStep = -1;
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
                // the object fetch waits for the background fetcher's tile data
                fetcher.advance(position, window, windowLineCounter, true);
                return true;
            }
            objStep = 0;
            objTick();
            return true;
        }

        renderPixelIfPossible();
        fetcher.advance(position, window, windowLineCounter, false);
        return position != 160;
    }

    private void objTick() {
        switch (objStep) {
            case 0:
                fetcher.advance(position, window, windowLineCounter, true);
                break;

            case 1: {
                fetcher.advance(position, window, windowLineCounter, true);
                SpritePosition sprite = sprites[spriteOrder[spriteHead]];
                objTileId = fetcher.readSpriteTileId(sprite);
                objAttributes = fetcher.readSpriteAttributes(sprite);
                objTileLine = r.get(LY) + 16 - sprite.getY();
                break;
            }

            case 3:
                objData0 = fetcher.readSpriteData(objTileId, objTileLine, 0, objAttributes);
                break;

            case 5: {
                int objData1 = fetcher.readSpriteData(objTileId, objTileLine, 1, objAttributes);
                fifo.setOverlay(
                        fetcher.zip(objData0, objData1, objAttributes.isXflip()),
                        0,
                        objAttributes,
                        spriteOrder[spriteHead]);
                spriteHead++;
                objStep = -1;
                return;
            }

            default:
                break;
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
                position = -16;
                return;
            }
        }
        windowBeingFetched = false;

        if (position < 0 || position >= 160) {
            fifo.dropPixel();
            position++;
            return;
        }
        fifo.putPixelToScreen();
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
                machineActive);
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
            boolean machineActive)
            implements Memento<PixelTransfer> {
    }
}
