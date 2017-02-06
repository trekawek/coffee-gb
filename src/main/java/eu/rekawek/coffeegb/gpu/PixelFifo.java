package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.memory.MemoryRegisters;

public interface PixelFifo {

    int getLength();

    void putPixelToScreen();

    void dropPixel();

    void enqueue8Pixels(int[] pixels, TileAttributes tileAttributes);

    void setOverlay(int[] pixels, int offset, TileAttributes flags, MemoryRegisters registers);

    void clear();

}
