package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.Dumper;
import eu.rekawek.coffeegb.memory.MemoryRegisters;

import static eu.rekawek.coffeegb.cpu.BitUtils.abs;
import static eu.rekawek.coffeegb.cpu.BitUtils.isNegative;
import static eu.rekawek.coffeegb.gpu.GpuRegister.LCDC;
import static eu.rekawek.coffeegb.gpu.GpuRegister.LY;
import static eu.rekawek.coffeegb.gpu.GpuRegister.SCX;
import static eu.rekawek.coffeegb.gpu.GpuRegister.SCY;

public class Fetcher {

    private enum State {
        READ_TILE_ID, READ_DATA_1, READ_DATA_2, PUSH
    }

    private final PixelFifo fifo;

    private final AddressSpace videoRam;

    private final int line;

    private final int scrollX;

    private final int scrollY;

    private final int lcdc;

    private int xPos;

    private boolean divider;

    private State state = State.READ_TILE_ID;

    private int tileId;

    private int tileData1;

    private int tileData2;

    public Fetcher(PixelFifo fifo, AddressSpace videoRam, MemoryRegisters r) {
        this.fifo = fifo;
        this.videoRam = videoRam;
        this.line = r.get(LY);
        this.scrollX = r.get(SCX);
        this.scrollY = r.get(SCY);
        this.lcdc = r.get(LCDC);
        //dumpVideoRam();
    }

    public void tick() {
        divider = !divider;
        if (!divider) {
            return;
        }

        switch (state) {
            case READ_TILE_ID:
                tileId = getTileId((scrollX + xPos) / 0x08, (scrollY + line) / 0x08);
                state = State.READ_DATA_1;
                break;

            case READ_DATA_1:
                tileData1 = getTileData(tileId, (scrollY + line) % 0x08, 0);
                state = State.READ_DATA_2;
                break;

            case READ_DATA_2:
                tileData2 = getTileData(tileId, (scrollY + line) % 0x08, 1);
                state = State.PUSH;

            case PUSH:
                if (fifo.getLength() <= 8) {
                    fifo.enqueue8Pixels(tileData1, tileData2);
                    if (xPos == 0) {
                        for (int i = 0; i < scrollX % 0x08; i++) {
                            fifo.dequeuePixel();
                        }
                    }
                    state = State.READ_TILE_ID;
                    xPos += 0x08;
                }
        }
    }

    private int getTileId(int backgroundTileX, int backgroundTileY) {
        int map = ((lcdc & (1 << 3)) == 0) ? 0x9800 : 0x9c00;
        return videoRam.getByte(map + (backgroundTileX % 0x20) + (backgroundTileY % 0x20) * 0x20);
    }

    private void dumpVideoRam() {
        int map = ((lcdc & (1 << 3)) == 0) ? 0x9800 : 0x9c00;
        Dumper.dump(videoRam, map, 32 * 32, 32);
        System.out.println("---");
    }

    private int getTileData(int tileId, int line, int byteNumber) {
        int tileAddress;
        if ((lcdc & (1 << 4)) == 0) {
            if (isNegative(tileId)) {
                tileAddress = 0x9000 - abs(tileId) * 0x10;
            } else {
                tileAddress = 0x9000 + abs(tileId) * 0x10;
            }
        } else {
            tileAddress = 0x8000 + tileId * 0x10;
        }
        return videoRam.getByte(tileAddress + line * 2 + byteNumber);
    }
}
