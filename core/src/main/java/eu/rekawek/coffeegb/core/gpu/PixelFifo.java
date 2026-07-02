package eu.rekawek.coffeegb.core.gpu;

public interface PixelFifo {

    int getLength();

    void putPixelToScreen();

    /**
     * Advances the LCD output stage by one T-cycle. Called every GPU tick regardless of
     * the mode, since the last pixels of a line leave the output delay line during HBlank.
     */
    default void outputTick() {
    }

    /** Drops any pixels still in the output delay line (LCD disable). */
    default void clearOutput() {
    }

    void dropPixel();

    void enqueue8Pixels(int[] pixels, TileAttributes tileAttributes);

    /** DMG window-insertion glitch: a single background pixel enters the FIFO. */
    default void enqueuePixel(int pixel) {
    }

    void setOverlay(int[] pixelLine, int offset, TileAttributes flags, int oamIndex);

    void clear();
}
