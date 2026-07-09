package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.gpu.phase.OamSearch.SpritePosition;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

import static eu.rekawek.coffeegb.core.cpu.BitUtils.toSigned;

/**
 * The background/window tile fetcher, advancing one state per T-cycle. VRAM reads take two
 * T-cycles: the address is determined in the first one and the data arrives in the second.
 * The PUSH state waits until the pixel FIFO is empty and completes in the same T-cycle as
 * GET_TILE_DATA_HIGH_T2 when possible.
 */
public class Fetcher implements Serializable, Originator<Fetcher> {

    public static final int GET_TILE_T1 = 0;

    // the horizontal part of the map offset: the position is frozen at the fetch
    // start, but SCX is read two dots later - the DMG commits SCX writes two cycles
    // into the write, earlier than other registers (SameBoy
    // GB_CONFLICT_SCX_DMG_AND_CGB_DOUBLE; m3_scx_high_5_bits)
    private int tileMapX;

    private int xBasePosition;

    private boolean xBaseObjectFetch;

    private void sampleXBase(int position, boolean duringObjectFetch) {
        xBasePosition = position;
        xBaseObjectFetch = duringObjectFetch;
    }

    private void sampleX(boolean window) {
        if (window) {
            tileMapX = windowTileX;
        } else if (xBasePosition + 16 < 8) {
            tileMapX = r.get(GpuRegister.SCX) >> 3;
        } else {
            tileMapX = ((r.get(GpuRegister.SCX) + xBasePosition + 8 - ((gbc && !xBaseObjectFetch) ? 1 : 0)) / 8) & 0x1f;
        }
    }

    private void sampleY(boolean window, int windowY) {
        if (window) {
            fetcherY = windowY & 0xff;
        } else {
            fetcherY = (r.get(GpuRegister.LY) + r.get(GpuRegister.SCY)) & 0xff;
        }
        tileMapOffset = (fetcherY / 8) * 0x20 + tileMapX;
    }

    public static final int GET_TILE_T2 = 1;
    public static final int GET_TILE_DATA_LOW_T1 = 2;
    public static final int GET_TILE_DATA_LOW_T2 = 3;
    public static final int GET_TILE_DATA_HIGH_T1 = 4;
    public static final int GET_TILE_DATA_HIGH_T2 = 5;
    public static final int PUSH = 6;

    private final PixelFifo fifo;

    private final AddressSpace videoRam0;

    private final AddressSpace videoRam1;

    private final AddressSpace oemRam;

    private final GpuRegisterValues r;

    private final Lcdc lcdc;

    private final boolean gbc;

    private final int[] pixelLine = new int[8];

    private int state;

    private int windowTileX;

    private int fetcherY;

    private int tileMapAddress;

    private int tileMapOffset;

    private int tileId;

    private TileAttributes tileAttributes = TileAttributes.EMPTY;

    private int tileData1;

    private int tileData2;

    // the most recent push was the first tile of a window fetch (see PixelTransfer's
    // window D1 refresh); consumed by takeWindowRefresh
    private boolean windowRefreshPending;

    private int windowRefreshTileId;

    private int windowRefreshY;

    private TileAttributes windowRefreshAttrs = TileAttributes.EMPTY;

    private int windowRefreshD0;

    private final int[] windowRefreshZip = new int[8];

    public boolean takeWindowRefresh() {
        boolean p = windowRefreshPending;
        windowRefreshPending = false;
        return p;
    }

    public int getWindowRefreshD0() {
        return windowRefreshD0;
    }

    public int[] getWindowRefreshZip() {
        return windowRefreshZip;
    }

    public TileAttributes getWindowRefreshAttrs() {
        return windowRefreshAttrs;
    }

    /** Re-reads the recorded first-window-tile high byte with the current registers. */
    public int readWindowRefreshData1() {
        return getTileData(windowRefreshTileId, windowRefreshY, 1,
                lcdc.getBgWindowTileData(), lcdc.isBgWindowTileDataSigned(), windowRefreshAttrs, 8);
    }

    // DMG: a disabled window whose WX matches during a push inserts one blank pixel
    // instead of the tile row (SameBoy issue #278); an LCDC write that disables the
    // window mid-window-fetch suppresses the glitch for the rest of the line
    private boolean insertionGlitchDisabled;

    public Fetcher(
            PixelFifo fifo,
            AddressSpace videoRam0,
            AddressSpace videoRam1,
            AddressSpace oemRam,
            Lcdc lcdc,
            GpuRegisterValues registers,
            boolean gbc) {
        this.gbc = gbc;
        this.fifo = fifo;
        this.videoRam0 = videoRam0;
        this.videoRam1 = videoRam1;
        this.oemRam = oemRam;
        this.r = registers;
        this.lcdc = lcdc;
    }

    public void startLine() {
        insertionGlitchDisabled = false;
        state = GET_TILE_T1;
        tileId = 0;
        tileAttributes = TileAttributes.EMPTY;
        tileData1 = 0;
        tileData2 = 0;
    }

