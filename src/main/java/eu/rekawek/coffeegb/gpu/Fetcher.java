package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.gpu.phase.OamSearch.SpritePosition;
import eu.rekawek.coffeegb.memory.MemoryRegisters;

import java.util.EnumSet;

import static eu.rekawek.coffeegb.cpu.BitUtils.toSigned;
import static eu.rekawek.coffeegb.gpu.GpuRegister.LY;

public class Fetcher {

    private enum State {
        READ_TILE_ID, READ_DATA_1, READ_DATA_2, PUSH,
        READ_SPRITE_TILE_ID, READ_SPRITE_FLAGS, READ_SPRITE_DATA_1, READ_SPRITE_DATA_2, PUSH_SPRITE
    }

    private static final int[] EMPTY_PIXEL_LINE = new int[8];

    private final PixelFifo fifo;

    private final AddressSpace videoRam0;

    private final AddressSpace videoRam1;

    private final AddressSpace oemRam;

    private final MemoryRegisters r;

    private final Lcdc lcdc;

    private final boolean gbc;

    private final int[] pixelLine = new int[8];

    private State state;

    private boolean fetchingDisabled;

    private int mapAddress;

    private int xOffset;

    private int tileDataAddress;

    private boolean tileIdSigned;

    private int tileLine;

    private int tileId;

    private TileAttributes tileAttributes;

    private int tileData1;

    private int tileData2;

    private int spriteTileLine;

    private SpritePosition sprite;

    private TileAttributes spriteAttributes;

    private int spriteOffset;

    private int spriteOamIndex;

    private int divider = 2;

    public Fetcher(PixelFifo fifo, AddressSpace videoRam0, AddressSpace videoRam1, AddressSpace oemRam, Lcdc lcdc, MemoryRegisters registers, boolean gbc) {
        this.gbc = gbc;
        this.fifo = fifo;
        this.videoRam0 = videoRam0;
        this.videoRam1 = videoRam1;
        this.oemRam = oemRam;
        this.r = registers;
        this.lcdc = lcdc;
    }

    public void init() {
        state = State.READ_TILE_ID;
        tileId = 0;
        tileData1 = 0;
        tileData2 = 0;
        divider = 2;
        fetchingDisabled = false;
    }

    public void startFetching(int mapAddress, int tileDataAddress, int xOffset, boolean tileIdSigned, int tileLine) {
        this.mapAddress = mapAddress;
        this.tileDataAddress = tileDataAddress;
        this.xOffset = xOffset;
        this.tileIdSigned = tileIdSigned;
        this.tileLine = tileLine;
        this.fifo.clear();

        state = State.READ_TILE_ID;
        tileId = 0;
        tileData1 = 0;
        tileData2 = 0;
        divider = 2;
    }

    public void fetchingDisabled() {
        this.fetchingDisabled = true;
    }

    public void addSprite(SpritePosition sprite, int offset, int oamIndex) {
        this.sprite = sprite;
        this.state = State.READ_SPRITE_TILE_ID;
        this.spriteTileLine = r.get(LY) + 16 - sprite.getY();
        this.spriteOffset = offset;
        this.spriteOamIndex = oamIndex;
    }

    public void tick() {
        if (fetchingDisabled && state == State.READ_TILE_ID) {
            if (fifo.getLength() <= 8) {
                fifo.enqueue8Pixels(EMPTY_PIXEL_LINE, tileAttributes);
            }
            return;
        }

        if (--divider == 0) {
            divider = 2;
        } else {
            return;
        }

        switch (state) {
            case READ_TILE_ID:
                tileId = videoRam0.getByte(mapAddress + xOffset);
                if (gbc) {
                    tileAttributes = TileAttributes.valueOf(videoRam1.getByte(mapAddress + xOffset));
                } else {
                    tileAttributes = TileAttributes.EMPTY;
                }
                state = State.READ_DATA_1;
                break;

            case READ_DATA_1:
                tileData1 = getTileData(tileId, tileLine, 0, tileDataAddress, tileIdSigned, tileAttributes, 8);
                state = State.READ_DATA_2;
                break;

            case READ_DATA_2:
                tileData2 = getTileData(tileId, tileLine, 1, tileDataAddress, tileIdSigned, tileAttributes, 8);
                state = State.PUSH;

            case PUSH:
                if (fifo.getLength() <= 8) {
                    fifo.enqueue8Pixels(zip(tileData1, tileData2, tileAttributes.isXflip()), tileAttributes);
                    xOffset = (xOffset + 1) % 0x20;
                    state = State.READ_TILE_ID;
                }
                break;

            case READ_SPRITE_TILE_ID:
                tileId = oemRam.getByte(sprite.getAddress() + 2);
                state = State.READ_SPRITE_FLAGS;
                break;

            case READ_SPRITE_FLAGS:
                spriteAttributes = TileAttributes.valueOf(oemRam.getByte(sprite.getAddress() + 3));
                state = State.READ_SPRITE_DATA_1;
                break;

            case READ_SPRITE_DATA_1:
                if (lcdc.getSpriteHeight() == 16) {
                    tileId &= 0xfe;
                }
                tileData1 = getTileData(tileId, spriteTileLine, 0, 0x8000, false, spriteAttributes, lcdc.getSpriteHeight());
                state = State.READ_SPRITE_DATA_2;
                break;

            case READ_SPRITE_DATA_2:
                tileData2 = getTileData(tileId, spriteTileLine, 1, 0x8000, false, spriteAttributes, lcdc.getSpriteHeight());
                state = State.PUSH_SPRITE;
                break;

            case PUSH_SPRITE:
                fifo.setOverlay(zip(tileData1, tileData2, spriteAttributes.isXflip()), spriteOffset, spriteAttributes, spriteOamIndex);
                state = State.READ_TILE_ID;
                break;
        }
    }

    private int getTileData(int tileId, int line, int byteNumber, int tileDataAddress, boolean signed, TileAttributes attr, int tileHeight) {
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

    public boolean spriteInProgress() {
        return EnumSet.of(State.READ_SPRITE_TILE_ID, State.READ_SPRITE_FLAGS, State.READ_SPRITE_DATA_1, State.READ_SPRITE_DATA_2, State.PUSH_SPRITE).contains(state);
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

}
