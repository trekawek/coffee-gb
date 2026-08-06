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

    /**
     * Emits one synthetic blank (colour 0) pixel without dequeuing the FIFO - the
     * window insertion glitch (a WX re-match while the window is active).
     */
    default void putInsertedPixel() {
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

    /**
     * Re-resolves the most recent object overlay with freshly read tile data; only pixels
     * still in the FIFO change (DMG object-read sampling; no-op on the CGB).
     */
    default void refreshOverlay(int[] oldLine, int[] newLine, int fromIndex, TileAttributes flags) {
    }

    /**
     * Re-resolves the most recent 8-pixel background push with fresh tile data, patching
     * FIFO-resident pixels and not-yet-displayed popped ones (DMG window D1 refresh).
     */
    default void refreshBgPixels(int[] oldLine, int[] newLine, int popped) {
    }


    void clear();

    /** Clears the background FIFO only, preserving the object FIFO (window activation). */
    void clearBg();

    /** Number of old background pixels retained across a CGB window-start fetch. */
    default int getClearedBgLength() {
        return 0;
    }

    /** Emits one retained background pixel without disturbing newly fetched window pixels. */
    default void putClearedBgToScreen() {
    }

    /** Drops one retained background pixel during the off-screen discard phase. */
    default void dropClearedBgPixel() {
    }

    /** Discards background pixels retained by {@link #clearBg()}. */
    default void discardClearedBg() {
    }
}