    /** Restarts the current fetch from GET_TILE_T1 (used when the window drops at T2). */
    public void restartTile() {
        state = GET_TILE_T1;
    }

    public void startWindow() {
        state = GET_TILE_T1;
        windowTileX = 0;
    }

    public int getState() {
        return state;
    }

    public void disableInsertionGlitch() {
        insertionGlitchDisabled = true;
    }

    /**
     * Advances the fetcher by one T-cycle. The tile map, the scroll registers and the tile
     * data area are read live at the respective fetch states (like on hardware), so
     * mid-line writes to SCX/SCY/LCDC affect the remaining fetches of the line.
     *
     * @param position          the pixel pipeline position (see PixelTransfer)
     * @param window            whether the window is being fetched
     * @param windowY           the window line counter
     * @param duringObjectFetch whether the advance happens as part of an object fetch
     */
    private boolean data2Pending;

    private int data2Delay;

    private void readData2(boolean window, int windowY) {
        tileData2 = getTileData(tileId, effectiveY(window, windowY) & 7, 1,
                lcdc.getBgWindowTileData(), lcdc.isBgWindowTileDataSigned(), tileAttributes, 8);
        data2Pending = false;
    }

    public void advance(int position, boolean window, int windowY, boolean duringObjectFetch) {
        if (data2Pending && state == PUSH && --data2Delay <= 0) {
            readData2(window, windowY);
        }
        switch (state) {
            case GET_TILE_T1:
                // the pipeline position for the horizontal map coordinate is frozen
                // at the fetch start ...
                sampleXBase(position, duringObjectFetch);
                state++;
                break;

            case GET_TILE_T2:
                state++;
                break;

            case GET_TILE_DATA_LOW_T1:
                state++;
                break;

            case GET_TILE_DATA_LOW_T2: {
                // ... SCX itself and the vertical coordinate (SCY) resolve with the
                // map read here (m3_scx_high_5_bits, m3_scy_change)
                sampleX(window);
                sampleY(window, windowY);
                // the map read (and the map-select LCDC bit with it) resolves here,
                // two T-cycles after the offset was sampled (m3_lcdc_bg_map_change,
                // m3_lcdc_win_map_change)
                int map = window ? lcdc.getWindowTileMapDisplay() : lcdc.getBgTileMapDisplay();
                tileMapAddress = map + tileMapOffset;
                tileId = videoRam0.getByte(tileMapAddress);
                if (gbc) {
                    tileAttributes = TileAttributes.valueOf(videoRam1.getByte(tileMapAddress));
                } else {
                    tileAttributes = TileAttributes.EMPTY;
                }
                state++;
                break;
            }

            case GET_TILE_DATA_HIGH_T1:
                state++;
                break;

            case GET_TILE_DATA_HIGH_T2:
                // like the map read, the data reads sit one T-cycle later than the state
                // names suggest: the low byte here, the high byte at the tick of the push
                // itself (mealybug m3_scy_change / m3_lcdc_tile_sel_change)
                tileData1 = getTileData(tileId, effectiveY(window, windowY) & 7, 0,
                        lcdc.getBgWindowTileData(), lcdc.isBgWindowTileDataSigned(), tileAttributes, 8);
                data2Pending = true;
                // when the push must wait, the hardware fetch still reads the high byte
                // on its own schedule two dots later - even while an object fetch pauses
                // the pipeline; register writes during the wait must not affect it
                // (m3_lcdc_tile_sel_change around a sprite fetch). With a free FIFO the
                // push (and with it the read) happens in this same T-cycle.
                data2Delay = fifo.getLength() != 0 ? 2 : 0;
                state = PUSH;
                // falls through: the push happens in the same T-cycle when the FIFO is free

            case PUSH:
                if (fifo.getLength() == 0) {
                    if (!gbc
                            && !insertionGlitchDisabled
                            && !lcdc.isWindowDisplay()
                            && r.get(GpuRegister.LY) >= r.get(GpuRegister.WY)) {
                        int logicalPosition = (position + 7) & 0xff;
                        if (logicalPosition > 167) {
                            logicalPosition = 0;
                        }
                        if (r.get(GpuRegister.WX) == logicalPosition) {
                            fifo.enqueuePixel(0);
                            break;
                        }
                    }
                    if (data2Pending) {
                        // the push arrived before the scheduled high-byte read
                        readData2(window, windowY);
                    }
                    if (window && !gbc && windowTileX == 0) {
                        // the FIRST window tile's high data byte is read two dots later
                        // on hardware than our compressed empty-FIFO push; record the
                        // push so PixelTransfer can re-read it with the registers of
                        // that dot and patch the pixels (m3_lcdc_tile_sel_win_change)
                        windowRefreshPending = true;
                        windowRefreshTileId = tileId;
                        windowRefreshY = effectiveY(true, windowY) & 7;
                        windowRefreshAttrs = tileAttributes;
                        windowRefreshD0 = tileData1;
                        System.arraycopy(zip(tileData1, tileData2, tileAttributes.isXflip()), 0,
                                windowRefreshZip, 0, 8);
                    }
                    if (window) {
                        windowTileX = (windowTileX + 1) & 0x1f;
                    }
                    fifo.enqueue8Pixels(zip(tileData1, tileData2, tileAttributes.isXflip()), tileAttributes);
                    state = GET_TILE_T1;
                }
                break;
        }
    }

