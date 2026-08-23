package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.state.ComponentState;

/**
 * Timing-only CGB FIFO used by the unshifted mode-3 skeleton.
 *
 * <p>Native CGB and CGB DMG-compatibility use this same occupancy/timing storage.  Their output
 * resolvers remain separate in the full shifted {@link ColorPixelFifo} machine.</p>
 */
public final class ScalarTimingColorPixelFifo implements PixelFifo {

    private final ScalarTimingFifoSupport.Queue background = new ScalarTimingFifoSupport.Queue();

    private final ScalarTimingFifoSupport.Queue clearedBackground = new ScalarTimingFifoSupport.Queue();

    private final ScalarTimingFifoSupport.Sprite spriteFifo = new ScalarTimingFifoSupport.Sprite();

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
        // With the one-dot CGB delay every entry already present is due on this suppressed
        // tick.  Advance the ring head without retaining payload or timestamps.
        delayHead = (delayHead + delaySize) & 7;
        delaySize = 0;
    }

    @Override
    public boolean isPerformanceOutputIdle() {
        return delaySize == 0;
    }

    @Override
    public void advancePerformanceOutputIdleSpanTrusted(int ticks) {
        if (ticks < 0 || delaySize != 0) {
            throw new IllegalStateException("CGB timing output delay is not idle");
        }
        outputTicks += ticks;
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
        background.dequeue();
        spriteFifo.pop();
    }

    @Override
    public void dropClearedBgPixel() {
        clearedBackground.dequeue();
        spriteFifo.pop();
    }

    @Override
    public void enqueue8Pixels(int[] pixelLine, TileAttributes tileAttributes) {
        background.enqueue8();
    }

    // CGB has no single-pixel insertion operation.
    @Override
    public void enqueuePixel(int pixel) {
    }

    // CGB has no DMG insertion glitch.
    @Override
    public void putInsertedPixel() {
    }

    @Override
    public void setOverlay(int[] pixelLine, int offset, TileAttributes flags, int oamIndex) {
        spriteFifo.overlay();
    }

    @Override
    public void refreshOverlay(int[] oldLine, int[] newLine, int fromIndex, TileAttributes flags) {
        spriteFifo.refresh();
    }

    @Override
    public void refreshBgPixels(int[] oldLine, int[] newLine, int popped) {
        // CGB background refresh is payload-only.
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

    @Override
    public void resetForMissingState() {
        clear();
        delayHead = 0;
        delaySize = 0;
        outputTicks = 0;
        linePixels = 0;
    }

    /** Captures all scalar state in a detached value. */
    public State captureTimingState() {
        ScalarTimingFifoSupport.Sprite.State sprite = spriteFifo.captureState();
        return new State(
                background.size(),
                clearedBackground.size(),
                sprite.size(),
                sprite.underflow(),
                delayHead,
                delaySize,
                outputTicks,
                linePixels);
    }

    /** State-component spelling used by the regular PixelTransfer/Gpu memento graph. */
    public State captureState() {
        return captureTimingState();
    }

    public void restoreTimingState(State state) {
        validate(state);
        background.restoreState(new ScalarTimingFifoSupport.Queue.State(state.backgroundSize));
        clearedBackground.restoreState(
                new ScalarTimingFifoSupport.Queue.State(state.clearedBackgroundSize));
        spriteFifo.restoreState(
                new ScalarTimingFifoSupport.Sprite.State(state.spriteSize, state.spriteUnderflow));
        delayHead = state.delayHead;
        delaySize = state.delaySize;
        outputTicks = state.outputTicks;
        linePixels = state.linePixels;
    }

    public void validate(State state) {
        if (state == null) {
            throw new IllegalArgumentException("CGB scalar FIFO state is missing");
        }
        if (state.backgroundSize < 0
                || state.backgroundSize > ScalarTimingFifoSupport.BACKGROUND_CAPACITY
                || state.clearedBackgroundSize < 0
                || state.clearedBackgroundSize > ScalarTimingFifoSupport.BACKGROUND_CAPACITY) {
            throw new IllegalArgumentException("Invalid CGB scalar background occupancy");
        }
        if (state.spriteSize < 0 || state.spriteSize > ScalarTimingFifoSupport.SPRITE_CAPACITY
                || state.spriteUnderflow < 0) {
            throw new IllegalArgumentException("Invalid CGB scalar object occupancy");
        }
        if (state.delayHead < 0 || state.delayHead >= 8
                || state.delaySize < 0 || state.delaySize > 8) {
            throw new IllegalArgumentException("Invalid CGB scalar delay cursor");
        }
        if (state.outputTicks < 0) {
            throw new IllegalArgumentException("CGB scalar output ticks cannot be negative");
        }
        if (state.linePixels < 0 || state.linePixels > 160) {
            throw new IllegalArgumentException("Invalid CGB scalar output position");
        }
    }

    public int getDelayHead() {
        return delayHead;
    }

    public int getDelaySize() {
        return delaySize;
    }

    public long getOutputTicks() {
        return outputTicks;
    }

    public int getLinePixels() {
        return linePixels;
    }

    private void enqueuePending() {
        if (delaySize == 8) {
            throw new IllegalStateException("Output delay is full");
        }
        delaySize++;
    }

    public record State(
            int backgroundSize,
            int clearedBackgroundSize,
            int spriteSize,
            int spriteUnderflow,
            int delayHead,
            int delaySize,
            long outputTicks,
            int linePixels)
            implements ComponentState<ScalarTimingColorPixelFifo> {
    }
}
