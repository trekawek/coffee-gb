package eu.rekawek.coffeegb.core.gpu;

/**
 * Unused scalar model of the DMG timing-only FIFO.
 *
 * <p>The production FIFO retains pixel, palette, priority, and OAM payloads for the visible
 * machine. The timing skeleton needs only queue capacity/occupancy, sprite alignment, the
 * output timestamps, and the first-pixel latch. This model intentionally keeps only those
 * structural fields and is not wired into {@link PixelTransfer}.</p>
 *
 * <p>It models the suppressed ({@code renderOutput == false}) path only. That path still
 * advances timestamps and the first-entry/out-count state, but never resolves a pixel or
 * touches a display/VRAM transfer.</p>
 */
final class TimingDmgPixelFifo implements PixelFifo {

    static final int OUTPUT_DELAY = DmgPixelFifo.OUTPUT_DELAY;

    private final TimingQueueLength pixels = new TimingQueueLength();

    private final TimingSpriteFifo spriteFifo = new TimingSpriteFifo();

    private final long[] delayStamp = new long[8];

    private int delayHead;

    private int delaySize;

    private long outputTicks;

    private int linePixels;

    private int outCount;

    private boolean firstEntryPresent;

    @Override
    public int getLength() {
        return pixels.size();
    }

    @Override
    public void putPixelToScreen() {
        linePixels++;
        popBackgroundAndSprite();
        enqueueDelay();
    }

    @Override
    public void putInsertedPixel() {
        linePixels++;
        // The insertion glitch supplies a blank background pixel but still pops the
        // object FIFO; only that structural pop matters to this model.
        spriteFifo.pop();
        enqueueDelay();
    }

    @Override
    public void outputTick() {
        outputTicks++;
        if (delaySize == 0 && !firstEntryPresent) {
            return;
        }
        if (firstEntryPresent) {
            firstEntryPresent = false;
        }
        while (isDue()) {
            popDelay();
            if (outCount == 0) {
                outCount++;
                // The payload is deliberately discarded; only the presence of this
                // one-tick latch is part of the timing skeleton's structural state.
                firstEntryPresent = true;
                break;
            }
            outCount++;
        }
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
        outCount = 0;
        firstEntryPresent = false;
    }

    @Override
    public void dropPixel() {
        pixels.drop();
        spriteFifo.pop();
    }

    @Override
    public void enqueue8Pixels(int[] pixelLine, TileAttributes tileAttributes) {
        pixels.enqueue8();
    }

    @Override
    public void enqueuePixel(int pixel) {
        pixels.enqueue();
    }

    @Override
    public void setOverlay(int[] pixelLine, int offset, TileAttributes flags, int oamIndex) {
        spriteFifo.overlay();
    }

    /** Background refresh rewrites payload only; it is a structural no-op. */
    @Override
    public void refreshBgPixels(int[] oldLine, int[] newLine, int popped) {
    }

    /** Object refresh rewrites payload only; it is a structural no-op. */
    @Override
    public void refreshOverlay(int[] oldLine, int[] newLine, int fromIndex, TileAttributes flags) {
        spriteFifo.refresh();
    }

    /** Test helper mirroring the production pop without resolving a visible color. */
    int dequeuePixel() {
        popBackgroundAndSprite();
        return 0;
    }

    @Override
    public void clear() {
        pixels.clear();
        spriteFifo.clear();
    }

    @Override
    public void clearBg() {
        pixels.clear();
    }

    @Override
    public void clearOutput() {
        delaySize = 0;
    }

    TimingFifoProjection projection() {
        return new TimingFifoProjection(
                pixels.projection(),
                new TimingQueueProjection(TimingQueueLength.CAPACITY, 0),
                spriteFifo.projection(),
                delayStamp,
                delayHead,
                delaySize,
                outputTicks,
                linePixels,
                outCount,
                firstEntryPresent);
    }

    State captureState() {
        return new State(
                pixels.captureState(),
                spriteFifo.captureState(),
                delayStamp,
                delayHead,
                delaySize,
                outputTicks,
                linePixels,
                outCount,
                firstEntryPresent);
    }

    void restoreState(State state) {
        pixels.restoreState(state.pixels);
        spriteFifo.restoreState(state.spriteFifo);
        long[] stamps = state.delayStamp();
        if (stamps.length != delayStamp.length) {
            throw new IllegalArgumentException("Timing DMG delay capacity mismatch");
        }
        System.arraycopy(stamps, 0, delayStamp, 0, delayStamp.length);
        delayHead = state.delayHead;
        delaySize = state.delaySize;
        outputTicks = state.outputTicks;
        linePixels = state.linePixels;
        outCount = state.outCount;
        firstEntryPresent = state.firstEntryPresent;
    }

    private void popBackgroundAndSprite() {
        pixels.dequeue();
        spriteFifo.pop();
    }

    private void enqueueDelay() {
        if (delaySize == delayStamp.length) {
            throw new IllegalStateException("Output delay is full");
        }
        int tail = (delayHead + delaySize) & 7;
        delayStamp[tail] = outputTicks;
        delaySize++;
    }

    private boolean isDue() {
        return delaySize > 0 && delayStamp[delayHead] + OUTPUT_DELAY <= outputTicks;
    }

    private void popDelay() {
        delayHead = (delayHead + 1) & 7;
        delaySize--;
    }

    record State(
            TimingQueueLength.State pixels,
            TimingSpriteFifo.State spriteFifo,
            long[] delayStamp,
            int delayHead,
            int delaySize,
            long outputTicks,
            int linePixels,
            int outCount,
            boolean firstEntryPresent) {

        State {
            delayStamp = delayStamp.clone();
        }

        @Override
        public long[] delayStamp() {
            return delayStamp.clone();
        }
    }
}
