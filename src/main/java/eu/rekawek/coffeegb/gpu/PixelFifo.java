package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.memory.MemoryRegisters;

public interface PixelFifo {

    int getLength();

    void putPixelToScreen();

    void dropPixel();

    void enqueue8Pixels(int data1, int data2);

    void setOverlay(int data1, int data2, int offset, TileAttributes flags, MemoryRegisters registers);

    void clear();

}
