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

    private final PixelFifo fifo;

    private final AddressSpace videoRam;

    private final AddressSpace oemRam;

    private final MemoryRegisters r;

    private final Lcdc lcdc;

    private State state = State.READ_TILE_ID;

    private boolean fetchingDisabled;

    private int mapAddress;

    private int xOffset;

    private int tileDataAddress;

    private boolean tileIdSigned;

    private int tileLine;

    private int tileId;

    private int tileData1;

    private int tileData2;

    private int spriteTileLine;

    private SpritePosition sprite;

    private TileAttributes spriteAttributes;

    private int spriteOffset;

    private int divider = 2;

    public Fetcher(PixelFifo fifo, AddressSpace videoRam, AddressSpace oemRam, MemoryRegisters registers) {
        this.fifo = fifo;
        this.videoRam = videoRam;
        this.oemRam = oemRam;
        this.r = registers;
        this.lcdc = new Lcdc(r);
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

    public void addSprite(SpritePosition sprite, int offset) {
        this.sprite = sprite;
        this.state = State.READ_SPRITE_TILE_ID;
        this.spriteTileLine = r.get(LY) + 16 - sprite.getY();
        this.spriteOffset = offset;
    }

    public void tick() {
        if (fetchingDisabled && state == State.READ_TILE_ID) {
            if (fifo.getLength() <= 8) {
                fifo.enqueue8Pixels(0, 0);
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
                tileId = videoRam.getByte(mapAddress + xOffset);
                state = State.READ_DATA_1;
                break;

            case READ_DATA_1:
                tileData1 = getTileData(tileId, tileLine, 0, tileDataAddress, tileIdSigned);
                state = State.READ_DATA_2;
                break;

            case READ_DATA_2:
                tileData2 = getTileData(tileId, tileLine, 1, tileDataAddress, tileIdSigned);
                state = State.PUSH;

            case PUSH:
                if (fifo.getLength() <= 8) {
                    fifo.enqueue8Pixels(tileData1, tileData2);
                    xOffset = (xOffset + 1) % 0x20;
                    state = State.READ_TILE_ID;
                }
                break;

            case READ_SPRITE_TILE_ID:
                tileId = oemRam.getByte(sprite.getAddress() + 2);
                state = State.READ_SPRITE_FLAGS;
                break;

            case READ_SPRITE_FLAGS:
                spriteAttributes = new TileAttributes(oemRam.getByte(sprite.getAddress() + 3));
                state = State.READ_SPRITE_DATA_1;
                break;

            case READ_SPRITE_DATA_1:
                if (spriteAttributes.isYflip()) {
                    spriteTileLine = lcdc.getSpriteHeight() - 1 - spriteTileLine;
                }
                tileData1 = getTileData(tileId, spriteTileLine, 0, 0x8000, false);
                state = State.READ_SPRITE_DATA_2;
                break;

            case READ_SPRITE_DATA_2:
                tileData2 = getTileData(tileId, spriteTileLine, 1, 0x8000, false);
                state = State.PUSH_SPRITE;
                break;

            case PUSH_SPRITE:
                fifo.setOverlay(tileData1, tileData2, spriteOffset, spriteAttributes, r);
                state = State.READ_TILE_ID;
                break;
        }
    }

    private int getTileData(int tileId, int line, int byteNumber, int tileDataAddress, boolean signed) {
        int tileAddress;
        if (signed) {
            tileAddress = tileDataAddress + toSigned(tileId) * 0x10;
        } else {
            tileAddress = tileDataAddress + tileId * 0x10;
        }
        return videoRam.getByte(tileAddress + line * 2 + byteNumber);
    }

    public boolean spriteInProgress() {
        return EnumSet.of(State.READ_SPRITE_TILE_ID, State.READ_SPRITE_FLAGS, State.READ_SPRITE_DATA_1, State.READ_SPRITE_DATA_2, State.PUSH_SPRITE).contains(state);
    }
}
