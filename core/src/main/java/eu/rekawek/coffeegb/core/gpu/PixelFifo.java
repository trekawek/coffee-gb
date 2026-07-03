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

    /**
     * Steps the LCD x pointer back one pixel (the DMG WX==position+6 window activation
     * desync): the next emitted pixel overwrites the previous output slot.
     */
    default void rewindOnePixel() {
    }

    /** Marks the start of a new line for the LCD x bookkeeping. */
    default void startLine() {
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