    /**
     * The DMG recomputes the fetch line from LY+SCY at the data reads; the CGB (rev D and
     * later) uses the value cached at the tile index fetch.
     */
    private int effectiveY(boolean window, int windowY) {
        if (gbc) {
            return fetcherY;
        }
        if (window) {
            return windowY;
        }
        return (r.get(GpuRegister.LY) + r.get(GpuRegister.SCY)) & 0xff;
    }

    public int readSpriteTileId(SpritePosition sprite) {
        // the raw tile index; the height-16 masking happens at each data read with
        // the LCDC.2 value of that dot (m3_lcdc_obj_size_change)
        return oemRam.getByte(sprite.getAddress() + 2);
    }

    public TileAttributes readSpriteAttributes(SpritePosition sprite) {
        return TileAttributes.valueOf(oemRam.getByte(sprite.getAddress() + 3));
    }

    public int readSpriteData(int tileId, int spriteTileLine, int byteNumber, TileAttributes attributes) {
        // the object line address is recomputed at each data-byte read: the height
        // bit (LCDC.2) masks both the line within the object and the tile index with
        // its value at this dot, so a mid-fetch change affects the two bytes
        // independently (m3_lcdc_obj_size_change)
        int mask = lcdc.getSpriteHeight() == 16 ? 0xf : 0x7;
        int line = spriteTileLine & mask;
        if (attributes.isYflip()) {
            line ^= mask;
        }
        int id = lcdc.getSpriteHeight() == 16 ? (tileId & 0xfe) : tileId;
        AddressSpace videoRam = (attributes.getBank() == 0 || !gbc) ? videoRam0 : videoRam1;
        return videoRam.getByte(0x8000 + id * 0x10 + line * 2 + byteNumber);
    }

    private int getTileData(
            int tileId,
            int line,
            int byteNumber,
            int tileDataAddress,
            boolean signed,
            TileAttributes attr,
            int tileHeight) {
        int effectiveLine;
        if (attr.isYflip()) {
            effectiveLine = tileHeight - 1 - line;
        } else {
            effectiveLine = line;
        }

        int tileAddress;
        if (signed) {
            tileAddress = tileDataAddress + toSigned(tileId) * 0x10;
        } else {
            tileAddress = tileDataAddress + tileId * 0x10;
        }
        AddressSpace videoRam = (attr.getBank() == 0 || !gbc) ? videoRam0 : videoRam1;
        return videoRam.getByte(tileAddress + effectiveLine * 2 + byteNumber);
    }

    public int[] zip(int data1, int data2, boolean reverse) {
        return zip(data1, data2, reverse, pixelLine);
    }

    public static int[] zip(int data1, int data2, boolean reverse, int[] pixelLine) {
        for (int i = 7; i >= 0; i--) {
            int mask = (1 << i);
            int p = 2 * ((data2 & mask) == 0 ? 0 : 1) + ((data1 & mask) == 0 ? 0 : 1);
            if (reverse) {
                pixelLine[i] = p;
            } else {
                pixelLine[7 - i] = p;
            }
        }
        return pixelLine;
    }

    @Override
    public Memento<Fetcher> saveToMemento() {
        return new FetcherMemento(
                pixelLine.clone(),
                state,
                windowTileX,
                fetcherY,
                tileMapAddress,
                tileId,
                tileAttributes == null ? -1 : tileAttributes.getValue(),
                tileData1,
                tileData2,
                insertionGlitchDisabled,
                data2Pending,
                data2Delay);
    }

    @Override
    public void restoreFromMemento(Memento<Fetcher> memento) {
        if (!(memento instanceof FetcherMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }

        System.arraycopy(mem.pixelLine, 0, this.pixelLine, 0, this.pixelLine.length);
        this.state = mem.state;
        this.windowTileX = mem.windowTileX;
        this.fetcherY = mem.fetcherY;
        this.tileMapAddress = mem.tileMapAddress;
        this.tileId = mem.tileId;
        if (mem.tileAttributesValue != -1) {
            this.tileAttributes = TileAttributes.valueOf(mem.tileAttributesValue);
        } else {
            this.tileAttributes = TileAttributes.EMPTY;
        }
        this.tileData1 = mem.tileData1;
        this.tileData2 = mem.tileData2;
        this.insertionGlitchDisabled = mem.insertionGlitchDisabled;
        this.data2Pending = mem.data2Pending;
        this.data2Delay = mem.data2Delay;
    }

    private record FetcherMemento(
            int[] pixelLine,
            int state,
            int windowTileX,
            int fetcherY,
            int tileMapAddress,
            int tileId,
            int tileAttributesValue,
            int tileData1,
            int tileData2,
            boolean insertionGlitchDisabled,
            boolean data2Pending,
            int data2Delay)
            implements Memento<Fetcher> {
    }
}
