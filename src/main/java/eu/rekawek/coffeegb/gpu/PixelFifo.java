package eu.rekawek.coffeegb.gpu;

public interface PixelFifo {

    int getLength();

    void putPixelToScreen();

    void dropPixel();

    void enqueue8Pixels(int[] pixels, TileAttributes tileAttributes);

    void setOverlay(int[] pixelLine, int offset, TileAttributes flags, int oamIndex);

    void clear();


}
