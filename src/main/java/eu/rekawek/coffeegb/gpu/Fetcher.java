package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.memory.MemoryRegisters;

import static eu.rekawek.coffeegb.cpu.BitUtils.abs;
import static eu.rekawek.coffeegb.cpu.BitUtils.isNegative;

public class Fetcher {

    private enum State {
        READ_TILE_ID, READ_DATA_1, READ_DATA_2, PUSH
    }

    private final PixelFifo fifo;

    private final AddressSpace videoRam;

    private boolean divider;

    private State state = State.READ_TILE_ID;

    private boolean fetchingDisabled;

    private int mapAddress;

    private int tileDataAddress;

    private boolean tileIdSigned;

    private int tileLine;

    private int tileId;

    private int tileData1;

    private int tileData2;

    public Fetcher(PixelFifo fifo, AddressSpace videoRam) {
        this.fifo = fifo;
        this.videoRam = videoRam;
    }

    public void startFetching(int mapAddress, int tileDataAddress, boolean tileIdSigned, int tileLine) {
        this.mapAddress = mapAddress;
        this.tileDataAddress = tileDataAddress;
        this.tileIdSigned = tileIdSigned;
        this.tileLine = tileLine;

        state = State.READ_TILE_ID;
        divider = false;
        tileId = 0;
        tileData1 = 0;
        tileData2 = 0;
    }

    public void fetchingDisabled() {
        this.fetchingDisabled = true;
    }

    public void addSprite(int tileAddress, int tileLine) {

    }

    public void tick() {
        if (fetchingDisabled && state == State.READ_TILE_ID) {
            if (fifo.getLength() <= 8) {
                fifo.enqueue8Pixels(0, 0);
            }
            return;
        }

        divider = !divider;
        if (!divider) {
            return;
        }

        switch (state) {
            case READ_TILE_ID:
                tileId = videoRam.getByte(mapAddress);
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
                    mapAddress++;
                    state = State.READ_TILE_ID;
                }
        }
    }

    private int getTileData(int tileId, int line, int byteNumber, int tileDataAddress, boolean signed) {
        int tileAddress;
        if (signed) {
            if (isNegative(tileId)) {
                tileAddress = tileDataAddress - abs(tileId) * 0x10;
            } else {
                tileAddress = tileDataAddress + abs(tileId) * 0x10;
            }
        } else {
            tileAddress = tileDataAddress + tileId * 0x10;
        }
        return videoRam.getByte(tileAddress + line * 2 + byteNumber);
    }
}
