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

    private int mapAddress;

    private int xOffset;

    private int tileDataAddress;

    private boolean tileIdSigned;

    private int tileLine;

    private int tileId;

    private TileAttributes tileAttributes = TileAttributes.EMPTY;

    private int tileData1;

    private int tileData2;

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

    public void startFetching(
            int mapAddress, int tileDataAddress, int xOffset, boolean tileIdSigned, int tileLine) {
        this.mapAddress = mapAddress;
        this.tileDataAddress = tileDataAddress;
        this.xOffset = xOffset;
        this.tileIdSigned = tileIdSigned;
        this.tileLine = tileLine;

        state = GET_TILE_T1;
        tileId = 0;
        tileAttributes = TileAttributes.EMPTY;
        tileData1 = 0;
        tileData2 = 0;
    }

    public int getState() {
        return state;
    }

    /**
     * Advances the fetcher by one T-cycle.
     */
    public void advance() {
        switch (state) {
            case GET_TILE_T1:
            case GET_TILE_DATA_LOW_T1:
            case GET_TILE_DATA_HIGH_T1:
                state++;
                break;

            case GET_TILE_T2:
                tileId = videoRam0.getByte(mapAddress + xOffset);
                if (gbc) {
                    tileAttributes = TileAttributes.valueOf(videoRam1.getByte(mapAddress + xOffset));
                } else {
                    tileAttributes = TileAttributes.EMPTY;
                }
                state++;
                break;

            case GET_TILE_DATA_LOW_T2:
                tileData1 =
                        getTileData(tileId, tileLine, 0, tileDataAddress, tileIdSigned, tileAttributes, 8);
                state++;
                break;

            case GET_TILE_DATA_HIGH_T2:
                tileData2 =
                        getTileData(tileId, tileLine, 1, tileDataAddress, tileIdSigned, tileAttributes, 8);
                state = PUSH;
                // falls through: the push happens in the same T-cycle when the FIFO is free

            case PUSH:
                if (fifo.getLength() == 0) {
                    fifo.enqueue8Pixels(zip(tileData1, tileData2, tileAttributes.isXflip()), tileAttributes);
                    xOffset = (xOffset + 1) % 0x20;
                    state = GET_TILE_T1;
                }
                break;
        }
    }

    public int readSpriteTileId(SpritePosition sprite) {
        int tileId = oemRam.getByte(sprite.getAddress() + 2);
        if (lcdc.getSpriteHeight() == 16) {
            tileId &= 0xfe;
        }
        return tileId;
    }

    public TileAttributes readSpriteAttributes(SpritePosition sprite) {
        return TileAttributes.valueOf(oemRam.getByte(sprite.getAddress() + 3));
    }

    public int readSpriteData(int tileId, int spriteTileLine, int byteNumber, TileAttributes attributes) {
        return getTileData(tileId, spriteTileLine, byteNumber, 0x8000, false, attributes, lcdc.getSpriteHeight());
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
                mapAddress,
                xOffset,
                tileDataAddress,
                tileIdSigned,
                tileLine,
                tileId,
                tileAttributes == null ? -1 : tileAttributes.getValue(),
                tileData1,
                tileData2);
    }

    @Override
    public void restoreFromMemento(Memento<Fetcher> memento) {
        if (!(memento instanceof FetcherMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }

        System.arraycopy(mem.pixelLine, 0, this.pixelLine, 0, this.pixelLine.length);
        this.state = mem.state;
        this.mapAddress = mem.mapAddress;
        this.xOffset = mem.xOffset;
        this.tileDataAddress = mem.tileDataAddress;
        this.tileIdSigned = mem.tileIdSigned;
        this.tileLine = mem.tileLine;
        this.tileId = mem.tileId;
        if (mem.tileAttributesValue != -1) {
            this.tileAttributes = TileAttributes.valueOf(mem.tileAttributesValue);
        } else {
            this.tileAttributes = TileAttributes.EMPTY;
        }
        this.tileData1 = mem.tileData1;
        this.tileData2 = mem.tileData2;
    }

    private record FetcherMemento(
            int[] pixelLine,
            int state,
            int mapAddress,
            int xOffset,
            int tileDataAddress,
            boolean tileIdSigned,
            int tileLine,
            int tileId,
            int tileAttributesValue,
            int tileData1,
            int tileData2)
            implements Memento<Fetcher> {
    }
}
