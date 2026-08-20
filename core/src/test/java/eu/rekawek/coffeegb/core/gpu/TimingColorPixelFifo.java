package eu.rekawek.coffeegb.core.gpu;

/**
 * Unused scalar model of the CGB timing-only FIFO.
 *
 * <p>Only queue occupancies, object alignment, the suppressed output pending count, and the
 * line position are retained. CGB output suppression drains all entries already present at
 * the next output tick, so the visible payload and per-entry timestamps are not needed by
 * this model. The production FIFO remains the implementation used by the visible machine.</p>
 */
final class TimingColorPixelFifo implements PixelFifo {

    private final TimingQueueLength background = new TimingQueueLength();

    private final TimingQueueLength clearedBackground = new TimingQueueLength();

    private final TimingSpriteFifo spriteFifo = new TimingSpriteFifo();

    private int delayHead;

    private int delaySize;

    private long outputTicks;

    private int linePixels;

    @Override
    public int getLength() {
        return background.size();
    }

    @Override
    public void putPixelToScreen() {
        linePixels++;
        background.dequeue();
        spriteFifo.pop();
        enqueuePending();
    }

    @Override
    public void putClearedBgToScreen() {
        linePixels++;
        clearedBackground.dequeue();
        spriteFifo.pop();
        enqueuePending();
    }

    @Override
    public void outputTick() {
        outputTicks++;
        if (delaySize == 0) {
            return;
        }
        // The suppressed CGB path drains every entry already present. Its one-dot
        // timestamp loop is structurally equivalent to advancing past the pending count.
        delayHead = (delayHead + delaySize) & 7;
        delaySize = 0;
    }

    @Override
    public void rewindOnePixel() {
        if (linePixels == 0) {
            return;
        }
        linePixels--;
        spriteFifo.rewind();
        if (delaySize > 0) {
            delaySize--;
        }
    }

    @Override
    public void startLine() {
        linePixels = 0;
    }

    @Override
    public void dropPixel() {
        background.drop();
        spriteFifo.pop();
    }

    @Override
    public void dropClearedBgPixel() {
        clearedBackground.drop();
        spriteFifo.pop();
    }

    @Override
    public void enqueue8Pixels(int[] pixelLine, TileAttributes tileAttributes) {
        background.enqueue8();
    }

    /** CGB has no single-pixel enqueue operation; the default production operation is a no-op. */
    @Override
    public void enqueuePixel(int pixel) {
    }

    /** CGB has no insertion glitch; the default production operation is a no-op. */
    @Override
    public void putInsertedPixel() {
    }

    @Override
    public void setOverlay(int[] pixelLine, int offset, TileAttributes flags, int oamIndex) {
        spriteFifo.overlay();
    }

    /** CGB overlay refresh is payload-only and structurally inert. */
    @Override
    public void refreshOverlay(int[] oldLine, int[] newLine, int fromIndex, TileAttributes flags) {
        spriteFifo.refresh();
    }

    /** CGB background refresh is a production no-op and is structurally inert. */
    @Override
    public void refreshBgPixels(int[] oldLine, int[] newLine, int popped) {
    }

    @Override
    public void clear() {
        background.clear();
        spriteFifo.clear();
        discardClearedBg();
    }

    @Override
    public void clearBg() {
        discardClearedBg();
        background.copyTo(clearedBackground);
        background.clear();
    }

    @Override
    public int getClearedBgLength() {
        return clearedBackground.size();
    }

    @Override
    public void discardClearedBg() {
        clearedBackground.clear();
    }

    @Override
    public void clearOutput() {
        delaySize = 0;
    }

    TimingFifoProjection projection() {
        return new TimingFifoProjection(
                background.projection(),
                clearedBackground.projection(),
                spriteFifo.projection(),
                new long[0],
                delayHead,
                delaySize,
                outputTicks,
                linePixels,
                0,
                false);
    }

    State captureState() {
        return new State(
                background.captureState(),
                clearedBackground.captureState(),
                spriteFifo.captureState(),
                delayHead,
                delaySize,
                outputTicks,
                linePixels);
    }

    void restoreState(State state) {
        background.restoreState(state.background);
        clearedBackground.restoreState(state.clearedBackground);
        spriteFifo.restoreState(state.spriteFifo);
        delayHead = state.delayHead;
        delaySize = state.delaySize;
        outputTicks = state.outputTicks;
        linePixels = state.linePixels;
    }

    private void enqueuePending() {
        if (delaySize == 8) {
            throw new IllegalStateException("Output delay is full");
        }
        delaySize++;
    }

    record State(
            TimingQueueLength.State background,
            TimingQueueLength.State clearedBackground,
            TimingSpriteFifo.State spriteFifo,
            int delayHead,
            int delaySize,
            long outputTicks,
            int linePixels) {
    }
}
